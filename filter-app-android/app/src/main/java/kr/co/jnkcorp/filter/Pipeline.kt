package kr.co.jnkcorp.filter

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 사진에 입히는 설정 한 벌 */
data class Grade(
    val lut: Lut3D?,          // null 이면 색은 그대로
    val intensity: Float,     // 0..1  LUT 를 얼마나 섞을지
    val grain: Float,         // 0..1
    val vignette: Float,      // 0..1
    val layers: List<LayerRender> = emptyList(),
)

/**
 * 처리할 픽셀 배열이 사진 전체의 어디인지 (마스크 좌표 맞추기용).
 * 전체 크기 [fullW]×[fullH] 안에서 ([left], [top]) 부터.
 */
data class Region(val left: Int, val top: Int, val fullW: Int, val fullH: Int)

/**
 * 픽셀 배열(ARGB)에 [Grade] 를 입힙니다. 안드로이드 클래스에 기대지 않아서
 * JVM 테스트에서도 그대로 돌릴 수 있습니다.
 */
object Pipeline {

    private val threads = max(2, Runtime.getRuntime().availableProcessors())
    private val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r).apply { isDaemon = true } }

    /** [px] 를 제자리에서 바꿉니다. [grainScale] 은 미리보기 대비 해상도 배율 (그레인 굵기 보정). */
    fun process(px: IntArray, w: Int, h: Int, g: Grade, grainScale: Float = 1f, region: Region = Region(0, 0, w, h)) {
        val lut = g.lut
        val useLut = lut != null && g.intensity > 0f
        val layers = g.layers
        if (!useLut && g.grain <= 0f && g.vignette <= 0f && layers.isEmpty()) return
        val band = (h + threads - 1) / threads
        val jobs = (0 until threads).map { t ->
            Callable {
                val out = FloatArray(3)
                val lo = FloatArray(3)
                val cx = w * 0.5f
                val cy = h * 0.5f
                val vAmt = g.vignette * 0.6f
                // 해상도가 높을수록 한 픽셀 노이즈가 눈에 덜 띄므로 진폭을 조금 올립니다.
                val gAmt = g.grain * 0.1f * sqrt(max(1f, grainScale))
                for (y in t * band until min(h, (t + 1) * band)) {
                    val ny = (y - cy) / cy
                    val mv = (y + region.top + 0.5f) / region.fullH
                    var i = y * w
                    for (x in 0 until w) {
                        val c = px[i]
                        val r0 = ((c shr 16) and 0xFF) / 255f
                        val g0 = ((c shr 8) and 0xFF) / 255f
                        val b0 = (c and 0xFF) / 255f
                        var r = r0; var gg = g0; var b = b0

                        if (useLut) {
                            lut!!.sample(r0, g0, b0, out)
                            // 흑백 LUT 는 강도를 낮춰도 색이 돌아오지 않게 원본 흑백과 섞음
                            var br = r0; var bg = g0; var bb = b0
                            if (lut.isMono) { val m = luma(r0, g0, b0); br = m; bg = m; bb = m }
                            r = mix(br, out[0], g.intensity)
                            gg = mix(bg, out[1], g.intensity)
                            b = mix(bb, out[2], g.intensity)
                        }
                        // 마스크 레이어: 칠한 만큼만 그 레이어 보정을 섞음
                        if (layers.isNotEmpty()) {
                            val mu = (x + region.left + 0.5f) / region.fullW
                            for (L in layers) {
                                val m = L.mask.sample(mu, mv)
                                if (m < 0.002f) continue
                                L.lut.sample(clamp01(r), clamp01(gg), clamp01(b), lo)
                                r = mix(r, lo[0], m); gg = mix(gg, lo[1], m); b = mix(b, lo[2], m)
                            }
                        }
                        if (vAmt > 0f) {
                            val nx = (x - cx) / cx
                            val v = 1f - vAmt * smooth(0.25f, 1.6f, nx * nx + ny * ny)
                            r *= v; gg *= v; b *= v
                        }
                        if (gAmt > 0f) {
                            val n = (hash(x, y) - 0.5f) * gAmt
                            r += n; gg += n; b += n
                        }
                        px[i] = (c and 0xFF000000.toInt()) or (to8(r) shl 16) or (to8(gg) shl 8) or to8(b)
                        i++
                    }
                }
            }
        }
        pool.invokeAll(jobs).forEach { it.get() }
    }

    private fun to8(v: Float): Int = (clamp01(v) * 255f + 0.5f).toInt()

    private fun hash(x: Int, y: Int): Float {
        var n = x * 374761393 + y * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0xFFFF) / 65535f
    }
}
