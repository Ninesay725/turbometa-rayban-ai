package com.smartview.glassai.debug

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartview.glassai.R
import com.smartview.glassai.glasses.DisplayAction
import com.smartview.glassai.glasses.DisplayCard
import com.smartview.glassai.glasses.DisplayIcon
import com.smartview.glassai.glasses.DisplayLayout
import com.smartview.glassai.glasses.DisplayNode
import com.smartview.glassai.glasses.DisplayStrings
import com.smartview.glassai.glasses.GlassesDisplayIntegration
import com.smartview.glassai.glasses.NodeAlignment
import com.smartview.glassai.glasses.NodeBackground
import com.smartview.glassai.glasses.NodeButtonStyle
import com.smartview.glassai.glasses.NodeTextColor
import com.smartview.glassai.glasses.NodeTextStyle
import com.smartview.glassai.glasses.ResourceDisplayStrings
import com.smartview.glassai.glasses.decodeDisplayImage
import com.smartview.glassai.glasses.pageCount
import com.smartview.glassai.glasses.toNode
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassesDisplayPreviewScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val application = context.applicationContext as Application
    val strings = remember(context, configuration) { ResourceDisplayStrings(context) }
    val samples = remember(strings) { DisplayPreviewSamples.all(strings) }
    val manager = remember(application) { GlassesDisplayIntegration.displayManager(application) }
    val router = remember(application) { GlassesDisplayIntegration.router(application) }
    val currentCard by manager.currentCard.collectAsStateWithLifecycle()
    var card by remember(samples) { mutableStateOf(samples.first().second) }
    var pickerExpanded by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Real router paging reaches the same currentCard flow used by the display sender.
    LaunchedEffect(currentCard, samples) { card = currentCard ?: samples.first().second }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.display_preview_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.display_preview_subtitle), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.display_preview_actions_hint), style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { pickerExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(samples.first { it.second::class == card::class }.first, Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = pickerExpanded, onDismissRequest = { pickerExpanded = false }) {
                    samples.forEach { (label, sample) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { card = sample; pickerExpanded = false },
                        )
                    }
                }
            }
            val pages = card.pageCount(strings)
            val page = card.previewPage().coerceIn(0, pages - 1)
            if (pages > 1) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { card = card.withPreviewPage(page - 1, strings) }, enabled = page > 0) {
                        Text(stringResource(R.string.display_prev))
                    }
                    Text("${page + 1}/$pages")
                    TextButton(onClick = { card = card.withPreviewPage(page + 1, strings) }, enabled = page < pages - 1) {
                        Text(stringResource(R.string.display_next))
                    }
                }
            }
            DisplayNodePreview(
                node = card.toNode(strings),
                onAction = { action ->
                    // Also update locally when the router emits an equal StateFlow value.
                    when (action) {
                        is DisplayAction.Page -> card = action.card
                        DisplayAction.BackToMenu -> card = samples.first().second
                        else -> Unit
                    }
                    val label = if (action is DisplayAction.Page) {
                        "Page(page=${action.card.previewPage()})"
                    } else {
                        action::class.simpleName.orEmpty()
                    }
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(label)
                    }
                    router.dispatch(action)
                },
            )
        }
    }
}

/** A clipped, black 600×600 logical canvas. Density scales layout, text, icons and hit targets together. */
@Composable
fun DisplayNodePreview(node: DisplayNode, onAction: (DisplayAction) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().background(Color.Black)) {
        val previewDensity = Density((constraints.maxWidth.toFloat() / DisplayLayout.VIEWPORT).coerceAtLeast(0.01f), fontScale = 1f)
        CompositionLocalProvider(LocalDensity provides previewDensity) {
            PreviewNode(node, onAction, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PreviewNode(node: DisplayNode, onAction: (DisplayAction) -> Unit, modifier: Modifier = Modifier) {
    when (node) {
        is DisplayNode.Column -> Column(
            modifier.fillMaxWidth().nodeBox(node.background, node.padding, node.paddingTop, node.paddingBottom, node.paddingStart, node.paddingEnd),
            verticalArrangement = Arrangement.spacedBy(node.gap.dp, node.alignment.vertical()),
            horizontalAlignment = node.crossAlignment.horizontal(),
        ) {
            node.children.forEach { child ->
                var childModifier: Modifier = Modifier
                if (child.flexGrow() > 0f) childModifier = childModifier.weight(child.flexGrow())
                if (node.crossAlignment == NodeAlignment.STRETCH) childModifier = childModifier.fillMaxWidth()
                PreviewNode(child, onAction, childModifier)
            }
        }
        is DisplayNode.Row -> Row(
            modifier.fillMaxWidth().nodeBox(node.background, node.padding, node.paddingTop, node.paddingBottom, node.paddingStart, node.paddingEnd),
            horizontalArrangement = Arrangement.spacedBy(node.gap.dp, node.alignment.horizontal()),
            verticalAlignment = node.crossAlignment.vertical(),
        ) {
            node.children.forEach { child ->
                var childModifier: Modifier = Modifier
                if (child.flexGrow() > 0f) childModifier = childModifier.weight(child.flexGrow())
                if (node.crossAlignment == NodeAlignment.STRETCH) childModifier = childModifier.fillMaxHeight()
                PreviewNode(child, onAction, childModifier)
            }
        }
        is DisplayNode.Text -> Text(
            text = node.text,
            modifier = modifier,
            color = if (node.color == NodeTextColor.PRIMARY) Color.White else Color.White.copy(alpha = 0.7f),
            fontSize = node.style.fontSize.sp,
            fontWeight = if (node.style == NodeTextStyle.HEADING) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.SansSerif,
            lineHeight = node.style.lineHeight.sp,
        )
        is DisplayNode.Icon -> PreviewIcon(node.icon, modifier, outline = node.outline)
        is DisplayNode.Image -> {
            var bitmap by remember(node) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(node) {
                bitmap = withContext(Dispatchers.Default) { decodeDisplayImage(node) }
            }
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = null, modifier = modifier.size(node.size.dp))
            }
        }
        is DisplayNode.Button -> PreviewButton(node, onAction, modifier)
        is DisplayNode.ButtonGroup -> Row(
            modifier.fillMaxWidth().height(DisplayLayout.BUTTON_HEIGHT.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, node.alignment.horizontal()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.buttons.forEach { button ->
                val compactGroup = node.buttons.size == 4
                val iconOnly = compactGroup && button.action is DisplayAction.Page && button.icon != null
                val buttonModifier = when {
                    iconOnly -> Modifier.width(DisplayLayout.BUTTON_HEIGHT.dp)
                    compactGroup -> Modifier.weight(1f)
                    else -> Modifier
                }
                PreviewButton(button, onAction, buttonModifier, iconOnly)
            }
        }
    }
}

@Composable
private fun PreviewButton(
    node: DisplayNode.Button, onAction: (DisplayAction) -> Unit, modifier: Modifier = Modifier, iconOnly: Boolean = false,
) {
    // Full labels remain available to accessibility even when the narrow preview hides/ellipsizes them.
    val buttonModifier = modifier.height(DisplayLayout.BUTTON_HEIGHT.dp).semantics {
        if (node.label.isNotEmpty()) contentDescription = node.label
    }
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            node.icon?.let { PreviewIcon(it, tint = if (node.style == NodeButtonStyle.PRIMARY) Color.Black else Color.White) }
            // Button glyphs/typeface are approximations; the fixed 88-unit height is from Nova.
            if (!iconOnly && node.label.isNotEmpty()) Text(node.label, fontSize = NodeTextStyle.BODY.fontSize.sp,
                lineHeight = NodeTextStyle.BODY.lineHeight.sp, fontFamily = FontFamily.SansSerif,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (node.style == NodeButtonStyle.OUTLINE) {
        OutlinedButton(
            onClick = { onAction(node.action) }, modifier = buttonModifier,
            border = BorderStroke(1.dp, Color.White),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) { content() }
    } else {
        Button(
            onClick = { onAction(node.action) }, modifier = buttonModifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (node.style == NodeButtonStyle.PRIMARY) Color.White else Color(0xFF333333),
                contentColor = if (node.style == NodeButtonStyle.PRIMARY) Color.Black else Color.White,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) { content() }
    }
}

@Composable
private fun PreviewIcon(icon: DisplayIcon, modifier: Modifier = Modifier, outline: Boolean = false, tint: Color = Color.White) {
    val glyph = when (icon) {
        DisplayIcon.SMART_GLASSES -> Icons.Default.Visibility
        DisplayIcon.META_AI -> Icons.Default.AutoAwesome
        DisplayIcon.EYE -> Icons.Default.RemoveRedEye
        DisplayIcon.FORK_KNIFE -> Icons.Default.Restaurant
        DisplayIcon.CODE -> Icons.Default.Code
        DisplayIcon.CHECKMARK -> Icons.Default.Check
        DisplayIcon.X -> Icons.Default.Close
        DisplayIcon.EXCLAMATION_TRIANGLE -> Icons.Default.Warning
        DisplayIcon.I_CIRCLE -> Icons.Default.Info
        DisplayIcon.ARROW_LEFT -> Icons.AutoMirrored.Filled.ArrowBack
        DisplayIcon.ARROW_RIGHT -> Icons.AutoMirrored.Filled.ArrowForward
        DisplayIcon.VIDEO_CAMERA -> Icons.Default.Videocam
        DisplayIcon.MUSIC_NOTE -> Icons.Default.MusicNote
        DisplayIcon.ENVELOPE_OPEN -> Icons.Default.Email
        DisplayIcon.SPEAKER_WITH_THREE_ARCS -> Icons.Default.VolumeUp
        DisplayIcon.TWO_ARROWS_CLOCKWISE -> Icons.Default.Refresh
        else -> null
    }
    if (glyph != null && !outline) {
        Icon(glyph, contentDescription = icon.name, modifier = modifier.size(24.dp), tint = tint)
    } else {
        Box(modifier.size(24.dp).border(1.dp, tint), contentAlignment = Alignment.Center) {
            Text(icon.name, color = tint, fontSize = 8.sp, lineHeight = 8.sp, maxLines = 3)
        }
    }
}

private fun Modifier.nodeBox(
    background: NodeBackground, padding: Int, top: Int?, bottom: Int?, start: Int?, end: Int?,
): Modifier = (if (background == NodeBackground.CARD) this.background(Color(0xFF1D1D1D), RoundedCornerShape(16.dp)) else this)
    .padding(start = (start ?: padding).dp, top = (top ?: padding).dp, end = (end ?: padding).dp, bottom = (bottom ?: padding).dp)

private fun DisplayNode.flexGrow(): Float = when (this) {
    is DisplayNode.Column -> flexGrow
    is DisplayNode.Row -> flexGrow
    is DisplayNode.Text -> flexGrow
    else -> 0f
}

private fun NodeAlignment.horizontal(): Alignment.Horizontal = when (this) {
    NodeAlignment.CENTER -> Alignment.CenterHorizontally
    NodeAlignment.END -> Alignment.End
    else -> Alignment.Start
}

private fun NodeAlignment.vertical(): Alignment.Vertical = when (this) {
    NodeAlignment.CENTER -> Alignment.CenterVertically
    NodeAlignment.END -> Alignment.Bottom
    else -> Alignment.Top
}

private fun DisplayCard.previewPage(): Int = when (this) {
    is DisplayCard.WeChat -> page
    is DisplayCard.QuickVision -> page
    is DisplayCard.LeanEat -> page
    is DisplayCard.OpenClaw -> page
    else -> 0
}

private fun DisplayCard.withPreviewPage(page: Int, strings: DisplayStrings): DisplayCard {
    val boundedPage = page.coerceIn(0, pageCount(strings) - 1)
    return when (this) {
        is DisplayCard.WeChat -> copy(page = boundedPage)
        is DisplayCard.QuickVision -> copy(page = boundedPage)
        is DisplayCard.LeanEat -> copy(page = boundedPage)
        is DisplayCard.OpenClaw -> copy(page = boundedPage)
        else -> this
    }
}
