import Foundation

/// 프레임(자르기) 비율. 가로 사진 기준 가로÷세로, 0 이면 원본 그대로
enum Frame: String, CaseIterable, Codable {
    case original = "ORIGINAL", scope = "SCOPE", wide = "WIDE", std = "STD", photo = "PHOTO", square = "SQUARE"

    var label: String {
        switch self {
        case .original: "원본"; case .scope: "시네마스코프"; case .wide: "16:9"
        case .std: "4:3"; case .photo: "3:2"; case .square: "1:1"
        }
    }
    var ratio: Float {
        switch self {
        case .original: 0; case .scope: 2.39; case .wide: 16 / 9; case .std: 4 / 3; case .photo: 3 / 2; case .square: 1
        }
    }
}

/// 자를 영역 (왼쪽 위 기준 x, y, w, h).
/// `cx`·`cy` 는 남는 쪽으로 어디에 둘지 0(왼쪽/위)...1(오른쪽/아래). `flip` 이면 사진과 반대 방향(세로 사진에 가로 프레임 등).
func cropBox(w: Int, h: Int, frame: Frame, flip: Bool, cx: Float, cy: Float) -> (x: Int, y: Int, w: Int, h: Int) {
    if frame.ratio == 0 || w <= 0 || h <= 0 { return (0, 0, w, h) }
    let landscape = w >= h
    let target = landscape != flip ? frame.ratio : 1 / frame.ratio
    if Float(w) / Float(h) > target {
        let cw = min(max(Int((Float(h) * target).rounded()), 1), w)
        return (Int((Float(w - cw) * min(max(cx, 0), 1)).rounded()), 0, cw, h)
    } else {
        let ch = min(max(Int((Float(w) / target).rounded()), 1), h)
        return (0, Int((Float(h - ch) * min(max(cy, 0), 1)).rounded()), w, ch)
    }
}

/// 사진 한 장에 입힌 설정 한 벌. 앱을 다시 켜거나 "최근" 에서 불러올 때 씁니다.
struct Settings: Codable, Equatable {
    var lutKey: String? = Look.tealOrange.key
    var lutName: String = Look.tealOrange.label
    var intensity: Float = 1
    var grain: Float = Look.tealOrange.grain
    var vignette: Float = Look.tealOrange.vignette
    var adjust = MakerParams()
    var frame: Frame = .original
    var frameFlip = false
    var cropX: Float = 0.5
    var cropY: Float = 0.5

    /// "최근" 칩에 보일 이름
    var label: String { frame == .original ? lutName : "\(lutName) · \(frame.label)" }

    /// 위치(crop)만 다른 건 같은 설정으로 봅니다.
    func sameLook(_ o: Settings) -> Bool {
        var a = self, b = o
        a.cropX = 0; a.cropY = 0; b.cropX = 0; b.cropY = 0
        return a == b
    }
}

/// 워터마크 글꼴 (Resources/fonts, 모두 OFL — 안드로이드판과 같은 파일)
enum WmFont: String, CaseIterable, Codable {
    case pretendard = "PRETENDARD", serif = "SERIF", scriptEn = "SCRIPT_EN", scriptKo = "SCRIPT_KO"

    var label: String {
        switch self {
        case .pretendard: "Pretendard"; case .serif: "Playfair 세리프"
        case .scriptEn: "Great Vibes 필기체"; case .scriptKo: "나눔 손글씨 펜"
        }
    }
    var file: String {
        switch self {
        case .pretendard: "Pretendard-SemiBold.otf"; case .serif: "PlayfairDisplay-Italic.ttf"
        case .scriptEn: "GreatVibes-Regular.ttf"; case .scriptKo: "NanumPenScript-Regular.ttf"
        }
    }
}

/// 워터마크 설정. 위치(u, v)는 잘린 사진 기준 0...1 (왼쪽 위가 0, 가운데가 0.5),
/// 크기는 사진 폭 대비 글자 높이, 회전은 도(°, 시계 방향).
struct Watermark: Codable, Equatable {
    var enabled = false
    var text = "FOFilter"
    var font: WmFont = .serif
    var color: UInt32 = 0xFFFFFF
    var opacity: Float = 0.85
    var size: Float = 0.07
    var spacing: Float = 0.05
    var shadow = true
    var u: Float = 0.5
    var v: Float = 0.88
    var rotation: Float = 0

    var visible: Bool { enabled && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

/// 마지막 설정 · 최근 설정 3개 · 워터마크를 UserDefaults 에 둡니다.
final class SettingsStore {
    static let shared = SettingsStore()
    static let maxRecent = 3

    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    private func load<T: Decodable>(_ key: String, _ type: T.Type) -> T? {
        guard let d = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: d)
    }
    private func save<T: Encodable>(_ key: String, _ value: T?) {
        if let value, let d = try? JSONEncoder().encode(value) { defaults.set(d, forKey: key) } else { defaults.removeObject(forKey: key) }
    }

    var current: Settings? {
        get { load("current", Settings.self) }
        set { save("current", newValue) }
    }

    var watermark: Watermark {
        get { load("watermark", Watermark.self) ?? Watermark() }
        set { save("watermark", newValue) }
    }

    var recent: [Settings] { load("recent", [Settings].self) ?? [] }

    /// 맨 앞에 넣고, 같은 설정은 하나만, 최대 3개
    func pushRecent(_ s: Settings) {
        let list = [s] + recent.filter { !$0.sameLook(s) }
        save("recent", Array(list.prefix(Self.maxRecent)))
    }
}

/// 렌즈 버튼 배율 (0.5× · 1× · 3× 처럼)
enum Lenses {
    /// 가상 카메라(트리플·듀얼)의 렌즈 전환 배율 → 화면에 보일 배율 목록.
    /// `switchOvers` 는 videoZoomFactor 기준 렌즈가 바뀌는 지점, `hasUltraWide` 면 1.0 이 초광각.
    /// 돌려주는 값: (화면 배율, videoZoomFactor)
    static func presets(switchOvers: [Float], hasUltraWide: Bool) -> [(display: Float, factor: Float)] {
        let wide: Float = hasUltraWide ? (switchOvers.first ?? 2) : 1
        var out: [(Float, Float)] = []
        if hasUltraWide { out.append((tidy(1 / wide), 1)) }
        out.append((1, wide))
        for f in (hasUltraWide ? Array(switchOvers.dropFirst()) : switchOvers) where f > wide * 1.12 {
            out.append((tidy(f / wide), f))
        }
        return out
    }

    /// 0.56 → 0.6, 2.9 → 3 처럼 보기 좋게
    static func tidy(_ r: Float) -> Float {
        if r < 1 { return (r * 10).rounded() / 10 }
        let whole = r.rounded()
        return abs(r - whole) < 0.2 ? whole : (r * 10).rounded() / 10
    }

    /// 버튼 글자: 0.5 → ".5", 1 → "1", 3 → "3", 1.5 → "1.5"
    static func label(_ r: Float) -> String {
        if r < 1 { return "." + String(Int((r * 10).rounded())) }
        if r == r.rounded() { return String(Int(r)) }
        return String(format: "%.1f", r)
    }
}
