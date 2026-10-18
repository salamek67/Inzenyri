package cz.inzenyri.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Non-modal speech bubble anchored to a badge, closed on outside touch or scrolling. */
internal class AiReviewBubble(private val context: Context) {
    private var popup: PopupWindow? = null
    private var currentAnchor: View? = null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun dismiss() = popup?.dismiss()

    fun show(anchor: View, name: String, darkMode: Boolean) {
        val wasOpen = currentAnchor === anchor && popup?.isShowing == true
        dismiss()
        if (wasOpen || !anchor.isAttachedToWindow) return
        val frame = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val width = minOf(dp(280), frame.width() - dp(24)).coerceAtLeast(dp(120))
        val color = if (darkMode) Color.rgb(69, 53, 43) else Color.rgb(245, 233, 220)
        val text = TextView(context).apply {
            this.text = "Úkol byl zkontrolován pomocí $name."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (darkMode) Color.rgb(250, 239, 227) else Color.rgb(76, 54, 39))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
        }
        text.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = text.measuredHeight + dp(10)
        val above = location[1] - height - dp(6) >= frame.top
        val center = location[0] + anchor.width / 2
        val left = (center - width / 2).coerceIn(frame.left + dp(12), frame.right - width - dp(12))
        val top = if (above) location[1] - height - dp(6) else location[1] + anchor.height + dp(6)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val tail = object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val tailWidth = this.width.toFloat()
                val tailHeight = this.height.toFloat()
                val path = Path().apply {
                    moveTo(0f, if (above) 0f else tailHeight)
                    lineTo(tailWidth, if (above) 0f else tailHeight)
                    lineTo(tailWidth / 2f, if (above) tailHeight else 0f)
                    close()
                }
                canvas.drawPath(path, paint)
            }
        }
        val tailParams = LinearLayout.LayoutParams(dp(18), dp(10)).apply {
            leftMargin = (center - left - dp(9)).coerceIn(dp(14), width - dp(32))
        }
        if (!above) content.addView(tail, tailParams)
        content.addView(text, LinearLayout.LayoutParams(-1, -2))
        if (above) content.addView(tail, tailParams)
        val window = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        val observer = anchor.viewTreeObserver
        val scrollListener = ViewTreeObserver.OnScrollChangedListener { window.dismiss() }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { window.dismiss() }
        }
        window.setOnDismissListener {
            if (observer.isAlive) observer.removeOnScrollChangedListener(scrollListener)
            anchor.removeOnAttachStateChangeListener(attachListener)
            if (popup === window) { popup = null; currentAnchor = null }
        }
        text.setOnClickListener { window.dismiss() }
        popup = window
        currentAnchor = anchor
        window.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, left, top.coerceAtMost(frame.bottom - height))
        observer.addOnScrollChangedListener(scrollListener)
        anchor.addOnAttachStateChangeListener(attachListener)
    }
}
