package kr.co.jnkcorp.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import kotlin.math.abs

class LutTest {

    private val o = FloatArray(3)

    @Test fun identityLutReturnsSameColor() {
        val lut = Lut3D.identity(17)
        for (c in listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.123f, 0.5f, 0.987f), floatArrayOf(1f, 1f, 1f))) {
            lut.sample(c[0], c[1], c[2], o)
            for (i in 0..2) assertEquals(c[i], o[i], 1e-5f)
        }
    }

    @Test fun cubeRoundTrip() {
        val lut = Look.TEAL_ORANGE.bake().withTitle("테스트 \"룩\"")
        val back = Lut3D.parseCube(StringReader(lut.toCube()))
        assertEquals(lut.size, back.size)
        assertEquals("테스트 '룩'", back.title)
        for (i in lut.data.indices) assertEquals(lut.data[i], back.data[i], 1e-5f)
    }

    @Test fun parsesDomainAndComments() {
        val cube = """
            # comment
            TITLE "tiny"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0 0 0
            DOMAIN_MAX 2 2 2
            0 0 0
            2 0 0
            0 2 0
            2 2 0
            0 0 2
            2 0 2
            0 2 2
            2 2 2
        """.trimIndent()
        val lut = Lut3D.parseCube(StringReader(cube))
        lut.sample(0.25f, 0.5f, 0.75f, o)
        assertEquals(0.25f, o[0], 1e-5f); assertEquals(0.5f, o[1], 1e-5f); assertEquals(0.75f, o[2], 1e-5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedCube() {
        Lut3D.parseCube(StringReader("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n"))
    }

    @Test fun makerWithDefaultsIsIdentity() {
        val lut = LutMaker.build(null, null, MakerParams(), size = 9)
        val id = Lut3D.identity(9)
        for (i in lut.data.indices) assertEquals(id.data[i], lut.data[i], 1e-4f)
    }

    @Test fun makerAdjustmentsGoTheRightWay() {
        LutMaker.build(null, null, MakerParams(temperature = 1f)).sample(0.5f, 0.5f, 0.5f, o)
        assertTrue("따뜻하게 하면 빨강 > 파랑", o[0] > o[2])
        LutMaker.build(null, null, MakerParams(saturation = -1f)).sample(0.9f, 0.2f, 0.1f, o)
        assertTrue("채도 -100 이면 흑백", abs(o[0] - o[1]) < 1e-3 && abs(o[1] - o[2]) < 1e-3)
        LutMaker.build(null, null, MakerParams(fade = 1f)).sample(0f, 0f, 0f, o)
        assertTrue("페이드는 블랙을 들어올림", o[0] > 0.1f)
        LutMaker.build(null, null, MakerParams(exposure = 1f)).sample(0.4f, 0.4f, 0.4f, o)
        assertTrue("노출 +1 은 밝게", o[0] > 0.5f)
    }

    @Test fun highlightsShadowsKeepEndpoints() {
        val lut = LutMaker.build(null, null, MakerParams(highlights = -1f, shadows = 1f))
        lut.sample(0f, 0f, 0f, o); assertEquals(0f, o[0], 1e-3f)
        lut.sample(1f, 1f, 1f, o); assertEquals(1f, o[0], 1e-3f)
        lut.sample(0.25f, 0.25f, 0.25f, o); assertTrue("그림자 + 는 어두운 곳을 밝게", o[0] > 0.3f)
        lut.sample(0.8f, 0.8f, 0.8f, o); assertTrue("하이라이트 - 는 밝은 곳을 누름", o[0] < 0.75f)
    }

    @Test fun baseIntensityBlends() {
        val base = Look.TEAL_ORANGE.bake()
        val zero = LutMaker.build(base, null, MakerParams(), size = 9, baseIntensity = 0f)
        val id = Lut3D.identity(9)
        for (i in zero.data.indices) assertEquals(id.data[i], zero.data[i], 1e-4f)
        // 흑백 LUT 는 강도를 낮춰도 무채색 유지
        LutMaker.build(Look.LEICA_MONO.bake(), null, MakerParams(), baseIntensity = 0.5f).sample(0.9f, 0.2f, 0.1f, o)
        assertTrue(abs(o[0] - o[1]) < 1e-3 && abs(o[1] - o[2]) < 1e-3)
    }

    /** 앱에 넣은 필름 LUT 가 모두 읽히는지 (Presets 목록과 assets 파일이 맞는지) */
    @Test fun allBundledLutsParse() {
        val root = assetsDir()
        for (p in Presets.all) {
            val f = File(root, "luts/${p.path}.cube")
            assertTrue("없음: ${f.path}", f.exists())
            val lut = f.reader().use { Lut3D.parseCube(it) }
            assertTrue(lut.size >= 2)
        }
    }

    private fun assetsDir(): File =
        listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }

    @Test fun cropBoxMovesAlongFreeAxis() {
        // 세로 사진 3000x4000 을 16:9(세로) 로 자르면 높이가 남지 않고 폭이 남는다… 가로↔세로 바꾸면 높이가 남음
        val top = cropBox(4000, 3000, Frame.WIDE, false, 0.5f, 0f)
        assertEquals(0, top[1]); assertEquals(4000, top[2]); assertEquals(2250, top[3])
        val bottom = cropBox(4000, 3000, Frame.WIDE, false, 0.5f, 1f)
        assertEquals(3000 - 2250, bottom[1])
        val sq = cropBox(4000, 3000, Frame.SQUARE, false, 0f, 0.5f)
        assertEquals(0, sq[0]); assertEquals(3000, sq[2])
        val orig = cropBox(4000, 3000, Frame.ORIGINAL, false, 0f, 0f)
        assertEquals(4000, orig[2]); assertEquals(3000, orig[3])
    }

    @Test fun maskAddThenErase() {
        val m = Mask(100, 100)
        m.dab(0.5f, 0.5f, 0.1f, 0f, 1f, erase = false)
        assertTrue("추가 브러시는 칠함", m.sample(0.5f, 0.5f) > 0.99f)
        assertTrue("브러시 밖은 그대로", m.sample(0.1f, 0.1f) < 0.01f)
        m.dab(0.5f, 0.5f, 0.05f, 0f, 1f, erase = true)
        assertTrue("빼기 브러시는 지움", m.sample(0.5f, 0.5f) < 0.01f)
        assertTrue("빼기 범위 밖은 남음", m.sample(0.58f, 0.5f) > 0.9f)
        m.stroke(0.1f, 0.9f, 0.9f, 0.9f, 0.03f, 0.5f, 1f, erase = false)
        assertTrue("선으로 칠하면 중간도 끊기지 않음", m.sample(0.5f, 0.9f) > 0.5f)
    }

    @Test fun layerAppliesOnlyWhereMasked() {
        val w = 40; val h = 20
        val px = IntArray(w * h) { 0xFF808080.toInt() }
        val mask = Mask(w, h)
        for (y in 0 until h) for (x in 0 until w / 2) mask.data[y * w + x] = 1f   // 왼쪽 절반만
        val bright = LutMaker.build(null, null, MakerParams(exposure = 1f), size = 17)
        Pipeline.process(px, w, h, Grade(null, 1f, 0f, 0f, listOf(LayerRender(bright, mask))))
        assertTrue("칠한 왼쪽은 밝아짐", (px[5] and 0xFF) > 0xA0)
        assertEquals("안 칠한 오른쪽은 그대로", 0x80, px[w - 5] and 0xFF)
    }

    @Test fun layerMaskFollowsCropRegion() {
        // 전체 40x20 중 오른쪽 절반(20..39)만 잘라서 처리 → 마스크 왼쪽 절반은 안 걸려야 함
        val mask = Mask(40, 20)
        for (y in 0 until 20) for (x in 0 until 20) mask.data[y * 40 + x] = 1f
        val px = IntArray(20 * 20) { 0xFF808080.toInt() }
        val bright = LutMaker.build(null, null, MakerParams(exposure = 1f), size = 17)
        Pipeline.process(px, 20, 20, Grade(null, 1f, 0f, 0f, listOf(LayerRender(bright, mask))), region = Region(20, 0, 40, 20))
        assertEquals(0x80, px[10 * 20 + 10] and 0xFF)
    }

    private fun skinImage(w: Int, h: Int): IntArray {
        val rnd = java.util.Random(1)
        return IntArray(w * h) {
            val n = rnd.nextInt(7) - 3
            (0xFF shl 24) or ((215 + n) shl 16) or ((170 + n) shl 8) or (145 + n)
        }
    }

    private fun darkDot(px: IntArray, w: Int, cx: Int, cy: Int, r: Int) {
        for (y in cy - r..cy + r) for (x in cx - r..cx + r)
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = 0xFF7A4A3A.toInt()
    }

    @Test fun detectsAndHealsBlemish() {
        val w = 800; val h = 600
        val px = skinImage(w, h)
        darkDot(px, w, 250, 300, 2)
        darkDot(px, w, 550, 150, 4)
        val spots = AutoFix.detectBlemishes(px, w, h, 0.5f)
        assertEquals("점 두 개를 찾아야 함", 2, spots.size)
        AutoFix.heal(px, w, h, spots)
        for ((x, y) in listOf(250 to 300, 550 to 150)) {
            val c = px[y * w + x]
            assertTrue("지운 자리는 피부색이어야 함: ${Integer.toHexString(c)}", ((c shr 16) and 0xFF) > 200)
        }
    }

    @Test fun manualHealRemovesDot() {
        val w = 200; val h = 200
        val px = skinImage(w, h)
        darkDot(px, w, 100, 100, 5)
        AutoFix.heal(px, w, h, listOf(Spot(0.5f, 0.5f, 8f / 200)))
        val c = px[100 * w + 100]
        assertTrue(((c shr 16) and 0xFF) > 200)
    }

    @Test fun noBlemishesOnProductPhotoColors() {
        // 피부색이 아닌 곳(파란 옷 위의 어두운 점)은 건드리지 않음
        val w = 200; val h = 200
        val px = IntArray(w * h) { 0xFF2040A0.toInt() }
        for (y in 98..102) for (x in 98..102) px[y * w + x] = 0xFF101830.toInt()
        assertEquals(0, AutoFix.detectBlemishes(px, w, h, 1f).size)
    }

    @Test fun autoWhiteBalanceCorrectsBlueCast() {
        val px = IntArray(1000) { 0xFF7080A0.toInt() }   // 파랗게 뜬 회색
        val (t, n) = AutoFix.whiteBalance(px)
        assertTrue("파란 기운이면 따뜻하게", t > 0.3f)
        val lut = LutMaker.build(null, null, MakerParams(temperature = t, tint = n))
        lut.sample(0x70 / 255f, 0x80 / 255f, 0xA0 / 255f, o)
        assertTrue("맞춘 뒤엔 거의 회색: ${o.toList()}", abs(o[0] - o[2]) < 0.03f && abs(o[1] - (o[0] + o[2]) / 2) < 0.03f)
    }

    @Test fun autoExposureBrightensDarkAndDarkensBright() {
        val dark = IntArray(1000) { 0xFF303030.toInt() }
        val bright = IntArray(1000) { 0xFFE0E0E0.toInt() }
        assertTrue(autoExposure(dark).first > 0.5f)
        assertTrue(autoExposure(bright).first < -0.3f)
        val mid = IntArray(1000) { 0xFF767676.toInt() }   // 18% 회색 근처
        assertTrue(abs(autoExposure(mid).first) < 0.2f)
    }

    @Test fun allLooksAreSane() {
        for (l in Look.values()) {
            val lut = l.bake()
            assertTrue(l.name, lut.data.all { !it.isNaN() && it in 0f..1f })
            lut.sample(0f, 0f, 0f, o); val black = luma(o[0], o[1], o[2])
            lut.sample(1f, 1f, 1f, o); val white = luma(o[0], o[1], o[2])
            lut.sample(0.5f, 0.5f, 0.5f, o); val grey = luma(o[0], o[1], o[2])
            assertTrue("${l.name} 밝기 순서", black < grey && grey < white)
        }
    }

    @Test fun monoDetection() {
        assertTrue(Look.LEICA_MONO.bake().isMono)
        assertFalse(Look.LEICA_CLASSIC.bake().isMono)
    }

    @Test fun labRoundTrip() {
        val lab = FloatArray(3)
        ColorTransfer.rgbToLab(0.8f, 0.4f, 0.2f, lab)
        ColorTransfer.labToRgb(lab[0], lab[1], lab[2], o)
        assertEquals(0.8f, o[0], 2e-3f); assertEquals(0.4f, o[1], 2e-3f); assertEquals(0.2f, o[2], 2e-3f)
    }

    /**
     * 실제 앱 코드(Pipeline·LutMaker)로 샘플 사진을 처리해 비교표를 만듭니다.
     * 환경변수 PREVIEW_OUT(출력 폴더)·PREVIEW_SRC(사진, P6 PPM)를 줄 때만 돕니다.
     * 유닛 테스트는 안드로이드 클래스패스라 ImageIO 가 없어서 PPM 으로 주고받습니다.
     */
    @Test fun renderPreviewSheet() {
        val outDir = System.getenv("PREVIEW_OUT") ?: return
        val (w, h, px) = readPpm(File(System.getenv("PREVIEW_SRC") ?: return))
        val ref = System.getenv("PREVIEW_REF")?.let { readPpm(File(it)) }

        val tiles = ArrayList<IntArray>()
        tiles += px.copyOf()
        for (l in Look.values().filter { it.category == Presets.LEICA }) {
            val c = px.copyOf()
            Pipeline.process(c, w, h, Grade(l.bake(), 1f, l.grain, l.vignette))
            tiles += c
        }
        val custom = MakerParams(contrast = 0.3f, saturation = -0.15f, temperature = 0.2f, fade = 0.4f,
            shadowHue = 190f, shadowAmount = 0.6f, highlightHue = 35f, highlightAmount = 0.5f)
        val c1 = px.copyOf()
        Pipeline.process(c1, w, h, Grade(LutMaker.build(null, null, custom), 1f, 0f, 0f))
        tiles += c1
        if (ref != null) {
            val t = ColorTransfer.from(px, ref.third)
            val c2 = px.copyOf()
            Pipeline.process(c2, w, h, Grade(LutMaker.build(null, t, MakerParams()), 1f, 0f, 0f))
            tiles += c2
        }
        tiles.forEachIndexed { i, p -> writePpm(File(outDir, "tile$i.ppm"), w, h, p) }
        // 앱에 넣은 필름 LUT 전부
        val names = StringBuilder()
        Presets.all.forEachIndexed { i, p ->
            val lut = File(assetsDir(), "luts/${p.path}.cube").reader().use { Lut3D.parseCube(it) }
            val c = px.copyOf()
            Pipeline.process(c, w, h, Grade(lut, 1f, 0f, 0f))
            writePpm(File(outDir, "film$i.ppm"), w, h, c)
            names.append(p.category).append(" · ").append(p.name).append('\n')
        }
        File(outDir, "film-names.txt").writeText(names.toString())
        File(outDir, "TealOrange.cube").writeText(Look.TEAL_ORANGE.bake().withTitle("Teal & Orange").toCube())
    }

    private fun readPpm(f: File): Triple<Int, Int, IntArray> {
        val bytes = f.readBytes()
        var pos = 0
        fun token(): String {
            while (bytes[pos].toInt().toChar().isWhitespace()) pos++
            val st = pos
            while (!bytes[pos].toInt().toChar().isWhitespace()) pos++
            return String(bytes, st, pos - st)
        }
        require(token() == "P6")
        val w = token().toInt(); val h = token().toInt(); token()
        pos++
        val px = IntArray(w * h) { i ->
            val o = pos + i * 3
            (0xFF shl 24) or ((bytes[o].toInt() and 0xFF) shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
        }
        return Triple(w, h, px)
    }

    private fun writePpm(f: File, w: Int, h: Int, px: IntArray) {
        val head = "P6\n$w $h\n255\n".toByteArray()
        val body = ByteArray(w * h * 3)
        for (i in px.indices) {
            body[i * 3] = (px[i] shr 16).toByte(); body[i * 3 + 1] = (px[i] shr 8).toByte(); body[i * 3 + 2] = px[i].toByte()
        }
        f.writeBytes(head + body)
    }
}
