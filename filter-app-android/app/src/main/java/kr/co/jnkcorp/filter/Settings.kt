package kr.co.jnkcorp.filter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 사진 한 장에 입힌 설정 한 벌. 앱을 다시 켜거나 "최근" 에서 불러올 때 씁니다. */
data class Settings(
    val lutKey: String?,
    val lutName: String,
    val intensity: Float,
    val grain: Float,
    val vignette: Float,
    val adjust: MakerParams,
    val frame: Frame,
    val frameFlip: Boolean,
    val cropX: Float,
    val cropY: Float,
) {
    /** "최근" 칩에 보일 이름 */
    fun label(): String = if (frame == Frame.ORIGINAL) lutName else "$lutName · ${frame.label}"

    /** 위치(crop)만 다른 건 같은 설정으로 봅니다. */
    fun sameLook(o: Settings) = copy(cropX = 0f, cropY = 0f) == o.copy(cropX = 0f, cropY = 0f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("lut", lutKey ?: ""); put("lutName", lutName)
        put("k", intensity.toDouble()); put("grain", grain.toDouble()); put("vig", vignette.toDouble())
        put("frame", frame.name); put("flip", frameFlip)
        put("cx", cropX.toDouble()); put("cy", cropY.toDouble())
        val a = adjust
        put("adj", JSONObject().apply {
            put("ev", a.exposure.toDouble()); put("con", a.contrast.toDouble())
            put("hi", a.highlights.toDouble()); put("sh", a.shadows.toDouble())
            put("sat", a.saturation.toDouble()); put("temp", a.temperature.toDouble())
            put("tint", a.tint.toDouble()); put("fade", a.fade.toDouble())
        })
    }

    companion object {
        fun fromJson(j: JSONObject): Settings {
            val a = j.optJSONObject("adj") ?: JSONObject()
            fun f(o: JSONObject, k: String, d: Float = 0f) = o.optDouble(k, d.toDouble()).toFloat()
            return Settings(
                lutKey = j.optString("lut").ifBlank { null },
                lutName = j.optString("lutName", "원본"),
                intensity = f(j, "k", 1f), grain = f(j, "grain"), vignette = f(j, "vig"),
                adjust = MakerParams(
                    exposure = f(a, "ev"), contrast = f(a, "con"), highlights = f(a, "hi"), shadows = f(a, "sh"),
                    saturation = f(a, "sat"), temperature = f(a, "temp"), tint = f(a, "tint"), fade = f(a, "fade"),
                ),
                frame = runCatching { Frame.valueOf(j.optString("frame")) }.getOrDefault(Frame.ORIGINAL),
                frameFlip = j.optBoolean("flip"),
                cropX = f(j, "cx", 0.5f), cropY = f(j, "cy", 0.5f),
            )
        }
    }
}

/** 마지막 설정과 최근 설정 3개를 SharedPreferences 에 둡니다. */
class SettingsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("fofilter", Context.MODE_PRIVATE)

    var current: Settings?
        get() = prefs.getString("current", null)?.let { runCatching { Settings.fromJson(JSONObject(it)) }.getOrNull() }
        set(v) { prefs.edit().putString("current", v?.toJson()?.toString()).apply() }

    var watermark: Watermark
        get() = prefs.getString("watermark", null)?.let { runCatching { Watermark.fromJson(JSONObject(it)) }.getOrNull() } ?: Watermark()
        set(v) { prefs.edit().putString("watermark", v.toJson().toString()).apply() }

    var autoSave: Boolean
        get() = prefs.getBoolean("autoSave", true)
        set(v) { prefs.edit().putBoolean("autoSave", v).apply() }

    fun recent(): List<Settings> = runCatching {
        val arr = JSONArray(prefs.getString("recent", "[]"))
        (0 until arr.length()).map { Settings.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** 맨 앞에 넣고, 같은 설정은 하나만, 최대 [MAX] 개 */
    fun pushRecent(s: Settings) {
        val list = listOf(s) + recent().filterNot { it.sameLook(s) }
        val arr = JSONArray()
        list.take(MAX).forEach { arr.put(it.toJson()) }
        prefs.edit().putString("recent", arr.toString()).apply()
    }

    companion object { const val MAX = 3 }
}

/** 프레임(자르기) 비율. 가로 사진 기준 가로÷세로, 0 이면 원본 그대로 */
enum class Frame(val label: String, val ratio: Float) {
    ORIGINAL("원본", 0f),
    SCOPE("시네마스코프", 2.39f),
    WIDE("16:9", 16f / 9f),
    STD("4:3", 4f / 3f),
    PHOTO("3:2", 3f / 2f),
    SQUARE("1:1", 1f),
}

/**
 * [frame] 비율로 자를 영역 (left, top, width, height).
 * [cx]·[cy] 는 남는 쪽으로 어디에 둘지 0(왼쪽/위)..1(오른쪽/아래).
 */
fun cropBox(w: Int, h: Int, frame: Frame, flip: Boolean, cx: Float, cy: Float): IntArray {
    if (frame.ratio == 0f) return intArrayOf(0, 0, w, h)
    val landscape = w >= h
    val target = if (landscape != flip) frame.ratio else 1f / frame.ratio
    return if (w.toFloat() / h > target) {
        val cw = Math.round(h * target).coerceIn(1, w)
        intArrayOf(Math.round((w - cw) * cx.coerceIn(0f, 1f)), 0, cw, h)
    } else {
        val ch = Math.round(w / target).coerceIn(1, h)
        intArrayOf(0, Math.round((h - ch) * cy.coerceIn(0f, 1f)), w, ch)
    }
}
