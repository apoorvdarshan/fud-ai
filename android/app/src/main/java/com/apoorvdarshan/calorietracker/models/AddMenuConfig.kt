package com.apoorvdarshan.calorietracker.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.apoorvdarshan.calorietracker.R
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class AddMenuGroupConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val methods: List<String>
)

@Serializable
data class AddMenuConfig(
    val version: Int = CURRENT_VERSION,
    val groups: List<AddMenuGroupConfig> = emptyList(),
    val flatMethods: List<String> = emptyList()
) {
    val usesFlatLayout: Boolean get() = groups.isEmpty()

    fun sanitized(): AddMenuConfig {
        val seen = mutableSetOf<FoodLogMethod>()

        fun filterMethods(raw: List<String>): List<String> {
            val result = mutableListOf<String>()
            for (key in raw) {
                val method = FoodLogMethod.fromStorage(key) ?: continue
                if (method !in seen) {
                    seen += method
                    result += method.storageKey
                }
            }
            return result
        }

        val trimmedGroups = groups.take(3).mapNotNull { group ->
            val methods = filterMethods(group.methods)
            if (methods.isEmpty()) return@mapNotNull null
            AddMenuGroupConfig(
                id = group.id.ifBlank { UUID.randomUUID().toString() },
                name = group.name.trim().ifBlank { DEFAULT_GROUP_FALLBACK_NAME },
                methods = methods
            )
        }

        return if (trimmedGroups.isEmpty()) {
            copy(version = CURRENT_VERSION, groups = emptyList(), flatMethods = filterMethods(flatMethods))
        } else {
            copy(version = CURRENT_VERSION, groups = trimmedGroups, flatMethods = emptyList())
        }
    }

    fun resolvedGroups(): List<ResolvedAddMenuGroup> =
        groups.mapNotNull { group ->
            val methods = group.methods.mapNotNull(FoodLogMethod::fromStorage)
            if (methods.isEmpty()) return@mapNotNull null
            ResolvedAddMenuGroup(
                id = group.id,
                name = group.name,
                methods = methods
            )
        }

    fun resolvedFlatMethods(): List<FoodLogMethod> =
        flatMethods.mapNotNull(FoodLogMethod::fromStorage)

    /**
     * Puts a hidden method back on the menu.
     *
     * - [groupIndex] set: add to that group (and drop the key from every other group).
     * - Flat layout: append to [flatMethods].
     * - Grouped layout with no index: last group, or the first group that already
     *   has methods if the last one is empty.
     */
    fun withRestoredMethod(
        method: FoodLogMethod,
        groupIndex: Int? = null
    ): AddMenuConfig {
        val key = method.storageKey
        val targetIndex = when {
            groupIndex != null && groupIndex in groups.indices -> groupIndex
            usesFlatLayout -> null
            groups.isEmpty() -> null
            groups.last().methods.isNotEmpty() -> groups.lastIndex
            else -> groups.indexOfFirst { it.methods.isNotEmpty() }.takeIf { it >= 0 }
                ?: groups.lastIndex
        }
        if (targetIndex == null) {
            return copy(flatMethods = flatMethods + key).sanitized()
        }
        val nextGroups = groups.mapIndexed { idx, group ->
            if (idx == targetIndex) {
                group.copy(methods = group.methods + key)
            } else {
                group.copy(methods = group.methods.filterNot { it == key })
            }
        }
        return copy(
            groups = nextGroups,
            flatMethods = flatMethods.filterNot { it == key }
        ).sanitized()
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val STORAGE_KEY = "addMenu.config"

        /** Stable identifiers for default group names — resolved to localized strings at display time. */
        const val DEFAULT_GROUP_PHOTO_SCAN = "__default_photo_scan__"
        const val DEFAULT_GROUP_DESCRIBE_MEAL = "__default_describe_meal__"
        const val DEFAULT_GROUP_REUSE_MEAL = "__default_reuse_meal__"
        const val DEFAULT_GROUP_FALLBACK_NAME = "__default_group__"

        /** Matches the pre-customization Android Home + food menu. */
        val Default = AddMenuConfig(
            groups = listOf(
                AddMenuGroupConfig(
                    name = DEFAULT_GROUP_PHOTO_SCAN,
                    methods = listOf(
                        FoodLogMethod.CAMERA.storageKey,
                        FoodLogMethod.PHOTOS.storageKey,
                        FoodLogMethod.BARCODE.storageKey
                    )
                ),
                AddMenuGroupConfig(
                    name = DEFAULT_GROUP_DESCRIBE_MEAL,
                    methods = listOf(
                        FoodLogMethod.TEXT.storageKey,
                        FoodLogMethod.VOICE.storageKey,
                        FoodLogMethod.MANUAL.storageKey
                    )
                ),
                AddMenuGroupConfig(
                    name = DEFAULT_GROUP_REUSE_MEAL,
                    methods = listOf(
                        FoodLogMethod.RECENT.storageKey,
                        FoodLogMethod.FREQUENT.storageKey,
                        FoodLogMethod.FAVORITES.storageKey,
                        FoodLogMethod.COPY_FROM_DAY.storageKey
                    )
                )
            )
        )

        fun decode(raw: String?): AddMenuConfig {
            if (raw.isNullOrBlank()) return Default
            return runCatching {
                val decoded = json.decodeFromString<AddMenuConfig>(raw)
                val sanitized = decoded.sanitized()
                if (sanitized.groups.isEmpty() && sanitized.flatMethods.isEmpty()) {
                    val hadConfiguredContent = decoded.groups.any { it.methods.isNotEmpty() } ||
                        decoded.flatMethods.isNotEmpty()
                    if (hadConfiguredContent) Default else sanitized
                } else {
                    sanitized
                }
            }.getOrDefault(Default)
        }

        fun encode(config: AddMenuConfig): String =
            json.encodeToString(serializer(), config.sanitized())

        /**
         * Settings startup reads the menu, then later replaces the whole screen state.
         * If the user already edited during that gap, keep the menu on screen.
         */
        fun afterSettingsSnapshot(
            alreadyLoaded: Boolean,
            onScreen: AddMenuConfig,
            diskRead: AddMenuConfig,
        ): AddMenuConfig = if (alreadyLoaded) onScreen else diskRead

        private val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

data class ResolvedAddMenuGroup(
    val id: String,
    val name: String,
    val methods: List<FoodLogMethod>
)

internal fun defaultGroupNameRes(storedName: String): Int? = when (storedName) {
    AddMenuConfig.DEFAULT_GROUP_PHOTO_SCAN -> R.string.home_menu_photo_scan
    AddMenuConfig.DEFAULT_GROUP_DESCRIBE_MEAL -> R.string.home_menu_describe_meal
    AddMenuConfig.DEFAULT_GROUP_REUSE_MEAL -> R.string.home_menu_reuse_meal
    AddMenuConfig.DEFAULT_GROUP_FALLBACK_NAME -> R.string.settings_add_menu_new_group
    else -> null
}

@Composable
fun AddMenuGroupConfig.displayName(): String {
    defaultGroupNameRes(name)?.let { return stringResource(it) }
    return name
}

@Composable
fun ResolvedAddMenuGroup.displayName(): String {
    defaultGroupNameRes(name)?.let { return stringResource(it) }
    return name
}
