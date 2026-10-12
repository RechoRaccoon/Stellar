package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A comment with media attached is being uploaded / posted (shown above
 *  the comment box; a video's stage comes from [VideoUpload]). */
object CommentSend {
    var sending by mutableStateOf(false)
}
