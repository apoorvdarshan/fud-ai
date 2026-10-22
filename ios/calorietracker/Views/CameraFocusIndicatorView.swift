import UIKit

/// The yellow square the system camera shows where you tap to focus. It shrinks into
/// place, dims, and fades away; the camera keeps focusing there until the scene changes.
final class CameraFocusIndicatorView: UIView {
    static let side: CGFloat = 76

    private let frameLayer = CAShapeLayer()
    private var showCount = 0

    init() {
        super.init(frame: CGRect(x: 0, y: 0, width: Self.side, height: Self.side))
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        accessibilityElementsHidden = true
        alpha = 0
        frameLayer.fillColor = UIColor.clear.cgColor
        frameLayer.strokeColor = UIColor.systemYellow.cgColor
        frameLayer.lineWidth = 1.5
        layer.addSublayer(frameLayer)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        frameLayer.frame = bounds
        let square = bounds.insetBy(dx: frameLayer.lineWidth / 2, dy: frameLayer.lineWidth / 2)
        let path = UIBezierPath(rect: square)
        // Short ticks pointing in from the middle of each side.
        let tick: CGFloat = 7
        path.move(to: CGPoint(x: square.midX, y: square.minY))
        path.addLine(to: CGPoint(x: square.midX, y: square.minY + tick))
        path.move(to: CGPoint(x: square.midX, y: square.maxY))
        path.addLine(to: CGPoint(x: square.midX, y: square.maxY - tick))
        path.move(to: CGPoint(x: square.minX, y: square.midY))
        path.addLine(to: CGPoint(x: square.minX + tick, y: square.midY))
        path.move(to: CGPoint(x: square.maxX, y: square.midY))
        path.addLine(to: CGPoint(x: square.maxX - tick, y: square.midY))
        frameLayer.path = path.cgPath
    }

    /// Shows the square centred on `point`, in the superview's coordinates.
    func show(at point: CGPoint) {
        showCount += 1
        let count = showCount
        layer.removeAllAnimations()
        center = point
        alpha = 1
        if UIAccessibility.isReduceMotionEnabled {
            transform = .identity
        } else {
            transform = CGAffineTransform(scaleX: 1.4, y: 1.4)
            UIView.animate(withDuration: 0.25, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
                self.transform = .identity
            }
        }
        UIView.animate(withDuration: 0.3, delay: 0.8, options: [.allowUserInteraction]) {
            self.alpha = 0.55
        } completion: { [weak self] finished in
            guard finished, let self, count == self.showCount else { return }
            UIView.animate(withDuration: 0.4, delay: 1.2, options: [.allowUserInteraction]) {
                self.alpha = 0
            }
        }
    }

    func hide() {
        showCount += 1
        layer.removeAllAnimations()
        alpha = 0
        transform = .identity
    }
}
