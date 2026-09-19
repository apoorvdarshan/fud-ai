import AVFoundation

/// Picks a back camera that can auto-switch lenses for close-up barcode scanning (macro on Pro iPhones).
enum BarcodeScannerCameraSelection {
    /// Highest priority first: virtual multi-cameras before the standalone wide lens.
    static let preferredBackVideoDeviceTypes: [AVCaptureDevice.DeviceType] = [
        .builtInTripleCamera,
        .builtInDualWideCamera,
        .builtInDualCamera,
        .builtInWideAngleCamera
    ]

    static func preferredBackVideoCaptureDevice() -> AVCaptureDevice? {
        for deviceType in preferredBackVideoDeviceTypes {
            if let device = AVCaptureDevice.default(deviceType, for: .video, position: .back) {
                return device
            }
        }

        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: preferredBackVideoDeviceTypes,
            mediaType: .video,
            position: .back
        )
        return discovery.devices.first
    }
}
