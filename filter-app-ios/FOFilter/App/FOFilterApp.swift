import LockedCameraCapture
import SwiftUI

@main
struct FOFilterApp: App {
    @StateObject private var app = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            CameraView()
                .environmentObject(app)
                .preferredColorScheme(.dark)
                .tint(accent)
                // 잠금화면 카메라에서 "앱 열기" 로 열려도 그냥 카메라로
                .onContinueUserActivity(CameraLockMode.openActivity) { _ in importLockCaptures() }
                .task { importLockCaptures() }
                .onChange(of: phase) { p in if p == .active { importLockCaptures() } }
        }
    }

    /// 잠금화면 카메라로 찍어 둔 사진을 사진 앱으로 옮김 (잠긴 동안엔 사진 앱에 바로 저장할 수 없어서)
    private func importLockCaptures() {
        guard #available(iOS 18.0, *) else { return }
        Task {
            let n = await LockCaptureImporter.importAll()
            if n > 0 { app.notice = "잠금화면에서 찍은 사진 \(n)장을 사진 앱에 저장했어요" }
        }
    }
}

@available(iOS 18.0, *)
enum LockCaptureImporter {
    @MainActor private static var running = false

    @MainActor
    static func importAll() async -> Int {
        guard !running else { return 0 }
        running = true
        defer { running = false }
        var count = 0
        for dir in LockedCameraCaptureManager.shared.sessionContentURLs {
            let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
            var allSaved = true
            for f in files where ["jpg", "jpeg", "heic"].contains(f.pathExtension.lowercased()) {
                do {
                    try await PhotoSaver.save(imageData: try Data(contentsOf: f))
                    try? FileManager.default.removeItem(at: f)
                    count += 1
                } catch {
                    allSaved = false   // 권한이 없으면 남겨 두었다가 다음에 다시
                }
            }
            if allSaved { try? await LockedCameraCaptureManager.shared.invalidateSessionContent(at: dir) }
        }
        return count
    }
}
