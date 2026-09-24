package kr.co.jnkcorp.filter

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 잡티 하나. 좌표는 자르기 전 사진 전체 기준 0..1, 반지름은 긴 변 대비 비율.
 * 해상도와 상관없이 미리보기와 원본 저장에 똑같이 적용하려고 비율로 둡니다.
 */
data class Spot(val u: Float, val v: Float, val r: Float, val auto: Boolean = false)

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

    // ───────────── 잡티 찾기 ─────────────

    /**
     * 피부색 영역에서 주변보다 작고 어두운 점을 찾습니다.
     * [sensitivity] 0..1 (클수록 옅은 잡티까지)
     */
    fun detectBlemishes(px: IntArray, w: Int, h: Int, sensitivity: Float = 0.5f): List<Spot> {
        val n = w * h
        val lum = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            lum[i] = luma(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
        }
        val rad = max(4, max(w, h) / 90)
        val blur = boxBlur(lum, w, h, rad)
        val thr = 0.09f - 0.06f * sensitivity.coerceIn(0f, 1f)

        val cand = BooleanArray(n)
        for (i in 0 until n) {
            if (blur[i] - lum[i] < thr) continue
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            // 둘레(블러) 쪽이 피부색이어야 함: 빨강 > 초록 > 파랑, 너무 어둡거나 밝지 않게
            if (blur[i] in 0.25f..0.95f && r > g && g >= b * 0.9f && r - b > 0.06f) cand[i] = true
        }

        val maxArea = (rad * rad * 1.2f).roundToInt()
        val seen = BooleanArray(n)
        val stack = IntArray(n)
        val spots = ArrayList<Spot>()
        val long = max(w, h).toFloat()
        for (start in 0 until n) {
            if (!cand[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var area = 0; var sx = 0L; var sy = 0L
            while (sp > 0) {
                val i = stack[--sp]
                area++; sx += i % w; sy += i / w
                val x = i % w; val y = i / w
                if (x > 0 && cand[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && cand[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && cand[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && cand[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            if (area < 3 || area > maxArea) continue
            val cx = sx.toFloat() / area; val cy = sy.toFloat() / area
            val radius = sqrt(area / PI.toFloat()) * 1.8f + 1.5f
            spots += Spot(cx / w, cy / h, radius / long, auto = true)
            if (spots.size >= 300) break
        }
        return spots
    }

    // ───────────── 잡티 지우기 (힐링) ─────────────

    /** [spots] 를 모두 지웁니다 (제자리 수정). */
    fun heal(px: IntArray, w: Int, h: Int, spots: List<Spot>) {
        val long = max(w, h)
        for (s in spots) healOne(px, w, h, s.u * w, s.v * h, max(1.5f, s.r * long))
    }

    /**
     * 힐링 브러시 방식: 근처에서 결이 비슷한 조각을 복사해 오고,
     * 가장자리 색 차이를 안쪽으로 부드럽게 메워서 경계가 보이지 않게 합니다.
     */
    private fun healOne(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float) {
        val ring = r * 1.25f
        val samples = 24
        val target = Array(samples) { FloatArray(3) }
        for (a in 0 until samples) {
            val t = 2 * PI * a / samples
            read(px, w, h, cx + ring * cos(t).toFloat(), cy + ring * sin(t).toFloat(), target[a])
        }

        // 복사해 올 곳: 8 방향 중 둘레 색이 가장 비슷한 곳
        var bestDx = 0f; var bestDy = 0f; var bestErr = Float.MAX_VALUE
        val tmp = FloatArray(3)
        for (d in 0 until 8) {
            val t = 2 * PI * d / 8
            val dx = (2.4f * r * cos(t)).toFloat(); val dy = (2.4f * r * sin(t)).toFloat()
            if (cx + dx - ring < 0 || cy + dy - ring < 0 || cx + dx + ring >= w || cy + dy + ring >= h) continue
            var err = 0f
            for (a in 0 until samples) {
                val tt = 2 * PI * a / samples
                read(px, w, h, cx + dx + ring * cos(tt).toFloat(), cy + dy + ring * sin(tt).toFloat(), tmp)
                for (c in 0..2) { val e = tmp[c] - target[a][c]; err += e * e }
            }
            if (err < bestErr) { bestErr = err; bestDx = dx; bestDy = dy }
        }
        val src = Array(samples) { FloatArray(3) }
        for (a in 0 until samples) {
            val t = 2 * PI * a / samples
            read(px, w, h, cx + bestDx + ring * cos(t).toFloat(), cy + bestDy + ring * sin(t).toFloat(), src[a])
        }

        val x0 = max(0, (cx - ring).toInt()); val x1 = min(w - 1, (cx + ring).toInt() + 1)
        val y0 = max(0, (cy - ring).toInt()); val y1 = min(h - 1, (cy + ring).toInt() + 1)
        val orig = IntArray((x1 - x0 + 1) * (y1 - y0 + 1))
        val bw = x1 - x0 + 1
        for (y in y0..y1) for (x in x0..x1) orig[(y - y0) * bw + (x - x0)] = px[y * w + x]
        val cl = FloatArray(3)
        for (y in y0..y1) for (x in x0..x1) {
            val d = hypot(x - cx, y - cy)
            if (d >= ring) continue
            // 둘레 색 차이를 각도 따라 보간 (가까운 두 샘플)
            val ang = ((atan2(y - cy, x - cx) / (2 * PI) * samples) + samples) % samples
            val a0 = ang.toInt() % samples; val a1 = (a0 + 1) % samples; val f = (ang - ang.toInt()).toFloat()
            readOrig(orig, bw, x0, y0, x1, y1, px, w, h, x + bestDx, y + bestDy, cl)
            val wgt = 1f - smooth(r * 0.85f, ring, d)
            val c = px[y * w + x]
            val o = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
            val out = IntArray(3)
            for (k in 0..2) {
                val diff = mix(target[a0][k] - src[a0][k], target[a1][k] - src[a1][k], f)
                val healed = cl[k] + diff
                out[k] = (clamp01(mix(o[k], healed, wgt)) * 255f + 0.5f).toInt()
            }
            px[y * w + x] = (c and 0xFF000000.toInt()) or (out[0] shl 16) or (out[1] shl 8) or out[2]
        }
    }

    /** 복사 원본은 이번 잡티를 고치기 전 값으로 읽습니다 (겹칠 때 번지지 않게). */
    private fun readOrig(orig: IntArray, bw: Int, x0: Int, y0: Int, x1: Int, y1: Int,
                         px: IntArray, w: Int, h: Int, x: Float, y: Float, out: FloatArray) {
        val xi = x.roundToInt().coerceIn(0, w - 1); val yi = y.roundToInt().coerceIn(0, h - 1)
        val c = if (xi in x0..x1 && yi in y0..y1) orig[(yi - y0) * bw + (xi - x0)] else px[yi * w + xi]
        out[0] = ((c shr 16) and 0xFF) / 255f; out[1] = ((c shr 8) and 0xFF) / 255f; out[2] = (c and 0xFF) / 255f
    }

    private fun read(px: IntArray, w: Int, h: Int, x: Float, y: Float, out: FloatArray) {
        val c = px[y.roundToInt().coerceIn(0, h - 1) * w + x.roundToInt().coerceIn(0, w - 1)]
        out[0] = ((c shr 16) and 0xFF) / 255f; out[1] = ((c shr 8) and 0xFF) / 255f; out[2] = (c and 0xFF) / 255f
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        // 적분 영상으로 한 번에
        val ii = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val xa = max(0, x - r); val xb = min(w, x + r + 1)
            val ya = max(0, y - r); val yb = min(h, y + r + 1)
            val s = ii[yb * (w + 1) + xb] - ii[ya * (w + 1) + xb] - ii[yb * (w + 1) + xa] + ii[ya * (w + 1) + xa]
            out[y * w + x] = (s / ((xb - xa) * (yb - ya))).toFloat()
        }
        return out
    }
}
