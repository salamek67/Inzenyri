package cz.inzenyri.mobile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

/** Inline inputs wrapped together with the following text, so equations fit small screens. */
internal class FillInQuestionView(
    context: Context,
    private val question: JSONObject,
    ink: Int,
    muted: Int,
    surface: Int,
    stroke: Int,
) : ViewGroup(context) {
    private val fields = mutableListOf<EditText>()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        val template = question.getString("template")
        val matches = marker.findAll(template).toList()
        if (matches.first().range.first > 0)
            addView(text(template.substring(0, matches.first().range.first), ink))
        matches.forEachIndexed { index, match ->
            val token =
                LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            val numeric = question.optBoolean("numeric")
            val field =
                EditText(context).apply {
                    hint = "?"
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                    setSingleLine(true)
                    setTextColor(ink)
                    setHintTextColor(muted)
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    inputType =
                        if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                    filters = arrayOf(InputFilter.LengthFilter(if (numeric) 6 else 80))
                    imeOptions =
                        if (index == matches.lastIndex) EditorInfo.IME_ACTION_DONE
                        else EditorInfo.IME_ACTION_NEXT
                    contentDescription = "${if (numeric) "Koeficient" else "Doplnění"} ${index + 1}"
                    background =
                        GradientDrawable().apply {
                            setColor(surface)
                            cornerRadius = dp(10).toFloat()
                            setStroke(dp(1), stroke)
                        }
                    setOnEditorActionListener { _, action, _ ->
                        if (action == EditorInfo.IME_ACTION_NEXT) {
                            fields.getOrNull(index + 1)?.requestFocus()
                            true
                        } else false
                    }
                }
            fields.add(field)
            token.addView(field, LinearLayout.LayoutParams(dp(if (numeric) 52 else 100), dp(48)))
            val end = matches.getOrNull(index + 1)?.range?.first ?: template.length
            token.addView(text(template.substring(match.range.last + 1, end), ink))
            addView(token)
        }
    }

    private fun text(value: String, ink: Int) =
        TextView(context).apply {
            text = value
            textSize = 18f
            setTextColor(ink)
            setPadding(dp(4), 0, dp(6), 0)
        }

    fun complete() = fields.all { it.text.toString().isNotBlank() }

    override fun generateDefaultLayoutParams() =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    fun correct(): Boolean {
        fun normalize(value: String): String {
            val text =
                Normalizer.normalize(value.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC)
            if (question.optBoolean("numeric") && text.matches(Regex("[0-9]+")))
                return text.trimStart('0').ifEmpty { "0" }
            return if (question.optBoolean("ignoreCase"))
                text.lowercase(Locale.forLanguageTag("cs"))
            else text
        }
        val answers = question.getJSONArray("answers")
        return fields.indices.all {
            normalize(fields[it].text.toString()) == normalize(answers.getString(it))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var y = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            if (x > 0 && x + child.measuredWidth > available) {
                x = 0
                y += rowHeight + dp(10)
                rowHeight = 0
            }
            x += child.measuredWidth
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(
            resolveSize(available, widthMeasureSpec),
            resolveSize(y + rowHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var x = 0
        var y = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (x > 0 && x + child.measuredWidth > width) {
                x = 0
                y += rowHeight + dp(10)
                rowHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }

    companion object {
        private val marker = Regex("\\{\\{([1-9][0-9]*)\\}\\}")

        fun valid(question: JSONObject): Boolean {
            val answers = question.optJSONArray("answers") ?: return false
            return answers.length() in 1..20 &&
                (0 until answers.length()).all {
                    answers.opt(it) is String && answers.optString(it).isNotBlank()
                } &&
                marker.findAll(question.optString("template")).map { it.groupValues[1] }.toList() ==
                    (1..answers.length()).map(Int::toString)
        }

        fun solution(question: JSONObject): String =
            marker.replace(question.optString("template")) {
                question.getJSONArray("answers").getString(it.groupValues[1].toInt() - 1)
            }
    }
}
