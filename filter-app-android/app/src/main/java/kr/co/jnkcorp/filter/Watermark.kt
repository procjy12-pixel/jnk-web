package kr.co.jnkcorp.filter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import org.json.JSONObject

/** 워터마크 글꼴 (assets/fonts, 모두 OFL) */
enum class WmFont(val label: String, val asset: String) {
    PRETENDARD("Pretendard", "fonts/Pretendard-SemiBold.otf"),
    SERIF("Playfair 세리프", "fonts/PlayfairDisplay-Italic.ttf"),
    SCRIPT_EN("Great Vibes 필기체", "fonts/GreatVibes-Regular.ttf"),
    SCRIPT_KO("나눔 손글씨 펜", "fonts/NanumPenScript-Regular.ttf");

    companion object {
        private val cache = HashMap<WmFont, Typeface>()
        fun typeface(ctx: Context, f: WmFont): Typeface = cache.getOrPut(f) {
            runCatching { Typeface.createFromAsset(ctx.assets, f.asset) }.getOrDefault(Typeface.DEFAULT)
        }
    }
}

/**
 * 워터마크 설정. 위치(u, v)는 잘린 사진 기준 0..1 (가운데가 0.5),
 * 크기는 사진 폭 대비 글자 높이, 회전은 도(°).
 */
data class Watermark(
    val enabled: Boolean = false,
    val text: String = "FOFilter",
    val font: WmFont = WmFont.SERIF,
    val color: Int = Color.WHITE,
    val opacity: Float = 0.85f,
    val size: Float = 0.07f,
    val spacing: Float = 0.05f,
    val shadow: Boolean = true,
    val u: Float = 0.5f,
    val v: Float = 0.88f,
    val rotation: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("on", enabled); put("text", text); put("font", font.name); put("color", color)
        put("op", opacity.toDouble()); put("size", size.toDouble()); put("sp", spacing.toDouble())
        put("shadow", shadow); put("u", u.toDouble()); put("v", v.toDouble()); put("rot", rotation.toDouble())
    }

    companion object {
        fun fromJson(j: JSONObject): Watermark {
            fun f(k: String, d: Float) = j.optDouble(k, d.toDouble()).toFloat()
            val d = Watermark()
            return Watermark(
                enabled = j.optBoolean("on", false),
                text = j.optString("text", d.text),
                font = runCatching { WmFont.valueOf(j.optString("font")) }.getOrDefault(d.font),
                color = j.optInt("color", d.color),
                opacity = f("op", d.opacity), size = f("size", d.size), spacing = f("sp", d.spacing),
                shadow = j.optBoolean("shadow", d.shadow),
                u = f("u", d.u), v = f("v", d.v), rotation = f("rot", d.rotation),
            )
        }
    }
}

object WatermarkPainter {

    fun paint(ctx: Context, wm: Watermark, imageWidth: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = WmFont.typeface(ctx, wm.font)
        textSize = wm.size * imageWidth
        textAlign = Paint.Align.CENTER
        letterSpacing = wm.spacing
        color = (((wm.opacity.coerceIn(0f, 1f) * 255).toInt()) shl 24) or (wm.color and 0xFFFFFF)
        if (wm.shadow) setShadowLayer(textSize * 0.08f, 0f, textSize * 0.03f, 0x66000000)
    }

    /** ([left], [top]) 부터 [w]×[h] 인 사진 영역 위에 그립니다. 여러 줄이면 가운데 정렬. */
    fun draw(ctx: Context, c: Canvas, left: Float, top: Float, w: Float, h: Float, wm: Watermark) {
        if (!wm.enabled || wm.text.isBlank()) return
        val p = paint(ctx, wm, w)
        val lines = wm.text.split('\n')
        val lh = p.fontSpacing
        val fm = p.fontMetrics
        val firstBase = -(lines.size - 1) * lh / 2f - (fm.ascent + fm.descent) / 2f
        c.save()
        c.translate(left + wm.u * w, top + wm.v * h)
        c.rotate(wm.rotation)
        lines.forEachIndexed { i, line -> c.drawText(line, 0f, firstBase + i * lh, p) }
        c.restore()
    }

    /** 글자 덩어리의 반쯤 되는 크기 (손가락이 글자 위인지 볼 때) */
    fun halfExtent(ctx: Context, wm: Watermark, w: Float): Pair<Float, Float> {
        val p = paint(ctx, wm, w)
        val lines = wm.text.split('\n')
        val width = lines.maxOf { p.measureText(it) }
        return width / 2f to lines.size * p.fontSpacing / 2f
    }
}

/**
 * 미리보기 위에 워터마크와 자석 안내선을 그리는 뷰.
 * 사진이 뷰 안 어디에 그려지는지는 [photoRect] 가 알려 줍니다.
 */
class WatermarkView(ctx: Context) : View(ctx) {
    var watermark = Watermark()
        set(v) { field = v; invalidate() }
    var photoRect: () -> FloatArray? = { null }   // left, top, width, height
    var guideX = false
    var guideY = false
    var guideAngle = false
    var editing = false

    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8743B"); strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    override fun onDraw(c: Canvas) {
        val r = photoRect() ?: return
        val (l, t, w, h) = listOf(r[0], r[1], r[2], r[3])
        WatermarkPainter.draw(context, c, l, t, w, h, watermark)
        if (!editing || !watermark.enabled) return
        if (guideX) c.drawLine(l + w / 2, t, l + w / 2, t + h, guide)
        if (guideY) c.drawLine(l, t + h / 2, l + w, t + h / 2, guide)
        // 편집 중엔 글자 둘레에 점선 상자
        val (hw, hh) = WatermarkPainter.halfExtent(context, watermark, w)
        c.save()
        c.translate(l + watermark.u * w, t + watermark.v * h)
        c.rotate(watermark.rotation)
        if (guideAngle) c.drawLine(-hw - 40f, 0f, hw + 40f, 0f, guide)
        c.drawRect(-hw - 16f, -hh - 10f, hw + 16f, hh + 10f, frame)
        c.restore()
    }
}
