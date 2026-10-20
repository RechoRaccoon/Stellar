package com.mediaviewer.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape as FoundationCircleShape
import androidx.compose.foundation.shape.RoundedCornerShape as FoundationRoundedCornerShape

/*
 * Every rounded corner in Stellar comes from here (the app imports these
 * instead of Compose's own), following Settings → UI Customization → UI
 * Shape: Round is exactly Compose's RoundedCornerShape and CircleShape;
 * Squared makes every one of them square; Hybrid (the default) squares
 * these too, but the "soft" shapes below — interaction bars, Jump to Top,
 * the More menu's buttons — stay round. Profile icons and the camera
 * notch bubble are round in every mode.
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

/** Round in Round and Hybrid; square only with UI Shape → Squared. For the
 *  interaction bars, Jump to Top and the More menu's buttons. */
private val softSquared: Boolean get() = com.mediaviewer.util.UiToggles.fullySquare
fun SoftRoundedCornerShape(size: Dp): RoundedCornerShape = if (softSquared) Square else FoundationRoundedCornerShape(size)
fun SoftRoundedCornerShape(percent: Int): RoundedCornerShape = if (softSquared) Square else FoundationRoundedCornerShape(percent)
val SoftCircleShape: RoundedCornerShape get() = if (softSquared) Square else FoundationCircleShape

/** Always a true circle, whatever the UI Shape: profile icons (unless the
 *  profile chose a square one — see profileIconShape) and the camera
 *  notch bubble. */
val AvatarShape: RoundedCornerShape = FoundationCircleShape
val TrueCircleShape: RoundedCornerShape = FoundationCircleShape

/** A profile's own Square Profile Icon: a slightly rounded square in Round,
 *  fully square in Hybrid and Squared. */
val SquareIconShape: RoundedCornerShape get() =
    if (com.mediaviewer.util.UiToggles.squareCorners) Square else FoundationRoundedCornerShape(percent = 24)
