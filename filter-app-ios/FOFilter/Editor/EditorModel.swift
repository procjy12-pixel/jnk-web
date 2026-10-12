import CoreImage
import CoreTransferable
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// 편집할 것: 사진(방향 반영한 원본) 또는 영상 파일
struct EditorItem: Identifiable {
    enum Source {
        case photo(CIImage)
        case video(URL)
    }

    let id = UUID()
    let source: Source

    static func load(_ item: PhotosPickerItem) async -> EditorItem? {
        if item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) {
            guard let m = try? await item.loadTransferable(type: PickedMovie.self) else { return nil }
            return EditorItem(source: .video(m.url))
        }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let img = CIImage(data: data, options: [.applyOrientationProperty: true]) else { return nil }
        return EditorItem(source: .photo(img))
    }
}

/// 사진 앱에서 고른 영상을 임시 폴더로 복사
struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { m in SentTransferredFile(m.url) } importing: { received in
            let ext = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
            let dst = FileManager.default.temporaryDirectory.appendingPathComponent("pick-\(UUID().uuidString).\(ext)")
            try FileManager.default.copyItem(at: received.file, to: dst)
            return PickedMovie(url: dst)
        }
    }
}

/// 편집 화면 상태. 미리보기는 작게 줄인 사진으로, 저장은 원본 해상도로.
@MainActor
final class EditorModel: ObservableObject {
    let item: EditorItem
    var isVideo: Bool { if case .video = item.source { return true }; return false }

    @Published var settings = Settings() { didSet { if settings != oldValue { scheduleRender() } } }
    @Published private(set) var preview: UIImage?
    @Published private(set) var original: UIImage?
    @Published private(set) var thumbs: [String: UIImage] = [:]
    @Published private(set) var progress: Float?
    @Published var message: String?
    /// 원본(방향 반영) 픽셀 크기
    @Published private(set) var sourceSize: CGSize = .zero

    private weak var app: AppModel?
    private var full: CIImage?
    private var base: CIImage?
    private let queue = DispatchQueue(label: "editor.render", qos: .userInitiated)
    private var rendering = false
    private var dirty = false
    private var gradeKey = ""
    private var gradeCache = PreparedGrade.none
    private var wmCache: (wm: Watermark, width: CGFloat, image: UIImage?)?

    init(item: EditorItem) { self.item = item }

    func attach(_ app: AppModel) async {
        guard self.app == nil else { return }
        self.app = app
        settings = app.settings
        do {
            switch item.source {
            case .photo(let img):
                full = img
                sourceSize = img.extent.size
                base = Self.downscale(img, to: 1400)
            case .video(let url):
                sourceSize = try await VideoExporter.displaySize(of: url)
                base = Self.downscale(try await VideoExporter.frame(of: url), to: 1400)
            }
        } catch {
            message = "불러오지 못했어요: \(error.localizedDescription)"
        }
        scheduleRender()
        makeThumbs()
    }

    private static func downscale(_ img: CIImage, to side: CGFloat) -> CIImage {
        let e = img.extent
        let s = min(1, side / max(e.width, e.height))
        let moved = img.transformed(by: CGAffineTransform(translationX: -e.minX, y: -e.minY))
        return s < 1 ? moved.transformed(by: CGAffineTransform(scaleX: s, y: s)) : moved
    }

    private func baseLut(_ s: Settings) -> Lut3D? { app?.entry(s.lutKey)?.lut }

    // MARK: 미리보기

    func scheduleRender() {
        guard base != nil else { return }
        if rendering { dirty = true; return }
        rendering = true
        let s = settings
        let lut = baseLut(s)
        let base = self.base!
        let key = "\(s.lutKey ?? "")|\(s.intensity)|\(s.grain)|\(s.vignette)|\(s.adjust)"
        let cached = key == gradeKey ? gradeCache : nil
        queue.async {
            let grade = cached ?? PreparedGrade.make(settings: s, base: lut)
            let r = Renderer.shared
            let cropped = r.crop(base, s)
            let out = r.uiImage(r.apply(cropped, grade, grainScale: max(base.extent.width, base.extent.height) / 1440))
            let orig = r.uiImage(cropped)
            DispatchQueue.main.async {
                self.gradeKey = key; self.gradeCache = grade
                self.preview = out; self.original = orig
                self.rendering = false
                if self.dirty { self.dirty = false; self.scheduleRender() }
            }
        }
    }

    private func makeThumbs() {
        guard let base, let entries = app?.entries else { return }
        let small = Self.downscale(base, to: 180)
        queue.async {
            var out: [String: UIImage] = [:]
            for e in entries {
                out[e.key] = Renderer.shared.uiImage(Renderer.shared.apply(small, PreparedGrade(lut: e.lut, grain: 0, vignette: 0)))
            }
            DispatchQueue.main.async { self.thumbs.merge(out) { $1 } }
        }
    }

    /// 화면 위 워터마크 그림 (폭이 같으면 다시 그리지 않음)
    func watermarkImage(_ wm: Watermark, width: CGFloat, scale: CGFloat) -> UIImage? {
        if let c = wmCache, c.wm == wm, abs(c.width - width) < 0.5 { return c.image }
        let img = WatermarkRenderer.textImage(wm, imageWidth: width, scale: scale)
        wmCache = (wm, width, img)
        return img
    }

    // MARK: LUT

    func select(_ e: LutEntry) { settings = AppModel.selecting(e, in: settings) }

    func apply(recent r: Settings) { settings = r }

    func delete(_ e: LutEntry) {
        guard let app else { return }
        app.library.delete(e)
        app.reloadUserLuts()
        if settings.lutKey == e.key, let o = app.entry(Look.original.key) { select(o) }
    }

    func importCube(_ url: URL) {
        guard let app else { return }
        do {
            let e = try app.library.importCube(from: url)
            app.reloadUserLuts()
            select(e)
            makeThumbs()
            message = "‘\(e.name)’ 을(를) 가져왔어요"
        } catch {
            message = "가져오지 못했어요: \(error.localizedDescription)"
        }
    }

    /// 지금 LUT·강도·보정을 LUT 하나로 저장하고 그걸 고릅니다 (보정은 0 으로 → 보이는 건 그대로)
    func saveAsLut(name: String) {
        guard let app else { return }
        let title = name.trimmingCharacters(in: .whitespaces).isEmpty ? "내 LUT" : name
        let lut = LutMaker.build(base: baseLut(settings), params: settings.adjust, title: title, baseIntensity: settings.intensity)
        do {
            let e = try app.library.save(name: title, lut: lut)
            app.reloadUserLuts()
            var s = settings
            s.lutKey = e.key; s.lutName = e.name; s.intensity = 1; s.adjust = MakerParams()
            settings = s
            makeThumbs()
            message = "‘\(title)’ LUT 로 저장했어요 (내 LUT)"
        } catch {
            message = "저장하지 못했어요: \(error.localizedDescription)"
        }
    }

    // MARK: 자동 보정

    /// 지금 LUT 를 입힌 결과에서 색 치우침을 재서 색온도·틴트를 맞춥니다.
    func autoWhiteBalance() {
        guard let base else { return }
        var probe = settings
        probe.adjust = MakerParams()
        let lut = baseLut(settings)
        queue.async {
            let r = Renderer.shared
            let g = PreparedGrade.make(settings: probe, base: lut)
            let px = r.rgbaPixels(r.apply(r.crop(base, probe), PreparedGrade(lut: nil, grain: 0, vignette: 0).with(cube: g)))
            let (t, n) = AutoFix.whiteBalance(rgba: px)
            DispatchQueue.main.async {
                self.settings.adjust.temperature = t
                self.settings.adjust.tint = n
                self.message = String(format: "색온도 %+.0f · 틴트 %+.0f 로 맞췄어요", t * 100, n * 100)
            }
        }
    }

    /// 지금 LUT·보정을 입힌 결과를 재서 노출(과 날아가는 하이라이트)을 맞춥니다.
    func autoExposure() {
        guard let base else { return }
        var probe = settings
        probe.adjust.exposure = 0
        probe.adjust.highlights = 0
        let lut = baseLut(settings)
        queue.async {
            let r = Renderer.shared
            let g = PreparedGrade.make(settings: probe, base: lut)
            let px = r.rgbaPixels(r.apply(r.crop(base, probe), PreparedGrade(lut: nil, grain: 0, vignette: 0).with(cube: g)))
            let (ev, hi) = AutoFix.exposure(rgba: px)
            DispatchQueue.main.async {
                self.settings.adjust.exposure = ev
                self.settings.adjust.highlights = hi
                self.message = String(format: "노출 %+.1f EV", ev) + (hi < 0 ? String(format: " · 하이라이트 %.0f", hi * 100) : "")
            }
        }
    }

    // MARK: 저장

    func save(watermark: Watermark) {
        guard progress == nil, let app else { return }
        let s = settings
        let lut = baseLut(s)
        switch item.source {
        case .photo:
            guard let full else { return }
            progress = 0
            queue.async {
                let r = Renderer.shared
                let g = PreparedGrade.make(settings: s, base: lut)
                let out = r.process(full, settings: s, grade: g, watermark: watermark,
                                    grainScale: max(full.extent.width, full.extent.height) / 1440)
                let jpeg = r.jpeg(out)
                Task { @MainActor in
                    do {
                        guard let jpeg else { throw CocoaError(.fileWriteUnknown) }
                        try await PhotoSaver.save(imageData: jpeg)
                        self.saved(s, app: app, what: "사진")
                    } catch {
                        self.message = "저장 실패: \(error.localizedDescription)"
                    }
                    self.progress = nil
                }
            }
        case .video(let url):
            progress = 0
            Task { @MainActor in
                do {
                    let g = await Task.detached { PreparedGrade.make(settings: s, base: lut) }.value
                    let out = try await VideoExporter.export(url: url, settings: s, grade: g, watermark: watermark) { p in
                        Task { @MainActor in self.progress = p }
                    }
                    try await PhotoSaver.save(videoURL: out)
                    self.saved(s, app: app, what: "영상")
                } catch {
                    self.message = "저장 실패: \(error.localizedDescription)"
                }
                self.progress = nil
            }
        }
    }

    private func saved(_ s: Settings, app: AppModel, what: String) {
        app.settings = s
        app.pushRecent(s)
        message = "\(what)을 사진 앱에 저장했어요"
    }

    /// 닫을 때 마지막 설정을 카메라에도
    func close() { app?.settings = settings }
}

extension PreparedGrade {
    /// 색(LUT)만 가져오고 그레인·비네팅은 이 값 그대로
    func with(cube g: PreparedGrade) -> PreparedGrade {
        var c = self
        c.cubeData = g.cubeData
        c.cubeSize = g.cubeSize
        return c
    }
}
