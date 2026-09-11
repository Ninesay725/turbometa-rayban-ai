package com.smartview.glassai.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.smartview.glassai.viewmodels.WearablesViewModel

/**
 * The one place that toasts glasses errors (Phase A review Minor #13). Lives above the NavHost so
 * a navigation during an error cannot show the toast twice, and screens keep reading
 * WearablesViewModel.errorMessage / StreamState.Error as state when they need inline text.
 */
@Composable
fun WearablesErrorToast(wearablesViewModel: WearablesViewModel) {
    val context = LocalContext.current
    LaunchedEffect(wearablesViewModel) {
        wearablesViewModel.errorEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
