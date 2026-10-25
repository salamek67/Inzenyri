package cz.inzenyri.mobile

import android.content.Context
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.util.Base64
import org.json.JSONObject

/** Decode an embedded, decrypted raster in memory. Never writes an attachment to disk. */
internal object QuestionImageView {
    fun create(context: Context, question: JSONObject): View? {
        if (!question.has("image")) return null
        val image = question.optJSONObject("image")
        val bitmap =
            try {
                require(image != null)
                val mime = image.getString("mime")
                require(mime == "image/png" || mime == "image/jpeg")
                val encoded = image.getString("data")
                require(encoded.length in 1..699052)
                val bytes = Base64.getDecoder().decode(encoded)
                require(bytes.size <= 512 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outMimeType == mime)
                require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
                require(
                    image.getInt("width") == bounds.outWidth &&
                        image.getInt("height") == bounds.outHeight,
                )
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid raster")
            } catch (_: Exception) {
                return TextView(context).apply { text = "Obrázek se nepodařilo načíst." }
            }
        return ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = image.optString("alt").ifBlank { "Obrázek k otázce" }
            setImageBitmap(bitmap)
        }
    }
}
