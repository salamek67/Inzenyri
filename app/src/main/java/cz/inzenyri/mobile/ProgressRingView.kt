package cz.inzenyri.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

internal class ProgressRingView(context: Context, private val percent: Int, private val color: Int, private val track: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription = "$percent % správných odpovědí"; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val stroke = size * 0.075f
        val oval = RectF((width-size+stroke)/2, (height-size+stroke)/2, (width+size-stroke)/2, (height+size-stroke)/2)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.strokeCap = Paint.Cap.ROUND
        paint.color = track; canvas.drawOval(oval, paint)
        paint.color = color; canvas.drawArc(oval, -90f, percent.coerceIn(0, 100) * 3.6f, false, paint)
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER; paint.textSize = size * 0.23f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val baseline = height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        canvas.drawText("$percent %", width / 2f, baseline, paint)
    }
}
