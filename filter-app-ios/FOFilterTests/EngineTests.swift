import XCTest
@testable import FOFilter

/// 렌더링·카메라와 상관없는 엔진 테스트 (LUT · 보정 · 자르기 · 렌즈 · 설정)
final class EngineTests: XCTestCase {

    /// 같이 넣은 LUT 폴더. 앱 번들 안, 없으면 환경변수 FOFILTER_LUTS (리눅스에서 돌릴 때)
    private var lutsDir: URL? {
        if let p = ProcessInfo.processInfo.environment["FOFILTER_LUTS"] { return URL(fileURLWithPath: p) }
        return Bundle.main.resourceURL?.appendingPathComponent("luts")
    }

    private func tmpDir() -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    func testIdentityReturnsSameColor() {
        let id = Lut3D.identity(size: 17)
        for c in [SIMD3<Float>(0.1, 0.5, 0.9), SIMD3(0.33, 0.66, 0.2), SIMD3(1, 0, 0.5)] {
            let o = id.sample(c.x, c.y, c.z)
            for i in 0..<3 { XCTAssertEqual(o[i], c[i], accuracy: 1e-5) }
        }
    }

    func testCubeRoundTrip() throws {
        let lut = Look.tealOrange.bake().withTitle("테스트 \"룩\"")
        let back = try Lut3D.parseCube(lut.toCube())
        XCTAssertEqual(back.size, lut.size)
        XCTAssertEqual(back.title, "테스트 '룩'")
        for i in stride(from: 0, to: lut.data.count, by: 97) { XCTAssertEqual(lut.data[i], back.data[i], accuracy: 1e-5) }
    }

    func testParsesDomainAndComments() throws {
        let text = """
        # comment
        TITLE "Half"
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
        """
        let lut = try Lut3D.parseCube(text)
        XCTAssertEqual(lut.title, "Half")
        let o = lut.sample(0.25, 0.5, 0.75)
        XCTAssertEqual(o.x, 0.25, accuracy: 1e-5); XCTAssertEqual(o.y, 0.5, accuracy: 1e-5); XCTAssertEqual(o.z, 0.75, accuracy: 1e-5)
        XCTAssertThrowsError(try Lut3D.parseCube("LUT_3D_SIZE 2\n0 0 0\n"))
    }

    func testCubeDataIsRGBAInCubeOrder() {
        let lut = Lut3D.identity(size: 2)
        let d = lut.cubeData()
        XCTAssertEqual(d.count, 2 * 2 * 2 * 4 * 4)
        let f = d.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        // 두 번째 칸 = 빨강 1, 초록 0, 파랑 0
        XCTAssertEqual(Array(f[4..<8]), [1, 0, 0, 1])
    }

    func testMakerDefaultsAreIdentity() {
        let lut = LutMaker.build(base: nil, params: MakerParams(), size: 9)
        let id = Lut3D.identity(size: 9)
        for i in lut.data.indices { XCTAssertEqual(lut.data[i], id.data[i], accuracy: 1e-4) }
    }

    func testMakerAdjustmentsGoTheRightWay() {
        func lumaOf(_ p: MakerParams, _ v: Float = 0.5) -> SIMD3<Float> { LutMaker.build(base: nil, params: p, size: 17).sample(v, v, v) }
        XCTAssertGreaterThan(lumaOf(MakerParams(exposure: 1)).x, 0.55)
        XCTAssertLessThan(lumaOf(MakerParams(exposure: -1)).x, 0.45)
        let warm = lumaOf(MakerParams(temperature: 0.5))
        XCTAssertGreaterThan(warm.x, warm.z)
        XCTAssertLessThan(lumaOf(MakerParams(contrast: 0.8), 0.25).x, 0.25)
        XCTAssertGreaterThan(lumaOf(MakerParams(fade: 1), 0).x, 0.1)
        let grey = LutMaker.build(base: nil, params: MakerParams(saturation: -1), size: 17).sample(0.9, 0.2, 0.1)
        XCTAssertEqual(grey.x, grey.y, accuracy: 1e-3)
    }

    func testBaseIntensityBlendsAndMonoStaysMono() {
        let zero = LutMaker.build(base: Look.tealOrange.bake(), params: MakerParams(), size: 9, baseIntensity: 0)
        let id = Lut3D.identity(size: 9)
        for i in zero.data.indices { XCTAssertEqual(zero.data[i], id.data[i], accuracy: 1e-4) }
        let mono = Lut3D.bake { r, g, b in let m = 0.3 * r + 0.6 * g + 0.1 * b; return SIMD3(m, m, m) }
        XCTAssertTrue(mono.isMono)
        XCTAssertFalse(Look.tealOrange.bake().isMono)
        let o = LutMaker.build(base: mono, params: MakerParams(), baseIntensity: 0.5).sample(0.9, 0.2, 0.1)
        XCTAssertEqual(o.x, o.y, accuracy: 1e-3); XCTAssertEqual(o.y, o.z, accuracy: 1e-3)
    }

    private func assertSane(_ name: String, _ lut: Lut3D) {
        XCTAssertTrue(lut.data.allSatisfy { !$0.isNaN && $0 >= 0 && $0 <= 1 }, name)
        XCTAssertFalse(lut.isMono, name)
        var prev: Float = -1
        for i in 0...20 {
            let v = Float(i) / 20
            let o = lut.sample(v, v, v)
            let l = luma(o.x, o.y, o.z)
            XCTAssertGreaterThanOrEqual(l, prev - 1e-3, "\(name) 회색 밝기 순서 \(v)")
            prev = l
        }
        let skin = lut.sample(0.9, 0.68, 0.55)
        XCTAssertTrue(skin.x >= skin.y && skin.y >= skin.z, "\(name) 피부색 \(skin)")
    }

    func testLooksAreSane() {
        for l in Look.allCases where l != .original { assertSane(l.rawValue, l.bake()) }
    }

    func testBundledLeicaLutsLoad() throws {
        let dir = try XCTUnwrap(lutsDir)
        let lib = LutLibrary(dir: tmpDir(), resources: dir)
        let b = lib.bundled()
        XCTAssertEqual(b.map(\.key), Presets.all.map(\.key))
        for e in b {
            XCTAssertEqual(e.lut?.size, 33)
            XCTAssertEqual(e.category, .leica)
            XCTAssertNotNil(e.vignette)
            assertSane(e.name, try XCTUnwrap(e.lut))
        }
        XCTAssertEqual(lib.all().count, Look.allCases.count + Presets.all.count)
    }

    func testUserLutSaveImportDelete() throws {
        let lib = LutLibrary(dir: tmpDir(), resources: nil)
        let e = try lib.save(name: "내 룩/1", lut: Look.cinemaYellow.bake())
        XCTAssertEqual(lib.userLuts().map(\.name), ["내 룩/1"])
        XCTAssertTrue(e.key.hasPrefix("file:"))
        let e2 = try lib.save(name: "내 룩/1", lut: Look.tealOrange.bake())
        XCTAssertNotEqual(e.key, e2.key)
        let imported = try lib.importCube(from: try lib.exportFile(e2))
        XCTAssertEqual(lib.userLuts().count, 3)
        lib.delete(imported); lib.delete(e); lib.delete(e2)
        XCTAssertTrue(lib.userLuts().isEmpty)
    }

    func testCropBox() {
        // 세로 사진(3000x4000)에 16:9 (가로 프레임을 세로로 돌린 것 = 9:16)
        let b = cropBox(w: 3000, h: 4000, frame: .wide, flip: false, cx: 0.5, cy: 0.5)
        XCTAssertEqual(b.w, 2250); XCTAssertEqual(b.h, 4000); XCTAssertEqual(b.x, 375)
        // 같은 사진에 가로 16:9 (flip) → 위쪽 끝
        let t = cropBox(w: 3000, h: 4000, frame: .wide, flip: true, cx: 0.5, cy: 0)
        XCTAssertEqual(t.w, 3000); XCTAssertEqual(t.h, 1688); XCTAssertEqual(t.y, 0)
        let o = cropBox(w: 100, h: 50, frame: .original, flip: false, cx: 0.5, cy: 0.5)
        XCTAssertEqual(o.w, 100); XCTAssertEqual(o.h, 50)
        let sq = cropBox(w: 400, h: 300, frame: .square, flip: false, cx: 1, cy: 0.5)
        XCTAssertEqual(sq.x, 100); XCTAssertEqual(sq.w, 300)
    }

    func testLensPresets() {
        func labels(_ s: [Float], _ uw: Bool) -> [String] { Lenses.presets(switchOvers: s, hasUltraWide: uw).map { Lenses.label($0.display) } }
        XCTAssertEqual(labels([2, 6], true), [".5", "1", "3"])        // 트리플 (3배 망원)
        XCTAssertEqual(labels([2, 10], true), [".5", "1", "5"])       // 트리플 (5배 망원)
        XCTAssertEqual(labels([2], true), [".5", "1"])                // 듀얼 광각
        XCTAssertEqual(labels([2], false), ["1", "2"])                // 광각 + 2배 망원
        XCTAssertEqual(labels([], false), ["1"])                      // 렌즈 하나
        XCTAssertEqual(Lenses.presets(switchOvers: [2, 6], hasUltraWide: true)[1].factor, 2)
    }

    func testSettingsStoreRecent() throws {
        let suite = "fofilter-test-\(UUID().uuidString)"
        let store = SettingsStore(defaults: try XCTUnwrap(UserDefaults(suiteName: suite)))
        XCTAssertNil(store.current)
        var s = Settings()
        store.current = s
        XCTAssertEqual(store.current, s)
        store.pushRecent(s)
        s.cropX = 0.1
        store.pushRecent(s)                       // 위치만 다르면 같은 설정
        XCTAssertEqual(store.recent.count, 1)
        for i in 0..<5 { var t = s; t.lutName = "룩 \(i)"; store.pushRecent(t) }
        XCTAssertEqual(store.recent.map(\.lutName), ["룩 4", "룩 3", "룩 2"])
        var wm = store.watermark
        wm.text = "안녕"; store.watermark = wm
        XCTAssertEqual(store.watermark.text, "안녕")
    }

    func testAutoWhiteBalanceCorrectsBlueCast() {
        var px = [UInt8]()
        for i in 0..<400 { let v = UInt8(60 + i % 120); px += [v, v &+ 8, v &+ 30, 255] }
        let (t, _) = AutoFix.whiteBalance(rgba: px)
        XCTAssertGreaterThan(t, 0.05)
    }

    func testAutoExposure() {
        let dark = [UInt8](repeating: 25, count: 400 * 4)
        let bright = [UInt8](repeating: 235, count: 400 * 4)
        XCTAssertGreaterThan(AutoFix.exposure(rgba: dark).ev, 0.5)
        XCTAssertLessThan(AutoFix.exposure(rgba: bright).ev, 0)
    }
}
