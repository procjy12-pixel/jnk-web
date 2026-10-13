import LockedCameraCapture
import SwiftUI

/// 잠금화면 카메라 (iOS 18+): 잠금을 풀지 않고 FOFilter 카메라로 찍기.
/// 화면은 앱의 카메라 화면을 그대로 쓰고, 찍은 사진은 잠금화면 보관함에 두었다가 앱을 열면 사진 앱으로 옮깁니다.
@main
struct FOFilterLockCamera: LockedCameraCaptureExtension {
    var body: some LockedCameraCaptureExtensionScene {
        LockedCameraCaptureUIScene { session in
            LockCameraRoot(session: session)
        }
    }
}

struct LockCameraRoot: View {
    let session: LockedCameraCaptureSession
    @StateObject private var app = AppModel()

    var body: some View {
        CameraView(lock: CameraLockMode(
            save: { data in
                let f = session.sessionContentURL.appendingPathComponent("FOFilter-\(UUID().uuidString).jpg")
                try data.write(to: f, options: .atomic)
            },
            openApp: {
                Task { try? await session.openApplication(for: NSUserActivity(activityType: CameraLockMode.openActivity)) }
            }))
            .environmentObject(app)
            .preferredColorScheme(.dark)
            .tint(accent)
            .task {
                // 앱에서 쓰던 LUT·프레임·워터마크 그대로
                if let ctx = try? await FOFilterCaptureIntent.appContext { app.apply(lockContext: ctx) }
            }
    }
}
