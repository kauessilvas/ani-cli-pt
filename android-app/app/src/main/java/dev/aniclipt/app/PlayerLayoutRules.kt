package dev.aniclipt.app

internal fun shouldLiftSubtitle(position: String, landscape: Boolean, controlsVisible: Boolean, screenHeightDp: Int): Boolean =
    position == "bottom" && landscape && controlsVisible && screenHeightDp < 430
