package com.smartview.glassai.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.smartview.glassai.R
import com.smartview.glassai.glasses.DisplayCard
import com.smartview.glassai.glasses.DisplayIcon
import com.smartview.glassai.glasses.LeanEatFood
import com.smartview.glassai.glasses.RecordingDisplaySink
import com.smartview.glassai.glasses.TestBitmaps
import com.smartview.glassai.models.FoodItem
import com.smartview.glassai.models.FoodNutritionResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LeanEatViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val sink = RecordingDisplaySink()
    private val viewModels = ViewModelStore()
    private val response = FoodNutritionResponse(
        foods = listOf(FoodItem("Rice", "1 bowl", 200, 4.5, 0.5, 40.0)),
        totalCalories = 200, totalProtein = 4.5, totalFat = 0.5, totalCarbs = 40.9,
        healthScore = 75, suggestions = listOf("More vegetables"),
    )

    private fun str(id: Int) = "str:$id"

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        key: String? = "sk-test",
        analyze: suspend () -> Result<FoodNutritionResponse> = { Result.success(response) },
    ) = LeanEatViewModel(
        Application(), sink, ::str, { key }, { LeanEatAnalyzer { analyze() } },
    ).also {
        viewModels.put("leanEat", it)
        it.setCapturedImage(TestBitmaps.stub())
    }

    @Test fun analyzingShowsTheAnalyzingNotice() {
        val result = CompletableDeferred<Result<FoodNutritionResponse>>()
        val vm = newViewModel { result.await() }

        vm.analyzeFood()

        assertEquals(LeanEatViewModel.ViewState.Analyzing, vm.viewState.value)
        assertTrue(vm.isAnalyzing.value)
        assertEquals(
            DisplayCard.Notice(str(R.string.feature_leaneat_title), str(R.string.display_analyzing), DisplayIcon.FORK_KNIFE),
            sink.last,
        )
    }

    @Test fun successShowsTheLeanEatCardMappedFromTheResponse() {
        val vm = newViewModel()
        vm.analyzeFood()

        assertEquals(LeanEatViewModel.ViewState.Result(response), vm.viewState.value)
        assertEquals(
            DisplayCard.LeanEat(200, 4, 0, 40, 75, listOf(LeanEatFood("Rice", "1 bowl", 200)), listOf("More vegetables")),
            sink.last,
        )
        assertFalse(vm.isAnalyzing.value)
    }

    @Test fun failureShowsAnErrorNotice() {
        val vm = newViewModel { Result.failure(IllegalStateException("Service unavailable")) }
        vm.analyzeFood()

        assertEquals(LeanEatViewModel.ViewState.Error("Service unavailable"), vm.viewState.value)
        assertEquals(
            DisplayCard.Notice(str(R.string.feature_leaneat_title), "Service unavailable", DisplayIcon.EXCLAMATION_TRIANGLE),
            sink.last,
        )
        assertFalse(vm.isAnalyzing.value)
    }

    @Test fun thrownFailureShowsAnErrorNotice() {
        val vm = newViewModel { throw IllegalStateException("Offline") }
        vm.analyzeFood()
        assertEquals("Offline", (sink.last as DisplayCard.Notice).body)
        assertEquals(DisplayIcon.EXCLAMATION_TRIANGLE, (sink.last as DisplayCard.Notice).icon)
    }

    @Test fun retakeAndResetReturnToTheStatusMenu() {
        val vm = newViewModel()
        vm.analyzeFood()
        vm.retakePhoto()
        assertEquals(1, sink.statusCalls)
        assertNull(vm.nutritionResult.value)

        vm.setCapturedImage(TestBitmaps.stub())
        vm.analyzeFood()
        vm.reset()
        assertEquals(2, sink.statusCalls)
        assertEquals(LeanEatViewModel.ViewState.Idle, vm.viewState.value)
    }

    @Test fun missingApiKeyShowsNothingOnTheGlasses() {
        val vm = newViewModel(key = null)
        vm.analyzeFood()
        assertTrue(vm.viewState.value is LeanEatViewModel.ViewState.Error)
        assertTrue(sink.shown.isEmpty())
    }

    @Test fun onClearedReturnsToStatus() {
        val vm = newViewModel()
        vm.analyzeFood()
        viewModels.clear()
        assertEquals(1, sink.statusCalls)
    }

    @Test fun onClearedDuringAnalysisCannotRestoreTheFeatureCard() {
        val result = CompletableDeferred<Result<FoodNutritionResponse>>()
        val vm = newViewModel { withContext(NonCancellable) { result.await() } }
        vm.analyzeFood()
        viewModels.clear()
        result.complete(Result.success(response))

        assertEquals(1, sink.statusCalls)
        assertEquals(1, sink.shown.size)
        assertNull(vm.nutritionResult.value)
        assertFalse(vm.isAnalyzing.value)
    }

    @Test fun resetDuringAnalysisCannotRestoreAStaleResult() {
        val result = CompletableDeferred<Result<FoodNutritionResponse>>()
        // An HTTP operation can finish after cancellation. Even then it must not publish a card.
        val vm = newViewModel { withContext(NonCancellable) { result.await() } }
        vm.analyzeFood()
        // Screen disposal resets the retained ViewModel; another feature can now own the display.
        vm.reset()
        val nextFeature = DisplayCard.OpenClaw("Next question", "Current answer", true)
        sink.show(nextFeature)
        val shownAtExit = sink.shown.size

        result.complete(Result.success(response))

        assertEquals(shownAtExit, sink.shown.size)
        assertEquals(nextFeature, sink.last)
        assertEquals(1, sink.statusCalls)
        assertEquals(LeanEatViewModel.ViewState.Idle, vm.viewState.value)
        assertNull(vm.nutritionResult.value)
        assertFalse(vm.isAnalyzing.value)
    }

    @Test fun incomingFramesDoNotCancelTheCurrentAnalysis() {
        val result = CompletableDeferred<Result<FoodNutritionResponse>>()
        val vm = newViewModel { result.await() }
        vm.analyzeFood()

        vm.setCapturedImage(TestBitmaps.stub())
        assertTrue(vm.isAnalyzing.value)
        result.complete(Result.success(response))

        assertEquals(response, vm.nutritionResult.value)
        assertEquals(LeanEatViewModel.ViewState.Result(response), vm.viewState.value)
        assertTrue(sink.last is DisplayCard.LeanEat)
        assertFalse(vm.isAnalyzing.value)
    }

    @Test fun retakeDuringAnalysisCannotRestoreAnError() {
        val result = CompletableDeferred<Result<FoodNutritionResponse>>()
        val vm = newViewModel { withContext(NonCancellable) { result.await() } }
        vm.analyzeFood()
        vm.retakePhoto()
        result.complete(Result.failure(IllegalStateException("Late failure")))

        assertEquals(1, sink.shown.size) // only the original analyzing notice
        assertEquals(1, sink.statusCalls)
        assertEquals(LeanEatViewModel.ViewState.Idle, vm.viewState.value)
        assertNull(vm.errorMessage.value)
    }

    @Test fun cancelledAnalysisCannotClearTheNewAnalysisBusyFlag() {
        val first = CompletableDeferred<Result<FoodNutritionResponse>>()
        val second = CompletableDeferred<Result<FoodNutritionResponse>>()
        var calls = 0
        val vm = newViewModel {
            if (++calls == 1) withContext(NonCancellable) { first.await() } else second.await()
        }
        vm.analyzeFood()
        vm.retakePhoto()
        vm.setCapturedImage(TestBitmaps.stub())
        vm.analyzeFood()
        first.complete(Result.success(response))

        assertTrue(vm.isAnalyzing.value)
        assertEquals(LeanEatViewModel.ViewState.Analyzing, vm.viewState.value)
        second.complete(Result.success(response))
        assertFalse(vm.isAnalyzing.value)
        assertTrue(sink.last is DisplayCard.LeanEat)
    }
}
