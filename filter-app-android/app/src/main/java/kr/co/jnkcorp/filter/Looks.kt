package kr.co.jnkcorp.filter

import kotlin.math.max

/**
 * 기본 룩. 색 변환만 여기서 정의하고 LUT 로 구워서 씁니다.
 * 그레인·비네팅은 위치에 따라 달라지는 효과라 LUT 에 담을 수 없어서,
 * 룩마다 기본값만 정해 두고 [Pipeline] 에서 따로 입힙니다.
 */
enum class Look(val label: String, val sub: String, val grain: Float, val vignette: Float) {
    ORIGINAL("원본", "Original", 0f, 0f),
    TEAL_ORANGE("틸앤오렌지", "Teal & Orange", 0f, 0.15f),
    LEICA_CLASSIC("라이카 클래식", "Leica Classic", 0.35f, 0.55f),
    LEICA_MONO("라이카 모노", "Leica Monochrom", 0.55f, 0.7f);

    fun bake(): Lut3D = Lut3D.bake(title = sub) { r, g, b, o -> Looks.color(this, r, g, b, o) }
}

object Looks {

    fun color(look: Look, r: Float, g: Float, b: Float, o: FloatArray) = when (look) {
        Look.ORIGINAL -> { o[0] = r; o[1] = g; o[2] = b }
        Look.TEAL_ORANGE -> tealOrange(r, g, b, o)
        Look.LEICA_CLASSIC -> leicaClassic(r, g, b, o)
        Look.LEICA_MONO -> leicaMono(r, g, b, o)
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
        var g = g0
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

    internal fun saturate(r: Float, g: Float, b: Float, s: Float, o: FloatArray) {
        val l = luma(r, g, b)
        o[0] = l + (r - l) * s
        o[1] = l + (g - l) * s
        o[2] = l + (b - l) * s
    }
}
