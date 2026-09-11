package com.smartview.glassai.viewmodels

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartview.glassai.R
import com.smartview.glassai.glasses.DisplayCard
import com.smartview.glassai.glasses.DisplayIcon
import com.smartview.glassai.glasses.GlassesDisplayIntegration
import com.smartview.glassai.glasses.GlassesDisplaySink
import com.smartview.glassai.glasses.toLeanEatCard
import com.smartview.glassai.models.FoodNutritionResponse
import com.smartview.glassai.services.LeanEatService
import com.smartview.glassai.utils.APIKeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

fun interface LeanEatAnalyzer {
    suspend fun analyzeFood(image: Bitmap): Result<FoodNutritionResponse>
}

class LeanEatViewModel internal constructor(
    application: Application,
    private val sink: GlassesDisplaySink,
    private val strings: (Int) -> String,
    private val apiKey: () -> String?,
    private val analyzerFactory: (String) -> LeanEatAnalyzer,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        GlassesDisplayIntegration.displayManager(application).ownedSink(Any()),
        application::getString,
        { APIKeyManager.getInstance(application).getAPIKey() },
        { key -> LeanEatService(key) },
    )

    private var leanEatService: LeanEatAnalyzer? = null
    private var analysisJob: Job? = null

    // State
    sealed class ViewState {
        object Idle : ViewState()
        object Capturing : ViewState()
        object Analyzing : ViewState()
        data class Result(val response: FoodNutritionResponse) : ViewState()
        data class Error(val message: String) : ViewState()
    }

    private val _viewState = MutableStateFlow<ViewState>(ViewState.Idle)
    val viewState: StateFlow<ViewState> = _viewState.asStateFlow()

    private val _capturedImage = MutableStateFlow<Bitmap?>(null)
    val capturedImage: StateFlow<Bitmap?> = _capturedImage.asStateFlow()

    private val _nutritionResult = MutableStateFlow<FoodNutritionResponse?>(null)
    val nutritionResult: StateFlow<FoodNutritionResponse?> = _nutritionResult.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    init {
        initializeService()
    }

    private fun initializeService() {
        val key = apiKey()
        if (!key.isNullOrBlank()) {
            leanEatService = analyzerFactory(key)
        }
    }

    fun setCapturedImage(bitmap: Bitmap) {
        _capturedImage.value = bitmap
        _viewState.value = ViewState.Capturing
        _nutritionResult.value = null
    }

    fun analyzeFood() {
        val image = _capturedImage.value
        if (image == null) {
            _errorMessage.value = "No image captured"
            return
        }

        if (leanEatService == null) {
            val key = apiKey()
            if (key.isNullOrBlank()) {
                _errorMessage.value = "API Key not configured"
                _viewState.value = ViewState.Error("API Key not configured")
                return
            }
            leanEatService = analyzerFactory(key)
        }

        cancelAnalysis()
        analysisJob = viewModelScope.launch {
            _viewState.value = ViewState.Analyzing
            _isAnalyzing.value = true
            val title = strings(R.string.feature_leaneat_title)
            sink.show(DisplayCard.Notice(title, strings(R.string.display_analyzing), DisplayIcon.FORK_KNIFE))

            try {
                val result = leanEatService!!.analyzeFood(image)
                // A network operation may return after reset/retake cancelled this analysis.
                currentCoroutineContext().ensureActive()
                val response = result.getOrThrow()
                _nutritionResult.value = response
                _viewState.value = ViewState.Result(response)
                sink.show(response.toLeanEatCard())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val message = e.message ?: "Analysis failed"
                _errorMessage.value = e.message
                _viewState.value = ViewState.Error(message)
                sink.show(DisplayCard.Notice(title, message, DisplayIcon.EXCLAMATION_TRIANGLE))
            } finally {
                // A cancelled, slow request must not clear the next analysis's busy flag.
                if (currentCoroutineContext().isActive) _isAnalyzing.value = false
            }
        }
    }

    fun retakePhoto() {
        reset()
    }

    fun saveImageToGallery(): Boolean {
        val bitmap = _capturedImage.value ?: return false
        val context = getApplication<Application>()

        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "LeanEat_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TurboMeta")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Failed to create media store entry")

            resolver.openOutputStream(uri)?.use { outputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)) {
                    throw IOException("Failed to save bitmap")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            true
        } catch (e: Exception) {
            _errorMessage.value = "Failed to save image: ${e.message}"
            false
        }
    }

    fun clearError() {
        _errorMessage.value = null
        if (_viewState.value is ViewState.Error) {
            _viewState.value = if (_capturedImage.value != null) {
                ViewState.Capturing
            } else {
                ViewState.Idle
            }
        }
    }

    fun reset() {
        cancelAnalysis()
        _capturedImage.value = null
        _nutritionResult.value = null
        _viewState.value = ViewState.Idle
        _errorMessage.value = null
        sink.showStatus()
    }

    private fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _isAnalyzing.value = false
    }

    fun refreshService() {
        leanEatService = null
        initializeService()
    }

    override fun onCleared() {
        super.onCleared()
        cancelAnalysis()
        sink.showStatus()
        _capturedImage.value?.recycle()
    }
}
