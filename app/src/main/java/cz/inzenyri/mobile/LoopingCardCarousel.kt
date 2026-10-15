package cz.inzenyri.mobile

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import kotlin.math.roundToInt

internal data class CarouselAnchor(val key: String, val index: Int)

/** One clone at each edge keeps swipe navigation continuous without an unbounded view list. */
internal class LoopingCardCarousel(
    context: Context,
    private val keys: List<String>,
    anchor: CarouselAnchor?,
    cardWidth: Int,
    private val ink: Int,
    muted: Int,
    dark: Boolean,
    private val onSelected: (CarouselAnchor) -> Unit,
    createCard: (Int) -> View,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private val stride = cardWidth + dp(12)
    private var selected = keys.indexOf(anchor?.key).takeIf { it >= 0 }
        ?: (anchor?.index ?: 0).coerceIn(0, keys.lastIndex)
    private var ready = false
    private var touching = false
    private val counter = TextView(context).apply {
        setTextColor(muted); textSize = 13f; gravity = Gravity.CENTER
    }
    private val settle = Runnable { settlePosition() }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    init {
        orientation = VERTICAL
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        val indexes = if (keys.size > 1) listOf(keys.lastIndex) + keys.indices.toList() + listOf(0) else listOf(0)
        indexes.forEach { index ->
            row.addView(createCard(index), LayoutParams(cardWidth, -2).apply { marginEnd = dp(12) })
        }
        scroller.addView(row)
        addView(scroller)
        if (keys.size > 1) {
            val navigation = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            fun arrow(text: String, direction: Int) = TextView(context).apply {
                this.text = text; textSize = 25f; setTextColor(ink)
                gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                contentDescription = if (direction < 0) "Předchozí karta" else "Další karta"
                isFocusable = true
                background = GradientDrawable().apply {
                    setColor(if (dark) Color.rgb(43, 42, 61) else Color.rgb(241, 239, 247))
                    cornerRadius = dp(14).toFloat()
                }
                setOnClickListener {
                    scroller.removeCallbacks(settle)
                    var physical = (scroller.scrollX.toFloat() / stride).roundToInt()
                    if (physical == 0) { physical = keys.size; scroller.scrollTo(physical * stride, 0) }
                    if (physical == keys.size + 1) { physical = 1; scroller.scrollTo(stride, 0) }
                    scroller.smoothScrollTo((physical + direction).coerceIn(0, keys.size + 1) * stride, 0)
                }
            }
            navigation.addView(arrow("‹", -1), LayoutParams(dp(48), dp(44)))
            navigation.addView(counter, LayoutParams(0, -2, 1f))
            navigation.addView(arrow("›", 1), LayoutParams(dp(48), dp(44)))
            addView(navigation, LayoutParams(-1, -2).apply { topMargin = dp(8) })
            scroller.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { touching = true; scroller.removeCallbacks(settle) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touching = false; scroller.postDelayed(settle, 140)
                    }
                }
                false
            }
            scroller.setOnScrollChangeListener { _, x, _, _, _ ->
                if (ready) {
                    val physical = (x.toFloat() / stride).roundToInt().coerceIn(0, keys.size + 1)
                    selected = (physical - 1 + keys.size) % keys.size
                    remember()
                    scroller.removeCallbacks(settle)
                    if (!touching) scroller.postDelayed(settle, 140)
                }
            }
        }
        remember()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scroller.doOnLayout {
            if (!isAttachedToWindow) return@doOnLayout
            scroller.scrollTo((if (keys.size > 1) selected + 1 else 0) * stride, 0)
            ready = true
        }
    }

    private fun remember() {
        counter.text = "${selected + 1} / ${keys.size}"
        onSelected(CarouselAnchor(keys[selected], selected))
    }

    private fun settlePosition() {
        if (!ready || keys.size < 2) return
        val physical = (scroller.scrollX.toFloat() / stride).roundToInt().coerceIn(0, keys.size + 1)
        when (physical) {
            0 -> scroller.scrollTo(keys.size * stride, 0)
            keys.size + 1 -> scroller.scrollTo(stride, 0)
            else -> if (scroller.scrollX != physical * stride) scroller.smoothScrollTo(physical * stride, 0)
        }
    }

    override fun onDetachedFromWindow() {
        ready = false
        scroller.removeCallbacks(settle)
        super.onDetachedFromWindow()
    }
}
