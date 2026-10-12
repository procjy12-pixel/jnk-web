import SwiftUI

@main
struct FOFilterApp: App {
    @StateObject private var app = AppModel()

    var body: some Scene {
        WindowGroup {
            CameraView()
                .environmentObject(app)
                .preferredColorScheme(.dark)
                .tint(accent)
        }
    }
}
