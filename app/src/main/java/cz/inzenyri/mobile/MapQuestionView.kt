package cz.inzenyri.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.*
import org.json.JSONObject

/** Offline geography exercise. The web and Android read the same country geometry. */
internal class MapQuestionView(
    context: Context,
    private val question: JSONObject,
    private val ink: Int,
    private val muted: Int,
    private val dark: Boolean,
) : LinearLayout(context) {
    private val base = RectF(views.getValue(question.getString("map")))
    private val viewport = RectF(base)
    private val countries =
        loadCountries(context).filter { it.bounds.any { bounds -> RectF.intersects(bounds, base) } }
    private val selected = mutableSetOf<String>()
    private var pin: PointF? = null
    private var reviewed = false
    private val mode = question.getString("mode")
    private val targets =
        question
            .optJSONArray("targets")
            ?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            .orEmpty()
    private val status =
        TextView(context).apply {
            setTextColor(ink)
            textSize = 13f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    private val zoomButtons = mutableListOf<Button>()
    private val map = MapCanvas(context)
    private val clear: Button

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        val controls = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun control(title: String, description: String, action: () -> Unit): Button {
            val button =
                Button(context).apply {
                    text = title
                    contentDescription = description
                    isAllCaps = false
                    textSize = 13f
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = dp(44)
                    setTextColor(ink)
                    background =
                        GradientDrawable().apply {
                            setColor(if (dark) Color.rgb(43, 42, 61) else Color.rgb(246, 243, 255))
                            cornerRadius = dp(9).toFloat()
                        }
                    setOnClickListener { action() }
                }
            controls.addView(button, LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            return button
        }
        zoomButtons += control("＋", "Přiblížit mapu") { map.zoom(0.65f) }
        zoomButtons += control("−", "Oddálit mapu") { map.zoom(1f / 0.65f) }
        zoomButtons +=
            control("Celá mapa", "Obnovit mapu") {
                viewport.set(base)
                map.invalidate()
            }
        clear =
            control("Vymazat", "Vymazat odpověď") {
                if (!reviewed) {
                    selected.clear()
                    pin = null
                    update()
                }
            }
        addView(controls, LayoutParams(-1, -2))
        addView(map, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(status, LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(
            TextView(context).apply {
                text =
                    "Natural Earth · přehledová mapa 1:110m. Přiblížení dvěma prsty nebo +/−; posun tažením."
                setTextColor(muted)
                textSize = 10f
            },
            LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
        update()
    }

    fun complete() = if (mode == "pin") pin != null else selected.isNotEmpty()

    fun correct(): Boolean =
        if (mode == "pin")
            pin?.let {
                distance(
                    it.x.toDouble(),
                    it.y.toDouble(),
                    question.getJSONArray("target").getDouble(0),
                    question.getJSONArray("target").getDouble(1),
                ) <= question.getDouble("toleranceKm")
            } ?: false
        else selected == targets

    fun review() {
        reviewed = true
        isEnabled = true
        map.isEnabled = true
        zoomButtons.forEach { it.isEnabled = true }
        clear.isEnabled = false
        update()
    }

    private fun update() {
        status.text =
            if (reviewed) {
                if (mode == "pin") {
                    val target = question.getJSONArray("target")
                    val distance =
                        pin?.let {
                            distance(
                                it.x.toDouble(),
                                it.y.toDouble(),
                                target.getDouble(0),
                                target.getDouble(1),
                            )
                        } ?: 0.0
                    "Vzdálenost od cíle: ${String.format(Locale.forLanguageTag("cs"), "%.1f", distance)} km. Tolerance: ${question.getDouble("toleranceKm")} km. Zeleně je správné místo."
                } else
                    "Správné oblasti: ${solution(context, question)}. Zeleně správné, červeně chybně vybrané."
            } else if (mode == "pin") {
                pin?.let {
                    "Špendlík: ${String.format(Locale.ROOT, "%.3f° š., %.3f° d.", it.y, it.x)}"
                }
                    ?: "Klepni do mapy pro umístění špendlíku. Tolerance: ${question.getDouble("toleranceKm")} km."
            } else
                "Vybráno oblastí: ${selected.size} / ${targets.size}. Klepnutím výběr přidáš nebo zrušíš."
        map.invalidate()
    }

    private inner class MapCanvas(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var lastX = 0f
        private var lastY = 0f
        private var startX = 0f
        private var startY = 0f
        private var moved = false
        private val detector =
            ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        moved = true
                        zoom(
                            1f / detector.scaleFactor,
                            detector.focusX / width,
                            detector.focusY / height,
                        )
                        return true
                    }
                },
            )

        init {
            isFocusable = true
            isClickable = true
            contentDescription =
                if (mode == "pin") "Mapa: klepni pro umístění špendlíku. Lze použít také šipky."
                else "Mapa: klepni na požadované oblasti."
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(
                w,
                (w * base.height() / base.width()).toInt().coerceAtLeast(dp(120)),
            )
        }

        private fun setViewport(left: Float, top: Float, width: Float, height: Float) {
            val factor = (width / base.width()).coerceIn(1f / 16, 1f)
            val w = base.width() * factor
            val h = base.height() * factor
            val x = left.coerceIn(base.left, base.right - w)
            val y = top.coerceIn(base.top, base.bottom - h)
            viewport.set(x, y, x + w, y + h)
            invalidate()
        }

        fun zoom(factor: Float, x: Float = 0.5f, y: Float = 0.5f) {
            val nextWidth = (viewport.width() * factor).coerceIn(base.width() / 16, base.width())
            val nextHeight = base.height() * nextWidth / base.width()
            setViewport(
                viewport.left + (viewport.width() - nextWidth) * x,
                viewport.top + (viewport.height() - nextHeight) * y,
                nextWidth,
                nextHeight,
            )
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(if (dark) Color.rgb(30, 53, 70) else Color.rgb(226, 239, 248))
            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
            canvas.scale(width / viewport.width(), height / viewport.height())
            canvas.translate(-viewport.left, -viewport.top)
            for (country in countries) {
                paint.style = Paint.Style.FILL
                paint.color =
                    when {
                        reviewed && country.id in targets -> Color.rgb(77, 170, 117)
                        reviewed && country.id in selected && country.id !in targets ->
                            Color.rgb(222, 120, 135)
                        country.id in selected -> Color.rgb(160, 126, 218)
                        dark -> Color.rgb(69, 75, 80)
                        else -> Color.rgb(242, 240, 232)
                    }
                canvas.drawPath(country.path, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth =
                    viewport.width() / width * resources.displayMetrics.density * 0.6f
                paint.color = if (dark) Color.rgb(140, 155, 164) else Color.rgb(136, 155, 168)
                canvas.drawPath(country.path, paint)
            }
            canvas.restore()
            fun marker(point: PointF, color: Int, radius: Int = 6) {
                val x = (point.x - viewport.left) / viewport.width() * width
                val y = (-point.y - viewport.top) / viewport.height() * height
                paint.style = Paint.Style.FILL
                paint.color = color
                canvas.drawCircle(x, y, dp(radius).toFloat(), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, dp(radius).toFloat(), paint)
            }
            if (reviewed && mode == "pin") {
                val target = question.getJSONArray("target")
                marker(
                    PointF(target.getDouble(0).toFloat(), target.getDouble(1).toFloat()),
                    Color.rgb(22, 140, 77),
                    9,
                )
            }
            pin?.let {
                marker(
                    it,
                    if (reviewed && !correct()) Color.rgb(204, 62, 81) else Color.rgb(121, 84, 216),
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)
                    lastX = event.x
                    lastY = event.y
                    startX = event.x
                    startY = event.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> moved = true
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.x - startX, event.y - startY) > dp(6)) moved = true
                    if (event.pointerCount == 1 && !detector.isInProgress && moved)
                        setViewport(
                            viewport.left - (event.x - lastX) / width * viewport.width(),
                            viewport.top - (event.y - lastY) / height * viewport.height(),
                            viewport.width(),
                            viewport.height(),
                        )
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    moved = true
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !reviewed) {
                        val point =
                            PointF(
                                (viewport.left + event.x / width * viewport.width()).coerceIn(
                                    base.left,
                                    base.right,
                                ),
                                -(viewport.top + event.y / height * viewport.height()).coerceIn(
                                    base.top,
                                    base.bottom,
                                ),
                            )
                        if (mode == "pin") pin = point
                        else
                            countries
                                .lastOrNull { country -> country.contains(point) }
                                ?.let { if (!selected.add(it.id)) selected.remove(it.id) }
                        performClick()
                        update()
                    }
                    parent.requestDisallowInterceptTouchEvent(false)
                }
                MotionEvent.ACTION_CANCEL -> parent.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (
                mode != "pin" ||
                    reviewed ||
                    keyCode !in
                        listOf(
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_ENTER,
                        )
            )
                return super.onKeyDown(keyCode, event)
            val point = pin ?: PointF(viewport.centerX(), -viewport.centerY())
            val step = if (event.isShiftPressed) 0.05f else 0.01f
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> point.x -= viewport.width() * step
                KeyEvent.KEYCODE_DPAD_RIGHT -> point.x += viewport.width() * step
                KeyEvent.KEYCODE_DPAD_UP -> point.y += viewport.height() * step
                KeyEvent.KEYCODE_DPAD_DOWN -> point.y -= viewport.height() * step
            }
            point.x = point.x.coerceIn(base.left, base.right)
            point.y = point.y.coerceIn(-base.bottom, -base.top)
            pin = point
            update()
            return true
        }
    }

    private data class Country(
        val id: String,
        val name: String,
        val polygons: List<List<List<PointF>>>,
        val path: Path,
        val bounds: List<RectF>,
    ) {
        fun contains(point: PointF): Boolean {
            fun inside(ring: List<PointF>): Boolean {
                var result = false
                var j = ring.lastIndex
                for (i in ring.indices) {
                    val a = ring[i]
                    val b = ring[j]
                    if (
                        (a.y > point.y) != (b.y > point.y) &&
                            point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x
                    )
                        result = !result
                    j = i
                }
                return result
            }
            return polygons.any { polygon -> inside(polygon[0]) && polygon.drop(1).none(::inside) }
        }
    }

    companion object {
        private val views =
            mapOf(
                "world" to RectF(-180f, -90f, 180f, 90f),
                "europe" to RectF(-25f, -72f, 45f, -32f),
                "africa" to RectF(-20f, -38f, 55f, 36f),
                "asia" to RectF(25f, -80f, 180f, 12f),
                "northAmerica" to RectF(-170f, -84f, -50f, -5f),
                "southAmerica" to RectF(-85f, -15f, -32f, 57f),
                "oceania" to RectF(110f, -5f, 180f, 50f),
            )
        @Volatile private var cache: List<Country>? = null

        private fun loadCountries(context: Context): List<Country> =
            cache
                ?: synchronized(this) {
                    cache
                        ?: context.assets.open("world.json").bufferedReader().use { reader ->
                            val array = JSONObject(reader.readText()).getJSONArray("countries")
                            (0 until array.length())
                                .map { index ->
                                    val item = array.getJSONObject(index)
                                    val values = item.getJSONArray("polygons")
                                    val polygons =
                                        (0 until values.length()).map { p ->
                                            val rings = values.getJSONArray(p)
                                            (0 until rings.length()).map { r ->
                                                val points = rings.getJSONArray(r)
                                                (0 until points.length()).map { i ->
                                                    val value = points.getJSONArray(i)
                                                    PointF(
                                                        value.getDouble(0).toFloat(),
                                                        value.getDouble(1).toFloat(),
                                                    )
                                                }
                                            }
                                        }
                                    val path =
                                        Path().apply {
                                            fillType = Path.FillType.EVEN_ODD
                                            polygons.forEach { polygon ->
                                                polygon.forEach { ring ->
                                                    ring.forEachIndexed { i, point ->
                                                        if (i == 0) moveTo(point.x, -point.y)
                                                        else lineTo(point.x, -point.y)
                                                    }
                                                    close()
                                                }
                                            }
                                        }
                                    val bounds = polygons.map { polygon ->
                                        val ring = polygon[0]
                                        RectF(
                                            ring.minOf { it.x },
                                            -ring.maxOf { it.y },
                                            ring.maxOf { it.x },
                                            -ring.minOf { it.y },
                                        )
                                    }
                                    val code = item.optString("code")
                                    val name =
                                        if (code.matches(Regex("[A-Z]{2}")))
                                            Locale.Builder()
                                                .setRegion(code)
                                                .build()
                                                .getDisplayCountry(Locale.forLanguageTag("cs"))
                                        else item.getString("name")
                                    Country(item.getString("id"), name, polygons, path, bounds)
                                }
                                .also { cache = it }
                        }
                }

        fun valid(context: Context, q: JSONObject): Boolean = runCatching {
            val view = views[q.optString("map")] ?: return false
            if (q.optString("prompt").isBlank()) return false
            when (q.optString("mode")) {
                "regions" -> {
                    val a = q.optJSONArray("targets") ?: return false
                    val ids = (0 until a.length()).map { a.opt(it) as? String ?: return false }
                    val available =
                        loadCountries(context)
                            .filter { it.bounds.any { r -> RectF.intersects(r, view) } }
                            .map { it.id }
                            .toSet()
                    ids.size in 1..200 && ids.toSet().size == ids.size && available.containsAll(ids)
                }
                "pin" -> {
                    val a = q.optJSONArray("target") ?: return false
                    if (
                        a.length() != 2 ||
                            a.opt(0) !is Number ||
                            a.opt(1) !is Number ||
                            q.opt("toleranceKm") !is Number
                    )
                        return false
                    val x = a.getDouble(0)
                    val y = a.getDouble(1)
                    val t = q.getDouble("toleranceKm")
                    x.isFinite() &&
                        y.isFinite() &&
                        t.isFinite() &&
                        x >= view.left &&
                        x <= view.right &&
                        y >= -view.bottom &&
                        y <= -view.top &&
                        t in 1.0..2000.0
                }
                else -> false
            }
        }
            .getOrDefault(false)

        fun solution(context: Context, q: JSONObject): String =
            if (q.optString("mode") == "pin") {
                val a = q.getJSONArray("target")
                "${a.getDouble(1)}° š., ${a.getDouble(0)}° d. (±${q.getDouble("toleranceKm")} km)"
            } else {
                val a = q.getJSONArray("targets")
                val ids = (0 until a.length()).map { a.getString(it) }.toSet()
                loadCountries(context).filter { it.id in ids }.joinToString(", ") { it.name }
            }

        fun distance(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
            val rad = PI / 180
            val h =
                sin((lat2 - lat1) * rad / 2).pow(2) +
                    cos(lat1 * rad) * cos(lat2 * rad) * sin((lon2 - lon1) * rad / 2).pow(2)
            return 6371.0088 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
