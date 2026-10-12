import Foundation

/// 목록에 보이는 LUT 한 개. 직접 만든 룩이면 `look`, 사용자 LUT 면 `file` 이 있습니다.
struct LutEntry: Identifiable {
    let name: String
    let sub: String
    let lut: Lut3D?
    let category: LutCategory
    var look: Look? = nil
    var file: URL? = nil
    /// 설정 저장용 고유 키 (look:… / preset:… / file:…) — 안드로이드판과 같은 형식
    let key: String
    /// 고르면 맞춰 줄 그레인·비네팅 (사용자 LUT 는 nil → 지금 값 유지)
    var grain: Float? = nil
    var vignette: Float? = nil

    var id: String { key }
}

/// 직접 만든 룩 · 같이 넣은 라이카 LUT · 사용자가 만들거나 가져온 LUT(앱 저장소의 .cube 파일)
final class LutLibrary {
    static let shared = LutLibrary()

    let dir: URL
    private let resources: URL?

    init(dir: URL? = nil, resources: URL? = Bundle.main.resourceURL?.appendingPathComponent("luts")) {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        self.dir = dir ?? base.appendingPathComponent("luts", isDirectory: true)
        self.resources = resources
        try? FileManager.default.createDirectory(at: self.dir, withIntermediateDirectories: true)
    }

    func all() -> [LutEntry] { builtIns() + bundled() + userLuts() }

    func builtIns() -> [LutEntry] {
        Look.allCases.map { l in
            LutEntry(name: l.label, sub: l.sub, lut: l == .original ? nil : l.bake(), category: l.category,
                     look: l, key: l.key, grain: l.grain, vignette: l.vignette)
        }
    }

    func bundled() -> [LutEntry] {
        guard let resources else { return [] }
        return Presets.all.compactMap { p in
            let url = resources.appendingPathComponent(p.path + ".cube")
            guard let text = try? String(contentsOf: url, encoding: .utf8),
                  let lut = try? Lut3D.parseCube(text, fallbackTitle: p.sub) else { return nil }
            return LutEntry(name: p.name, sub: p.sub, lut: lut, category: p.category, key: p.key,
                            grain: p.grain, vignette: p.vignette)
        }
    }

    func userLuts() -> [LutEntry] {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        return files.filter { $0.pathExtension.lowercased() == "cube" }
            .sorted { modified($0) < modified($1) }
            .compactMap { f in
                guard let text = try? String(contentsOf: f, encoding: .utf8),
                      let lut = try? Lut3D.parseCube(text, fallbackTitle: f.deletingPathExtension().lastPathComponent) else { return nil }
                return LutEntry(name: lut.title.isEmpty ? f.deletingPathExtension().lastPathComponent : lut.title,
                                sub: "내 LUT · \(lut.size)³", lut: lut, category: .mine, file: f, key: "file:\(f.lastPathComponent)")
            }
    }

    private func modified(_ u: URL) -> Date {
        (try? u.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
    }

    private func safeName(_ name: String) -> String {
        let s = String(name.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) || $0 == " " || $0 == "_" || $0 == "-" })
            .trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? "LUT" : s
    }

    @discardableResult
    func save(name: String, lut: Lut3D) throws -> LutEntry {
        let titled = lut.withTitle(name)
        let safe = safeName(name)
        var f = dir.appendingPathComponent("\(safe).cube")
        var n = 2
        while FileManager.default.fileExists(atPath: f.path) {
            f = dir.appendingPathComponent("\(safe) (\(n)).cube"); n += 1
        }
        try titled.toCube().write(to: f, atomically: true, encoding: .utf8)
        return LutEntry(name: name, sub: "내 LUT · \(lut.size)³", lut: titled, category: .mine, file: f, key: "file:\(f.lastPathComponent)")
    }

    func delete(_ e: LutEntry) {
        if let f = e.file { try? FileManager.default.removeItem(at: f) }
    }

    /// 외부 .cube 파일을 읽어 라이브러리에 복사합니다.
    @discardableResult
    func importCube(from url: URL) throws -> LutEntry {
        #if canImport(Darwin)
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        #endif
        let base = url.deletingPathExtension().lastPathComponent
        let text = try String(contentsOf: url, encoding: .utf8)
        let lut = try Lut3D.parseCube(text, fallbackTitle: base)
        return try save(name: lut.title.isEmpty ? base : lut.title, lut: lut)
    }

    /// 공유 시트로 내보낼 임시 .cube 파일
    func exportFile(_ e: LutEntry) throws -> URL {
        let lut = (e.lut ?? Lut3D.identity()).withTitle(e.name)
        let f = FileManager.default.temporaryDirectory.appendingPathComponent("\(safeName(e.name)).cube")
        try lut.toCube().write(to: f, atomically: true, encoding: .utf8)
        return f
    }
}
