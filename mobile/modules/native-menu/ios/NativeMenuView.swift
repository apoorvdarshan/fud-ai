import ExpoModulesCore
import UIKit

/// Transparent `UIButton` over React children. `showsMenuAsPrimaryAction` presents the system
/// `UIMenu` on tap (Home +). With that off, a long-press shows the menu and tap forwards `onPress`.
final class NativeMenuView: ExpoView {
  let onSelect = EventDispatcher()
  let onPress = EventDispatcher()

  private let button = UIButton(type: .system)

  var itemsJson = "[]" {
    didSet { rebuildMenu() }
  }

  var trigger = "press" {
    didSet { applyTrigger() }
  }

  var accessibilityTitle: String? {
    didSet { button.accessibilityLabel = accessibilityTitle }
  }

  var testIdentifier: String? {
    didSet { button.accessibilityIdentifier = testIdentifier }
  }

  var isMenuDisabled = false {
    didSet { applyTrigger() }
  }

  required init(appContext: AppContext? = nil) {
    super.init(appContext: appContext)
    clipsToBounds = false
    button.backgroundColor = .clear
    button.tintColor = .clear
    if #available(iOS 16.0, *) {
      button.preferredMenuElementOrder = .fixed
    }
    button.addTarget(self, action: #selector(handleTap), for: .touchUpInside)
    addSubview(button)
    applyTrigger()
    rebuildMenu()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    button.frame = bounds
    bringSubviewToFront(button)
  }

  @objc private func handleTap() {
    // Primary-action menus consume the tap. Long-press menus still deliver touchUpInside.
    if trigger != "press" {
      onPress([:])
    }
  }

  private func applyTrigger() {
    let hasMenu = button.menu != nil
    button.showsMenuAsPrimaryAction = trigger == "press" && hasMenu && !isMenuDisabled
    button.isUserInteractionEnabled = !isMenuDisabled
    button.isEnabled = !isMenuDisabled
  }

  private func rebuildMenu() {
    let items = decodeItems(itemsJson)
    if items.isEmpty {
      button.menu = nil
      applyTrigger()
      return
    }
    let children = items.map { makeElement($0) }
    button.menu = UIMenu(title: "", children: children)
    applyTrigger()
  }

  private func makeElement(_ item: MenuItemDTO) -> UIMenuElement {
    if let children = item.children, !children.isEmpty {
      var options: UIMenu.Options = []
      if item.displayInline == true {
        options.insert(.displayInline)
      }
      return UIMenu(
        title: item.title,
        image: item.systemImage.flatMap { UIImage(systemName: $0) },
        identifier: nil,
        options: options,
        children: children.map { makeElement($0) }
      )
    }

    var attributes: UIMenuElement.Attributes = []
    if item.destructive == true { attributes.insert(.destructive) }
    if item.disabled == true { attributes.insert(.disabled) }
    let id = item.id
    return UIAction(
      title: item.title,
      image: item.systemImage.flatMap { UIImage(systemName: $0) },
      attributes: attributes
    ) { [weak self] _ in
      self?.onSelect(["id": id])
    }
  }

  private func decodeItems(_ json: String) -> [MenuItemDTO] {
    guard let data = json.data(using: .utf8),
          let items = try? JSONDecoder().decode([MenuItemDTO].self, from: data)
    else {
      return []
    }
    return items
  }
}

private struct MenuItemDTO: Decodable {
  let id: String
  let title: String
  let systemImage: String?
  let destructive: Bool?
  let disabled: Bool?
  let displayInline: Bool?
  let children: [MenuItemDTO]?
}
