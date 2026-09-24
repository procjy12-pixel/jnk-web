package kr.co.jnkcorp.filter

import kotlin.math.max

/**
 * 기본 룩. 색 변환만 여기서 정의하고 LUT 로 구워서 씁니다.
 * 그레인·비네팅은 위치에 따라 달라지는 효과라 LUT 에 담을 수 없어서,
 * 룩마다 기본값만 정해 두고 [Pipeline] 에서 따로 입힙니다.
 */
enum class Look(val label: String, val sub: String, val grain: Float, val vignette: Float, val category: String = Presets.LEICA) {
    ORIGINAL("원본", "Original", 0f, 0f, Presets.BASIC),
    TEAL_ORANGE("틸앤오렌지", "Teal & Orange", 0f, 0.15f, Presets.BASIC),
    LEICA_CLASSIC("라이카 클래식", "Leica Classic", 0.35f, 0.55f),
    LEICA_CONTEMPORARY("라이카 컨템퍼러리", "Contemporary", 0.1f, 0.3f),
    LEICA_NATURAL("라이카 내추럴", "Natural", 0f, 0.2f),
    LEICA_VIVID("라이카 비비드", "Vivid", 0f, 0.25f),
    LEICA_CHROME("라이카 크롬", "Chrome", 0.2f, 0.4f),
    LEICA_ETERNAL("라이카 이터널", "Eternal", 0.3f, 0.5f),
    LEICA_MONO("라이카 모노", "Monochrom", 0.55f, 0.7f),
    LEICA_MONO_HC("라이카 모노 하이콘", "Mono High Contrast", 0.5f, 0.6f),
    LEICA_MONO_NATURAL("라이카 모노 내추럴", "Mono Natural", 0.35f, 0.4f),
    LEICA_SELENIUM("라이카 셀레늄", "Selenium", 0.45f, 0.6f),
    LEICA_SEPIA("라이카 세피아", "Sepia", 0.45f, 0.6f),
    LEICA_BLUE("라이카 블루", "Blue", 0.4f, 0.55f);

    fun bake(): Lut3D = Lut3D.bake(title = sub) { r, g, b, o -> Looks.color(this, r, g, b, o) }
}

object Looks {

    fun color(look: Look, r: Float, g: Float, b: Float, o: FloatArray) = when (look) {
        Look.ORIGINAL -> { o[0] = r; o[1] = g; o[2] = b }
        Look.TEAL_ORANGE -> tealOrange(r, g, b, o)
        Look.LEICA_CLASSIC -> leicaClassic(r, g, b, o)
        Look.LEICA_MONO -> leicaMono(r, g, b, o)
        Look.LEICA_CONTEMPORARY -> grade(r, g, b, o, warm = 0.015f, contrast = 0.45f, sat = 1.08f, lift = 0f)
        Look.LEICA_NATURAL -> grade(r, g, b, o, warm = 0.005f, contrast = 0.2f, sat = 0.95f, lift = 0.01f)
        Look.LEICA_VIVID -> grade(r, g, b, o, warm = 0.01f, contrast = 0.45f, sat = 1.3f, lift = 0f)
        Look.LEICA_CHROME -> chrome(r, g, b, o)
        Look.LEICA_ETERNAL -> eternal(r, g, b, o)
        Look.LEICA_MONO_HC -> mono(r, g, b, o, 0.5f, 0.4f, 0.1f, contrast = 1f, lift = 0f, twice = true)
        Look.LEICA_MONO_NATURAL -> mono(r, g, b, o, 0.3f, 0.59f, 0.11f, contrast = 0.35f, lift = 0.03f)
        Look.LEICA_SELENIUM -> { mono(r, g, b, o, 0.4f, 0.5f, 0.1f, 0.6f, 0.02f); toneMono(o, -0.02f, -0.035f, 0.02f, 0.01f, 0f, -0.01f) }
        Look.LEICA_SEPIA -> { mono(r, g, b, o, 0.4f, 0.5f, 0.1f, 0.5f, 0.03f); toneMono(o, 0.05f, 0.0f, -0.09f, 0.03f, 0.005f, -0.05f) }
        Look.LEICA_BLUE -> { mono(r, g, b, o, 0.4f, 0.5f, 0.1f, 0.5f, 0.03f); toneMono(o, -0.07f, -0.02f, 0.07f, -0.03f, 0f, 0.03f) }
    }

    /** 공통: 따뜻함 → 대비 → 채도 → 블랙 들어올림 */
    private fun grade(r0: Float, g0: Float, b0: Float, o: FloatArray, warm: Float, contrast: Float, sat: Float, lift: Float) {
        val r = sCurve(clamp01(r0 * (1f + warm) + warm * 0.3f), contrast)
        val g = sCurve(clamp01(g0), contrast)
        val b = sCurve(clamp01(b0 * (1f - warm)), contrast)
        saturate(r, g, b, sat, o)
        for (i in 0..2) o[i] = lift + (1f - lift) * o[i]
    }

    // ── 크롬: 슬라이드 필름처럼 단단한 대비, 깊은 파랑과 빨강, 약간 차가운 그림자
    private fun chrome(r0: Float, g0: Float, b0: Float, o: FloatArray) {
        var r = sCurve(clamp01(r0), 0.6f)
        var g = sCurve(clamp01(g0), 0.6f)
        var b = sCurve(clamp01(b0 * 0.97f), 0.6f)
        val l = luma(r, g, b)
        val blue = clamp01((b - max(r, g)) * 2f)
        b -= 0.06f * blue * l          // 파란 하늘을 깊게
        val sh = (1f - l) * (1f - l)
        b += 0.02f * sh; r -= 0.01f * sh
        saturate(r, g, b, 1.12f, o)
    }

    // ── 이터널: 채도를 빼고 블랙을 띄운 영화 같은 톤, 청록 그림자와 따뜻한 밝은 부분
    private fun eternal(r0: Float, g0: Float, b0: Float, o: FloatArray) {
        var r = sCurve(clamp01(r0), 0.3f)
        var g = sCurve(clamp01(g0), 0.3f)
        var b = sCurve(clamp01(b0), 0.3f)
        saturate(r, g, b, 0.72f, o)
        r = o[0]; g = o[1]; b = o[2]
        val l = luma(r, g, b)
        val sh = (1f - l) * (1f - l) * l * 3f
        val hi = l * l * (1f - l) * 3f
        r += -0.03f * sh + 0.03f * hi
        g += 0.01f * sh + 0.01f * hi
        b += 0.035f * sh - 0.03f * hi
        val lift = 0.05f
        o[0] = lift + (1f - lift) * r; o[1] = lift + (1f - lift) * g; o[2] = lift + (1f - lift) * b
    }

    private fun mono(r: Float, g: Float, b: Float, o: FloatArray, wr: Float, wg: Float, wb: Float,
                     contrast: Float, lift: Float, twice: Boolean = false) {
        var m = sCurve(clamp01(wr * r + wg * g + wb * b), contrast)
        if (twice) m = sCurve(m, contrast * 0.5f)
        m = lift + (1f - lift) * m
        o[0] = m; o[1] = m; o[2] = m
    }

    /** 흑백 토닝: 그림자(s*)와 밝은 부분(h*)에 각각 색을 얹음 */
    private fun toneMono(o: FloatArray, sr: Float, sg: Float, sb: Float, hr: Float, hg: Float, hb: Float) {
        val m = o[0]
        val sh = (1f - m) * m * 4f * (1f - m)
        val hi = m * m * (1f - m) * 4f
        o[0] = m + sr * sh + hr * hi
        o[1] = m + sg * sh + hg * hi
        o[2] = m + sb * sh + hb * hi
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
