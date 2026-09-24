package kr.co.jnkcorp.filter

import java.io.Reader
import java.util.Locale
import kotlin.math.abs

/**
 * 3D LUT. 격자 한 변이 [size] 칸이고, 값은 0..1 RGB.
 * 배열 순서는 .cube 와 같습니다 — 빨강이 가장 빨리 변합니다: index = (b*N + g)*N + r
 */
class Lut3D(val size: Int, val data: FloatArray, val title: String = "") {

    init {
        require(size >= 2) { "LUT 크기가 너무 작습니다" }
        require(data.size == size * size * size * 3) { "LUT 데이터 개수가 맞지 않습니다" }
    }

    /** 모든 칸이 무채색이면 흑백 LUT. 강도를 낮출 때 원본 색 대신 원본 흑백과 섞기 위해 씁니다. */
    val isMono: Boolean by lazy {
        var i = 0
        var mono = true
        while (i < data.size) {
            if (abs(data[i] - data[i + 1]) > 1e-3f || abs(data[i + 1] - data[i + 2]) > 1e-3f) { mono = false; break }
            i += 3
        }
        mono
    }

    /** 삼선형 보간으로 한 색을 찾습니다. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val n1 = size - 1
        val fr = clamp01(r) * n1
        val fg = clamp01(g) * n1
        val fb = clamp01(b) * n1
        val r0 = minOf(fr.toInt(), n1 - 1)
        val g0 = minOf(fg.toInt(), n1 - 1)
        val b0 = minOf(fb.toInt(), n1 - 1)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        val sR = 3
        val sG = size * 3
        val sB = size * size * 3
        val base = b0 * sB + g0 * sG + r0 * sR
        for (c in 0..2) {
            val p = base + c
            val c000 = data[p]
            val c100 = data[p + sR]
            val c010 = data[p + sG]
            val c110 = data[p + sG + sR]
            val c001 = data[p + sB]
            val c101 = data[p + sB + sR]
            val c011 = data[p + sB + sG]
            val c111 = data[p + sB + sG + sR]
            val c00 = c000 + (c100 - c000) * dr
            val c10 = c010 + (c110 - c010) * dr
            val c01 = c001 + (c101 - c001) * dr
            val c11 = c011 + (c111 - c011) * dr
            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg
            out[c] = c0 + (c1 - c0) * db
        }
    }

    fun withTitle(t: String) = Lut3D(size, data, t)

    /** Adobe/Resolve 공용 .cube 텍스트 */
    fun toCube(): String {
        val sb = StringBuilder(data.size * 10 + 128)
        sb.append("# JNK Filter\n")
        if (title.isNotBlank()) sb.append("TITLE \"").append(title.replace("\"", "'")).append("\"\n")
        sb.append("LUT_3D_SIZE ").append(size).append('\n')
        sb.append("DOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n")
        var i = 0
        while (i < data.size) {
            sb.append(String.format(Locale.US, "%.6f %.6f %.6f\n", data[i], data[i + 1], data[i + 2]))
            i += 3
        }
        return sb.toString()
    }

    companion object {
        const val DEFAULT_SIZE = 33

        /** 색 함수 [fn] 을 격자 위에서 계산해 LUT 로 굽습니다. */
        fun bake(size: Int = DEFAULT_SIZE, title: String = "", fn: (Float, Float, Float, FloatArray) -> Unit): Lut3D {
            val d = FloatArray(size * size * size * 3)
            val o = FloatArray(3)
            val n1 = (size - 1).toFloat()
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                fn(r / n1, g / n1, b / n1, o)
                d[i] = clamp01(o[0]); d[i + 1] = clamp01(o[1]); d[i + 2] = clamp01(o[2])
                i += 3
            }
            return Lut3D(size, d, title)
        }

        fun identity(size: Int = DEFAULT_SIZE) = bake(size) { r, g, b, o -> o[0] = r; o[1] = g; o[2] = b }

        /** .cube 읽기. 3D LUT 만 받습니다. */
        fun parseCube(reader: Reader, fallbackTitle: String = ""): Lut3D {
            var size = 0
            var title = fallbackTitle
            val min = floatArrayOf(0f, 0f, 0f)
            val max = floatArrayOf(1f, 1f, 1f)
            var data: FloatArray? = null
            var n = 0
            reader.buffered().forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val head = line.substringBefore(' ').uppercase(Locale.US)
                when {
                    head == "TITLE" -> title = line.substringAfter(' ').trim().trim('"').ifBlank { title }
                    head == "LUT_3D_SIZE" -> {
                        size = line.substringAfter(' ').trim().toInt()
                        require(size in 2..256) { "지원하지 않는 LUT 크기: $size" }
                        data = FloatArray(size * size * size * 3)
                    }
                    head == "LUT_1D_SIZE" -> throw IllegalArgumentException("1D LUT 는 지원하지 않습니다")
                    head == "DOMAIN_MIN" -> parse3(line, min)
                    head == "DOMAIN_MAX" -> parse3(line, max)
                    head.first().isLetter() -> Unit   // LUT_3D_INPUT_RANGE 같은 기타 키워드
                    else -> {
                        val d = data ?: throw IllegalArgumentException("LUT_3D_SIZE 가 먼저 나와야 합니다")
                        require(n + 3 <= d.size) { "데이터 줄이 너무 많습니다" }
                        val parts = line.split(Regex("\\s+"))
                        for (c in 0..2) d[n + c] = (parts[c].toFloat() - min[c]) / (max[c] - min[c])
                        n += 3
                    }
                }
            }
            val d = data ?: throw IllegalArgumentException("LUT_3D_SIZE 가 없습니다")
            require(n == d.size) { "데이터 줄 수가 모자랍니다 (${n / 3} / ${d.size / 3})" }
            return Lut3D(size, d, title)
        }

        private fun parse3(line: String, out: FloatArray) {
            val p = line.split(Regex("\\s+"))
            for (c in 0..2) out[c] = p[c + 1].toFloat()
        }
    }
}

internal fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v
internal fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
internal fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
internal fun sCurve(x: Float, k: Float): Float = mix(x, x * x * (3f - 2f * x), k)
internal fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3f - 2f * t)
}
