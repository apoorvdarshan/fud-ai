import SwiftUI
import UIKit

/// The assistant lives in its own window above the app, so its bubble and
/// captions stay visible while the app navigates and over sheets such as a food
/// entry it opened. Touches outside the assistant's own controls pass through to
/// the app underneath.
final class VoiceAssistantWindow: UIWindow {
    /// Where the assistant has controls, in window coordinates (compact mode).
    var interactiveRegions: [CGRect] = []
    /// Expanded mode and the access screens cover the app.
    var capturesAllTouches = false

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard !isHidden else { return nil }
        let presenting = rootViewController?.presentedViewController != nil
        guard presenting || capturesAllTouches || interactiveRegions.contains(where: { $0.contains(point) }) else {
            return nil
        }
        return super.hitTest(point, with: event)
    }
}

/// Lets the dark expanded overlay use a light status bar.
final class VoiceAssistantHostingController: UIHostingController<AnyView> {
    var statusBarStyle: UIStatusBarStyle = .default {
        didSet {
            guard statusBarStyle != oldValue else { return }
            setNeedsStatusBarAppearanceUpdate()
        }
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { statusBarStyle }
}

@Observable
final class VoiceAssistantWindowActivity {
    var isActive = false
}

/// Creates the assistant's window once, and shows it only while there is
/// something to show.
@MainActor
final class VoiceAssistantWindowManager {
    static let shared = VoiceAssistantWindowManager()

    private var window: VoiceAssistantWindow?
    private var hosting: VoiceAssistantHostingController?
    private let activity = VoiceAssistantWindowActivity()

    func install(in scene: UIWindowScene, controller: VoiceAssistantController) {
        guard window == nil else { return }
        let window = VoiceAssistantWindow(windowScene: scene)
        // Above the app's window, below system alerts and the keyboard.
        window.windowLevel = UIWindow.Level(UIWindow.Level.normal.rawValue + 1)
        window.backgroundColor = .clear
        let overlay = VoiceAssistantOverlay(controller: controller, windowActivity: activity) { [weak window] regions, capturesAll in
            window?.interactiveRegions = regions
            window?.capturesAllTouches = capturesAll
        } onStatusBarStyle: { [weak self] style in
            self?.hosting?.statusBarStyle = style
        }
        let hosting = VoiceAssistantHostingController(rootView: AnyView(overlay))
        hosting.view.backgroundColor = .clear
        window.rootViewController = hosting
        window.isHidden = true
        self.window = window
        self.hosting = hosting
        setVisible(controller.presentation != .hidden)
    }

    func setVisible(_ visible: Bool) {
        guard let window, window.isHidden == visible else { return }
        window.isHidden = !visible
    }

    // The non-key hosting window does not receive SwiftUI's scene phase reliably.
    // Use the lifecycle of the main scene that owns the overlay.
    func setSceneActive(_ isActive: Bool) {
        guard activity.isActive != isActive else { return }
        activity.isActive = isActive
    }
}

/// Placed behind ContentView: finds the app's window scene and installs the
/// assistant's window in it.
struct VoiceAssistantWindowInstaller: UIViewRepresentable {
    let controller: VoiceAssistantController

    func makeUIView(context: Context) -> InstallerView {
        let view = InstallerView()
        view.controller = controller
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ uiView: InstallerView, context: Context) {}

    final class InstallerView: UIView {
        weak var controller: VoiceAssistantController?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let scene = window?.windowScene, let controller else { return }
            VoiceAssistantWindowManager.shared.install(in: scene, controller: controller)
        }
    }
}

// MARK: - Interactive regions

struct VoiceAssistantRegionsKey: PreferenceKey {
    static let defaultValue: [CGRect] = []

    static func reduce(value: inout [CGRect], nextValue: () -> [CGRect]) {
        value += nextValue()
    }
}

extension View {
    /// Marks a control the user can touch while the assistant is compact; touches
    /// anywhere else reach the app.
    func voiceAssistantInteractive() -> some View {
        background(
            GeometryReader { proxy in
                Color.clear.preference(key: VoiceAssistantRegionsKey.self, value: [proxy.frame(in: .global)])
            }
        )
    }
}
