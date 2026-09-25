package kr.co.jnkcorp.filter

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 얼굴 구조 (좌표는 px). 얼굴 인식이 준 윤곽선들로 "잡티를 찾아도 되는 곳" 을 만듭니다.
 * 안드로이드에 기대지 않아 테스트할 수 있습니다.
 */
class FaceRegions(
    val oval: List<FloatArray>,           // 얼굴 윤곽 [x, y] …
    val protect: List<List<FloatArray>>,  // 눈·눈썹·코·입술 윤곽들
) {
    val width: Float get() = if (oval.isEmpty()) 0f else oval.maxOf { it[0] } - oval.minOf { it[0] }

    /**
     * w×h 격자에서 잡티를 찾아도 되는 칸: 얼굴 윤곽 안(조금 안쪽)이면서
     * 눈·눈썹·코·입술과 그 둘레(얼굴 폭의 [pad] 배)는 뺀 곳.
     */
    fun allowedMask(w: Int, h: Int, pad: Float = 0.06f): BooleanArray {
        val m = BooleanArray(w * h)
        if (oval.size < 3) return m
        val fw = width
        val shrink = fw * 0.04f
        fill(m, w, h, oval, true)
        // 윤곽 가장자리(머리카락·턱선 그림자) 조금 빼기
        erodeNear(m, w, h, oval, shrink)
        val p = fw * pad
        for (poly in protect) {
            if (poly.isEmpty()) continue
            // 볼록 껍질을 채우고 둘레까지 넉넉히 빼기 (윗입술·아랫입술 사이, 눈 흰자 포함)
            val hull = convexHull(poly)
            fill(m, w, h, hull, false)
            erodeNear(m, w, h, hull, p)
        }
        return m
    }

    private fun fill(m: BooleanArray, w: Int, h: Int, poly: List<FloatArray>, value: Boolean) {
        if (poly.size < 3) return
        val y0 = max(0, poly.minOf { it[1] }.toInt()); val y1 = min(h - 1, poly.maxOf { it[1] }.toInt() + 1)
        val xs = FloatArray(poly.size)
        for (y in y0..y1) {
            val fy = y + 0.5f
            var k = 0
            for (i in poly.indices) {
                val a = poly[i]; val b = poly[(i + 1) % poly.size]
                if ((a[1] <= fy && b[1] > fy) || (b[1] <= fy && a[1] > fy)) {
                    xs[k++] = a[0] + (fy - a[1]) / (b[1] - a[1]) * (b[0] - a[0])
                }
            }
            xs.sort(0, k)
            var j = 0
            while (j + 1 < k) {
                val xa = max(0, xs[j].toInt()); val xb = min(w - 1, xs[j + 1].toInt())
                for (x in xa..xb) m[y * w + x] = value
                j += 2
            }
        }
    }

    /** 다각형 변에서 [d] 안쪽/바깥쪽 거리의 칸을 false 로 */
    private fun erodeNear(m: BooleanArray, w: Int, h: Int, poly: List<FloatArray>, d: Float) {
        if (d <= 0f || poly.size < 2) return
        val x0 = max(0, (poly.minOf { it[0] } - d).toInt()); val x1 = min(w - 1, (poly.maxOf { it[0] } + d).toInt())
        val y0 = max(0, (poly.minOf { it[1] } - d).toInt()); val y1 = min(h - 1, (poly.maxOf { it[1] } + d).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * w + x
            if (!m[i]) continue
            if (distToPoly(x + 0.5f, y + 0.5f, poly) < d) m[i] = false
        }
    }

    private fun distToPoly(px: Float, py: Float, poly: List<FloatArray>): Float {
        var best = Float.MAX_VALUE
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val dx = b[0] - a[0]; val dy = b[1] - a[1]
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((px - a[0]) * dx + (py - a[1]) * dy) / len2).coerceIn(0f, 1f)
            best = min(best, hypot(px - (a[0] + t * dx), py - (a[1] + t * dy)))
        }
        return best
    }

    companion object {
        fun convexHull(pts: List<FloatArray>): List<FloatArray> {
            if (pts.size < 3) return pts
            val p = pts.sortedWith(compareBy({ it[0] }, { it[1] }))
            fun cross(o: FloatArray, a: FloatArray, b: FloatArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
            val lower = ArrayList<FloatArray>()
            for (q in p) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0) lower.removeAt(lower.size - 1); lower.add(q) }
            val upper = ArrayList<FloatArray>()
            for (q in p.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0) upper.removeAt(upper.size - 1); upper.add(q) }
            lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
            return lower + upper
        }
    }
}
