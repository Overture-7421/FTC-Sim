package dev.ftcsim.ui.common

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest

private const val TALL_WIDTH = 1280
private const val TALL_HEIGHT = 3200

/**
 * Like `runComposeUiTest`, but in a window tall enough for a whole page of a screen that scrolls, so that a test can
 * click anything on it without scrolling first; a click outside the window would reach nothing.
 */
@OptIn(ExperimentalTestApi::class)
fun runTallComposeUiTest(block: suspend ComposeUiTest.() -> Unit) =
    runDesktopComposeUiTest(width = TALL_WIDTH, height = TALL_HEIGHT, block = block)
