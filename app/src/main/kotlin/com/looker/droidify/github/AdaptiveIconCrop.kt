package com.looker.droidify.github

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Adaptive icon layers are 108dp wide but only the centre 72dp (2/3) is visible, so the
 * foreground raster of one shows the logo shrunken inside a transparent padding.
 */
object AdaptiveIconCrop {

    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    /** The centre 2/3 of an image of the given size: one sixth is cut off at each side. */
    fun visibleArea(width: Int, height: Int): Rect {
        val x = width / 6
        val y = height / 6
        return Rect(x, y, maxOf(1, width - 2 * x), maxOf(1, height - 2 * y))
    }

    /**
     * Crops the raster [bytes] of an adaptive icon foreground to its visible area and returns
     * it as ("png", bytes), or null when it cannot be decoded.
     */
    fun cropToVisibleArea(bytes: ByteArray): Pair<String, ByteArray>? {
        val bitmap = (try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }) ?: return null
        var cropped: Bitmap? = null
        return try {
            val area = visibleArea(bitmap.width, bitmap.height)
            cropped = Bitmap.createBitmap(bitmap, area.x, area.y, area.width, area.height)
            val output = ByteArrayOutputStream()
            if (cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "png" to output.toByteArray()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            if (cropped != null && cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
        }
    }
}
