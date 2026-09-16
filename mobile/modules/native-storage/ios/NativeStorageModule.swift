import ExpoModulesCore
import Foundation

/// Read-only bridge over `UserDefaults.standard` — the same store the native iOS app writes.
/// Never deletes or overwrites keys; Expo hydrates AsyncStorage from this snapshot once.
public class NativeStorageModule: Module {
  public func definition() -> ModuleDefinition {
    Name("NativeStorage")

    Constants([
      "isAvailable": true,
      "platform": "ios",
    ])

    AsyncFunction("readSnapshot") { () -> [String: Any] in
      let defaults = UserDefaults.standard
      var blobs: [String: String] = [:]
      var prefs: [String: Any] = [:]

      for key in Self.blobKeys {
        if let string = Self.stringValue(defaults, key: key) {
          blobs[key] = string
        }
      }

      for key in Self.prefKeys {
        guard defaults.object(forKey: key) != nil else { continue }
        if let value = Self.jsPref(defaults.object(forKey: key)) {
          prefs[key] = value
        }
      }

      return [
        "available": true,
        "platform": "ios",
        "blobs": blobs,
        "prefs": prefs,
        "foodImagesDirectory": Self.foodImagesDirectoryPath() as Any,
      ]
    }

    AsyncFunction("copyFoodImages") { (destination: String) -> Int in
      Self.copyFoodImages(to: destination)
    }
  }

  /// JSON blobs persisted as `Data` (PersistedBlobGuard) or, rarely, as a UTF-8 string.
  private static let blobKeys: [String] = [
    "foodEntries",
    "favoriteFoodEntries",
    "favorites",
    "waterEntries",
    "fastingSessions",
    "weightEntries",
    "bodyFatEntries",
    "userProfile",
    "coachChatHistory",
    "fudai.workouts.diary.state.v1",
    "workoutDiaryStateV1",
    "adaptiveGoalsPreviousTargets",
  ]

  /// Property names match `Preferences` / iOS `@AppStorage` keys, plus Android aliases
  /// so one snapshot shape works on both platforms.
  private static let prefKeys: [String] = [
    "hasCompletedOnboarding",
    "appearanceMode",
    "appThemeColor",
    "heightUnit",
    "weightUnit",
    "useMetric",
    "weekStartsOnMonday",
    "homeTopNutrients",
    "foodLogSortOrder",
    "waterTrackingEnabled",
    "waterDailyGoalMl",
    "waterUnit",
    "waterReminderEnabled",
    "waterReminderHour",
    "waterReminderMinute",
    "fastingTrackingEnabled",
    "fastingDefaultGoalMinutes",
    "fastingGoalNotificationEnabled",
    "notificationsEnabled",
    "breakfastReminderEnabled",
    "breakfastReminderHour",
    "breakfastReminderMinute",
    "lunchReminderEnabled",
    "lunchReminderHour",
    "lunchReminderMinute",
    "dinnerReminderEnabled",
    "dinnerReminderHour",
    "dinnerReminderMinute",
    "healthKitEnabled",
    "healthConnectEnabled",
    "adaptiveGoalsEnabled",
    "adaptiveGoalsPreviousTargets",
    "adaptiveGoalsLastCheckDay",
    "selectedSpeechProvider",
    "onboardingPlanEdited",
    "aiAccessMode",
    "aiConsentGiven",
    "aiAnalysisConsentGiven",
    "acceptedTermsAndPrivacy",
    "selectedAIProvider",
    "selectedAIModel",
    "separateTextProviderEnabled",
    "selectedTextAIProvider",
    "selectedTextAIModel",
    "aiUserContext",
    "userContext",
    "aiFallbackEnabled",
    "selectedFallbackAIProvider",
    "selectedFallbackAIModel",
    "aiMaxResponseTokens",
    "maxResponseTokens",
    "aiRequestTimeoutSeconds",
  ]

  private static func stringValue(_ defaults: UserDefaults, key: String) -> String? {
    if let data = defaults.data(forKey: key), let string = String(data: data, encoding: .utf8), !string.isEmpty {
      return string
    }
    if let string = defaults.string(forKey: key), !string.isEmpty {
      return string
    }
    return nil
  }

  private static func jsPref(_ value: Any?) -> Any? {
    switch value {
    case let flag as Bool:
      return flag
    case let number as Int:
      return number
    case let number as Double:
      return number
    case let number as Float:
      return Double(number)
    case let string as String:
      return string
    case let data as Data:
      return String(data: data, encoding: .utf8)
    case let number as NSNumber:
      if CFGetTypeID(number) == CFBooleanGetTypeID() {
        return number.boolValue
      }
      if CFNumberIsFloatType(number) {
        return number.doubleValue
      }
      return number.intValue
    default:
      return nil
    }
  }

  /// Native `FoodImageStore` writes JPEGs under Application Support, not Documents.
  private static func foodImagesDirectoryPath() -> String? {
    foodImagesSourceDirectories().first?.path
  }

  private static func fileURL(_ path: String) -> URL {
    if path.hasPrefix("file:"), let url = URL(string: path) {
      return url
    }
    return URL(fileURLWithPath: path)
  }

  private static func foodImagesSourceDirectories() -> [URL] {
    var urls: [URL] = []
    if let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
      urls.append(support.appendingPathComponent("fudai-food-images", isDirectory: true))
    }
    if let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
      urls.append(documents.appendingPathComponent("fudai-food-images", isDirectory: true))
    }
    return urls
  }

  private static func copyFoodImages(to destination: String) -> Int {
    let dest = fileURL(destination)
    try? FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
    var copied = 0
    for source in foodImagesSourceDirectories() {
      copied += copyJPEGs(from: source, to: dest)
    }
    return copied
  }

  private static func copyJPEGs(from source: URL, to dest: URL) -> Int {
    let fm = FileManager.default
    guard fm.fileExists(atPath: source.path),
          let items = try? fm.contentsOfDirectory(at: source, includingPropertiesForKeys: nil) else {
      return 0
    }
    if (try? source.resourceValues(forKeys: [.canonicalPathKey]).canonicalPath)
        == (try? dest.resourceValues(forKeys: [.canonicalPathKey]).canonicalPath) {
      return items.filter { isImage($0) }.count
    }
    var copied = 0
    for file in items where isImage(file) {
      let target = dest.appendingPathComponent(file.lastPathComponent)
      if fm.fileExists(atPath: target.path) {
        copied += 1
        continue
      }
      do {
        try fm.copyItem(at: file, to: target)
        copied += 1
      } catch {
        continue
      }
    }
    return copied
  }

  private static func isImage(_ url: URL) -> Bool {
    ["jpg", "jpeg", "png", "webp"].contains(url.pathExtension.lowercased())
  }
}
