import CoreImage
import Metal
import QuartzCore
import SwiftUI
import UIKit

/// 카메라 프레임(이미 색을 입힌 CIImage)을 Metal 로 바로 그리는 뷰. `display(_:)` 는 카메라 스레드에서 부릅니다.
final class PreviewMetalView: UIView {
    override class var layerClass: AnyClass { CAMetalLayer.self }
    /// 카메라 스레드에서도 쓰므로 UIView.layer 대신 들고 있음
    private var metalLayer: CAMetalLayer!

    private let lock = NSLock()
    private var drawableSize: CGSize = .zero
    private var screenScale: CGFloat = 2
    private var drawnRect: CGRect = .zero   // 픽셀

    override init(frame: CGRect) {
        super.init(frame: frame)
        metalLayer = (layer as! CAMetalLayer)
        backgroundColor = .black
        isOpaque = true
        metalLayer.device = Renderer.shared.device
        metalLayer.pixelFormat = .bgra8Unorm
        metalLayer.framebufferOnly = false
        metalLayer.isOpaque = true
        metalLayer.colorspace = CGColorSpace(name: CGColorSpace.sRGB)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let scale = window?.screen.scale ?? UIScreen.main.scale
        metalLayer.contentsScale = scale
        let size = CGSize(width: (bounds.width * scale).rounded(), height: (bounds.height * scale).rounded())
        metalLayer.drawableSize = size
        lock.lock(); drawableSize = size; screenScale = scale; lock.unlock()
    }

    /// 사진이 그려진 영역 (뷰 좌표, 포인트)
    var imageRect: CGRect {
        lock.lock(); defer { lock.unlock() }
        let s = screenScale
        return CGRect(x: drawnRect.minX / s, y: drawnRect.minY / s, width: drawnRect.width / s, height: drawnRect.height / s)
    }

    func display(_ image: CIImage) {
        lock.lock(); let size = drawableSize; lock.unlock()
        guard size.width > 0, let queue = Renderer.shared.queue,
              let drawable = metalLayer.nextDrawable(), let cb = queue.makeCommandBuffer() else { return }
        let rect = Renderer.shared.renderFit(image, size: size, commandBuffer: cb, pixelFormat: metalLayer.pixelFormat) { drawable.texture }
        cb.present(drawable)
        cb.commit()
        lock.lock(); drawnRect = rect; lock.unlock()
    }
}

struct CameraPreview: UIViewRepresentable {
    let camera: CameraController

    func makeUIView(context: Context) -> PreviewMetalView {
        let v = PreviewMetalView(frame: .zero)
        camera.attach(preview: v)
        return v
    }

    func updateUIView(_ uiView: PreviewMetalView, context: Context) {}
}
