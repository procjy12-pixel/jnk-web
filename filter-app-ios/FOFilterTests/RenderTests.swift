#if canImport(UIKit)
import CoreImage
import Metal
import XCTest
@testable import FOFilter

/// Core Image 파이프라인 테스트 (시뮬레이터에서 GPU 로)
final class RenderTests: XCTestCase {
    private let r = Renderer.shared

    private func solid(_ c: CIColor, _ w: CGFloat, _ h: CGFloat) -> CIImage {
        CIImage(color: c).cropped(to: CGRect(x: 0, y: 0, width: w, height: h))
    }

    /// 화면 기준 위쪽 절반 빨강, 아래쪽 절반 파랑 (Core Image 는 y 가 위로 커짐)
    private func topRedBottomBlue(_ w: CGFloat = 40, _ h: CGFloat = 60) -> CIImage {
        let red = CIImage(color: CIColor(red: 1, green: 0, blue: 0)).cropped(to: CGRect(x: 0, y: h / 2, width: w, height: h / 2))
        let blue = CIImage(color: CIColor(red: 0, green: 0, blue: 1)).cropped(to: CGRect(x: 0, y: 0, width: w, height: h / 2))
        return red.composited(over: blue)
    }

    private func mean(_ px: [UInt8]) -> SIMD3<Double> {
        var s = SIMD3<Double>(0, 0, 0)
        let n = px.count / 4
        for i in 0..<n { s += SIMD3(Double(px[i * 4]), Double(px[i * 4 + 1]), Double(px[i * 4 + 2])) }
        return s / Double(max(n, 1))
    }

    /// LUT 데이터 순서와 색공간(sRGB)이 맞는지: GPU 결과 = Lut3D.sample
    func testCubeMatchesCpuSample() {
        let lut = Look.cinemaYellow.bake()
        let g = PreparedGrade(lut: lut, grain: 0, vignette: 0)
        for c in [SIMD3<Float>(0.5, 0.5, 0.5), SIMD3(0.8, 0.3, 0.2), SIMD3(0.2, 0.4, 0.9)] {
            let img = solid(CIColor(red: CGFloat(c.x), green: CGFloat(c.y), blue: CGFloat(c.z), alpha: 1, colorSpace: r.srgb)!, 8, 8)
            let got = mean(r.rgbaPixels(r.apply(img, g)))
            let want = lut.sample(c.x, c.y, c.z) * 255
            XCTAssertEqual(got.x, Double(want.x), accuracy: 3, "\(c)")
            XCTAssertEqual(got.y, Double(want.y), accuracy: 3, "\(c)")
            XCTAssertEqual(got.z, Double(want.z), accuracy: 3, "\(c)")
        }
    }

    /// 세로 사진에 가로 프레임, 위쪽 끝(cy=0) → 위쪽(빨강)만 남아야 함
    func testCropTakesTopWhenCyIsZero() {
        var s = Settings()
        s.frame = .wide; s.frameFlip = true; s.cropY = 0
        let out = r.crop(topRedBottomBlue(), s)
        XCTAssertEqual(out.extent.origin, .zero)
        XCTAssertEqual(out.extent.width, 40)
        XCTAssertEqual(out.extent.height, 23)
        let m = mean(r.rgbaPixels(out))
        XCTAssertGreaterThan(m.x, 240); XCTAssertLessThan(m.z, 15)
        s.cropY = 1
        let bottom = mean(r.rgbaPixels(r.crop(topRedBottomBlue(), s)))
        XCTAssertGreaterThan(bottom.z, 240)
    }

    func testVignetteDarkensCornersOnly() {
        let img = solid(CIColor(red: 0.6, green: 0.6, blue: 0.6), 200, 200)
        let out = r.apply(img, PreparedGrade(lut: nil, grain: 0, vignette: 1))
        let center = mean(r.rgbaPixels(out.cropped(to: CGRect(x: 95, y: 95, width: 10, height: 10))))
        let corner = mean(r.rgbaPixels(out.cropped(to: CGRect(x: 0, y: 0, width: 10, height: 10))))
        let before = mean(r.rgbaPixels(img))
        XCTAssertEqual(center.x, before.x, accuracy: 2)
        XCTAssertLessThan(corner.x, before.x * 0.7)
        XCTAssertEqual(out.extent, img.extent)
    }

    func testGrainKeepsMeanAddsNoise() {
        let img = solid(CIColor(red: 0.5, green: 0.5, blue: 0.5), 120, 120)
        let px = r.rgbaPixels(r.apply(img, PreparedGrade(lut: nil, grain: 1, vignette: 0)))
        let m = mean(px)
        let before = mean(r.rgbaPixels(img))
        var varSum = 0.0
        for i in 0..<(px.count / 4) { let d = Double(px[i * 4]) - m.x; varSum += d * d }
        let sd = sqrt(varSum / Double(px.count / 4))
        XCTAssertEqual(m.x, before.x, accuracy: 3, "평균 \(m) / 원래 \(before) / 표준편차 \(sd)")
        XCTAssertGreaterThan(sd, 2, "평균 \(m) / 원래 \(before)")
        XCTAssertLessThan(sd, 20)
    }

    func testWatermarkDrawsInside() {
        var wm = Watermark()
        wm.enabled = true; wm.text = "FOFilter"; wm.u = 0.5; wm.v = 0.5; wm.size = 0.15
        let img = solid(CIColor(red: 0, green: 0, blue: 0), 400, 300)
        let out = WatermarkRenderer.composite(wm, over: img)
        XCTAssertEqual(out.extent, img.extent)
        XCTAssertGreaterThan(mean(r.rgbaPixels(out, maxSide: 400)).x, 3)
        for f in WmFont.allCases { XCTAssertNotEqual(WatermarkRenderer.font(f, size: 20).familyName, UIFont.systemFont(ofSize: 20).familyName, f.file) }
    }

    func testProcessOutputSizeMatchesFrame() {
        var s = Settings()
        s.frame = .square
        let out = r.process(solid(CIColor(red: 0.3, green: 0.3, blue: 0.3), 300, 400), settings: s, grade: .none, watermark: nil)
        XCTAssertEqual(out.extent.size, CGSize(width: 300, height: 300))
    }

    /// 미리보기(Metal 텍스처)가 뒤집히지 않는지: 텍스처 첫 줄 = 사진 위쪽(빨강)
    func testPreviewIsNotFlipped() throws {
        let device = try XCTUnwrap(r.device)
        let queue = try XCTUnwrap(r.queue)
        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 40, height: 60, mipmapped: false)
        desc.usage = [.shaderRead, .shaderWrite, .renderTarget]
        desc.storageMode = .shared
        let tex = try XCTUnwrap(device.makeTexture(descriptor: desc))
        let cb = try XCTUnwrap(queue.makeCommandBuffer())
        let rect = r.renderFit(topRedBottomBlue(), size: CGSize(width: 40, height: 60), commandBuffer: cb, pixelFormat: .bgra8Unorm) { tex }
        cb.commit()
        cb.waitUntilCompleted()
        XCTAssertEqual(rect, CGRect(x: 0, y: 0, width: 40, height: 60))
        var row = [UInt8](repeating: 0, count: 40 * 4)
        tex.getBytes(&row, bytesPerRow: 40 * 4, from: MTLRegionMake2D(0, 2, 40, 1), mipmapLevel: 0)
        // BGRA: 위쪽 줄은 빨강
        XCTAssertGreaterThan(row[2], 200, "위쪽이 빨강이어야 함 (BGRA \(Array(row[0..<4])))")
        XCTAssertLessThan(row[0], 60)
    }
}
#endif
