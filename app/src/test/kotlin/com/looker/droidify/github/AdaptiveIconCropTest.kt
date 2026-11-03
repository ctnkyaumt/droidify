package com.looker.droidify.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AdaptiveIconCropTest {

    @Test
    fun `crops a square foreground to its centre two thirds`() {
        assertEquals(AdaptiveIconCrop.Rect(x = 72, y = 72, width = 288, height = 288), AdaptiveIconCrop.visibleArea(432, 432))
    }

    @Test
    fun `crops a non square foreground per axis`() {
        assertEquals(AdaptiveIconCrop.Rect(x = 100, y = 50, width = 400, height = 200), AdaptiveIconCrop.visibleArea(600, 300))
    }

    @Test
    fun `keeps at least one pixel`() {
        assertEquals(AdaptiveIconCrop.Rect(x = 0, y = 0, width = 5, height = 1), AdaptiveIconCrop.visibleArea(5, 1))
    }
}
