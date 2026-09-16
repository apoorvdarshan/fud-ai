import ExpoModulesCore
import Foundation
import WatchConnectivity
import WidgetKit

public class CompanionSnapshotModule: Module {
  private static let productionAppGroupID = "group.com.apoorvdarshan.calorietracker"
  private static let debugAppGroupID = "group.com.apoorvdarshan.calorietracker.debug"
  private static let key = "widget_snapshot_v1"
  private static let fileName = "widget_snapshot_v1.json"

  public func definition() -> ModuleDefinition {
    Name("CompanionSnapshot")

    Constants([
      "isAvailable": true
    ])

    AsyncFunction("write") { (json: String) in
      guard let data = json.data(using: .utf8) else { return }
      Self.persist(data)
      if #available(iOS 14.0, *) {
        WidgetCenter.shared.reloadAllTimelines()
      }
      Self.sendToWatch(data)
    }

    AsyncFunction("clear") {
      Self.sharedDefaults?.removeObject(forKey: Self.key)
      if let fileURL = Self.snapshotFileURL {
        try? FileManager.default.removeItem(at: fileURL)
      }
      if #available(iOS 14.0, *) {
        WidgetCenter.shared.reloadAllTimelines()
      }
    }
  }

  private static var appGroupID: String {
    if let configured = Bundle.main.object(forInfoDictionaryKey: "AppGroupIdentifier") as? String,
       !configured.isEmpty,
       !configured.contains("$(") {
      return configured
    }
    return Bundle.main.bundleIdentifier?.contains(".debug") == true
      ? debugAppGroupID
      : productionAppGroupID
  }

  private static var sharedDefaults: UserDefaults? {
    UserDefaults(suiteName: appGroupID)
  }

  private static var snapshotDirectoryURL: URL? {
    FileManager.default
      .containerURL(forSecurityApplicationGroupIdentifier: appGroupID)?
      .appendingPathComponent("Library/Application Support/FudAIWidgets", isDirectory: true)
  }

  private static var snapshotFileURL: URL? {
    snapshotDirectoryURL?.appendingPathComponent(fileName, isDirectory: false)
  }

  private static func persist(_ data: Data) {
    if let directoryURL = snapshotDirectoryURL, let fileURL = snapshotFileURL {
      try? FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
      try? data.write(to: fileURL, options: [.atomic])
    }
    sharedDefaults?.set(data, forKey: key)
  }

  private static func sendToWatch(_ data: Data) {
    guard WCSession.isSupported() else { return }
    let session = WCSession.default
    if session.delegate == nil {
      session.delegate = WatchRelay.shared
    }
    WatchRelay.shared.enqueue(data)
    if session.activationState != .activated {
      session.activate()
    } else {
      WatchRelay.shared.flush()
    }
  }
}

private final class WatchRelay: NSObject, WCSessionDelegate {
  static let shared = WatchRelay()
  private static let watchPayloadKey = "widget_snapshot_data_v1"
  private var pending: Data?

  func enqueue(_ data: Data) {
    pending = data
  }

  func flush() {
    let session = WCSession.default
    guard session.activationState == .activated, let data = pending else { return }
    pending = nil
    let context = [Self.watchPayloadKey: data]
    try? session.updateApplicationContext(context)
    if session.isReachable {
      session.sendMessage(context, replyHandler: nil, errorHandler: nil)
    }
  }

  func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
    if activationState == .activated {
      flush()
    }
  }

  func sessionDidBecomeInactive(_ session: WCSession) {}

  func sessionDidDeactivate(_ session: WCSession) {
    session.activate()
  }
}
