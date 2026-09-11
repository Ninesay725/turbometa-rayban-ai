package com.smartview.glassai.viewmodels

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamState as DatStreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceType
import com.meta.wearable.dat.core.types.PermissionStatus
import com.smartview.glassai.glasses.CameraPermissionCheck
import com.smartview.glassai.glasses.DatRegistrationGateway
import com.smartview.glassai.glasses.FakeDatDeviceObserver
import com.smartview.glassai.glasses.FakeDatSessionFactory
import com.smartview.glassai.glasses.FakeRegistrationGateway
import com.smartview.glassai.glasses.GlassesDeviceInfo
import com.smartview.glassai.glasses.GlassesSessionManager
import com.smartview.glassai.glasses.PhotoCaptureResult
import com.smartview.glassai.glasses.TestBitmaps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

/** Actual ViewModel/session composition using the existing DAT fakes; no SDK/hardware calls. */
@OptIn(ExperimentalCoroutinesApi::class)
class WearablesLeaseTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val factory = FakeDatSessionFactory()
    private val observer = FakeDatDeviceObserver()
    private val registration = FakeRegistrationGateway()
    private val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val viewModels = ViewModelStore()
    private lateinit var manager: GlassesSessionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        observer.device.value = GlassesDeviceInfo(
            id = "lease-device", name = "Ray-Ban Meta", deviceType = DeviceType.RAYBAN_META,
            isDisplayCapable = false, compatibility = DeviceCompatibility.COMPATIBLE,
        )
        manager = GlassesSessionManager(factory, observer, managerScope).also { it.startMonitoring() }
    }

    @After
    fun tearDown() {
        viewModels.clear()
        managerScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(
        gateway: DatRegistrationGateway = registration,
        bluetoothGranted: () -> Boolean = { true },
        decodePhoto: (PhotoData) -> Bitmap? = { null },
    ) = WearablesViewModel(
        application = Application(), sessionManager = manager, registration = gateway,
        strings = { "str:$it" }, videoQuality = { VideoQuality.MEDIUM }, frameDispatcher = dispatcher,
        bluetoothGranted = bluetoothGranted, decodePhoto = decodePhoto,
    ).also { viewModels.put("wearables", it) }

    @Test
    fun missingBluetoothPermissionNeverChecksDatOrRequestsCameraAccess() = runTest(dispatcher) {
        var checks = 0
        var prompts = 0
        val gateway = object : DatRegistrationGateway by registration {
            override suspend fun checkCameraPermission(): CameraPermissionCheck {
                checks++
                return CameraPermissionCheck.Denied
            }
        }
        val vm = viewModel(gateway, bluetoothGranted = { false })

        assertFalse(vm.startStream(Any()) { prompts++; PermissionStatus.Granted })

        assertEquals(0, checks)
        assertEquals(0, prompts)
        assertEquals(0, factory.createCalls)
        assertEquals(0, manager.ownerCount)
    }

    @Test
    fun retiredDatCheckCannotOpenAPermissionPromptOverTheSuccessor() = runTest(dispatcher) {
        val oldCheck = CompletableDeferred<CameraPermissionCheck>()
        var checks = 0
        var oldPrompts = 0
        val gateway = object : DatRegistrationGateway by registration {
            override suspend fun checkCameraPermission(): CameraPermissionCheck =
                if (++checks == 1) oldCheck.await() else CameraPermissionCheck.Granted
        }
        val vm = viewModel(gateway)
        val oldOwner = Any()
        val newOwner = Any()
        val oldStart = async {
            vm.startStream(oldOwner) { oldPrompts++; PermissionStatus.Granted }
        }
        assertEquals(1, checks)
        vm.stopStream(oldOwner)
        assertTrue(vm.startStream(newOwner) { error("Granted DAT access needs no prompt") })
        factory.last.emitStarted()
        val camera = factory.last.cameras.single()
        camera.stateFlow.value = DatStreamState.STREAMING

        oldCheck.complete(CameraPermissionCheck.Denied)
        assertFalse(oldStart.await())
        assertEquals("Retired checks must be fenced before opening the system prompt", 0, oldPrompts)
        vm.stopStream(oldOwner)
        vm.stopStream() // Legacy disposal must also leave the new owner alone.
        assertEquals(0, camera.stopCalls)
        assertEquals(WearablesViewModel.StreamState.Streaming, vm.streamState.value)
        assertEquals(1, manager.ownerCount)
    }

    @Test
    fun cancelledPublicCaptureCannotPublishOrUnlockANewerCapture() = runTest(dispatcher) {
        val oldPhoto = PhotoData.HEIC(ByteBuffer.wrap(byteArrayOf(1)))
        val newPhoto = PhotoData.HEIC(ByteBuffer.wrap(byteArrayOf(2)))
        val oldBitmap = TestBitmaps.stub()
        val newBitmap = TestBitmaps.stub()
        val vm = viewModel(decodePhoto = { if (it === oldPhoto) oldBitmap else newBitmap })
        val owner = Any()
        assertTrue(vm.startStream(owner) { PermissionStatus.Granted })
        factory.last.emitStarted()
        val camera = factory.last.cameras.single()
        camera.stateFlow.value = DatStreamState.STREAMING
        val completeOld = CompletableDeferred<Unit>()
        val completeNew = CompletableDeferred<Unit>()
        var captures = 0
        camera.onCapture = {
            when (++captures) {
                1 -> {
                    withContext(NonCancellable) { completeOld.await() }
                    PhotoCaptureResult.Success(oldPhoto)
                }
                2 -> {
                    completeNew.await()
                    PhotoCaptureResult.Success(newPhoto)
                }
                else -> error("The current capture must stay locked until it completes")
            }
        }
        try {
            val old = async { vm.capturePhoto(owner) }
            assertEquals(1, captures)
            old.cancel()
            val fresh = async { vm.capturePhoto(owner) }
            assertEquals(2, captures)
            completeOld.complete(Unit)
            old.join()

            assertTrue(old.isCancelled)
            assertNull(vm.capturedPhoto.value)
            assertNull(vm.errorMessage.value)
            assertNull(vm.capturePhoto(owner))
            assertEquals(2, captures)
            assertEquals(0, camera.stopCalls)
            assertEquals(1, manager.ownerCount)

            completeNew.complete(Unit)
            assertSame(newBitmap, fresh.await())
            assertSame(newBitmap, vm.capturedPhoto.value)
        } finally {
            completeOld.complete(Unit)
            completeNew.complete(Unit)
        }
    }
}
