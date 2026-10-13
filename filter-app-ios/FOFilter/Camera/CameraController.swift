import AVFoundation
import CoreImage
import CoreMotion
import UIKit

struct LensButton: Hashable {
    let display: Float   // 화면 배율 (.5 · 1 · 3)
    let factor: CGFloat  // videoZoomFactor
}

/// 앱 카메라: 실시간 LUT 미리보기 · 사진 · 영상(필터를 입힌 채로 녹화) · 렌즈 버튼 · 줌 · 초점.
final class CameraController: NSObject, ObservableObject {
    enum Mode { case photo, video }

    @Published private(set) var authorized: Bool?
    @Published private(set) var mode: Mode = .photo
    @Published private(set) var position: AVCaptureDevice.Position = .back
    @Published private(set) var lenses: [LensButton] = []
    @Published private(set) var zoomDisplay: Float = 1
    @Published private(set) var isRecording = false
    @Published private(set) var recordSeconds = 0
    @Published private(set) var processing = 0
    @Published private(set) var lastThumb: UIImage?
    @Published var flash: AVCaptureDevice.FlashMode = .off
    @Published private(set) var exposureBias: Float = 0
    @Published private(set) var exposureRange: ClosedRange<Float> = -2...2
    @Published var message: String?

    /// 마지막에 찍은 원본 (편집 화면에서 다시 손볼 때)
    private(set) var lastOriginal: CIImage?
    /// 찍은 사진 저장 (보통은 사진 앱, 잠금화면 카메라에선 잠금화면 보관함)
    var saveImage: (Data) async throws -> Void = { try await PhotoSaver.save(imageData: $0) }
    /// 사진을 다 저장했을 때 (최근 설정에 넣기)
    var onSaved: (@MainActor (Settings) -> Void)?

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "camera.session")
    private let videoQueue = DispatchQueue(label: "camera.video", qos: .userInteractive)
    private let photoQueue = DispatchQueue(label: "camera.photo", qos: .userInitiated)
    private let videoOutput = AVCaptureVideoDataOutput()
    private let audioOutput = AVCaptureAudioDataOutput()
    private let photoOutput = AVCapturePhotoOutput()
    private var videoInput: AVCaptureDeviceInput?
    private var audioInput: AVCaptureDeviceInput?
    private var device: AVCaptureDevice?
    private var wideFactor: CGFloat = 1
    private var pinchStart: CGFloat = 1
    private var configured = false

    // 렌더 상태: 메인에서 쓰고 카메라 스레드에서 읽음
    private let lock = NSLock()
    private var settings = Settings()
    private var grade = PreparedGrade.none
    private var watermark = Watermark()
    private var orientation: UIDeviceOrientation = .portrait
    private weak var preview: PreviewMetalView?
    private var frameIndex = 0
    private var frameSize: CGSize = .zero   // 세로로 돌린 카메라 프레임 크기

    // 녹화 (videoQueue 에서만)
    private var wantRecord = false
    private var recordOrientation: UIDeviceOrientation = .portrait
    private var recordSettings = Settings()
    private var writer: AVAssetWriter?
    private var writerVideo: AVAssetWriterInput?
    private var writerAudio: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var recordStart: CMTime?
    private var timer: Timer?

    private let motion = CMMotionManager()

    func attach(preview v: PreviewMetalView) {
        lock.lock(); preview = v; lock.unlock()
    }

    func update(settings s: Settings, grade g: PreparedGrade, watermark w: Watermark) {
        lock.lock(); settings = s; grade = g; watermark = w; lock.unlock()
    }

    private func snapshot() -> (Settings, PreparedGrade, Watermark, UIDeviceOrientation) {
        lock.lock(); defer { lock.unlock() }
        return (settings, grade, watermark, orientation)
    }

    // MARK: 시작 · 멈춤

    func start() {
        startMotion()
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            authorized = true
            configureIfNeeded()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async {
                    self.authorized = ok
                    if ok { self.configureIfNeeded() }
                }
            }
        default:
            authorized = false
        }
    }

    func resume() {
        sessionQueue.async { if self.configured && !self.session.isRunning { self.session.startRunning() } }
    }

    func pause() {
        if isRecording { stopRecording() }
        sessionQueue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    private func configureIfNeeded() {
        sessionQueue.async {
            guard !self.configured else { if !self.session.isRunning { self.session.startRunning() }; return }
            self.configured = true
            self.session.beginConfiguration()
            self.session.sessionPreset = .photo
            self.setupVideoInput(self.position)
            if self.session.canAddOutput(self.videoOutput) {
                self.session.addOutput(self.videoOutput)
                self.videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
                self.videoOutput.alwaysDiscardsLateVideoFrames = true
                self.videoOutput.setSampleBufferDelegate(self, queue: self.videoQueue)
            }
            if self.session.canAddOutput(self.photoOutput) {
                self.session.addOutput(self.photoOutput)
                // 셔터 지연을 줄이면서 화질은 지킴
                self.photoOutput.maxPhotoQualityPrioritization = .balanced
            }
            self.configureConnections()
            self.session.commitConfiguration()
            self.session.startRunning()
        }
    }

    /// 렌즈가 여러 개인 폰은 가상 카메라(트리플·듀얼)를 써서 줌만으로 렌즈가 바뀌게 합니다.
    private func setupVideoInput(_ pos: AVCaptureDevice.Position) {
        let types: [AVCaptureDevice.DeviceType] = pos == .back
            ? [.builtInTripleCamera, .builtInDualWideCamera, .builtInDualCamera, .builtInWideAngleCamera]
            : [.builtInWideAngleCamera]
        let found = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: pos).devices
        guard let dev = types.lazy.compactMap({ t in found.first { $0.deviceType == t } }).first,
              let input = try? AVCaptureDeviceInput(device: dev) else {
            DispatchQueue.main.async { self.message = "카메라를 열 수 없어요" }
            return
        }
        if let old = videoInput { session.removeInput(old) }
        guard session.canAddInput(input) else { return }
        session.addInput(input)
        videoInput = input
        device = dev

        var buttons: [LensButton] = [LensButton(display: 1, factor: 1)]
        if dev.isVirtualDevice {
            let switches = dev.virtualDeviceSwitchOverVideoZoomFactors.map { $0.floatValue }
            let hasUW = dev.constituentDevices.contains { $0.deviceType == .builtInUltraWideCamera }
            buttons = Lenses.presets(switchOvers: switches, hasUltraWide: hasUW).map { LensButton(display: $0.display, factor: CGFloat($0.factor)) }
        }
        wideFactor = buttons.first { $0.display == 1 }?.factor ?? 1
        if (try? dev.lockForConfiguration()) != nil {
            dev.videoZoomFactor = min(max(wideFactor, dev.minAvailableVideoZoomFactor), dev.maxAvailableVideoZoomFactor)
            if dev.isFocusModeSupported(.continuousAutoFocus) { dev.focusMode = .continuousAutoFocus }
            if dev.isExposureModeSupported(.continuousAutoExposure) { dev.exposureMode = .continuousAutoExposure }
            dev.setExposureTargetBias(0, completionHandler: nil)
            dev.unlockForConfiguration()
        }
        let range = dev.minExposureTargetBias...dev.maxExposureTargetBias
        let shown = buttons.count > 1 ? buttons : []
        DispatchQueue.main.async {
            self.lenses = shown
            self.zoomDisplay = 1
            self.exposureBias = 0
            self.exposureRange = max(range.lowerBound, -3)...min(range.upperBound, 3)
        }
    }

    private func configureConnections() {
        if let c = videoOutput.connection(with: .video) {
            if c.isVideoOrientationSupported { c.videoOrientation = .portrait }
            if c.isVideoMirroringSupported {
                c.automaticallyAdjustsVideoMirroring = false
                c.isVideoMirrored = position == .front
            }
        }
    }

    // MARK: 모드 · 카메라 전환

    func setMode(_ m: Mode) {
        guard m != mode, !isRecording else { return }
        mode = m
        if m == .video {
            AVCaptureDevice.requestAccess(for: .audio) { ok in self.reconfigure(audio: ok) }
        } else {
            reconfigure(audio: false)
        }
    }

    private func reconfigure(audio: Bool) {
        let m = mode
        sessionQueue.async {
            self.session.beginConfiguration()
            self.session.sessionPreset = m == .photo ? .photo : (self.session.canSetSessionPreset(.hd1920x1080) ? .hd1920x1080 : .high)
            if audio, self.audioInput == nil, let mic = AVCaptureDevice.default(for: .audio),
               let input = try? AVCaptureDeviceInput(device: mic), self.session.canAddInput(input) {
                self.session.addInput(input)
                self.audioInput = input
                if self.session.canAddOutput(self.audioOutput) {
                    self.session.addOutput(self.audioOutput)
                    self.audioOutput.setSampleBufferDelegate(self, queue: self.videoQueue)
                }
            } else if !audio, let input = self.audioInput {
                self.session.removeInput(input)
                self.session.removeOutput(self.audioOutput)
                self.audioInput = nil
            }
            self.configureConnections()
            self.session.commitConfiguration()
        }
    }

    func switchCamera() {
        guard !isRecording else { return }
        let next: AVCaptureDevice.Position = position == .back ? .front : .back
        position = next
        sessionQueue.async {
            self.session.beginConfiguration()
            self.setupVideoInput(next)
            self.configureConnections()
            self.session.commitConfiguration()
        }
    }

    // MARK: 줌 · 초점 · 노출

    func selectLens(_ b: LensButton) {
        sessionQueue.async {
            guard let d = self.device, (try? d.lockForConfiguration()) != nil else { return }
            d.ramp(toVideoZoomFactor: min(max(b.factor, d.minAvailableVideoZoomFactor), d.maxAvailableVideoZoomFactor), withRate: 12)
            d.unlockForConfiguration()
            DispatchQueue.main.async { self.zoomDisplay = b.display }
        }
    }

    func pinch(_ scale: CGFloat, began: Bool) {
        sessionQueue.async {
            guard let d = self.device else { return }
            if began { self.pinchStart = d.videoZoomFactor }
            let maxZoom = min(d.maxAvailableVideoZoomFactor, self.wideFactor * 15)
            let z = min(max(self.pinchStart * scale, d.minAvailableVideoZoomFactor), maxZoom)
            guard (try? d.lockForConfiguration()) != nil else { return }
            d.cancelVideoZoomRamp()
            d.videoZoomFactor = z
            d.unlockForConfiguration()
            let shown = Float(z / self.wideFactor)
            DispatchQueue.main.async { self.zoomDisplay = shown }
        }
    }

    /// 미리보기 뷰 위 한 점 → 초점·노출. 사진 밖이면 false.
    func focus(viewPoint p: CGPoint) -> Bool {
        lock.lock(); let v = preview; let s = settings; let fs = frameSize; lock.unlock()
        guard let rect = v?.imageRect, rect.width > 0, rect.height > 0, fs.width > 0 else { return false }
        let u = (p.x - rect.minX) / rect.width, w = (p.y - rect.minY) / rect.height
        guard (0...1).contains(u), (0...1).contains(w) else { return false }
        // 프레임으로 잘린 부분 → 카메라 프레임 전체 기준
        let b = cropBox(w: Int(fs.width), h: Int(fs.height), frame: s.frame, flip: s.frameFlip, cx: s.cropX, cy: s.cropY)
        focus(at: CGPoint(x: (CGFloat(b.x) + u * CGFloat(b.w)) / fs.width, y: (CGFloat(b.y) + w * CGFloat(b.h)) / fs.height))
        return true
    }

    /// 카메라 프레임 위 한 점 (0...1, 왼쪽 위 기준, 세로 화면) → 초점·노출
    func focus(at p: CGPoint) {
        // 센서는 가로(홈 버튼 오른쪽) 기준: 세로 화면 (u, v) → (v, 1 − u). 전면은 좌우가 뒤집혀 (v, u).
        let poi = position == .front ? CGPoint(x: p.y, y: p.x) : CGPoint(x: p.y, y: 1 - p.x)
        sessionQueue.async {
            guard let d = self.device, (try? d.lockForConfiguration()) != nil else { return }
            if d.isFocusPointOfInterestSupported, d.isFocusModeSupported(.autoFocus) {
                d.focusPointOfInterest = poi; d.focusMode = .autoFocus
            }
            if d.isExposurePointOfInterestSupported, d.isExposureModeSupported(.autoExpose) {
                d.exposurePointOfInterest = poi; d.exposureMode = .autoExpose
            }
            d.isSubjectAreaChangeMonitoringEnabled = true
            d.unlockForConfiguration()
        }
    }

    func setExposureBias(_ v: Float) {
        exposureBias = v
        sessionQueue.async {
            guard let d = self.device, (try? d.lockForConfiguration()) != nil else { return }
            d.setExposureTargetBias(min(max(v, d.minExposureTargetBias), d.maxExposureTargetBias), completionHandler: nil)
            d.unlockForConfiguration()
        }
    }

    // MARK: 방향 (화면 회전을 잠가도 폰을 가로로 들면 가로 사진)

    private func startMotion() {
        guard motion.isDeviceMotionAvailable, !motion.isDeviceMotionActive else { return }
        motion.deviceMotionUpdateInterval = 0.2
        motion.startDeviceMotionUpdates(to: OperationQueue()) { [weak self] m, _ in
            guard let self, let g = m?.gravity, abs(g.z) < 0.85 else { return }   // 평평하게 두면 그대로
            let o: UIDeviceOrientation = abs(g.y) >= abs(g.x)
                ? (g.y < 0 ? .portrait : .portraitUpsideDown)
                : (g.x < 0 ? .landscapeLeft : .landscapeRight)
            self.lock.lock(); self.orientation = o; self.lock.unlock()
        }
    }

    private static func videoOrientation(_ o: UIDeviceOrientation) -> AVCaptureVideoOrientation {
        switch o {
        case .landscapeLeft: .landscapeRight
        case .landscapeRight: .landscapeLeft
        case .portraitUpsideDown: .portraitUpsideDown
        default: .portrait
        }
    }

    /// 세로로 들어오는 프레임을 폰 방향에 맞게 돌림
    private static func upright(_ img: CIImage, _ o: UIDeviceOrientation) -> CIImage {
        let angle: CGFloat
        switch o {
        case .landscapeLeft: angle = .pi / 2
        case .landscapeRight: angle = -.pi / 2
        case .portraitUpsideDown: angle = .pi
        default: return img
        }
        let r = img.transformed(by: CGAffineTransform(rotationAngle: angle))
        return r.transformed(by: CGAffineTransform(translationX: -r.extent.minX, y: -r.extent.minY))
    }

    // MARK: 사진

    func capturePhoto() {
        let (_, _, _, o) = snapshot()
        let flashMode = flash
        DispatchQueue.main.async { self.processing += 1 }
        sessionQueue.async {
            if let c = self.photoOutput.connection(with: .video), c.isVideoOrientationSupported {
                c.videoOrientation = Self.videoOrientation(o)
            }
            let ps = AVCapturePhotoSettings()
            if self.photoOutput.supportedFlashModes.contains(flashMode) { ps.flashMode = flashMode }
            ps.photoQualityPrioritization = .balanced
            self.photoOutput.capturePhoto(with: ps, delegate: self)
        }
    }

    private func finishPhoto(_ data: Data?) {
        let (s, g, wm, _) = snapshot()
        photoQueue.async {
            defer { DispatchQueue.main.async { self.processing -= 1 } }
            guard let data, let img = CIImage(data: data, options: [.applyOrientationProperty: true]) else {
                DispatchQueue.main.async { self.message = "사진을 읽지 못했어요" }
                return
            }
            let r = Renderer.shared
            let out = r.process(img, settings: s, grade: g, watermark: wm, grainScale: max(img.extent.width, img.extent.height) / 1440)
            guard let jpeg = r.jpeg(out) else { return }
            let thumb = r.uiImage(out, maxSide: 240)
            Task {
                do {
                    try await self.saveImage(jpeg)
                    await MainActor.run {
                        self.lastOriginal = img
                        self.lastThumb = thumb
                        self.onSaved?(s)
                    }
                } catch {
                    await MainActor.run { self.message = error.localizedDescription }
                }
            }
        }
    }

    // MARK: 영상 (필터를 입힌 프레임을 바로 기록)

    func toggleRecording() { isRecording ? stopRecording() : startRecording() }

    private func startRecording() {
        let (s, _, _, o) = snapshot()
        isRecording = true
        recordSeconds = 0
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.recordSeconds += 1 }
        videoQueue.async {
            self.recordOrientation = o
            self.recordSettings = s
            self.wantRecord = true
        }
    }

    func stopRecording() {
        timer?.invalidate(); timer = nil
        isRecording = false
        videoQueue.async {
            self.wantRecord = false
            guard let w = self.writer else { return }
            self.writerVideo?.markAsFinished()
            self.writerAudio?.markAsFinished()
            self.writer = nil; self.writerVideo = nil; self.writerAudio = nil; self.adaptor = nil; self.recordStart = nil
            let url = w.outputURL
            DispatchQueue.main.async { self.processing += 1 }
            w.finishWriting {
                Task {
                    do {
                        if w.status == .completed { try await PhotoSaver.save(videoURL: url) }
                        else { throw w.error ?? CocoaError(.fileWriteUnknown) }
                        await MainActor.run { self.message = "영상을 저장했어요" }
                    } catch {
                        await MainActor.run { self.message = "영상 저장 실패: \(error.localizedDescription)" }
                    }
                    try? FileManager.default.removeItem(at: url)
                    await MainActor.run { self.processing -= 1 }
                }
            }
        }
    }

    private func makeWriter(width: Int, height: Int, at time: CMTime) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("FOFilter-\(UUID().uuidString).mov")
        guard let w = try? AVAssetWriter(outputURL: url, fileType: .mov) else { return }
        let video = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width, AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: width * height * 8],
            AVVideoColorPropertiesKey: [
                AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
            ],
        ])
        video.expectsMediaDataInRealTime = true
        let ad = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: video, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
        ])
        guard w.canAdd(video) else { return }
        w.add(video)
        var audio: AVAssetWriterInput?
        if audioInput != nil {
            let a = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVNumberOfChannelsKey: 1, AVSampleRateKey: 44_100, AVEncoderBitRateKey: 128_000,
            ])
            a.expectsMediaDataInRealTime = true
            if w.canAdd(a) { w.add(a); audio = a }
        }
        guard w.startWriting() else { return }
        w.startSession(atSourceTime: time)
        writer = w; writerVideo = video; writerAudio = audio; adaptor = ad; recordStart = time
    }

    private func appendVideo(_ image: CIImage, at time: CMTime) {
        if writer == nil {
            makeWriter(width: Int(image.extent.width) & ~1, height: Int(image.extent.height) & ~1, at: time)
        }
        guard let ad = adaptor, let input = writerVideo, input.isReadyForMoreMediaData, let pool = ad.pixelBufferPool else { return }
        var pb: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pb)
        guard let pb else { return }
        let r = Renderer.shared
        r.context.render(image, to: pb, bounds: CGRect(x: 0, y: 0, width: CVPixelBufferGetWidth(pb), height: CVPixelBufferGetHeight(pb)), colorSpace: r.srgb)
        ad.append(pb, withPresentationTime: time)
    }
}

extension CameraController: AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if output === audioOutput {
            if let a = writerAudio, let start = recordStart, a.isReadyForMoreMediaData,
               CMTimeCompare(CMSampleBufferGetPresentationTimeStamp(sampleBuffer), start) >= 0 {
                a.append(sampleBuffer)
            }
            return
        }
        guard let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let (s, g, wm, _) = snapshot()
        frameIndex &+= 1
        let base = CIImage(cvPixelBuffer: pb)
        let r = Renderer.shared
        lock.lock(); frameSize = base.extent.size; lock.unlock()

        if wantRecord {
            let rs = recordSettings
            let img = r.process(Self.upright(base, recordOrientation), settings: rs, grade: g, watermark: wm,
                                grainScale: 1, seed: frameIndex)
            appendVideo(img, at: CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
        }

        lock.lock(); let view = preview; lock.unlock()
        // 미리보기: 보이는 그대로 찍히게 프레임·색·워터마크까지
        view?.display(r.process(base, settings: s, grade: g, watermark: wm, seed: frameIndex))
    }
}

extension CameraController: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error {
            DispatchQueue.main.async { self.message = "촬영 실패: \(error.localizedDescription)"; self.processing -= 1 }
            return
        }
        finishPhoto(photo.fileDataRepresentation())
    }
}
