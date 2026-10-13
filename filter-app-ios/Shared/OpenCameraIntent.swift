import AppIntents

/// 잠금화면·제어 센터·동작 버튼의 "FOFilter 카메라" 컨트롤이 실행하는 동작: 앱을 열면 바로 카메라
/// (앱과 컨트롤 확장 양쪽에 같이 들어감)
struct OpenFOFilterCameraIntent: AppIntent {
    static var title: LocalizedStringResource { "FOFilter 카메라 열기" }
    static var description: IntentDescription { IntentDescription("필터를 입힌 카메라를 엽니다.") }
    static var openAppWhenRun: Bool { true }

    func perform() async throws -> some IntentResult { .result() }
}
