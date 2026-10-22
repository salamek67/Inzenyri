package cz.inzenyri.mobile

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Call on the UI thread; a running operation cannot be dismissed accidentally. */
internal class OperationProgressDialog(
    private val activity: Activity,
    title: String,
    private val steps: List<String>,
    darkMode: Boolean,
    private val accent: Int,
) {
    private val dialog = Dialog(activity)
    private val ink = if (darkMode) Color.rgb(244, 242, 252) else Color.rgb(31, 38, 56)
    private val muted = if (darkMode) Color.rgb(174, 174, 192) else Color.rgb(103, 113, 132)
    private val rows = mutableListOf<TextView>()
    private val spinner = ProgressBar(activity)
    private val status = TextView(activity)
    private val close = Button(activity)

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    init {
        val content =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(20), dp(22), dp(20))
                background =
                    GradientDrawable().apply {
                        setColor(if (darkMode) Color.rgb(30, 29, 45) else Color.rgb(250, 249, 255))
                        cornerRadius = dp(24).toFloat()
                    }
            }
        fun text(value: String, size: Float) =
            TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(ink)
                setPadding(0, dp(7), 0, dp(7))
            }
        val header = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(text(title, 21f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(spinner, LinearLayout.LayoutParams(dp(24), dp(24)))
        content.addView(header)
        status.textSize = 14f
        status.setTextColor(muted)
        status.setPadding(0, dp(10), 0, dp(12))
        content.addView(status)
        steps.forEach { step ->
            val row = text("○  $step", 14f).apply { setTextColor(muted) }
            rows.add(row)
            content.addView(row)
        }
        close.apply {
            text = "Zavřít"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background =
                GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(16).toFloat()
                }
            visibility = View.GONE
            setOnClickListener { dialog.dismiss() }
        }
        content.addView(close, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
        dialog.setContentView(ScrollView(activity).apply { addView(content) })
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(
                (activity.resources.displayMetrics.widthPixels - dp(40)).coerceAtMost(dp(440)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    fun step(index: Int) {
        rows.forEachIndexed { i, row ->
            row.text = "${if (i < index) "✓" else if (i == index) "●" else "○"}  ${steps[i]}"
            row.setTextColor(
                if (i < index) Color.rgb(35, 135, 75) else if (i == index) accent else muted,
            )
        }
        status.text = steps.getOrNull(index).orEmpty()
    }

    fun finish(message: String, failedStep: Int? = null) {
        if (failedStep == null) step(steps.size)
        else
            rows.getOrNull(failedStep)?.apply {
                text = "✕  ${steps[failedStep]}"
                setTextColor(Color.rgb(190, 45, 55))
            }
        status.text = message
        spinner.visibility = View.GONE
        close.visibility = View.VISIBLE
        dialog.setCancelable(true)
    }

    fun dismiss() = dialog.dismiss()
}
