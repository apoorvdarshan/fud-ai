package expo.modules.nativemenu

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class NativeMenuModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("NativeMenu")

    Constants {
      mapOf("isNativeMenuAvailable" to true)
    }

    View(NativeMenuView::class) {
      Events("onSelect", "onPress")

      Prop("itemsJson") { view: NativeMenuView, json: String ->
        view.itemsJson = json
      }

      Prop("trigger") { view: NativeMenuView, trigger: String ->
        view.trigger = trigger
      }

      Prop("accessibilityLabel") { view: NativeMenuView, label: String? ->
        view.contentDescription = label
      }

      Prop("testID") { view: NativeMenuView, testID: String? ->
        view.tag = testID
      }

      Prop("disabled") { view: NativeMenuView, disabled: Boolean ->
        view.isMenuDisabled = disabled
      }
    }
  }
}
