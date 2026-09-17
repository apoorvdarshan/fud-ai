import ExpoModulesCore

/// iOS-only bridge for system `UIMenu` attached to a view. Android uses the shared JS
/// anchored dropdown (`AnchoredMenu`) so layout stays the same without a Material menu.
public class NativeMenuModule: Module {
  public func definition() -> ModuleDefinition {
    Name("NativeMenu")

    Constants([
      "isNativeMenuAvailable": true
    ])

    View(NativeMenuView.self) {
      Events("onSelect", "onPress")

      Prop("itemsJson") { (view: NativeMenuView, json: String) in
        view.itemsJson = json
      }

      Prop("trigger") { (view: NativeMenuView, trigger: String) in
        view.trigger = trigger
      }

      Prop("accessibilityLabel") { (view: NativeMenuView, label: String?) in
        view.accessibilityTitle = label
      }

      Prop("testID") { (view: NativeMenuView, testID: String?) in
        view.testIdentifier = testID
      }
    }
  }
}
