import Combine
import Foundation
import WatchConnectivity
import WatchKit
import WidgetKit

final class WatchSnapshotReceiver: NSObject, ObservableObject, WCSessionDelegate {
    @Published private(set) var snapshot: WidgetSnapshot = WidgetSnapshot.read() ?? .empty
    private var pendingWaterPayloads: [[String: Any]] = []

    override init() {
        super.init()
        #if targetEnvironment(simulator)
        // Simulator has no paired iPhone sync — always seed placeholder nutrition
        // (calories / macros / water ON) so the full Watch UI can be reviewed.
        WidgetSnapshot.write(.placeholder)
        snapshot = .placeholder
        #endif
        activate()
    }

    func activate() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        session.activate()
        receive(context: session.receivedApplicationContext)
    }

    func refreshFromDisk() {
        snapshot = WidgetSnapshot.read() ?? .empty
    }

    func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        guard activationState == .activated else { return }
        receive(context: session.receivedApplicationContext)
        let pending = pendingWaterPayloads
        pendingWaterPayloads.removeAll()
        pending.forEach { deliverWaterLog($0, through: session) }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        receive(context: applicationContext)
    }

    func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        receive(context: userInfo)
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        receive(context: message)
    }

    func logWater(milliliters: Int) {
        guard WatchWaterMessage.allowedAmounts.contains(milliliters), snapshot.waterIsEnabled else {
            return
        }

        let payload: [String: Any] = [
            WatchWaterMessage.actionKey: WatchWaterMessage.actionValue,
            WatchWaterMessage.requestIDKey: UUID().uuidString,
            WatchWaterMessage.millilitersKey: milliliters,
            WatchWaterMessage.dateKey: Date(),
        ]

        applyOptimisticWater(milliliters)
        WKInterfaceDevice.current().play(.click)

        let session = WCSession.default
        guard session.activationState == .activated else {
            pendingWaterPayloads.append(payload)
            session.activate()
            return
        }
        deliverWaterLog(payload, through: session)
    }

    private func deliverWaterLog(_ payload: [String: Any], through session: WCSession) {
        guard session.isReachable else {
            session.transferUserInfo(payload)
            return
        }

        session.sendMessage(payload) { [weak self] reply in
            DispatchQueue.main.async {
                self?.handleWaterReply(reply, payload: payload)
            }
        } errorHandler: { [weak self] _ in
            session.transferUserInfo(payload)
            DispatchQueue.main.async {
                self?.playQueuedHaptic()
            }
        }
    }

    private func handleWaterReply(_ reply: [String: Any], payload: [String: Any]) {
        let result = reply[WatchWaterMessage.resultKey] as? String
        switch result {
        case "added", "duplicate":
            WKInterfaceDevice.current().play(.success)
        case "disabled", "invalid":
            if let milliliters = payload[WatchWaterMessage.millilitersKey] as? Int {
                applyOptimisticWater(-milliliters)
            }
            WKInterfaceDevice.current().play(.failure)
        default:
            playQueuedHaptic()
        }
    }

    private func playQueuedHaptic() {
        WKInterfaceDevice.current().play(.directionUp)
    }

    private func applyOptimisticWater(_ delta: Int) {
        var updated = snapshot
        updated.waterCurrentMl = max(0, snapshot.waterCurrent + delta)
        WidgetSnapshot.write(updated)
        snapshot = updated
        WidgetCenter.shared.reloadAllTimelines()
    }

    private func receive(context: [String: Any]) {
        guard let data = context[WidgetSnapshot.watchPayloadKey] as? Data,
              let incomingSnapshot = WidgetSnapshot.decodePayload(data)
        else { return }

        let normalizedSnapshot = incomingSnapshot.normalizedForToday()

        DispatchQueue.main.async {
            WidgetSnapshot.write(normalizedSnapshot)
            self.snapshot = normalizedSnapshot
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}

private enum WatchWaterMessage {
    static let actionKey = "watch_action"
    static let actionValue = "log_water"
    static let requestIDKey = "watch_water_request_id"
    static let millilitersKey = "watch_water_milliliters"
    static let dateKey = "watch_water_date"
    static let resultKey = "watch_water_result"
    static let allowedAmounts = Set([250, 500, 750])
}
