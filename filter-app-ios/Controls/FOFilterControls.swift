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
            ControlWidgetButton(action: OpenFOFilterCameraIntent()) {
                Label("FOFilter", systemImage: "camera.filters")
            }
        }
        .displayName("FOFilter 카메라")
        .description("필터를 입힌 FOFilter 카메라를 바로 엽니다.")
    }
}
