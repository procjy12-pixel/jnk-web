import Foundation
import Photos
import SwiftUI
import UniformTypeIdentifiers

/// 카메라와 편집 화면이 같이 쓰는 상태: LUT 목록 · 지금 설정(앱을 껐다 켜도 유지) · 최근 3개 · 워터마크
@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var entries: [LutEntry] = []
    @Published private(set) var recent: [Settings] = []
    @Published private(set) var loaded = false
    @Published var settings: Settings { didSet { if settings != oldValue { store.current = settings; rebuildGrade() } } }
    @Published var watermark: Watermark { didSet { if watermark != oldValue { store.watermark = watermark } } }
    /// 지금 설정을 구운 렌더링 값 (카메라가 씀)
    @Published private(set) var grade = PreparedGrade.none

    let store = SettingsStore.shared
    let library = LutLibrary.shared
    private let work = DispatchQueue(label: "app.grade", qos: .userInitiated)
    private var gradeVersion = 0

    init() {
        settings = SettingsStore.shared.current ?? Settings()
        watermark = SettingsStore.shared.watermark
        recent = SettingsStore.shared.recent
    }

    func load() {
        guard !loaded else { return }
        let lib = library
        work.async {
            let all = lib.all()
            DispatchQueue.main.async {
                self.entries = all
                self.loaded = true
                // 없어진 LUT 를 쓰던 설정이면 원본으로
                if let k = self.settings.lutKey, self.entry(k) == nil {
                    self.settings.lutKey = Look.original.key; self.settings.lutName = Look.original.label
                }
                self.rebuildGrade()
            }
        }
    }

    func reloadUserLuts() {
        entries = entries.filter { $0.category != .mine } + library.userLuts()
    }

    func entry(_ key: String?) -> LutEntry? { key.flatMap { k in entries.first { $0.key == k } } }

    func entries(in c: LutCategory) -> [LutEntry] { entries.filter { $0.category == c } }

    /// LUT 고르기. 룩에 맞는 그레인·비네팅도 같이.
    static func selecting(_ e: LutEntry, in s: Settings) -> Settings {
        var n = s
        n.lutKey = e.key
        n.lutName = e.name
        if let g = e.grain { n.grain = g }
        if let v = e.vignette { n.vignette = v }
        return n
    }

    func select(_ e: LutEntry) { settings = Self.selecting(e, in: settings) }

    func pushRecent(_ s: Settings) {
        store.pushRecent(s)
        recent = store.recent
    }

    private func rebuildGrade() {
        guard loaded else { return }
        gradeVersion += 1
        let v = gradeVersion
        let s = settings
        let base = entry(s.lutKey)?.lut
        work.async {
            let g = PreparedGrade.make(settings: s, base: base)
            DispatchQueue.main.async { if v == self.gradeVersion { self.grade = g } }
        }
    }
}

/// 사진 앱에 저장 (추가 권한만)
enum PhotoSaver {
    enum SaveError: LocalizedError {
        case denied
        var errorDescription: String? { "사진 앱 저장 권한이 없어요 (설정 > FOFilter > 사진)" }
    }

    private static func authorize() async throws {
        let s = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard s == .authorized || s == .limited else { throw SaveError.denied }
    }

    static func save(imageData: Data) async throws {
        try await authorize()
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetCreationRequest.forAsset().addResource(with: .photo, data: imageData, options: nil)
        }
    }

    enum OverwriteError: LocalizedError {
        case noAccess, notFound, notEditable, failed
        var errorDescription: String? {
            switch self {
            case .noAccess: "사진 앱 접근 권한이 있어야 원본에 덮어쓸 수 있어요 (설정 > FOFilter > 사진 > 전체 접근)"
            case .notFound: "원본을 찾을 수 없어요 (권한이 ‘선택한 사진’ 이면 이 사진도 선택해 주세요)"
            case .notEditable: "이 항목은 수정할 수 없어요"
            case .failed: "원본을 열지 못했어요"
            }
        }
    }

    private static func editableAsset(_ id: String) async throws -> PHAsset {
        let s = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        guard s == .authorized || s == .limited else { throw OverwriteError.noAccess }
        guard let a = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { throw OverwriteError.notFound }
        guard a.canPerform(.content) else { throw OverwriteError.notEditable }
        return a
    }

    private static func editingInput(_ a: PHAsset) async throws -> PHContentEditingInput {
        try await withCheckedThrowingContinuation { c in
            let o = PHContentEditingInputRequestOptions()
            o.isNetworkAccessAllowed = true          // iCloud 에만 있는 원본도
            o.canHandleAdjustmentData = { _ in false }
            _ = a.requestContentEditingInput(with: o) { input, info in
                if let input { c.resume(returning: input) }
                else { c.resume(throwing: (info[PHContentEditingInputErrorKey] as? Error) ?? OverwriteError.failed) }
            }
        }
    }

    /// 사진 앱의 원본에 덮어쓰기. 사진 앱의 '편집' 으로 저장되어 '편집 > 복귀' 로 되돌릴 수 있습니다.
    static func overwrite(assetID: String, imageData: Data? = nil, videoURL: URL? = nil, settings: Settings) async throws {
        let asset = try await editableAsset(assetID)
        let input = try await editingInput(asset)
        let output = PHContentEditingOutput(contentEditingInput: input)
        output.adjustmentData = PHAdjustmentData(formatIdentifier: "kr.co.jnkcorp.fofilter", formatVersion: "1.0",
                                                 data: (try? JSONEncoder().encode(settings)) ?? Data())
        if let imageData {
            let url: URL
            if #available(iOS 17, *) { url = try output.renderedContentURL(for: .jpeg) } else { url = output.renderedContentURL }
            try imageData.write(to: url)
        } else if let videoURL {
            try FileManager.default.copyItem(at: videoURL, to: output.renderedContentURL)
        }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest(for: asset).contentEditingOutput = output
        }
    }

    static func save(videoURL: URL) async throws {
        try await authorize()
        try await PHPhotoLibrary.shared().performChanges {
            let o = PHAssetResourceCreationOptions()
            o.shouldMoveFile = true
            PHAssetCreationRequest.forAsset().addResource(with: .video, fileURL: videoURL, options: o)
        }
    }
}
