import Foundation

/// 3D LUT. 격자 한 변이 `size` 칸이고 값은 0...1 RGB.
/// 배열 순서는 .cube 와 같습니다 — 빨강이 가장 빨리 변합니다: index = (b*N + g)*N + r
struct Lut3D {
    let size: Int
    let data: [Float]
    let title: String
    /// 모든 칸이 무채색이면 흑백 LUT. 강도를 낮출 때 원본 색 대신 원본 흑백과 섞습니다.
    let isMono: Bool

    init(size: Int, data: [Float], title: String = "") {
        precondition(size >= 2 && data.count == size * size * size * 3, "LUT 데이터 개수가 맞지 않습니다")
        self.size = size
        self.data = data
        self.title = title
        var mono = true
        var i = 0
        while i < data.count {
            if abs(data[i] - data[i + 1]) > 1e-3 || abs(data[i + 1] - data[i + 2]) > 1e-3 { mono = false; break }
            i += 3
        }
        isMono = mono
    }

    func withTitle(_ t: String) -> Lut3D { Lut3D(size: size, data: data, title: t) }

    /// 삼선형 보간으로 한 색을 찾습니다.
    func sample(_ r: Float, _ g: Float, _ b: Float) -> SIMD3<Float> {
        let n1 = size - 1
        let fr = clamp01(r) * Float(n1), fg = clamp01(g) * Float(n1), fb = clamp01(b) * Float(n1)
        let r0 = min(Int(fr), n1 - 1), g0 = min(Int(fg), n1 - 1), b0 = min(Int(fb), n1 - 1)
        let dr = fr - Float(r0), dg = fg - Float(g0), db = fb - Float(b0)
        let sR = 3, sG = size * 3, sB = size * size * 3
        let base = b0 * sB + g0 * sG + r0 * sR
        var out = SIMD3<Float>(0, 0, 0)
        data.withUnsafeBufferPointer { d in
            for c in 0..<3 {
                let p = base + c
                let c00 = d[p] + (d[p + sR] - d[p]) * dr
                let c10 = d[p + sG] + (d[p + sG + sR] - d[p + sG]) * dr
                let c01 = d[p + sB] + (d[p + sB + sR] - d[p + sB]) * dr
                let c11 = d[p + sB + sG] + (d[p + sB + sG + sR] - d[p + sB + sG]) * dr
                let c0 = c00 + (c10 - c00) * dg
                let c1 = c01 + (c11 - c01) * dg
                out[c] = c0 + (c1 - c0) * db
            }
        }
        return out
    }

    /// Adobe/Resolve 공용 .cube 텍스트
    func toCube() -> String {
        var s = "# FOFilter\n"
        if !title.isEmpty { s += "TITLE \"\(title.replacingOccurrences(of: "\"", with: "'"))\"\n" }
        s += "LUT_3D_SIZE \(size)\nDOMAIN_MIN 0.0 0.0 0.0\nDOMAIN_MAX 1.0 1.0 1.0\n"
        s.reserveCapacity(s.count + data.count * 9)
        var i = 0
        while i < data.count {
            s += String(format: "%.6f %.6f %.6f\n", data[i], data[i + 1], data[i + 2])
            i += 3
        }
        return s
    }

    /// Core Image(CIColorCube) 용 RGBA float 데이터. 순서는 .cube 와 같습니다.
    func cubeData() -> Data {
        var rgba = [Float](repeating: 1, count: size * size * size * 4)
        var j = 0
        var i = 0
        while i < data.count {
            rgba[j] = data[i]; rgba[j + 1] = data[i + 1]; rgba[j + 2] = data[i + 2]
            i += 3; j += 4
        }
        return rgba.withUnsafeBufferPointer { Data(buffer: $0) }
    }

    static let defaultSize = 33

    /// 색 함수 `fn` 을 격자 위에서 계산해 LUT 로 굽습니다.
    static func bake(size: Int = defaultSize, title: String = "", _ fn: (Float, Float, Float) -> SIMD3<Float>) -> Lut3D {
        var d = [Float](repeating: 0, count: size * size * size * 3)
        let n1 = Float(size - 1)
        var i = 0
        for b in 0..<size {
            for g in 0..<size {
                for r in 0..<size {
                    let o = fn(Float(r) / n1, Float(g) / n1, Float(b) / n1)
                    d[i] = clamp01(o.x); d[i + 1] = clamp01(o.y); d[i + 2] = clamp01(o.z)
                    i += 3
                }
            }
        }
        return Lut3D(size: size, data: d, title: title)
    }

    static func identity(size: Int = defaultSize) -> Lut3D { bake(size: size) { SIMD3($0, $1, $2) } }

    /// .cube 읽기. 3D LUT 만 받습니다.
    static func parseCube(_ text: String, fallbackTitle: String = "") throws -> Lut3D {
        var size = 0
        var title = fallbackTitle
        var lo: [Float] = [0, 0, 0], hi: [Float] = [1, 1, 1]
        var data: [Float] = []
        var n = 0
        for raw in text.split(whereSeparator: { $0 == "\n" || $0 == "\r" }) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") { continue }
            let parts = line.split(whereSeparator: { $0 == " " || $0 == "\t" })
            guard let first = parts.first else { continue }
            let head = first.uppercased()
            if head == "TITLE" {
                let t = line.dropFirst(5).trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
                if !t.isEmpty { title = t }
            } else if head == "LUT_3D_SIZE" {
                guard parts.count > 1, let s = Int(parts[1]), (2...256).contains(s) else { throw LutError.invalid("지원하지 않는 LUT 크기") }
                size = s
                data = [Float](repeating: 0, count: s * s * s * 3)
            } else if head == "LUT_1D_SIZE" {
                throw LutError.invalid("1D LUT 는 지원하지 않습니다")
            } else if head == "DOMAIN_MIN" || head == "DOMAIN_MAX" {
                guard parts.count >= 4 else { throw LutError.invalid("DOMAIN 줄이 잘못되었습니다") }
                let v = parts[1...3].map { Float($0) ?? 0 }
                if head == "DOMAIN_MIN" { lo = v } else { hi = v }
            } else if first.first?.isLetter == true {
                continue   // LUT_3D_INPUT_RANGE 같은 기타 키워드
            } else {
                guard size > 0 else { throw LutError.invalid("LUT_3D_SIZE 가 먼저 나와야 합니다") }
                guard n + 3 <= data.count else { throw LutError.invalid("데이터 줄이 너무 많습니다") }
                guard parts.count >= 3 else { throw LutError.invalid("데이터 줄이 잘못되었습니다") }
                for c in 0..<3 {
                    guard let v = Float(parts[c]) else { throw LutError.invalid("숫자가 아닌 값이 있습니다") }
                    data[n + c] = (v - lo[c]) / (hi[c] - lo[c])
                }
                n += 3
            }
        }
        guard size > 0 else { throw LutError.invalid("LUT_3D_SIZE 가 없습니다") }
        guard n == data.count else { throw LutError.invalid("데이터 줄 수가 모자랍니다 (\(n / 3) / \(data.count / 3))") }
        return Lut3D(size: size, data: data, title: title)
    }
}

enum LutError: LocalizedError {
    case invalid(String)
    var errorDescription: String? { if case .invalid(let m) = self { return m }; return nil }
}

@inline(__always) func clamp01(_ v: Float) -> Float { v < 0 ? 0 : (v > 1 ? 1 : v) }
@inline(__always) func mix(_ a: Float, _ b: Float, _ t: Float) -> Float { a + (b - a) * t }
@inline(__always) func luma(_ r: Float, _ g: Float, _ b: Float) -> Float { 0.2126 * r + 0.7152 * g + 0.0722 * b }
@inline(__always) func sCurve(_ x: Float, _ k: Float) -> Float { mix(x, x * x * (3 - 2 * x), k) }
@inline(__always) func smoothstep(_ e0: Float, _ e1: Float, _ x: Float) -> Float {
    let t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3 - 2 * t)
}
