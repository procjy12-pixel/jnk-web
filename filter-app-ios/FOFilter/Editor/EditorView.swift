import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 편집 화면: 필터 · 보정 · 효과 · 프레임 · 글자
struct EditorView: View {
    enum Tab: String, CaseIterable { case filter = "필터", adjust = "보정", effect = "효과", frame = "프레임", text = "글자" }

    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var displayScale
    @StateObject private var model: EditorModel

    @State private var tab: Tab = .filter
    @State private var category: LutCategory = .basic
    @State private var comparing = false
    @State private var zoom: CGFloat = 1
    @State private var cropStart: (x: Float, y: Float)?
    @State private var wmMoveStart: (u: Float, v: Float)?
    @State private var wmSizeStart: Float?
    @State private var wmRotStart: Float?
    @State private var snapX = false
    @State private var snapY = false
    @State private var snapAngle = false
    @State private var showImporter = false
    @State private var share: ShareItem?
    @State private var askName = false
    @State private var newName = ""

    init(item: EditorItem) { _model = StateObject(wrappedValue: EditorModel(item: item)) }

    var body: some View {
        VStack(spacing: 0) {
            header
            canvas
            panel.frame(height: 250)
            tabBar
        }
        .background(Color.black.ignoresSafeArea())
        .foregroundStyle(.white)
        .overlay(alignment: .top) { Toast(message: $model.message).padding(.top, 56) }
        .overlay { if let p = model.progress { savingOverlay(p) } }
        .task {
            await model.attach(app)
            category = app.entry(model.settings.lutKey)?.category ?? .basic
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.data, .plainText]) { result in
            if case .success(let url) = result { model.importCube(url) }
        }
        .sheet(item: $share) { ActivityView(items: [$0.url]) }
        .alert("LUT 이름", isPresented: $askName) {
            TextField("예: 우리집 라이카", text: $newName)
            Button("저장") { model.saveAsLut(name: newName); category = .mine }
            Button("취소", role: .cancel) {}
        } message: { Text("지금 LUT · 강도 · 보정을 LUT 하나로 저장해요") }
    }

    // MARK: 위

    private var header: some View {
        HStack {
            Button("닫기") { model.close(); dismiss() }
            Spacer()
            Text(model.isVideo ? "영상 편집" : "사진 편집").font(.headline)
            Spacer()
            Button { model.save(watermark: app.watermark) } label: { Text("저장").bold().foregroundStyle(accent) }
                .disabled(model.preview == nil || model.progress != nil)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

    private func savingOverlay(_ p: Float) -> some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea()
            VStack(spacing: 12) {
                if model.isVideo {
                    ProgressView(value: Double(p)).tint(accent).frame(width: 220)
                    Text("영상에 필터 입히는 중 \(Int(p * 100))%").font(.footnote)
                } else {
                    ProgressView().tint(.white)
                    Text("원본 해상도로 저장 중…").font(.footnote)
                }
            }
            .padding(24).background(RoundedRectangle(cornerRadius: 14).fill(Color(white: 0.12)))
        }
    }

    // MARK: 사진

    private func fitRect(_ img: CGSize, in box: CGSize) -> CGRect {
        guard img.width > 0, img.height > 0 else { return .zero }
        let s = min(box.width / img.width, box.height / img.height)
        let w = img.width * s, h = img.height * s
        return CGRect(x: (box.width - w) / 2, y: (box.height - h) / 2, width: w, height: h)
    }

    private var canvas: some View {
        GeometryReader { geo in
            let shown = comparing ? (model.original ?? model.preview) : model.preview
            let fit = fitRect(shown?.size ?? .zero, in: geo.size)
            ZStack {
                if let shown {
                    Image(uiImage: shown).resizable().frame(width: fit.width, height: fit.height).position(x: fit.midX, y: fit.midY)
                    if !comparing, app.watermark.visible,
                       let t = model.watermarkImage(app.watermark, width: fit.width, scale: displayScale) {
                        Image(uiImage: t)
                            .rotationEffect(.degrees(Double(app.watermark.rotation)))
                            .position(x: fit.minX + CGFloat(app.watermark.u) * fit.width, y: fit.minY + CGFloat(app.watermark.v) * fit.height)
                            .allowsHitTesting(false)
                    }
                    guides(fit)
                } else {
                    ProgressView().tint(.white).position(x: geo.size.width / 2, y: geo.size.height / 2)
                }
            }
            .scaleEffect(zoom)
            .contentShape(Rectangle())
            .gesture(tab == .text && app.watermark.enabled ? AnyGesture(watermarkGesture(fit).map { _ in () }) : AnyGesture(photoGesture(fit).map { _ in () }))
            .overlay(alignment: .bottomLeading) { compareButton.padding(10) }
            .overlay(alignment: .topLeading) {
                if tab == .frame && model.settings.frame != .original {
                    hint("사진을 끌어서 잘리는 위치를 옮겨요")
                } else if tab == .text && app.watermark.enabled {
                    hint("글자를 끌어서 옮기고, 두 손가락으로 크기·회전")
                }
            }
        }
        .clipped()
    }

    private func hint(_ t: String) -> some View {
        Text(t).font(.caption).padding(.horizontal, 10).padding(.vertical, 5)
            .background(Capsule().fill(Color.black.opacity(0.55))).padding(10)
    }

    /// 누르고 있으면 원본
    private var compareButton: some View {
        Text("원본").font(.caption.bold()).foregroundStyle(comparing ? .black : .white)
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(Capsule().fill(comparing ? accent : Color.black.opacity(0.55)))
            // 누르는 동안만 true (끝까지 '길게 누르기' 가 되지 않게 시간을 아주 길게)
            .onLongPressGesture(minimumDuration: 600, maximumDistance: 80, perform: {}, onPressingChanged: { comparing = $0 })
    }

    @ViewBuilder private func guides(_ fit: CGRect) -> some View {
        let dash = StrokeStyle(lineWidth: 1.5, dash: [9, 6])
        if snapX && wmMoveStart != nil {
            Path { p in p.move(to: CGPoint(x: fit.midX, y: fit.minY)); p.addLine(to: CGPoint(x: fit.midX, y: fit.maxY)) }.stroke(accent, style: dash)
        }
        if snapY && wmMoveStart != nil {
            Path { p in p.move(to: CGPoint(x: fit.minX, y: fit.midY)); p.addLine(to: CGPoint(x: fit.maxX, y: fit.midY)) }.stroke(accent, style: dash)
        }
    }

    /// 한 손가락: 프레임 위치 옮기기, 두 손가락: 확대 (손을 떼면 원래 크기로)
    private func photoGesture(_ fit: CGRect) -> some Gesture {
        let drag = DragGesture(minimumDistance: 4)
            .onChanged { v in
                let s = model.settings
                guard s.frame != .original, fit.width > 0, model.sourceSize.width > 0 else { return }
                let W = Int(model.sourceSize.width), H = Int(model.sourceSize.height)
                let b = cropBox(w: W, h: H, frame: s.frame, flip: s.frameFlip, cx: s.cropX, cy: s.cropY)
                if cropStart == nil { cropStart = (s.cropX, s.cropY) }
                let start = cropStart!
                let pxPerPt = CGFloat(b.w) / fit.width
                var n = s
                if W > b.w { n.cropX = min(max(start.x - Float(v.translation.width * pxPerPt / CGFloat(W - b.w)), 0), 1) }
                if H > b.h { n.cropY = min(max(start.y - Float(v.translation.height * pxPerPt / CGFloat(H - b.h)), 0), 1) }
                model.settings = n
            }
            .onEnded { _ in cropStart = nil }
        let pinch = MagnificationGesture()
            .onChanged { zoom = max(1, min($0, 6)) }
            .onEnded { _ in withAnimation(.spring(response: 0.3)) { zoom = 1 } }
        return drag.simultaneously(with: pinch)
    }

    /// 인스타 글자처럼: 끌기 · 크기 · 회전, 가운데와 0°/90° 에 자석처럼 붙음
    private func watermarkGesture(_ fit: CGRect) -> some Gesture {
        let haptic = UIImpactFeedbackGenerator(style: .light)
        let drag = DragGesture(minimumDistance: 0)
            .onChanged { v in
                guard fit.width > 0 else { return }
                var w = app.watermark
                if wmMoveStart == nil { wmMoveStart = (w.u, w.v) }
                var u = wmMoveStart!.u + Float(v.translation.width / fit.width)
                var vv = wmMoveStart!.v + Float(v.translation.height / fit.height)
                let sx = abs(u - 0.5) < 0.025, sy = abs(vv - 0.5) < 0.025
                if sx { u = 0.5 }
                if sy { vv = 0.5 }
                if (sx && !snapX) || (sy && !snapY) { haptic.impactOccurred() }
                snapX = sx; snapY = sy
                w.u = min(max(u, 0), 1); w.v = min(max(vv, 0), 1)
                app.watermark = w
            }
            .onEnded { _ in wmMoveStart = nil; snapX = false; snapY = false }
        let pinch = MagnificationGesture()
            .onChanged { m in
                var w = app.watermark
                if wmSizeStart == nil { wmSizeStart = w.size }
                w.size = min(max(wmSizeStart! * Float(m), 0.015), 0.4)
                app.watermark = w
            }
            .onEnded { _ in wmSizeStart = nil }
        let rotate = RotationGesture()
            .onChanged { a in
                var w = app.watermark
                if wmRotStart == nil { wmRotStart = w.rotation }
                var r = wmRotStart! + Float(a.degrees)
                r = (r + 540).truncatingRemainder(dividingBy: 360) - 180
                let nearest = (r / 90).rounded() * 90
                let snapped = abs(r - nearest) < 4
                if snapped { r = nearest }
                if snapped && !snapAngle { haptic.impactOccurred() }
                snapAngle = snapped
                w.rotation = r
                app.watermark = w
            }
            .onEnded { _ in wmRotStart = nil; snapAngle = false }
        return drag.simultaneously(with: pinch.simultaneously(with: rotate))
    }

    // MARK: 아래 패널

    private var tabBar: some View {
        HStack {
            ForEach(Tab.allCases, id: \.self) { t in
                Button { tab = t } label: {
                    Text(t.rawValue).font(.subheadline.weight(tab == t ? .bold : .regular))
                        .foregroundStyle(tab == t ? accent : .white.opacity(0.75))
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                }
            }
        }
        .background(Color(white: 0.07))
    }

    @ViewBuilder private var panel: some View {
        switch tab {
        case .filter: filterPanel
        case .adjust: adjustPanel
        case .effect: effectPanel
        case .frame: framePanel
        case .text: textPanel
        }
    }

    private var filterPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !app.recent.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        Text("최근").font(.caption.bold()).foregroundStyle(accent)
                        ForEach(Array(app.recent.enumerated()), id: \.offset) { _, r in
                            Chip(title: r.label, on: false) { model.apply(recent: r) }
                        }
                    }.padding(.horizontal, 14)
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(LutCategory.allCases, id: \.self) { c in Chip(title: c.rawValue, on: category == c) { category = c } }
                }.padding(.horizontal, 14)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 10) {
                    if category == .mine {
                        Button { showImporter = true } label: {
                            VStack(spacing: 5) {
                                RoundedRectangle(cornerRadius: 8).stroke(Color.white.opacity(0.4), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                                    .frame(width: 64, height: 64).overlay(Image(systemName: "plus"))
                                Text(".cube 가져오기").font(.caption2)
                            }.frame(width: 76)
                        }
                    }
                    ForEach(app.entries(in: category)) { e in
                        LutTile(entry: e, thumb: model.thumbs[e.key], on: e.key == model.settings.lutKey) { model.select(e) }
                            .contextMenu {
                                Button { share = (try? app.library.exportFile(e)).map(ShareItem.init) } label: { Label(".cube 로 내보내기", systemImage: "square.and.arrow.up") }
                                if e.category == .mine {
                                    Button(role: .destructive) { model.delete(e) } label: { Label("삭제", systemImage: "trash") }
                                }
                            }
                    }
                }.padding(.horizontal, 14)
            }
            SliderRow(title: "강도", value: $model.settings.intensity, range: 0...1, def: 1, format: pct)
                .padding(.horizontal, 14)
            Button { newName = ""; askName = true } label: {
                Label("이 설정을 LUT로 저장", systemImage: "square.and.arrow.down").font(.footnote)
            }.padding(.horizontal, 14)
        }
        .padding(.top, 8)
    }

    private var adjustPanel: some View {
        ScrollView {
            VStack(spacing: 16) {
                HStack(spacing: 10) {
                    PillButton(title: "색온도 자동", icon: "thermometer.sun") { model.autoWhiteBalance() }
                    PillButton(title: "자동 노출", icon: "sun.max") { model.autoExposure() }
                    PillButton(title: "초기화", icon: "arrow.counterclockwise") { model.settings.adjust = MakerParams() }
                }
                SliderRow(title: "노출", value: $model.settings.adjust.exposure, range: -2...2, def: 0) { String(format: "%+.1f", $0) }
                SliderRow(title: "대비", value: $model.settings.adjust.contrast, range: -1...1, def: 0, format: signedPct)
                SliderRow(title: "하이라이트", value: $model.settings.adjust.highlights, range: -1...1, def: 0, format: signedPct)
                SliderRow(title: "그림자", value: $model.settings.adjust.shadows, range: -1...1, def: 0, format: signedPct)
                SliderRow(title: "채도", value: $model.settings.adjust.saturation, range: -1...1, def: 0, format: signedPct)
                SliderRow(title: "색온도", value: $model.settings.adjust.temperature, range: -1...1, def: 0, format: signedPct)
                SliderRow(title: "틴트", value: $model.settings.adjust.tint, range: -1...1, def: 0, format: signedPct)
                SliderRow(title: "페이드", value: $model.settings.adjust.fade, range: 0...1, def: 0, format: pct)
            }
            .padding(14)
        }
    }

    private var effectPanel: some View {
        VStack(spacing: 18) {
            SliderRow(title: "그레인", value: $model.settings.grain, range: 0...1, def: 0, format: pct)
            SliderRow(title: "비네팅", value: $model.settings.vignette, range: 0...1, def: 0, format: pct)
            Text("슬라이더 이름을 누르면 기본값으로 돌아가요").font(.caption).foregroundStyle(.gray)
            Spacer()
        }
        .padding(14)
    }

    private var framePanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Frame.allCases, id: \.self) { f in
                        Chip(title: f.label, on: model.settings.frame == f) { model.settings.frame = f }
                    }
                }
            }
            HStack {
                Chip(title: "사진 방향대로", on: !model.settings.frameFlip) { model.settings.frameFlip = false }
                Chip(title: "반대 방향", on: model.settings.frameFlip) { model.settings.frameFlip = true }
            }
            .disabled(model.settings.frame == .original || model.settings.frame == .square)
            Text("시네마스코프 2.39:1 · 16:9 · 4:3 · 3:2 · 1:1. 사진을 끌어서 잘리는 위치를 옮길 수 있어요.")
                .font(.caption).foregroundStyle(.gray)
            Spacer()
        }
        .padding(14)
    }

    private static let swatches: [UInt32] = [0xFFFFFF, 0x111111, 0xF3E9D2, 0xE8743B, 0xD9433B, 0x7FB3D5]

    private var textPanel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Toggle("워터마크 넣기", isOn: $app.watermark.enabled).tint(accent)
                TextField("글자", text: $app.watermark.text, axis: .vertical)
                    .lineLimit(1...3)
                    .padding(10).background(RoundedRectangle(cornerRadius: 8).fill(Color.white.opacity(0.1)))
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(WmFont.allCases, id: \.self) { f in
                            Button { app.watermark.font = f } label: {
                                Text(f == .scriptKo ? "손글씨" : "FOFilter")
                                    .font(.custom(WatermarkRenderer.font(f, size: 18).fontName, size: 18))
                                    .foregroundStyle(app.watermark.font == f ? Color.black : .white)
                                    .padding(.horizontal, 12).padding(.vertical, 6)
                                    .background(Capsule().fill(app.watermark.font == f ? accent : Color.white.opacity(0.12)))
                            }
                        }
                    }
                }
                HStack(spacing: 10) {
                    ForEach(Self.swatches, id: \.self) { c in
                        Circle().fill(Color(rgb: c)).frame(width: 28, height: 28)
                            .overlay(Circle().stroke(app.watermark.color == c ? accent : Color.white.opacity(0.3), lineWidth: app.watermark.color == c ? 3 : 1))
                            .onTapGesture { app.watermark.color = c }
                    }
                    Spacer()
                    Toggle("그림자", isOn: $app.watermark.shadow).tint(accent).fixedSize()
                }
                SliderRow(title: "크기", value: $app.watermark.size, range: 0.015...0.4, def: 0.07) { String(format: "%.0f", $0 * 100) }
                SliderRow(title: "투명도", value: $app.watermark.opacity, range: 0.1...1, def: 0.85, format: pct)
                SliderRow(title: "자간", value: $app.watermark.spacing, range: -0.1...0.5, def: 0.05) { String(format: "%.2f", $0) }
                Button { app.watermark.u = 0.5; app.watermark.v = 0.88; app.watermark.rotation = 0 } label: {
                    Label("위치 · 회전 처음대로", systemImage: "arrow.counterclockwise").font(.footnote)
                }
            }
            .padding(14)
        }
    }
}

// MARK: - 부품

private func pct(_ v: Float) -> String { "\(Int((v * 100).rounded()))" }
private func signedPct(_ v: Float) -> String { String(format: "%+d", Int((v * 100).rounded())) }

struct SliderRow: View {
    let title: String
    @Binding var value: Float
    let range: ClosedRange<Float>
    let def: Float
    var format: (Float) -> String

    var body: some View {
        HStack(spacing: 10) {
            Button { value = def } label: {
                Text(title).font(.footnote).foregroundStyle(value == def ? Color.white.opacity(0.8) : accent)
                    .frame(width: 74, alignment: .leading)
            }
            Slider(value: $value, in: range).tint(accent)
            Text(format(value)).font(.footnote.monospacedDigit()).frame(width: 42, alignment: .trailing)
        }
    }
}

struct PillButton: View {
    let title: String
    let icon: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(.footnote.weight(.semibold))
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(Capsule().fill(Color.white.opacity(0.12)))
        }
    }
}

struct LutTile: View {
    let entry: LutEntry
    let thumb: UIImage?
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8).fill(Color.white.opacity(0.08))
                    if let thumb { Image(uiImage: thumb).resizable().scaledToFill() }
                }
                .frame(width: 64, height: 64)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(on ? accent : .clear, lineWidth: 3))
                Text(entry.name).font(.caption2.weight(on ? .bold : .regular)).lineLimit(2).multilineTextAlignment(.center)
                    .foregroundStyle(on ? accent : .white)
            }
            .frame(width: 76)
        }
    }
}

struct ShareItem: Identifiable {
    let url: URL
    var id: URL { url }
}

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

extension Color {
    init(rgb: UInt32) {
        self.init(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}
