package kr.co.jnkcorp.filter

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 브러시로 칠하는 마스크 (0 = 안 칠함, 1 = 다 칠함).
 * 자르기 전 사진 전체를 덮는 낮은 해상도 격자로 두고, 쓸 때 늘려서 읽습니다.
 */
class Mask(val w: Int, val h: Int, val data: FloatArray = FloatArray(w * h)) {

    fun copy() = Mask(w, h, data.copyOf())

    /** 사진 전체 기준 0..1 좌표에서 값을 읽습니다 (쌍선형 보간). */
    fun sample(u: Float, v: Float): Float {
        val fx = (u * w - 0.5f).coerceIn(0f, (w - 1).toFloat())
        val fy = (v * h - 0.5f).coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val dx = fx - x0; val dy = fy - y0
        val a = mix(data[y0 * w + x0], data[y0 * w + x1], dx)
        val b = mix(data[y1 * w + x0], data[y1 * w + x1], dx)
        return mix(a, b, dy)
    }

    /**
     * 브러시 한 번 찍기. [radius] 는 사진 긴 변 대비 비율, [softness] 0(딱딱)..1(부드럽게),
     * [flow] 한 번에 얼마나 칠할지. [erase] 면 빼기.
     */
    fun dab(u: Float, v: Float, radius: Float, softness: Float, flow: Float, erase: Boolean) {
        val rp = max(0.5f, radius * max(w, h))
        val cx = u * w; val cy = v * h
        val x0 = max(0, (cx - rp).toInt()); val x1 = min(w - 1, (cx + rp).toInt() + 1)
        val y0 = max(0, (cy - rp).toInt()); val y1 = min(h - 1, (cy + rp).toInt() + 1)
        val hard = 1f - softness.coerceIn(0f, 1f)
        for (y in y0..y1) for (x in x0..x1) {
            val d = hypot(x + 0.5f - cx, y + 0.5f - cy) / rp
            if (d >= 1f) continue
            val f = (1f - smooth(hard * 0.999f, 1f, d)) * flow
            val i = y * w + x
            data[i] = if (erase) data[i] * (1f - f) else data[i] + (1f - data[i]) * f
        }
    }

    /** 두 점 사이를 촘촘히 찍어 끊기지 않는 선으로 */
    fun stroke(u0: Float, v0: Float, u1: Float, v1: Float, radius: Float, softness: Float, flow: Float, erase: Boolean) {
        val dist = hypot((u1 - u0) * w, (v1 - v0) * h)
        val step = max(1f, radius * max(w, h) * 0.3f)
        val n = max(1, (dist / step).roundToInt())
        for (i in 1..n) {
            val t = i.toFloat() / n
            dab(mix(u0, u1, t), mix(v0, v1, t), radius, softness, flow, erase)
        }
    }

    fun invert() { for (i in data.indices) data[i] = 1f - data[i] }
    fun fill(v: Float) { data.fill(v) }
    fun isEmpty() = data.all { it < 0.002f }

    companion object {
        /** 사진 비율에 맞춰 긴 변 [long] 칸짜리 빈 마스크 */
        fun forImage(imgW: Int, imgH: Int, long: Int = 1024): Mask {
            val s = long.toFloat() / max(imgW, imgH)
            return Mask(max(1, (imgW * s).roundToInt()), max(1, (imgH * s).roundToInt()))
        }
    }
}

/** 마스크 레이어: 칠한 곳에만 [params] 보정이 들어갑니다. */
class Layer(var name: String, val mask: Mask, var params: MakerParams = MakerParams())

/** 렌더링용으로 LUT 를 미리 구운 레이어 */
class LayerRender(val lut: Lut3D, val mask: Mask)
