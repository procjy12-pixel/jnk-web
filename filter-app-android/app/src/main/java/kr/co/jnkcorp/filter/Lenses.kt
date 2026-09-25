package kr.co.jnkcorp.filter

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 폰의 렌즈 구성 → 배율 버튼 (0.6× · 1× · 3× 처럼).
 * 렌즈마다 초점거리와 센서 크기로 35mm 환산 화각을 구해 기본 렌즈 대비 배율을 냅니다.
 * 안드로이드에 기대지 않아 테스트할 수 있습니다.
 */
object Lenses {

    /** [focal] mm, 센서 크기 mm */
    data class Lens(val focal: Float, val sensorW: Float, val sensorH: Float)

    fun eq35(l: Lens): Float = l.focal * 43.27f / hypot(l.sensorW, l.sensorH)

    /**
     * 배율 목록. [main] 기본 렌즈, [others] 다른 렌즈들(없으면 빈 목록),
     * 카메라가 허용하는 줌 범위 [minZoom]..[maxZoom] 안의 것만, 가까운 것(12% 이내)은 하나로.
     * 광각 렌즈 정보를 못 받아도 최소 줌이 1보다 작으면 그걸 광각으로 봅니다.
     */
    fun presets(main: Lens?, others: List<Lens>, minZoom: Float, maxZoom: Float): List<Float> {
        val raw = ArrayList<Float>()
        raw += 1f
        if (main != null) {
            val base = eq35(main)
            if (base > 0f) for (o in others) raw += tidy(eq35(o) / base)
        }
        if (minZoom < 0.95f) raw += tidy(minZoom)
        val inRange = raw.filter { it >= minZoom - 0.02f && it <= maxZoom + 0.02f }.sorted()
        val out = ArrayList<Float>()
        for (r in inRange) {
            val last = out.lastOrNull()
            if (last == null || r / last > 1.12f) out += r
        }
        return out
    }

    /** 0.56 → 0.6, 2.9 → 3, 5.1 → 5 처럼 보기 좋게 */
    fun tidy(r: Float): Float {
        if (r < 1f) return (r * 10).roundToInt() / 10f
        val whole = r.roundToInt().toFloat()
        return if (abs(r - whole) < 0.2f) whole else (r * 10).roundToInt() / 10f
    }

    /** 버튼 글자: 0.6 → ".6", 1 → "1", 3 → "3", 1.5 → "1.5" */
    fun label(r: Float): String = when {
        r < 1f -> "." + ((r * 10).roundToInt())
        r == r.roundToInt().toFloat() -> r.roundToInt().toString()
        else -> String.format("%.1f", r)
    }
}
