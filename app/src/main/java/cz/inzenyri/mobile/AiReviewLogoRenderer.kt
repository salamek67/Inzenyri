package cz.inzenyri.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas

/** Rasterize each logo once, independently of the GPU's vector drawable cache. */
internal class AiReviewLogoRenderer(private val context: Context) {
    private val bitmaps = mutableMapOf<Int, Bitmap>()

    fun bitmap(resource: Int): Bitmap = bitmaps.getOrPut(resource) {
        val size = (24 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.density = context.resources.displayMetrics.densityDpi
            val logo = requireNotNull(context.getDrawable(resource)).mutate()
            logo.clearColorFilter()
            logo.alpha = 255
            logo.setBounds(0, 0, size, size)
            logo.draw(Canvas(bitmap))
        }
    }
}
