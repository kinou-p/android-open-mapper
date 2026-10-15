package com.kinou.gameassist.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Lays [content] out left to right inside a right-to-left screen (Hebrew). For text that is never
 * translated and is read left to right: JSON, a package name, a technical report. Without it the
 * punctuation of such text is mirrored and the lines are aligned to the wrong side.
 */
@Composable
internal fun LtrContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}
