package com.smartview.glassai.glasses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.meta.wearable.dat.display.views.*

/**
 * The only SDK DSL interpreter. A single container is required at the root. Its flexGrow is
 * intentionally ignored: DAT 0.9.0 exposes flexGrow only on nested FlexBoxScope builders.
 */
fun ContentScope.render(node: DisplayNode, dispatch: (DisplayAction) -> Unit) {
    when (node) {
        is DisplayNode.Column -> flexBox(
            direction = Direction.COLUMN, gap = node.gap,
            alignment = node.alignment.toAlignment(), crossAlignment = node.crossAlignment.toAlignment(),
            padding = node.padding, paddingTop = node.paddingTop, paddingBottom = node.paddingBottom,
            paddingStart = node.paddingStart, paddingEnd = node.paddingEnd,
            background = node.background.toFlexBoxBackground(),
        ) { node.children.forEach { renderChild(it, dispatch) } }
        is DisplayNode.Row -> flexBox(
            direction = Direction.ROW, gap = node.gap,
            alignment = node.alignment.toAlignment(), crossAlignment = node.crossAlignment.toAlignment(),
            padding = node.padding, paddingTop = node.paddingTop, paddingBottom = node.paddingBottom,
            paddingStart = node.paddingStart, paddingEnd = node.paddingEnd,
            background = node.background.toFlexBoxBackground(),
        ) { node.children.forEach { renderChild(it, dispatch) } }
        is DisplayNode.Text, is DisplayNode.Icon, is DisplayNode.Image, is DisplayNode.Button, is DisplayNode.ButtonGroup ->
            throw IllegalArgumentException("Display content must have a Column or Row root")
    }
}

private fun FlexBoxScope.renderChild(node: DisplayNode, dispatch: (DisplayAction) -> Unit) {
    when (node) {
        is DisplayNode.Column -> flexBox(
            direction = Direction.COLUMN, gap = node.gap,
            alignment = node.alignment.toAlignment(), crossAlignment = node.crossAlignment.toAlignment(),
            padding = node.padding, paddingTop = node.paddingTop, paddingBottom = node.paddingBottom,
            paddingStart = node.paddingStart, paddingEnd = node.paddingEnd,
            background = node.background.toFlexBoxBackground(), flexGrow = node.flexGrow,
        ) { node.children.forEach { renderChild(it, dispatch) } }
        is DisplayNode.Row -> flexBox(
            direction = Direction.ROW, gap = node.gap,
            alignment = node.alignment.toAlignment(), crossAlignment = node.crossAlignment.toAlignment(),
            padding = node.padding, paddingTop = node.paddingTop, paddingBottom = node.paddingBottom,
            paddingStart = node.paddingStart, paddingEnd = node.paddingEnd,
            background = node.background.toFlexBoxBackground(), flexGrow = node.flexGrow,
        ) { node.children.forEach { renderChild(it, dispatch) } }
        is DisplayNode.Text -> text(
            node.text, style = node.style.toTextStyle(), color = node.color.toTextColor(),
            flexGrow = node.flexGrow,
        )
        is DisplayNode.Icon -> icon(
            node.icon.toIconName(), style = if (node.outline) IconStyle.OUTLINE else IconStyle.FILLED,
        )
        is DisplayNode.Image -> decodeDisplayImage(node)?.let { bitmap ->
            // DAT 0.9 retains the Bitmap until image resolution; do not recycle after this call.
            image(bitmap = bitmap, sizePreset = ImageSize.FILL)
        }
        is DisplayNode.Button -> button(
            label = node.label, style = node.style.toButtonStyle(), iconName = node.icon?.toIconName(),
            onClick = { dispatch(node.action) },
        )
        is DisplayNode.ButtonGroup -> buttonGroup(alignment = node.alignment.toButtonGroupAlignment()) {
            node.buttons.forEach { item ->
                button(
                    label = item.label, style = item.style.toButtonStyle(), iconName = item.icon?.toIconName(),
                    onClick = { dispatch(item.action) },
                )
            }
        }
    }
}

/**
 * Called on the sender's IO path (and by the debug preview on Default). Reject invalid/oversized
 * input before allocating pixels. Missing art is decorative: surrounding text/actions still render.
 * The platform reader already reduces art to <=240 px; this boundary never decodes a full-size photo.
 */
internal fun decodeDisplayImage(node: DisplayNode.Image): Bitmap? {
    val bytes = node.jpeg
    if (bytes.isEmpty() || bytes.size > 256 * 1024) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..DisplayLayout.ART_SIZE ||
            bounds.outHeight !in 1..DisplayLayout.ART_SIZE) return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (decoded.width == node.size && decoded.height == node.size) return decoded
        // The SDK resolves FILL's aspect ratio from the bitmap. Letterboxing makes that ratio 1,
        // so portrait art cannot grow taller than the square space reserved in the pure layout.
        try {
            val square = Bitmap.createBitmap(node.size, node.size, Bitmap.Config.ARGB_8888)
            val scale = node.size.toFloat() / maxOf(decoded.width, decoded.height)
            val width = decoded.width * scale
            val height = decoded.height * scale
            val left = (node.size - width) / 2
            val top = (node.size - height) / 2
            Canvas(square).apply {
                drawColor(Color.BLACK)
                drawBitmap(decoded, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            square
        } finally {
            decoded.recycle()
        }
    } catch (_: RuntimeException) {
        null
    }
}

/** The app catalog deliberately uses the SDK names; the mapping tests guard SDK changes. */
fun DisplayIcon.toIconName(): IconName = IconName.valueOf(name)

internal fun NodeTextStyle.toTextStyle(): TextStyle = when (this) {
    NodeTextStyle.HEADING -> TextStyle.HEADING
    NodeTextStyle.BODY -> TextStyle.BODY
    NodeTextStyle.META -> TextStyle.META
}

internal fun NodeTextColor.toTextColor(): TextColor = when (this) {
    NodeTextColor.PRIMARY -> TextColor.PRIMARY
    NodeTextColor.SECONDARY -> TextColor.SECONDARY
}

internal fun NodeButtonStyle.toButtonStyle(): ButtonStyle = when (this) {
    NodeButtonStyle.PRIMARY -> ButtonStyle.PRIMARY
    NodeButtonStyle.SECONDARY -> ButtonStyle.SECONDARY
    NodeButtonStyle.OUTLINE -> ButtonStyle.OUTLINE
}

internal fun NodeBackground.toFlexBoxBackground(): FlexBoxBackground = when (this) {
    NodeBackground.NONE -> FlexBoxBackground.NONE
    NodeBackground.CARD -> FlexBoxBackground.CARD
}

internal fun NodeAlignment.toAlignment(): Alignment = when (this) {
    NodeAlignment.START -> Alignment.START
    NodeAlignment.CENTER -> Alignment.CENTER
    NodeAlignment.END -> Alignment.END
    NodeAlignment.STRETCH -> Alignment.STRETCH
}

internal fun NodeAlignment.toButtonGroupAlignment(): ButtonGroupAlignment = when (this) {
    NodeAlignment.START -> ButtonGroupAlignment.START
    NodeAlignment.CENTER, NodeAlignment.STRETCH -> ButtonGroupAlignment.CENTER
    NodeAlignment.END -> ButtonGroupAlignment.END
}
