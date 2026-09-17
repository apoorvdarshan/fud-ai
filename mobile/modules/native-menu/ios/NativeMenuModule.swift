import ExpoModulesCore

/// Bridge for a system menu attached to a view. iOS uses `UIButton.menu` / `UIMenu`.
/// Android’s sibling module presents an anchored `PopupMenu` on the same view.
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

      Prop("disabled") { (view: NativeMenuView, disabled: Bool) in
        view.isMenuDisabled = disabled
      }
    }
  }
}
