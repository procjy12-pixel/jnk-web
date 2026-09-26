package kr.co.jnkcorp.filter

import kotlin.math.max
import kotlin.math.min

object AutoFix {

    // ───────────── 색온도 자동 ─────────────

    /**
     * 회색 세계 가정으로 색 치우침을 재서 [MakerParams] 의 (색온도, 틴트) 값을 돌려줍니다.
     * 채도가 낮은 중간 밝기 픽셀 위주로 보고, 그런 픽셀이 적으면 전체 평균을 씁니다.
     */
    fun whiteBalance(px: IntArray): Pair<Float, Float> {
        var nr = 0.0; var ng = 0.0; var nb = 0.0; var nn = 0
        var ar = 0.0; var ag = 0.0; var ab = 0.0
        for (c in px) {
            val r = ((c shr 16) and 0xFF) / 255.0
            val g = ((c shr 8) and 0xFF) / 255.0
            val b = (c and 0xFF) / 255.0
            ar += r; ag += g; ab += b
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
            val l = (r + g + b) / 3
            if (l in 0.15..0.92 && mx - mn < 0.18) { nr += r; ng += g; nb += b; nn++ }
        }
        val (r, g, b) = if (nn > px.size / 50) Triple(nr / nn, ng / nn, nb / nn)
        else Triple(ar / px.size, ag / px.size, ab / px.size)
        // LutMaker: r *= 1 + T·t, b *= 1 - T·t  →  r' = b' 가 되는 t
        val temp = ((b - r) / (LutMaker.WB_TEMP * (r + b))).toFloat().coerceIn(-1f, 1f)
        val rb = (r * (1 + LutMaker.WB_TEMP * temp) + b * (1 - LutMaker.WB_TEMP * temp)) / 2
        // g *= 1 - N·tint  →  g' = (r'+b')/2
        val tint = ((1 - rb / g) / LutMaker.WB_TINT).toFloat().coerceIn(-1f, 1f)
        return temp to tint
    }

}

/** 자동 노출: (노출 EV, 하이라이트) */
fun autoExposure(px: IntArray): Pair<Float, Float> {
    if (px.isEmpty()) return 0f to 0f
    var logSum = 0.0
    val lums = FloatArray(px.size)
    for (i in px.indices) {
        val c = px[i]
        val l = luma(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
        val lin = Math.pow(l.toDouble(), 2.2)
        lums[i] = lin.toFloat()
        logSum += Math.log(lin + 1e-4)
    }
    // 장면 평균(기하평균)을 18% 회색에 맞추되, 너무 세게 바꾸지 않도록 70% 만
    val geo = Math.exp(logSum / px.size)
    var ev = (Math.log(0.18 / geo) / Math.log(2.0) * 0.7).toFloat().coerceIn(-1.5f, 1.5f)
    lums.sort()
    val p99 = lums[(lums.size * 0.99).toInt().coerceAtMost(lums.size - 1)]
    val after = p99 * Math.pow(2.0, ev.toDouble()).toFloat()
    // 밝히다가 하이라이트가 날아가면 하이라이트를 눌러 줌
    val hi = if (after > 1f) -((after - 1f) * 1.2f).coerceIn(0f, 0.8f) else 0f
    if (kotlin.math.abs(ev) < 0.05f) ev = 0f
    return ev to hi
}
