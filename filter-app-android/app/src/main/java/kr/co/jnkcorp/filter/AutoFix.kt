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
data class Spot(val u: Float, val v: Float, val r: Float, val auto: Boolean = false, val feather: Float = 0.5f)

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
     * 잡티만 찾습니다. "주변보다 어두운 곳" 이 아니라, 아래를 모두 만족하는 점만:
     *  - [allowed] 가 있으면 그 안(얼굴 피부: 눈·눈썹·코·입 제외)에서만
     *  - 작고 둥근 점 (길쭉한 눈썹·속눈썹·주름은 제외)
     *  - 사방이 깨끗한 피부로 둘러싸임 (콧구멍 두 개·눈가처럼 옆에 다른 어두운 게 있으면 제외)
     *  - 너무 진하지 않음 (눈동자·콧구멍은 잡티보다 훨씬 어두움)
     * [sensitivity] 0..1 (클수록 옅은 잡티까지), [faceWidth] 는 얼굴 폭(px, 모르면 0) — 잡티 크기 상한에 씀.
     */
    fun detectBlemishes(
        px: IntArray, w: Int, h: Int, sensitivity: Float = 0.5f,
        allowed: BooleanArray? = null, faceWidth: Float = 0f,
    ): List<Spot> {
        val n = w * h
        val lum = FloatArray(n); val rr = FloatArray(n); val gg = FloatArray(n); val bb = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]
            rr[i] = ((c shr 16) and 0xFF) / 255f; gg[i] = ((c shr 8) and 0xFF) / 255f; bb[i] = (c and 0xFF) / 255f
            lum[i] = luma(rr[i], gg[i], bb[i])
        }
        // 잡티 지름 상한: 얼굴 폭의 3.5% (얼굴을 모르면 사진 긴 변의 1.2%)
        val maxDiam = if (faceWidth > 0f) faceWidth * 0.035f else max(w, h) * 0.012f
        val rad = max(3, (maxDiam * 1.5f).roundToInt())
        val blur = boxBlur(lum, w, h, rad)
        val br = boxBlur(rr, w, h, rad); val bg = boxBlur(gg, w, h, rad); val bbl = boxBlur(bb, w, h, rad)
        val s = sensitivity.coerceIn(0f, 1f)
        val thr = 0.075f - 0.04f * s          // 이만큼은 어두워야
        val tooDark = 0.20f                    // 이보다 더 어두우면 잡티가 아님(눈동자·콧구멍)

        fun skinBg(i: Int): Boolean {
            val r = br[i]; val g = bg[i]; val b = bbl[i]
            return blur[i] in 0.28f..0.95f && r > g && g > b * 0.85f && r - b in 0.06f..0.45f
        }

        val cand = BooleanArray(n)
        for (i in 0 until n) {
            if (allowed != null && !allowed[i]) continue
            val d = blur[i] - lum[i]
            if (d < thr || d > tooDark) continue
            if (skinBg(i)) cand[i] = true
        }

        val maxArea = (PI * (maxDiam / 2) * (maxDiam / 2)).toFloat().coerceAtLeast(6f)
        val seen = BooleanArray(n)
        val stack = IntArray(n)
        val spots = ArrayList<Spot>()
        val long = max(w, h).toFloat()
        for (start in 0 until n) {
            if (!cand[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var area = 0; var sx = 0L; var sy = 0L
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            var dark = 0f
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w; val y = i / w
                area++; sx += x; sy += y; dark += blur[i] - lum[i]
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                if (area > maxArea * 3) continue   // 너무 크면 더 볼 필요 없음
                if (x > 0 && cand[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && cand[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && cand[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && cand[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            if (area < 3 || area > maxArea) continue
            // 둥근가: 길쭉하면(눈썹·속눈썹·주름) 제외
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (max(bw, bh).toFloat() / min(bw, bh) > 2.0f) continue
            if (area.toFloat() / (bw * bh) < 0.45f) continue
            // 너무 진하면 제외
            if (dark / area > tooDark * 0.9f) continue
            // 사방이 깨끗한 피부인가
            val cx = sx.toFloat() / area; val cy = sy.toFloat() / area
            val r = sqrt(area / PI.toFloat())
            if (!cleanRing(cx, cy, max(r * 2.6f, r + 3f), w, h, lum, blur, cand, allowed, ::skinBg)) continue
            val radius = r * 1.5f + 1f
            spots += Spot(cx / w, cy / h, radius / long, auto = true)
            if (spots.size >= 200) break
        }
        return spots
    }

    /** 점 둘레를 한 바퀴 돌며, 거의 다 깨끗한 피부인지 */
    private fun cleanRing(
        cx: Float, cy: Float, ring: Float, w: Int, h: Int, lum: FloatArray, blur: FloatArray,
        cand: BooleanArray, allowed: BooleanArray?, skin: (Int) -> Boolean,
    ): Boolean {
        val samples = 20
        var ok = 0
        for (a in 0 until samples) {
            val t = 2 * PI * a / samples
            val x = (cx + ring * cos(t)).roundToInt(); val y = (cy + ring * sin(t)).roundToInt()
            if (x !in 0 until w || y !in 0 until h) continue
            val i = y * w + x
            if (cand[i]) continue
            if (allowed != null && !allowed[i]) continue
            if (!skin(i)) continue
            if (kotlin.math.abs(lum[i] - blur[i]) > 0.06f) continue   // 둘레에도 굴곡(눈가·콧방울)이 있으면
            ok++
        }
        return ok >= samples - 2
    }

    // ───────────── 잡티 지우기 (힐링) ─────────────

    /** [spots] 를 모두 지웁니다 (제자리 수정). */
    fun heal(px: IntArray, w: Int, h: Int, spots: List<Spot>) {
        val long = max(w, h)
        for (s in spots) healOne(px, w, h, s.u * w, s.v * h, max(1.5f, s.r * long), s.feather)
    }

    /**
     * 힐링 브러시 방식: 근처에서 결이 비슷한 조각을 복사해 오고,
     * 가장자리 색 차이를 안쪽으로 부드럽게 메워서 경계가 보이지 않게 합니다.
     */
    /**
     * [r] 안쪽은 완전히 메우고, [feather](0..1) 만큼 가장자리를 부드럽게 원래 피부와 섞습니다.
     * 0 이면 경계가 또렷, 1 이면 반지름의 1.6배까지 서서히.
     */
    private fun healOne(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, feather: Float = 0.5f) {
        val f = feather.coerceIn(0f, 1f)
        val inner = r * (1f - 0.6f * f)
        val ring = r * (1.1f + 0.5f * f)
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
            val wgt = 1f - smooth(inner, ring, d)
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
