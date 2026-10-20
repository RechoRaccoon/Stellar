package com.mediaviewer.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape as FoundationCircleShape
import androidx.compose.foundation.shape.RoundedCornerShape as FoundationRoundedCornerShape

/*
 * Every rounded corner in Stellar comes from here (the app imports these
 * instead of Compose's own): normally they're exactly Compose's
 * RoundedCornerShape and CircleShape; with Settings → UI Customization →
 * Squared-Off Corners on, every one of them is square — except profile
 * icons and the camera notch bubble, which stay round (see AvatarShape).
 */

typealias RoundedCornerShape = FoundationRoundedCornerShape

private val Square = FoundationRoundedCornerShape(0)
private val squared: Boolean get() = com.mediaviewer.util.UiToggles.squareCorners

fun RoundedCornerShape(corner: CornerSize): RoundedCornerShape = if (squared) Square else FoundationRoundedCornerShape(corner)
fun RoundedCornerShape(size: Dp): RoundedCornerShape = if (squared) Square else FoundationRoundedCornerShape(size)
fun RoundedCornerShape(size: Float): RoundedCornerShape = if (squared) Square else FoundationRoundedCornerShape(size)
fun RoundedCornerShape(percent: Int): RoundedCornerShape = if (squared) Square else FoundationRoundedCornerShape(percent)
fun RoundedCornerShape(topStart: Dp = 0.dp, topEnd: Dp = 0.dp, bottomEnd: Dp = 0.dp, bottomStart: Dp = 0.dp): RoundedCornerShape =
    if (squared) Square else FoundationRoundedCornerShape(topStart = topStart, topEnd = topEnd, bottomEnd = bottomEnd, bottomStart = bottomStart)
fun RoundedCornerShape(topStart: Float = 0f, topEnd: Float = 0f, bottomEnd: Float = 0f, bottomStart: Float = 0f): RoundedCornerShape =
    if (squared) Square else FoundationRoundedCornerShape(topStart = topStart, topEnd = topEnd, bottomEnd = bottomEnd, bottomStart = bottomStart)
fun RoundedCornerShape(topStartPercent: Int = 0, topEndPercent: Int = 0, bottomEndPercent: Int = 0, bottomStartPercent: Int = 0): RoundedCornerShape =
    if (squared) Square else FoundationRoundedCornerShape(
        topStartPercent = topStartPercent, topEndPercent = topEndPercent, bottomEndPercent = bottomEndPercent, bottomStartPercent = bottomStartPercent
    )

/** A circle — or, with Squared-Off Corners on, a square. */
val CircleShape: RoundedCornerShape get() = if (squared) Square else FoundationCircleShape

/** Always a true circle, even with Squared-Off Corners on: profile icons (unless the
 *  profile chose a square one — see profileIconShape) and the camera
 *  notch bubble. */
val AvatarShape: RoundedCornerShape = FoundationCircleShape
val TrueCircleShape: RoundedCornerShape = FoundationCircleShape

/** A profile's own Square Profile Icon: a slightly rounded square, or
 *  fully square with Squared-Off Corners on. */
val SquareIconShape: RoundedCornerShape get() =
    if (com.mediaviewer.util.UiToggles.squareCorners) Square else FoundationRoundedCornerShape(percent = 24)
