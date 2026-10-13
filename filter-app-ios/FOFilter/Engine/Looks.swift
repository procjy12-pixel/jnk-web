import Foundation

/// LUT 분류 (안드로이드판과 같음)
enum LutCategory: String, CaseIterable, Codable {
    case basic = "기본", leica = "라이카", ricoh = "리코", mine = "내 LUT"
}

/// 직접 만든 룩 (틸앤오렌지 · 리코 시네마 옐로우). 색 변환만 정의하고 LUT 로 구워서 씁니다.
/// 라이카 룩은 사진에서 피팅한 LUT 파일이라 `Presets` 에 있습니다.
/// 그레인·비네팅은 위치에 따라 달라지는 효과라 룩마다 기본값만 두고 렌더러에서 따로 입힙니다.
enum Look: String, CaseIterable {
    case original = "ORIGINAL"
    case tealOrange = "TEAL_ORANGE"
    case cinemaYellow = "RICOH_CINEMA_YELLOW"

    var label: String {
        switch self { case .original: "원본"; case .tealOrange: "틸앤오렌지"; case .cinemaYellow: "시네마 옐로우" }
    }
    var sub: String {
        switch self { case .original: "Original"; case .tealOrange: "Teal & Orange"; case .cinemaYellow: "Cinema Yellow" }
    }
    var grain: Float { self == .cinemaYellow ? 0.35 : 0 }
    var vignette: Float {
        switch self { case .original: 0; case .tealOrange: 0.15; case .cinemaYellow: 0.3 }
    }
    var category: LutCategory { self == .cinemaYellow ? .ricoh : .basic }
    var key: String { "look:\(rawValue)" }

    func bake() -> Lut3D { Lut3D.bake(title: sub) { Looks.color(self, $0, $1, $2) } }
}

/// 같이 넣은 라이카 룩 LUT (Resources/luts/leica — 안드로이드판 assets 와 같은 파일)
struct Preset {
    let path: String, name: String, sub: String, category: LutCategory, grain: Float, vignette: Float
    var key: String { "preset:\(path)" }
}

enum Presets {
    static let all: [Preset] = [
        Preset(path: "leica/leica_teal", name: "라이카 틸", sub: "Leica Teal", category: .leica, grain: 0, vignette: 0.36),
        Preset(path: "leica/leica_silver", name: "라이카 실버", sub: "Leica Silver", category: .leica, grain: 0, vignette: 0.34),
        Preset(path: "leica/leica_contemporary", name: "라이카 컨템퍼러리", sub: "Leica Contemporary · CNT", category: .leica, grain: 0, vignette: 0.27),
        Preset(path: "leica/leica_classic", name: "라이카 클래식", sub: "Leica Classic · CLS", category: .leica, grain: 0, vignette: 0.25),
    ]
}

enum Looks {
    static func color(_ look: Look, _ r: Float, _ g: Float, _ b: Float) -> SIMD3<Float> {
        switch look {
        case .original: return SIMD3(r, g, b)
        case .tealOrange: return tealOrange(r, g, b)
        case .cinemaYellow: return cinemaYellow(r, g, b)
        }
    }

    // ── 시네마 옐로우 (리코 GR 의 이미지 컨트롤을 참고한 근사치):
    //    옛 필름 영화처럼 전체가 호박빛, 밝은 곳은 금빛, 파랑은 채도를 빼 청록 쪽으로,
    //    그림자는 살짝 올리브, 부드러운 대비에 블랙을 조금 띄움.
    static func cinemaYellow(_ r0: Float, _ g0: Float, _ b0: Float) -> SIMD3<Float> {
        var r = sCurve(clamp01(r0), 0.25)
        var g = sCurve(clamp01(g0), 0.25)
        var b = sCurve(clamp01(b0), 0.25)

        let blue = clamp01((b - max(r, g)) * 3)
        let s = saturate(r, g, b, 0.82 - 0.3 * blue)
        r = s.x; g = s.y; b = s.z
        g += 0.02 * blue * b

        let l = clamp01(luma(r, g, b))
        let mid = 4 * l * (1 - l)
        let hi = l * l
        r += 0.02 * mid + 0.045 * hi
        g += 0.025 * mid + 0.04 * hi
        b -= 0.05 * mid + 0.085 * hi

        let sh = (1 - l) * (1 - l)
        r -= 0.004 * sh; g += 0.014 * sh; b += 0.004 * sh

        return SIMD3(0.04 + 0.92 * r, 0.04 + 0.92 * g, 0.035 + 0.9 * b)
    }

    // ── 틸앤오렌지: 차가운 색은 청록으로, 따뜻한 색(피부)은 오렌지로 벌려 놓습니다.
    static func tealOrange(_ r0: Float, _ g0: Float, _ b0: Float) -> SIMD3<Float> {
        var r = r0, g = g0, b = b0
        let warm = clamp01((r - b) * 2.2)
        let cool = clamp01((b - r) * 2 + (g - r) * 0.8)
        r += -0.12 * cool + 0.07 * warm
        g += 0.02 * cool + 0.01 * warm
        b += 0.07 * cool - 0.10 * warm

        // 순백·순흑은 물들지 않게, 중간 밝기 쪽에만 톤을 얹습니다.
        let l = luma(r, g, b)
        let sh = (1 - l) * (1 - l) * l * 3
        let hi = l * l * (1 - l) * 3
        r += -0.06 * sh + 0.06 * hi
        g += 0.02 * sh + 0.015 * hi
        b += 0.07 * sh - 0.07 * hi

        r = sCurve(clamp01(r), 0.35)
        g = sCurve(clamp01(g), 0.35)
        b = sCurve(clamp01(b), 0.35)
        return saturate(r, g, b, 1.12)
    }

    static func saturate(_ r: Float, _ g: Float, _ b: Float, _ s: Float) -> SIMD3<Float> {
        let l = luma(r, g, b)
        return SIMD3(l + (r - l) * s, l + (g - l) * s, l + (b - l) * s)
    }
}
