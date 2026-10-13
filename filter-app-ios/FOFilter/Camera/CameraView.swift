import AVFoundation
import PhotosUI
import SwiftUI

let accent = Color(red: 0.91, green: 0.45, blue: 0.23)   // #E8743B (안드로이드판과 같은 주황)

/// 첫 화면: LUT 를 입힌 실시간 카메라
struct CameraView: View {
    @EnvironmentObject var app: AppModel
    @StateObject private var cam = CameraController()
    @Environment(\.scenePhase) private var phase
    @State private var editor: EditorItem?
    @State private var pick: PhotosPickerItem?
    @State private var showExposure = false
    @State private var pinching = false
    @State private var focusPoint: CGPoint?
    @State private var loadingPick = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            preview
            lutStrip
            bottomBar
        }
        .background(Color.black.ignoresSafeArea())
        .overlay(alignment: .top) { Toast(message: $cam.message).padding(.top, 60) }
        .onAppear {
            app.load()
            cam.onSaved = { s in app.pushRecent(s) }
            cam.update(settings: app.settings, grade: app.grade, watermark: app.watermark)
            cam.start()
        }
        .onReceive(app.$grade) { g in cam.update(settings: app.settings, grade: g, watermark: app.watermark) }
        .onReceive(app.$watermark) { w in cam.update(settings: app.settings, grade: app.grade, watermark: w) }
        .onReceive(app.$settings) { s in cam.update(settings: s, grade: app.grade, watermark: app.watermark) }
        .onChange(of: phase) { p in
            if p == .active && editor == nil { cam.resume() } else if p != .active { cam.pause() }
        }
        .onChange(of: pick) { item in
            guard let item else { return }
            loadingPick = true
            Task {
                let loaded = await EditorItem.load(item)
                loadingPick = false
                pick = nil
                if let loaded { cam.pause(); editor = loaded } else { cam.message = "불러오지 못했어요" }
            }
        }
        .fullScreenCover(item: $editor, onDismiss: { cam.resume() }) { item in
            EditorView(item: item).environmentObject(app)
        }
    }

    // MARK: 위

    private var topBar: some View {
        HStack(spacing: 14) {
            if cam.mode == .photo {
                Button { cam.flash = cam.flash == .off ? .on : (cam.flash == .on ? .auto : .off) } label: {
                    Image(systemName: cam.flash == .off ? "bolt.slash" : (cam.flash == .on ? "bolt.fill" : "bolt.badge.automatic"))
                        .frame(width: 32, height: 32)
                }
            }
            Menu {
                ForEach(Frame.allCases, id: \.self) { f in
                    Button { app.settings.frame = f } label: {
                        if app.settings.frame == f { Label(f.label, systemImage: "checkmark") } else { Text(f.label) }
                    }
                }
                Divider()
                Button(app.settings.frameFlip ? "세로 프레임으로" : "가로 프레임으로") { app.settings.frameFlip.toggle() }
            } label: {
                Text("프레임 \(app.settings.frame.label)").font(.footnote.weight(.semibold))
                    .padding(.horizontal, 10).padding(.vertical, 6).background(Capsule().fill(Color.white.opacity(0.12)))
            }
            Spacer()
            Picker("", selection: Binding(get: { cam.mode }, set: { cam.setMode($0) })) {
                Text("사진").tag(CameraController.Mode.photo)
                Text("영상").tag(CameraController.Mode.video)
            }
            .pickerStyle(.segmented).frame(width: 120).disabled(cam.isRecording)
            Button { showExposure.toggle() } label: {
                Image(systemName: "plusminus.circle").frame(width: 32, height: 32)
                    .foregroundStyle(cam.exposureBias == 0 ? Color.white : accent)
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 14).padding(.vertical, 8)
    }

    // MARK: 미리보기

    private var preview: some View {
        GeometryReader { geo in
            ZStack {
                CameraPreview(camera: cam)
                if cam.authorized == false {
                    VStack(spacing: 12) {
                        Text("카메라 권한이 있어야 앱 카메라를 쓸 수 있어요").foregroundStyle(.white)
                        Button("설정 열기") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
                            .buttonStyle(.borderedProminent).tint(accent)
                        Text("앨범에서 사진·영상을 불러와 편집할 수는 있어요").font(.footnote).foregroundStyle(.gray)
                    }
                }
                if let p = focusPoint {
                    RoundedRectangle(cornerRadius: 4).stroke(accent, lineWidth: 1.5).frame(width: 64, height: 64).position(p)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                MagnificationGesture()
                    .onChanged { v in cam.pinch(v, began: !pinching); pinching = true }
                    .onEnded { _ in pinching = false }
            )
            .simultaneousGesture(SpatialTapGesture().onEnded { e in focus(e.location, in: geo.size) })
            .overlay(alignment: .bottom) { lensRow.padding(.bottom, 10) }
            .overlay(alignment: .trailing) {
                if showExposure {
                    ExposureSlider(value: Binding(get: { cam.exposureBias }, set: { cam.setExposureBias($0) }), range: cam.exposureRange)
                        .frame(width: 44, height: min(260, geo.size.height * 0.6)).padding(.trailing, 8)
                }
            }
            .overlay(alignment: .topLeading) {
                Text(cam.mode == .video && cam.isRecording ? String(format: "● %02d:%02d", cam.recordSeconds / 60, cam.recordSeconds % 60) : app.settings.label)
                    .font(.caption.monospacedDigit()).foregroundStyle(cam.isRecording ? .red : .white)
                    .padding(.horizontal, 8).padding(.vertical, 4).background(Capsule().fill(Color.black.opacity(0.45)))
                    .padding(10)
            }
        }
    }

    private func focus(_ p: CGPoint, in size: CGSize) {
        guard cam.authorized == true, cam.focus(viewPoint: p) else { return }
        focusPoint = p
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { if focusPoint == p { focusPoint = nil } }
    }

    private var lensRow: some View {
        HStack(spacing: 8) {
            if !cam.lenses.isEmpty {
                ForEach(cam.lenses, id: \.self) { b in
                    let on = abs(b.display - cam.zoomDisplay) < 0.05
                    Button { cam.selectLens(b) } label: {
                        Text(on ? "\(Lenses.label(b.display))×" : Lenses.label(b.display))
                            .font(.system(size: 13, weight: .bold)).frame(width: 38, height: 38)
                            .foregroundStyle(on ? accent : .white)
                            .background(Circle().fill(Color.black.opacity(0.5)))
                    }
                }
            }
            if !cam.lenses.contains(where: { abs($0.display - cam.zoomDisplay) < 0.05 }) {
                Text(String(format: "%.1f×", cam.zoomDisplay)).font(.system(size: 13, weight: .bold)).foregroundStyle(accent)
                    .padding(.horizontal, 10).frame(height: 38).background(Capsule().fill(Color.black.opacity(0.5)))
            }
        }
    }

    // MARK: LUT 줄

    private var lutStrip: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(app.recent.enumerated()), id: \.offset) { _, r in
                        Chip(title: "최근 · \(r.label)", on: false, tint: accent) { app.settings = r }
                    }
                    ForEach(app.entries) { e in
                        Chip(title: e.name, on: e.key == app.settings.lutKey) { app.select(e) }.id(e.key)
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 10)
            }
            .onAppear { if let k = app.settings.lutKey { proxy.scrollTo(k, anchor: .center) } }
        }
    }

    // MARK: 아래

    private var bottomBar: some View {
        HStack {
            Button {
                if let img = cam.lastOriginal { cam.pause(); editor = EditorItem(source: .photo(img)) }
            } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 8).fill(Color.white.opacity(0.1))
                    if let t = cam.lastThumb { Image(uiImage: t).resizable().scaledToFill() }
                    if cam.processing > 0 { ProgressView().tint(.white) }
                }
                .frame(width: 52, height: 52).clipShape(RoundedRectangle(cornerRadius: 8))
            }
            Spacer()
            // photoLibrary 를 주면 원본 사진 ID 가 같이 와서 '원본에 덮어쓰기' 가 됨
            PhotosPicker(selection: $pick, matching: .any(of: [.images, .videos]), photoLibrary: .shared()) {
                VStack(spacing: 2) {
                    if loadingPick { ProgressView().tint(.white) } else { Image(systemName: "photo.on.rectangle").font(.title3) }
                    Text("열기").font(.caption2)
                }.foregroundStyle(.white).frame(width: 52)
            }
            Spacer()
            ShutterButton(video: cam.mode == .video, recording: cam.isRecording) {
                if cam.mode == .video { cam.toggleRecording() } else { cam.capturePhoto() }
            }
            .disabled(cam.authorized != true)
            Spacer()
            Button { cam.switchCamera() } label: {
                VStack(spacing: 2) { Image(systemName: "arrow.triangle.2.circlepath.camera").font(.title3); Text("전환").font(.caption2) }
                    .foregroundStyle(.white).frame(width: 52)
            }
            .disabled(cam.isRecording)
            Spacer()
            Button {
                if let img = cam.lastOriginal { cam.pause(); editor = EditorItem(source: .photo(img)) }
                else { cam.message = "먼저 찍거나 ‘열기’로 사진·영상을 고르세요" }
            } label: {
                VStack(spacing: 2) { Image(systemName: "slider.horizontal.3").font(.title3); Text("편집").font(.caption2) }
                    .foregroundStyle(.white).frame(width: 52)
            }
        }
        .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 12)
    }
}

// MARK: - 작은 부품들

struct Chip: View {
    let title: String
    let on: Bool
    var tint: Color = .white
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).font(.footnote.weight(on ? .bold : .regular)).lineLimit(1)
                .foregroundStyle(on ? Color.black : tint)
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(Capsule().fill(on ? accent : Color.white.opacity(0.12)))
        }
    }
}

struct ShutterButton: View {
    let video: Bool
    let recording: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().stroke(Color.white, lineWidth: 4).frame(width: 74, height: 74)
                if video {
                    RoundedRectangle(cornerRadius: recording ? 6 : 30).fill(Color.red)
                        .frame(width: recording ? 30 : 60, height: recording ? 30 : 60)
                } else {
                    Circle().fill(Color.white).frame(width: 62, height: 62)
                }
            }
            .animation(.easeInOut(duration: 0.15), value: recording)
        }
    }
}

struct ExposureSlider: View {
    @Binding var value: Float
    let range: ClosedRange<Float>

    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let t = CGFloat((value - range.lowerBound) / max(range.upperBound - range.lowerBound, 0.01))
            ZStack(alignment: .top) {
                Capsule().fill(Color.black.opacity(0.45)).frame(width: 6).frame(maxWidth: .infinity)
                Image(systemName: "sun.max.fill").foregroundStyle(accent)
                    .offset(y: (1 - t) * (h - 24))
                Text(String(format: "%+.1f", value)).font(.caption2.monospacedDigit()).foregroundStyle(.white)
                    .offset(y: h + 2)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                let k = Float(1 - min(max(g.location.y / h, 0), 1))
                value = ((range.lowerBound + k * (range.upperBound - range.lowerBound)) * 10).rounded() / 10
            })
            .onTapGesture(count: 2) { value = 0 }
        }
    }
}

struct Toast: View {
    @Binding var message: String?

    var body: some View {
        if let m = message {
            Text(m).font(.footnote).foregroundStyle(.white).multilineTextAlignment(.center)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(Capsule().fill(Color.black.opacity(0.75)))
                .onAppear {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) { if message == m { message = nil } }
                }
                .id(m)
        }
    }
}
