package com.smartview.glassai

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.smartview.glassai.glasses.GlassesDisplayIntegration
import com.smartview.glassai.managers.LanguageManager
import com.smartview.glassai.ui.navigation.TurboMetaNavigation
import com.smartview.glassai.ui.theme.TurboMetaTheme
import com.smartview.glassai.viewmodels.WearablesViewModel
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : AppCompatActivity() {

    companion object {
        // Android permissions the DAT SDK needs before registration / device monitoring.
        // RECORD_AUDIO is NOT here any more: Live AI and the wake word request it when needed.
        val PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_CONNECT
        )

        // Requested at launch on API 33+ so the foreground-service notifications are visible,
        // but never a prerequisite for the SDK.
        private val OPTIONAL_PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
    }

    val wearablesViewModel: WearablesViewModel by viewModels()

    private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val permissionMutex = Mutex()
    private var monitoringStarted = false

    // Android permissions launcher - must be registered at creation time
    private val androidPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsResult ->
        val requiredGranted = PERMISSIONS.all { permission ->
            permissionsResult[permission] ?: isGranted(permission)
        }
        if (requiredGranted) {
            startWearablesMonitoring()
        } else {
            wearablesViewModel.setError(getString(R.string.permission_all_required))
        }
    }

    // Requesting wearable device permissions via the Meta AI app
    private val wearablesPermissionLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(permissionStatus)
        permissionContinuation = null
    }

    // Request wearables permission in a sequential manner
    suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                permissionContinuation = continuation
                continuation.invokeOnCancellation { permissionContinuation = null }
                wearablesPermissionLauncher.launch(permission)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Custom locale storage must be restored before AppCompat wraps the Activity context.
        // Restoring in onCreate is too late for cold starts on Android 12 and earlier.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) LanguageManager.init(newBase)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Language Manager (for app language switching)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LanguageManager.init(this)

        // Check and request permissions
        checkAndRequestPermissions()

        setContent {
            TurboMetaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TurboMetaNavigation(
                        wearablesViewModel = wearablesViewModel,
                        onRequestWearablesPermission = ::requestWearablesPermission,
                        navigationRequests = GlassesDisplayIntegration.navigationRequests
                    )
                }
            }
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun checkAndRequestPermissions() {
        val missing = (PERMISSIONS + OPTIONAL_PERMISSIONS).filter { !isGranted(it) }.toTypedArray()

        if (PERMISSIONS.all { isGranted(it) }) {
            // Required permissions already granted: start monitoring now
            startWearablesMonitoring()
        }
        if (missing.isNotEmpty()) {
            // Ask for whatever is missing (required and/or POST_NOTIFICATIONS)
            androidPermissionsLauncher.launch(missing)
        }
    }

    private fun startWearablesMonitoring() {
        if (monitoringStarted) return
        monitoringStarted = true

        // Wearables.initialize() already ran in TurboMetaApplication.onCreate().
        // Start observing Wearables state once the Bluetooth runtime permissions are granted.
        wearablesViewModel.startMonitoring()
        GlassesDisplayIntegration.ensureStarted(application)
    }
}
