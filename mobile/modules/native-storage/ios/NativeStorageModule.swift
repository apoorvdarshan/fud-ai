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
    guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
      return nil
    }
    let url = base.appendingPathComponent("fudai-food-images", isDirectory: true)
    return FileManager.default.fileExists(atPath: url.path) ? url.path : url.path
  }
}
