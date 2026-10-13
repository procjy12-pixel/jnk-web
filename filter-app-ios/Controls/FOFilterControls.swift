import AppIntents
import SwiftUI
import WidgetKit

/// 잠금화면 아래 버튼(기본은 손전등·카메라)·제어 센터·동작 버튼에 넣는 컨트롤 (iOS 18+)
@main
struct FOFilterControlsBundle: WidgetBundle {
    var body: some Widget {
        OpenCameraControl()
    }
}

struct OpenCameraControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "kr.co.jnkcorp.fofilter.camera") {
            // 카메라 동작이라 잠겨 있으면 잠금화면 카메라가 잠금 해제 없이 바로 뜸
            ControlWidgetButton(action: FOFilterCaptureIntent()) {
                Label("FOFilter", systemImage: "camera.filters")
            }
        }
        .displayName("FOFilter 카메라")
        .description("잠금을 풀지 않고 필터를 입힌 FOFilter 카메라로 바로 찍어요.")
    }
}
