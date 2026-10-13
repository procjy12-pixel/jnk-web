import AppIntents
import Foundation

/// 앱 → 잠금화면 카메라로 넘기는 지금 설정 (잠금화면 카메라는 앱 저장소를 볼 수 없어서)
struct LockCameraContext: Codable, Sendable {
    var settings: Settings
    var watermark: Watermark
}

/// 잠금화면·제어 센터·동작 버튼의 "FOFilter 카메라" 가 실행하는 카메라 동작.
/// 잠겨 있으면 잠금을 풀지 않고 잠금화면 카메라(확장)가, 풀려 있으면 앱이 바로 카메라로 열립니다.
/// (앱 · 컨트롤 확장 · 잠금화면 카메라 확장에 같이 들어감)
@available(iOS 18.0, *)
struct FOFilterCaptureIntent: CameraCaptureIntent {
    typealias AppContext = LockCameraContext

    static let title: LocalizedStringResource = "FOFilter 카메라"
    static let description = IntentDescription("잠금을 풀지 않고 FOFilter 카메라로 찍어요.")

    @MainActor
    func perform() async throws -> some IntentResult { .result() }
}

/// 잠금화면 카메라로 쓸 때: 사진은 사진 앱 대신 잠금화면 보관함으로, 앨범·편집은 앱을 열어서
struct CameraLockMode {
    static let openActivity = "kr.co.jnkcorp.fofilter.open"
    let save: (Data) async throws -> Void
    let openApp: () -> Void
}
