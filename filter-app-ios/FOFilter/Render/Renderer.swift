import CoreImage
import CoreText
import Metal
import UIKit

/// 렌더링에 쓸 색(LUT·보정을 한 LUT 로 구운 것) · 그레인 · 비네팅. 스레드 사이에 넘겨도 되는 값.
struct PreparedGrade {
    var cubeData: Data?
    var cubeSize = Lut3D.defaultSize
    var grain: Float = 0
    var vignette: Float = 0

    static let none = PreparedGrade()

    init() {}
    init(lut: Lut3D?, grain: Float, vignette: Float) {
        cubeData = lut?.cubeData()
        cubeSize = lut?.size ?? Lut3D.defaultSize
        self.grain = grain
        self.vignette = vignette
    }

    /// 설정 한 벌 → 렌더링 값. 기준 LUT · 강도 · 보정을 LUT 하나로 굽습니다 (조금 걸리니 백그라운드에서).
    static func make(settings s: Settings, base: Lut3D?) -> PreparedGrade {
        let lut: Lut3D? = (base == nil && s.adjust.isNeutral) ? nil
            : LutMaker.build(base: base, params: s.adjust, baseIntensity: s.intensity)
        return PreparedGrade(lut: lut, grain: s.grain, vignette: s.vignette)
    }
}

/// Core Image 파이프라인: 프레임 자르기 → LUT → 비네팅 · 그레인 → 워터마크.
/// 안드로이드 Pipeline 과 같은 식을 씁니다 (비네팅·그레인은 감마 공간에서).
final class Renderer {
    static let shared = Renderer()

    let device: MTLDevice?
    let queue: MTLCommandQueue?
    let context: CIContext
    let srgb = CGColorSpace(name: CGColorSpace.sRGB)!

    private init() {
        device = MTLCreateSystemDefaultDevice()
        queue = device?.makeCommandQueue()
        let opts: [CIContextOption: Any] = [.cacheIntermediates: false, .name: "FOFilter"]
        if let device { context = CIContext(mtlDevice: device, options: opts) } else { context = CIContext(options: opts) }
    }

    // MARK: 자르기

    /// 프레임대로 잘라 원점(0,0)으로 옮깁니다. cx·cy 는 왼쪽 위 기준 (안드로이드 cropBox 와 같음).
    func crop(_ image: CIImage, _ s: Settings) -> CIImage {
        let e = image.extent.integral
        let b = cropBox(w: Int(e.width), h: Int(e.height), frame: s.frame, flip: s.frameFlip, cx: s.cropX, cy: s.cropY)
        // 위에서 b.y 만큼 → Core Image 는 아래가 원점
        let rect = CGRect(x: e.minX + CGFloat(b.x), y: e.minY + e.height - CGFloat(b.y + b.h), width: CGFloat(b.w), height: CGFloat(b.h))
        return image.cropped(to: rect).transformed(by: CGAffineTransform(translationX: -rect.minX, y: -rect.minY))
    }

    // MARK: 색 · 비네팅 · 그레인

    /// `grainScale` 은 미리보기 대비 해상도 배율 (클수록 한 픽셀 노이즈가 덜 보이니 진폭을 조금 올림), `seed` 는 영상에서 프레임마다 다른 그레인.
    func apply(_ image: CIImage, _ g: PreparedGrade, grainScale: CGFloat = 1, seed: Int = 0) -> CIImage {
        let extent = image.extent
        var img = image
        if let data = g.cubeData, let f = CIFilter(name: "CIColorCubeWithColorSpace") {
            f.setValue(img, forKey: kCIInputImageKey)
            f.setValue(g.cubeSize, forKey: "inputCubeDimension")
            f.setValue(data, forKey: "inputCubeData")
            f.setValue(srgb, forKey: "inputColorSpace")
            img = f.outputImage ?? img
        }
        guard g.vignette > 0 || g.grain > 0 else { return img }
        img = img.applyingFilter("CILinearToSRGBToneCurve")
        if g.vignette > 0, let mask = vignetteMask(extent: extent, amount: g.vignette) {
            img = mask.applyingFilter("CIMultiplyCompositing", parameters: [kCIInputBackgroundImageKey: img])
        }
        if g.grain > 0 {
            let amp = CGFloat(g.grain) * 0.1 * sqrt(max(1, grainScale))
            img = grainImage(extent: extent, amplitude: amp, seed: seed)
                .applyingFilter("CIOverlayBlendMode", parameters: [kCIInputBackgroundImageKey: img])
        }
        return img.applyingFilter("CISRGBToneCurveToLinear").cropped(to: extent)
    }

    private let maskLock = NSLock()
    private var maskCache: [Int: CGImage] = [:]

    /// 1 − 양·smooth(0.25, 1.6, x²+y²) 를 256×256 회색 그림으로 만들어 사진 크기로 늘립니다 (타원 모양 그대로).
    private func vignetteMask(extent: CGRect, amount: Float) -> CIImage? {
        let key = Int((amount * 1000).rounded())
        maskLock.lock()
        var cg = maskCache[key]
        maskLock.unlock()
        if cg == nil {
            let n = 256
            let vAmt = amount * 0.6
            var px = [UInt8](repeating: 0, count: n * n)
            for y in 0..<n {
                let ny = (Float(y) + 0.5) / Float(n) * 2 - 1
                for x in 0..<n {
                    let nx = (Float(x) + 0.5) / Float(n) * 2 - 1
                    let v = 1 - vAmt * smoothstep(0.25, 1.6, nx * nx + ny * ny)
                    px[y * n + x] = UInt8(max(0, min(255, (v * 255).rounded())))
                }
            }
            let provider = CGDataProvider(data: Data(px) as CFData)
            cg = provider.flatMap {
                CGImage(width: n, height: n, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: n,
                        space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: 0),
                        provider: $0, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
            }
            maskLock.lock()
            if maskCache.count > 16 { maskCache.removeAll() }
            maskCache[key] = cg
            maskLock.unlock()
        }
        guard let cg else { return nil }
        // 색 관리를 끄고 값 그대로 (감마 공간 값에 곱함)
        let m = CIImage(cgImage: cg, options: [.colorSpace: NSNull()]).clampedToExtent()
        let t = CGAffineTransform(scaleX: extent.width / 256, y: extent.height / 256)
            .concatenating(CGAffineTransform(translationX: extent.minX, y: extent.minY))
        return m.transformed(by: t).cropped(to: extent)
    }

    /// 0.5 를 중심으로 ±진폭/2 인 흑백 노이즈 (오버레이 합성이라 중간톤에서 안드로이드의 더하기와 같은 세기)
    private func grainImage(extent: CGRect, amplitude a: CGFloat, seed: Int) -> CIImage {
        let noise = CIFilter(name: "CIRandomGenerator")!.outputImage!
        let shift = CGAffineTransform(translationX: CGFloat((seed * 7919) % 4096), y: CGFloat((seed * 104_729) % 4096))
        return noise.transformed(by: shift)
            .applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: a, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: a, y: 0, z: 0, w: 0),
                "inputBVector": CIVector(x: a, y: 0, z: 0, w: 0),
                "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 0),
                "inputBiasVector": CIVector(x: 0.5 - a / 2, y: 0.5 - a / 2, z: 0.5 - a / 2, w: 1),
            ])
            .cropped(to: extent)
    }

    // MARK: 한 번에

    /// 원본 → 자르기 → 색 → 워터마크
    func process(_ image: CIImage, settings s: Settings, grade: PreparedGrade, watermark: Watermark?,
                 grainScale: CGFloat = 1, seed: Int = 0) -> CIImage {
        var out = apply(crop(image, s), grade, grainScale: grainScale, seed: seed)
        if let wm = watermark, wm.visible { out = WatermarkRenderer.composite(wm, over: out) }
        return out
    }

    // MARK: 출력

    func jpeg(_ image: CIImage, quality: CGFloat = 0.95) -> Data? {
        context.jpegRepresentation(of: image, colorSpace: srgb,
                                   options: [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): quality])
    }

    func uiImage(_ image: CIImage, maxSide: CGFloat? = nil) -> UIImage? {
        var img = image
        if let maxSide {
            let s = min(1, maxSide / max(image.extent.width, image.extent.height))
            if s < 1 { img = image.transformed(by: CGAffineTransform(scaleX: s, y: s)) }
        }
        guard let cg = context.createCGImage(img, from: img.extent.integral, format: .RGBA8, colorSpace: srgb) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// 자동 보정용 작은 RGBA 픽셀
    func rgbaPixels(_ image: CIImage, maxSide: CGFloat = 256) -> [UInt8] {
        let e = image.extent
        guard e.width > 0, e.height > 0, !e.isInfinite else { return [] }
        let s = min(1, maxSide / max(e.width, e.height))
        let img = image.transformed(by: CGAffineTransform(translationX: -e.minX, y: -e.minY))
            .transformed(by: CGAffineTransform(scaleX: s, y: s))
        let w = max(1, Int(e.width * s)), h = max(1, Int(e.height * s))
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        context.render(img, toBitmap: &buf, rowBytes: w * 4, bounds: CGRect(x: 0, y: 0, width: w, height: h), format: .RGBA8, colorSpace: srgb)
        return buf
    }

    // MARK: 미리보기 (Metal)

    /// 화면 크기 `size` 안에 비율 유지해 가운데 그립니다. 반환: 그려진 영역 (픽셀, 위쪽 원점).
    @discardableResult
    func renderFit(_ image: CIImage, size: CGSize, commandBuffer: MTLCommandBuffer, pixelFormat: MTLPixelFormat,
                   texture: @escaping () -> MTLTexture) -> CGRect {
        let e = image.extent
        guard e.width > 0, e.height > 0, size.width > 0, size.height > 0 else { return .zero }
        let s = min(size.width / e.width, size.height / e.height)
        let w = e.width * s, h = e.height * s
        let ox = ((size.width - w) / 2).rounded(), oy = ((size.height - h) / 2).rounded()
        let fitted = image
            .transformed(by: CGAffineTransform(translationX: -e.minX, y: -e.minY))
            .transformed(by: CGAffineTransform(scaleX: s, y: s))
            .transformed(by: CGAffineTransform(translationX: ox, y: oy))
        let bounds = CGRect(origin: .zero, size: size)
        let composed = fitted.composited(over: CIImage(color: .black).cropped(to: bounds))
        let dest = CIRenderDestination(width: Int(size.width), height: Int(size.height), pixelFormat: pixelFormat,
                                       commandBuffer: commandBuffer, mtlTextureProvider: texture)
        dest.colorSpace = srgb
        _ = try? context.startTask(toRender: composed, from: bounds, to: dest, at: .zero)
        return CGRect(x: ox, y: oy, width: w, height: h)
    }
}

// MARK: - 워터마크

enum WatermarkRenderer {
    private static let lock = NSLock()
    private static var names: [WmFont: String] = [:]
    private static var cache: (wm: Watermark, width: CGFloat, image: CIImage)?

    /// 같이 넣은 글꼴 파일을 이 앱 안에서만 등록하고 PostScript 이름으로 씀
    static func font(_ f: WmFont, size: CGFloat) -> UIFont {
        lock.lock(); defer { lock.unlock() }
        if names[f] == nil, let url = Bundle.main.resourceURL?.appendingPathComponent("fonts/\(f.file)") {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
            if let list = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor], let d = list.first,
               let name = CTFontDescriptorCopyAttribute(d, kCTFontNameAttribute) as? String {
                names[f] = name
            }
        }
        if let n = names[f], let font = UIFont(name: n, size: size) { return font }
        return UIFont.systemFont(ofSize: size, weight: .semibold)
    }

    /// 글자 덩어리 그림 (회전 전). 그림 가운데가 글자 가운데. `imageWidth` 는 사진(또는 화면에 보이는 사진) 폭.
    static func textImage(_ wm: Watermark, imageWidth: CGFloat, scale: CGFloat = 1) -> UIImage? {
        guard wm.visible, imageWidth > 0 else { return nil }
        let size = CGFloat(wm.size) * imageWidth
        let para = NSMutableParagraphStyle()
        para.alignment = .center
        let rgb = wm.color
        let color = UIColor(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                            blue: CGFloat(rgb & 0xFF) / 255, alpha: CGFloat(min(max(wm.opacity, 0), 1)))
        var attrs: [NSAttributedString.Key: Any] = [
            .font: font(wm.font, size: size), .foregroundColor: color,
            .kern: CGFloat(wm.spacing) * size, .paragraphStyle: para,
        ]
        if wm.shadow {
            let sh = NSShadow()
            sh.shadowBlurRadius = size * 0.08
            sh.shadowOffset = CGSize(width: 0, height: size * 0.03)
            sh.shadowColor = UIColor.black.withAlphaComponent(0.4)
            attrs[.shadow] = sh
        }
        let str = NSAttributedString(string: wm.text, attributes: attrs)
        let box = str.boundingRect(with: CGSize(width: CGFloat.greatestFiniteMagnitude, height: .greatestFiniteMagnitude),
                                   options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil).integral
        let pad = (size * 0.3).rounded(.up)
        let canvas = CGSize(width: box.width + pad * 2, height: box.height + pad * 2)
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = false
        return UIGraphicsImageRenderer(size: canvas, format: format).image { _ in
            str.draw(with: CGRect(x: pad, y: pad, width: box.width, height: box.height),
                     options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
        }
    }

    /// (0,0) 부터 시작하는 사진 위에 합성. 위치 u·v 는 왼쪽 위 기준, 회전은 시계 방향.
    static func composite(_ wm: Watermark, over image: CIImage) -> CIImage {
        let e = image.extent
        guard let text = cachedText(wm, width: e.width) else { return image }
        let tw = text.extent.width, th = text.extent.height
        let t = CGAffineTransform(translationX: -tw / 2, y: -th / 2)
            .concatenating(CGAffineTransform(rotationAngle: -CGFloat(wm.rotation) * .pi / 180))
            .concatenating(CGAffineTransform(translationX: e.minX + CGFloat(wm.u) * e.width, y: e.minY + (1 - CGFloat(wm.v)) * e.height))
        return text.transformed(by: t).composited(over: image).cropped(to: e)
    }

    private static func cachedText(_ wm: Watermark, width: CGFloat) -> CIImage? {
        lock.lock()
        if let c = cache, c.wm == wm, abs(c.width - width) < 0.5 { lock.unlock(); return c.image }
        lock.unlock()
        guard let ui = textImage(wm, imageWidth: width), let cg = ui.cgImage else { return nil }
        let img = CIImage(cgImage: cg)
        lock.lock(); cache = (wm, width, img); lock.unlock()
        return img
    }
}
