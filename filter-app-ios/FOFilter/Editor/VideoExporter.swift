import AVFoundation
import CoreImage
import UIKit

/// 영상에 필터 입히기: 프레임마다 자르기 → LUT → 비네팅·그레인 → 워터마크 (Core Image, GPU)
enum VideoExporter {
    enum ExportError: LocalizedError {
        case noVideo, cannotExport, failed(String)
        var errorDescription: String? {
            switch self {
            case .noVideo: "영상 트랙이 없어요"
            case .cannotExport: "이 영상은 내보낼 수 없어요"
            case .failed(let m): m
            }
        }
    }

    /// 영상 방향을 반영한 크기
    static func displaySize(of url: URL) async throws -> CGSize {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw ExportError.noVideo }
        let (natural, t) = try await track.load(.naturalSize, .preferredTransform)
        let r = CGRect(origin: .zero, size: natural).applying(t)
        return CGSize(width: abs(r.width), height: abs(r.height))
    }

    /// 편집 화면에서 쓸 한 장면 (방향 반영)
    static func frame(of url: URL) async throws -> CIImage {
        let asset = AVURLAsset(url: url)
        let gen = AVAssetImageGenerator(asset: asset)
        gen.appliesPreferredTrackTransform = true
        gen.maximumSize = CGSize(width: 1600, height: 1600)
        let duration = try await asset.load(.duration)
        let t = CMTime(seconds: min(1, max(0, duration.seconds / 3)), preferredTimescale: 600)
        let (cg, _) = try await gen.image(at: t)
        return CIImage(cgImage: cg)
    }

    static func export(url: URL, settings: Settings, grade: PreparedGrade, watermark: Watermark,
                       progress: @escaping (Float) -> Void) async throws -> URL {
        let asset = AVURLAsset(url: url)
        let size = try await displaySize(of: url)
        let box = cropBox(w: Int(size.width), h: Int(size.height), frame: settings.frame, flip: settings.frameFlip,
                          cx: settings.cropX, cy: settings.cropY)
        let out = CGSize(width: box.w & ~1, height: box.h & ~1)
        let grainScale = max(size.width, size.height) / 1440

        let comp = AVMutableVideoComposition(asset: asset) { req in
            let frameNo = Int(req.compositionTime.seconds * 30)
            let img = Renderer.shared.process(req.sourceImage, settings: settings, grade: grade, watermark: watermark,
                                              grainScale: grainScale, seed: frameNo)
            req.finish(with: img.cropped(to: CGRect(origin: .zero, size: out)), context: nil)
        }
        comp.renderSize = out
        // HDR 영상도 SDR(BT.709)로
        comp.colorPrimaries = AVVideoColorPrimaries_ITU_R_709_2
        comp.colorTransferFunction = AVVideoTransferFunction_ITU_R_709_2
        comp.colorYCbCrMatrix = AVVideoYCbCrMatrix_ITU_R_709_2

        guard let ex = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetHighestQuality) else { throw ExportError.cannotExport }
        let dst = FileManager.default.temporaryDirectory.appendingPathComponent("FOFilter-\(UUID().uuidString).mov")
        ex.outputURL = dst
        ex.outputFileType = .mov
        ex.videoComposition = comp

        let poll = Task {
            while !Task.isCancelled {
                progress(ex.progress)
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
        }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in ex.exportAsynchronously { c.resume() } }
        poll.cancel()
        guard ex.status == .completed else {
            throw ExportError.failed(ex.error?.localizedDescription ?? "내보내기 실패")
        }
        progress(1)
        return dst
    }
}
