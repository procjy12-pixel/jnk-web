package kr.co.jnkcorp.filter

import android.graphics.Bitmap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * 필터(룩) 모음. 모든 계산은 0..1 실수 RGB 로 픽셀마다 합니다.
 * 강도(intensity)는 원본과 결과를 섞는 비율입니다.
 */
enum class Look(val label: String, val sub: String) {
    ORIGINAL("원본", "Original"),
    TEAL_ORANGE("틸앤오렌지", "Teal & Orange"),
    LEICA_CLASSIC("라이카 클래식", "Leica Classic"),
    LEICA_MONO("라이카 모노", "Leica Monochrom"),
}

object LookEngine {

    private val pool = Executors.newFixedThreadPool(max(2, Runtime.getRuntime().availableProcessors()))

    /** [src] 에 [look] 을 [intensity](0..1) 만큼 입힌 새 비트맵을 돌려줍니다. */
    fun apply(src: Bitmap, look: Look, intensity: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        if (look != Look.ORIGINAL && intensity > 0f) {
            val threads = max(2, Runtime.getRuntime().availableProcessors())
            val band = (h + threads - 1) / threads
            val jobs = (0 until threads).map { t ->
                Callable {
                    val y0 = t * band
                    val y1 = min(h, y0 + band)
                    for (y in y0 until y1) processRow(px, y, w, h, look, intensity)
                }
            }
            pool.invokeAll(jobs).forEach { it.get() }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun processRow(px: IntArray, y: Int, w: Int, h: Int, look: Look, k: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val ny = (y - cy) / cy
        val out = FloatArray(3)
        var i = y * w
        for (x in 0 until w) {
            val c = px[i]
            var r0 = ((c shr 16) and 0xFF) / 255f
            var g0 = ((c shr 8) and 0xFF) / 255f
            var b0 = (c and 0xFF) / 255f
            // 모노는 강도를 낮춰도 색이 돌아오지 않게, 흑백 원본과 섞습니다.
            if (look == Look.LEICA_MONO) {
                val m = luma(r0, g0, b0)
                r0 = m; g0 = m; b0 = m
            }

            when (look) {
                Look.TEAL_ORANGE -> tealOrange(r0, g0, b0, out)
                Look.LEICA_CLASSIC -> leicaClassic(r0, g0, b0, out)
                Look.LEICA_MONO -> leicaMono(r0, g0, b0, out)
                Look.ORIGINAL -> { out[0] = r0; out[1] = g0; out[2] = b0 }
            }

            // 라이카 계열은 비네팅 + 필름 그레인
            if (look == Look.LEICA_CLASSIC || look == Look.LEICA_MONO) {
                val nx = (x - cx) / cx
                val d = nx * nx + ny * ny
                val vAmt = if (look == Look.LEICA_MONO) 0.42f else 0.32f
                val v = 1f - vAmt * smooth(0.25f, 1.6f, d)
                val gAmt = if (look == Look.LEICA_MONO) 0.055f else 0.035f
                val n = (hash(x, y) - 0.5f) * gAmt
                for (j in 0..2) out[j] = out[j] * v + n
            }

            val r = mix(r0, out[0], k)
            val g = mix(g0, out[1], k)
            val b = mix(b0, out[2], k)
            px[i] = (c and 0xFF000000.toInt()) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
            i++
        }
    }

    // ── 틸앤오렌지: 차가운 색은 청록으로, 따뜻한 색(피부)은 오렌지로 벌려 놓습니다.
    private fun tealOrange(r0: Float, g0: Float, b0: Float, o: FloatArray) {
        var r = r0; var g = g0; var b = b0
        val warm = clamp01((r - b) * 2.2f)
        val cool = clamp01((b - r) * 2f + (g - r) * 0.8f)
        r += -0.12f * cool + 0.07f * warm
        g += 0.02f * cool + 0.01f * warm
        b += 0.07f * cool - 0.10f * warm

        // 순백·순흑은 물들지 않게, 중간 밝기 쪽에만 톤을 얹습니다.
        val l = luma(r, g, b)
        val sh = (1f - l) * (1f - l) * l * 3f
        val hi = l * l * (1f - l) * 3f
        r += -0.06f * sh + 0.06f * hi
        g += 0.02f * sh + 0.015f * hi
        b += 0.07f * sh - 0.07f * hi

        r = sCurve(clamp01(r), 0.35f)
        g = sCurve(clamp01(g), 0.35f)
        b = sCurve(clamp01(b), 0.35f)
        saturate(r, g, b, 1.12f, o)
    }

    // ── 라이카 클래식: 깊은 중간톤 대비, 살짝 들린 블랙, 부드러운 하이라이트,
    //    채도는 낮추되 빨강은 진하게, 전체는 약간 따뜻하게.
    private fun leicaClassic(r0: Float, g0: Float, b0: Float, o: FloatArray) {
        var r = r0 * 1.03f + 0.008f
        var g = g0 * 1.0f
        var b = b0 * 0.95f
        val redness = clamp01((r - max(g, b)) * 2.5f)

        r = sCurve(clamp01(r), 0.55f)
        g = sCurve(clamp01(g), 0.55f)
        b = sCurve(clamp01(b), 0.55f)

        saturate(r, g, b, 0.82f + 0.25f * redness, o)
        r = o[0]; g = o[1]; b = o[2]

        val l = luma(r, g, b)
        val sh = (1f - l) * (1f - l)
        g += 0.012f * sh          // 필름 같은 아주 옅은 녹색 그림자
        b += 0.006f * sh

        // 블랙 들어올림 + 하이라이트 롤오프
        o[0] = 0.035f + 0.935f * r
        o[1] = 0.035f + 0.935f * g
        o[2] = 0.04f + 0.925f * b
    }

    // ── 라이카 모노: 붉은 필터를 끼운 듯한 채널 믹스 + 강한 대비
    private fun leicaMono(r0: Float, g0: Float, b0: Float, o: FloatArray) {
        var m = 0.45f * r0 + 0.45f * g0 + 0.10f * b0
        m = sCurve(clamp01(m), 0.75f)
        m = 0.02f + 0.965f * m
        o[0] = m; o[1] = m; o[2] = m
    }

    // ── 도우미
    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun saturate(r: Float, g: Float, b: Float, s: Float, o: FloatArray) {
        val l = luma(r, g, b)
        o[0] = l + (r - l) * s
        o[1] = l + (g - l) * s
        o[2] = l + (b - l) * s
    }

    private fun sCurve(x: Float, k: Float): Float = mix(x, x * x * (3f - 2f * x), k)
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3f - 2f * t)
    }
    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
    private fun to8(v: Float): Int = (clamp01(v) * 255f + 0.5f).toInt()

    private fun hash(x: Int, y: Int): Float {
        var n = x * 374761393 + y * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFF) / 65535f
    }
}
