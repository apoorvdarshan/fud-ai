package expo.modules.nativemenu

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transparent overlay on React children. A press-trigger tap (Home + / FAB) or a long-press
 * trigger shows an anchored [PopupMenu] — the same chrome as native `SheetGlassDropdownMenu`,
 * not a modal bottom sheet.
 */
class NativeMenuView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  val onSelect by EventDispatcher()
  val onPress by EventDispatcher()

  var itemsJson: String = "[]"
  var trigger: String = "press"
  var isMenuDisabled: Boolean = false
    set(value) {
      field = value
      overlay.isClickable = !value
      overlay.isEnabled = !value
    }

  private val overlay = View(context).apply {
    isClickable = true
    isFocusable = true
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    setOnClickListener { handleClick() }
    setOnLongClickListener { handleLongClick() }
  }

  init {
    clipChildren = false
    clipToPadding = false
    addView(overlay, LayoutParams(0, 0))
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    overlay.layout(0, 0, width, height)
    overlay.bringToFront()
  }

  private fun handleClick() {
    if (isMenuDisabled) return
    if (trigger == "press") {
      showMenu()
    } else {
      onPress(emptyMap())
    }
  }

  private fun handleLongClick(): Boolean {
    if (isMenuDisabled) return false
    if (trigger != "press") {
      showMenu()
      return true
    }
    return false
  }

  @SuppressLint("RtlHardcoded")
  private fun showMenu() {
    if (isMenuDisabled) return
    val items = decodeItems(itemsJson)
    if (items.isEmpty()) return

    val popup = PopupMenu(context, this, Gravity.END)
    val actionIds = LinkedHashMap<Int, String>()
    var nextId = 1
    fun addItems(menu: Menu, nodes: List<MenuItemDTO>) {
      for (item in nodes) {
        val children = item.children
        if (!children.isNullOrEmpty()) {
          if (item.disabled == true) continue
          if (item.displayInline == true) {
            addItems(menu, children)
          } else {
            addItems(menu.addSubMenu(item.title), children)
          }
          continue
        }
        val id = nextId++
        actionIds[id] = item.id
        val menuItem = menu.add(Menu.NONE, id, Menu.NONE, item.title)
        menuItem.isEnabled = item.disabled != true
      }
    }
    addItems(popup.menu, items)
    popup.setOnMenuItemClickListener { menuItem ->
      val id = actionIds[menuItem.itemId] ?: return@setOnMenuItemClickListener false
      onSelect(mapOf("id" to id))
      true
    }
    popup.show()
  }
}

private data class MenuItemDTO(
  val id: String,
  val title: String,
  val destructive: Boolean?,
  val disabled: Boolean?,
  val displayInline: Boolean?,
  val children: List<MenuItemDTO>?,
)

private fun decodeItems(json: String): List<MenuItemDTO> {
  return try {
    parseArray(JSONArray(json))
  } catch (_: Exception) {
    emptyList()
  }
}

private fun parseArray(array: JSONArray): List<MenuItemDTO> {
  return buildList {
    for (index in 0 until array.length()) {
      val obj = array.optJSONObject(index) ?: continue
      add(parseItem(obj))
    }
  }
}

private fun parseItem(obj: JSONObject): MenuItemDTO {
  val children = obj.optJSONArray("children")
  return MenuItemDTO(
    id = obj.optString("id"),
    title = obj.optString("title"),
    destructive = if (obj.has("destructive")) obj.optBoolean("destructive") else null,
    disabled = if (obj.has("disabled")) obj.optBoolean("disabled") else null,
    displayInline = if (obj.has("displayInline")) obj.optBoolean("displayInline") else null,
    children = children?.let { parseArray(it) },
  )
}
