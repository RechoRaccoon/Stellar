package com.mediaviewer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediaviewer.platform.PlatformKind
import com.mediaviewer.platform.currentPlatform

/**
 * iOS has no key or gesture that puts the keyboard away, so the app does it:
 * a tap anywhere that isn't a text field (or a row marked with
 * [keyboardRegion], like a chat's send bar) closes it. Every text field in
 * the app registers where it is through the [BasicTextField] wrappers below.
 */
internal object KeyboardFields {
    private val regions = HashMap<Any, Rect>()
    private val focused = HashSet<Any>()

    val anyFocused: Boolean get() = focused.isNotEmpty()

    fun place(key: Any, rect: Rect) { regions[key] = rect }
    fun focus(key: Any, on: Boolean) { if (on) focused.add(key) else focused.remove(key) }
    fun remove(key: Any) { regions.remove(key); focused.remove(key) }
    fun covers(point: Offset): Boolean = regions.values.any { it.contains(point) }
}

/** Marks this as somewhere a tap keeps the keyboard up (a field's own
 *  bubble, the send button beside it). */
@Composable
fun Modifier.keyboardRegion(padding: Dp = 8.dp): Modifier {
    val key = remember { Any() }
    val pad = with(LocalDensity.current) { padding.toPx() }
    DisposableEffect(key) { onDispose { KeyboardFields.remove(key) } }
    return this.onGloballyPositioned { KeyboardFields.place(key, it.boundsInRoot().inflate(pad)) }
}

@Composable
private fun Modifier.trackedTextField(): Modifier {
    val key = remember { Any() }
    val pad = with(LocalDensity.current) { 8.dp.toPx() }
    DisposableEffect(key) { onDispose { KeyboardFields.remove(key) } }
    return this
        .onGloballyPositioned { KeyboardFields.place(key, it.boundsInRoot().inflate(pad)) }
        .onFocusChanged { KeyboardFields.focus(key, it.isFocused) }
}

/** On the app's root: a touch outside every text field puts the keyboard
 *  away. Android keeps its own behaviour (its keyboard has a close key). */
@Composable
fun Modifier.dismissKeyboardOnOutsideTap(enabled: Boolean = currentPlatform == PlatformKind.IOS): Modifier {
    if (!enabled) return this
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return this.pointerInput(Unit) {
        awaitEachGesture {
            // Seen on the way down to the children; never consumed, so
            // whatever was touched still gets the touch.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (KeyboardFields.anyFocused && !KeyboardFields.covers(down.position)) {
                focus.clearFocus()
                keyboard?.hide()
            }
        }
    }
}

/** The same BasicTextField as Compose's, plus the bookkeeping above. */
@Composable
fun BasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(Color.Black),
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { innerTextField -> innerTextField() }
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValueChange, modifier = modifier.trackedTextField(),
        enabled = enabled, readOnly = readOnly, textStyle = textStyle,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        visualTransformation = visualTransformation, onTextLayout = onTextLayout,
        interactionSource = interactionSource, cursorBrush = cursorBrush, decorationBox = decorationBox
    )
}

@Composable
fun BasicTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = TextStyle.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(Color.Black),
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { innerTextField -> innerTextField() }
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValueChange, modifier = modifier.trackedTextField(),
        enabled = enabled, readOnly = readOnly, textStyle = textStyle,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        singleLine = singleLine, maxLines = maxLines, minLines = minLines,
        visualTransformation = visualTransformation, onTextLayout = onTextLayout,
        interactionSource = interactionSource, cursorBrush = cursorBrush, decorationBox = decorationBox
    )
}
