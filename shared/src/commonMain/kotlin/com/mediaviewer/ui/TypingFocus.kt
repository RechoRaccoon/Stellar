package com.mediaviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether a text box the Timeline's keyboard shortcuts must stay out of
 * (the comment box) has the keys right now. While it does, the shortcuts are
 * off so letters, Space and the arrow keys type and move the caret; the
 * moment the box is let go of they're on again.
 */
object TypingFocus {
    var inCommentBox by mutableStateOf(false)
}
