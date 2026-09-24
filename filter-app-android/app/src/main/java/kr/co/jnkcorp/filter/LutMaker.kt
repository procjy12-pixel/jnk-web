package kr.co.jnkcorp.filter

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/** LUT 만들기 슬라이더 값. 모두 0 이면 아무것도 바꾸지 않습니다. */
data class MakerParams(
    var exposure: Float = 0f,      // -2..2 EV
    var contrast: Float = 0f,      // -1..1
    var highlights: Float = 0f,    // -1..1  (- 하이라이트 눌러서 살리기)
    var shadows: Float = 0f,       // -1..1  (+ 그림자 들어올리기)
    var saturation: Float = 0f,    // -1..1  (-1 이면 흑백)
    var temperature: Float = 0f,   // -1..1  (+ 따뜻하게)
    var tint: Float = 0f,          // -1..1  (+ 마젠타)
    var fade: Float = 0f,          // 0..1   블랙 들어올림
    var shadowHue: Float = 190f,   // 0..360 기본 청록
    var shadowAmount: Float = 0f,  // 0..1
    var highlightHue: Float = 30f, // 0..360 기본 오렌지
    var highlightAmount: Float = 0f,
)

/**
 * 기준(다른 LUT 또는 참고 사진 색감) 위에 슬라이더 조정을 얹어 새 LUT 를 굽습니다.
 */
object LutMaker {

    /**
     * [baseIntensity] 는 기준 LUT 를 얼마나 섞을지 (LUT 적용 탭의 강도).
     * 흑백 LUT 는 강도를 낮춰도 색이 돌아오지 않게 원본 흑백과 섞습니다.
     */
    fun build(
        base: Lut3D?, transfer: ColorTransfer?, p: MakerParams, title: String = "",
        size: Int = Lut3D.DEFAULT_SIZE, baseIntensity: Float = 1f,
    ): Lut3D {
        val baseMono = base?.isMono == true
        val tmp = FloatArray(3)
        val shadowTint = hueTint(p.shadowHue)
        val highTint = hueTint(p.highlightHue)
        val gain = 2f.pow(p.exposure)
        return Lut3D.bake(size, title) { r0, g0, b0, o ->
            o[0] = r0; o[1] = g0; o[2] = b0
            transfer?.apply(o)
            if (base != null) {
                base.sample(o[0], o[1], o[2], tmp)
                if (baseMono) { val m = luma(o[0], o[1], o[2]); o[0] = m; o[1] = m; o[2] = m }
                for (c in 0..2) o[c] = mix(o[c], tmp[c], baseIntensity)
            }
            var r = o[0]; var g = o[1]; var b = o[2]

            // 노출 (선형 공간에서 곱하기)
            if (p.exposure != 0f) {
                r = toGamma(toLinear(r) * gain)
                g = toGamma(toLinear(g) * gain)
                b = toGamma(toLinear(b) * gain)
            }
            // 화이트밸런스
            r *= 1f + 0.12f * p.temperature
            b *= 1f - 0.12f * p.temperature
            g *= 1f - 0.08f * p.tint
            r = clamp01(r); g = clamp01(g); b = clamp01(b)

            // 대비
            if (p.contrast > 0f) {
                r = sCurve(r, p.contrast); g = sCurve(g, p.contrast); b = sCurve(b, p.contrast)
            } else if (p.contrast < 0f) {
                val k = 1f + 0.6f * p.contrast
                r = 0.5f + (r - 0.5f) * k; g = 0.5f + (g - 0.5f) * k; b = 0.5f + (b - 0.5f) * k
            }

            // 하이라이트·그림자: 끝점(순흑·순백)은 그대로 두고 밝은 쪽/어두운 쪽만 움직임
            if (p.highlights != 0f || p.shadows != 0f) {
                r = tone(r, p.highlights, p.shadows)
                g = tone(g, p.highlights, p.shadows)
                b = tone(b, p.highlights, p.shadows)
            }

            // 채도
            Looks.saturate(r, g, b, 1f + p.saturation, o)
            r = o[0]; g = o[1]; b = o[2]

            // 스플릿 토닝: 그림자·하이라이트에 각각 색을 얹음
            val l = clamp01(luma(r, g, b))
            val sw = (1f - l) * (1f - l) * p.shadowAmount * 0.35f
            val hw = l * l * p.highlightAmount * 0.35f
            r += sw * shadowTint[0] + hw * highTint[0]
            g += sw * shadowTint[1] + hw * highTint[1]
            b += sw * shadowTint[2] + hw * highTint[2]

            // 페이드
            val lift = p.fade * 0.15f
            o[0] = lift + (1f - lift) * r
            o[1] = lift + (1f - lift) * g
            o[2] = lift + (1f - lift) * b
        }
    }

    private fun tone(x: Float, hi: Float, sh: Float): Float {
        val v = clamp01(x)
        return v + hi * 1.2f * v * v * (1f - v) + sh * 1.2f * v * (1f - v) * (1f - v)
    }

    /** 색상(도)을 밝기 0 인 색 차이 벡터로. 더해도 밝기는 거의 그대로입니다. */
    fun hueTint(h: Float): FloatArray {
        val c = hueRgb(h)
        val l = luma(c[0], c[1], c[2])
        return floatArrayOf(c[0] - l, c[1] - l, c[2] - l)
    }

    fun hueRgb(h: Float): FloatArray {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val x = 1f - kotlin.math.abs(hh % 2f - 1f)
        return when (hh.toInt()) {
            0 -> floatArrayOf(1f, x, 0f)
            1 -> floatArrayOf(x, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, x)
            3 -> floatArrayOf(0f, x, 1f)
            4 -> floatArrayOf(x, 0f, 1f)
            else -> floatArrayOf(1f, 0f, x)
        }
    }

    private fun toLinear(v: Float) = clamp01(v).toDouble().pow(2.2).toFloat()
    private fun toGamma(v: Float) = clamp01(v).toDouble().pow(1 / 2.2).toFloat()
}

/**
 * 참고 사진 색감 따오기 (Reinhard 색 전이, Lab 공간).
 * 지금 사진의 색 분포(평균·편차)를 참고 사진의 분포로 옮기는 변환을 만듭니다.
 */
class ColorTransfer private constructor(
    private val srcMean: FloatArray, private val srcStd: FloatArray,
    private val refMean: FloatArray, private val refStd: FloatArray,
    private val strength: Float,
) {
    /** 여러 스레드에서 동시에 불려도 되도록 버퍼를 공유하지 않습니다. */
    fun apply(rgb: FloatArray) {
        val lab = FloatArray(3)
        rgbToLab(rgb[0], rgb[1], rgb[2], lab)
        for (c in 0..2) {
            // 밝기(L)까지 통째로 옮기면 어두운 참고 사진에서 사진이 뭉개지므로,
            // 밝기는 살짝만, 색(a·b)은 강하게 옮깁니다.
            val isL = c == 0
            val ratio = (refStd[c] / maxOf(srcStd[c], 1e-3f)).coerceIn(if (isL) 0.75f else 0.4f, if (isL) 1.35f else 2.5f)
            val moved = (lab[c] - srcMean[c]) * ratio + refMean[c]
            lab[c] = mix(lab[c], moved, if (isL) strength * 0.35f else strength)
        }
        labToRgb(lab[0], lab[1], lab[2], rgb)
    }

    companion object {
        /** [src]·[ref] 는 ARGB 픽셀 배열 (작게 줄인 사진이면 충분) */
        fun from(src: IntArray, ref: IntArray, strength: Float = 0.85f): ColorTransfer {
            val (sm, ss) = stats(src)
            val (rm, rs) = stats(ref)
            return ColorTransfer(sm, ss, rm, rs, strength)
        }

        private fun stats(px: IntArray): Pair<FloatArray, FloatArray> {
            val sum = DoubleArray(3)
            val sq = DoubleArray(3)
            val lab = FloatArray(3)
            for (c in px) {
                rgbToLab(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, lab)
                for (i in 0..2) { sum[i] += lab[i]; sq[i] += (lab[i] * lab[i]).toDouble() }
            }
            val n = maxOf(1, px.size).toDouble()
            val mean = FloatArray(3) { (sum[it] / n).toFloat() }
            val std = FloatArray(3) { sqrt(maxOf(0.0, sq[it] / n - (sum[it] / n) * (sum[it] / n))).toFloat() }
            return mean to std
        }

        // sRGB(D65) ↔ CIE Lab
        private fun lin(v: Float): Double { val d = v.toDouble(); return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4) }
        private fun gam(v: Double): Float = clamp01((if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055).toFloat())
        private fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        private fun fi(t: Double) = if (t > 0.206893) t * t * t else (t - 16.0 / 116) / 7.787

        fun rgbToLab(r: Float, g: Float, b: Float, out: FloatArray) {
            val lr = lin(r); val lg = lin(g); val lb = lin(b)
            val x = (0.4124 * lr + 0.3576 * lg + 0.1805 * lb) / 0.95047
            val y = 0.2126 * lr + 0.7152 * lg + 0.0722 * lb
            val z = (0.0193 * lr + 0.1192 * lg + 0.9505 * lb) / 1.08883
            val fx = f(x); val fy = f(y); val fz = f(z)
            out[0] = (116 * fy - 16).toFloat()
            out[1] = (500 * (fx - fy)).toFloat()
            out[2] = (200 * (fy - fz)).toFloat()
        }

        fun labToRgb(l: Float, a: Float, bb: Float, out: FloatArray) {
            val fy = (l + 16) / 116.0
            val fx = fy + a / 500.0
            val fz = fy - bb / 200.0
            val x = fi(fx) * 0.95047
            val y = fi(fy)
            val z = fi(fz) * 1.08883
            out[0] = gam(3.2406 * x - 1.5372 * y - 0.4986 * z)
            out[1] = gam(-0.9689 * x + 1.8758 * y + 0.0415 * z)
            out[2] = gam(0.0557 * x - 0.2040 * y + 1.0570 * z)
        }
    }
}
