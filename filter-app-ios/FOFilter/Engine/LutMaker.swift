import Foundation

/// 보정 슬라이더 값. 모두 0 이면 아무것도 바꾸지 않습니다.
struct MakerParams: Codable, Equatable {
    var exposure: Float = 0      // -2...2 EV
    var contrast: Float = 0      // -1...1
    var highlights: Float = 0    // -1...1  (- 하이라이트 눌러서 살리기)
    var shadows: Float = 0       // -1...1  (+ 그림자 들어올리기)
    var saturation: Float = 0    // -1...1  (-1 이면 흑백)
    var temperature: Float = 0   // -1...1  (+ 따뜻하게)
    var tint: Float = 0          // -1...1  (+ 마젠타)
    var fade: Float = 0          // 0...1   블랙 들어올림

    var isNeutral: Bool { self == MakerParams() }
}

/// 기준 LUT 위에 보정을 얹어 LUT 하나로 굽습니다 (안드로이드 LutMaker 와 같은 식).
enum LutMaker {
    /// 색온도·틴트 슬라이더 ±1 이 채널을 얼마나 곱하는지
    static let wbTemp: Float = 0.25
    static let wbTint: Float = 0.15

    /// `baseIntensity` 는 기준 LUT 를 얼마나 섞을지. 흑백 LUT 는 강도를 낮춰도 원본 흑백과 섞습니다.
    static func build(base: Lut3D?, params p: MakerParams, title: String = "",
                      size: Int = Lut3D.defaultSize, baseIntensity: Float = 1) -> Lut3D {
        let baseMono = base?.isMono == true
        let gain = powf(2, p.exposure)
        return Lut3D.bake(size: size, title: title) { r0, g0, b0 in
            var r = r0, g = g0, b = b0
            if let base {
                let t = base.sample(r, g, b)
                if baseMono { let m = luma(r, g, b); r = m; g = m; b = m }
                r = mix(r, t.x, baseIntensity); g = mix(g, t.y, baseIntensity); b = mix(b, t.z, baseIntensity)
            }
            // 노출 (선형 공간에서 곱하기)
            if p.exposure != 0 {
                r = toGamma(toLinear(r) * gain); g = toGamma(toLinear(g) * gain); b = toGamma(toLinear(b) * gain)
            }
            // 화이트밸런스
            r *= 1 + wbTemp * p.temperature
            b *= 1 - wbTemp * p.temperature
            g *= 1 - wbTint * p.tint
            r = clamp01(r); g = clamp01(g); b = clamp01(b)
            // 대비
            if p.contrast > 0 {
                r = sCurve(r, p.contrast); g = sCurve(g, p.contrast); b = sCurve(b, p.contrast)
            } else if p.contrast < 0 {
                let k = 1 + 0.6 * p.contrast
                r = 0.5 + (r - 0.5) * k; g = 0.5 + (g - 0.5) * k; b = 0.5 + (b - 0.5) * k
            }
            // 하이라이트·그림자: 끝점(순흑·순백)은 그대로 두고 밝은 쪽/어두운 쪽만 움직임
            if p.highlights != 0 || p.shadows != 0 {
                r = tone(r, p.highlights, p.shadows); g = tone(g, p.highlights, p.shadows); b = tone(b, p.highlights, p.shadows)
            }
            // 채도
            let s = Looks.saturate(r, g, b, 1 + p.saturation)
            // 페이드
            let lift = p.fade * 0.15
            return SIMD3(lift + (1 - lift) * s.x, lift + (1 - lift) * s.y, lift + (1 - lift) * s.z)
        }
    }

    private static func tone(_ x: Float, _ hi: Float, _ sh: Float) -> Float {
        let v = clamp01(x)
        return v + hi * 1.2 * v * v * (1 - v) + sh * 1.2 * v * (1 - v) * (1 - v)
    }

    private static func toLinear(_ v: Float) -> Float { powf(clamp01(v), 2.2) }
    private static func toGamma(_ v: Float) -> Float { powf(clamp01(v), 1 / 2.2) }
}

/// 자동 색온도 · 자동 노출. 픽셀은 RGBA 8bit (작게 줄인 사진이면 충분).
enum AutoFix {
    /// 회색 세계 가정으로 색 치우침을 재서 (색온도, 틴트) 값을 돌려줍니다.
    static func whiteBalance(rgba px: [UInt8]) -> (temperature: Float, tint: Float) {
        var nr = 0.0, ng = 0.0, nb = 0.0, nn = 0
        var ar = 0.0, ag = 0.0, ab = 0.0
        let count = px.count / 4
        guard count > 0 else { return (0, 0) }
        for i in 0..<count {
            let r = Double(px[i * 4]) / 255, g = Double(px[i * 4 + 1]) / 255, b = Double(px[i * 4 + 2]) / 255
            ar += r; ag += g; ab += b
            let mx = max(r, g, b), mn = min(r, g, b)
            let l = (r + g + b) / 3
            if l >= 0.15 && l <= 0.92 && mx - mn < 0.18 { nr += r; ng += g; nb += b; nn += 1 }
        }
        let (r, g, b) = nn > count / 50 ? (nr / Double(nn), ng / Double(nn), nb / Double(nn))
                                         : (ar / Double(count), ag / Double(count), ab / Double(count))
        let T = Double(LutMaker.wbTemp), N = Double(LutMaker.wbTint)
        let temp = min(max((b - r) / (T * (r + b)), -1), 1)
        let rb = (r * (1 + T * temp) + b * (1 - T * temp)) / 2
        let tint = min(max((1 - rb / max(g, 1e-6)) / N, -1), 1)
        return (Float(temp), Float(tint))
    }

    /// 자동 노출: (노출 EV, 하이라이트). 장면 기하평균을 18% 회색 쪽으로 70% 만큼, 날아가는 하이라이트는 눌러 줌.
    static func exposure(rgba px: [UInt8]) -> (ev: Float, highlights: Float) {
        let count = px.count / 4
        guard count > 0 else { return (0, 0) }
        var logSum = 0.0
        var lums = [Float](repeating: 0, count: count)
        for i in 0..<count {
            let l = luma(Float(px[i * 4]) / 255, Float(px[i * 4 + 1]) / 255, Float(px[i * 4 + 2]) / 255)
            let lin = pow(Double(l), 2.2)
            lums[i] = Float(lin)
            logSum += log(lin + 1e-4)
        }
        let geo = exp(logSum / Double(count))
        var ev = Float(min(max(log2(0.18 / geo) * 0.7, -1.5), 1.5))
        lums.sort()
        let p99 = lums[min(Int(Double(count) * 0.99), count - 1)]
        let after = p99 * powf(2, ev)
        let hi: Float = after > 1 ? -min(max((after - 1) * 1.2, 0), 0.8) : 0
        if abs(ev) < 0.05 { ev = 0 }
        return (ev, hi)
    }
}
