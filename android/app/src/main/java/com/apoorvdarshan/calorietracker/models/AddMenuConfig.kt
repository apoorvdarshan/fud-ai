package com.apoorvdarshan.calorietracker.models

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
            if (methods.isEmpty() && group.name.isBlank()) return@mapNotNull null
            AddMenuGroupConfig(
                id = group.id.ifBlank { UUID.randomUUID().toString() },
                name = group.name.trim().ifBlank { "Group" },
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
        groups.map { group ->
            ResolvedAddMenuGroup(
                id = group.id,
                name = group.name,
                methods = group.methods.mapNotNull(FoodLogMethod::fromStorage)
            )
        }

    fun resolvedFlatMethods(): List<FoodLogMethod> =
        flatMethods.mapNotNull(FoodLogMethod::fromStorage)

    companion object {
        const val CURRENT_VERSION = 1
        const val STORAGE_KEY = "addMenu.config"

        /** Matches the pre-customization Android Home + food menu. */
        val Default = AddMenuConfig(
            groups = listOf(
                AddMenuGroupConfig(
                    name = "Photo & Scan",
                    methods = listOf(
                        FoodLogMethod.CAMERA.storageKey,
                        FoodLogMethod.PHOTOS.storageKey,
                        FoodLogMethod.BARCODE.storageKey
                    )
                ),
                AddMenuGroupConfig(
                    name = "Describe Meal",
                    methods = listOf(
                        FoodLogMethod.TEXT.storageKey,
                        FoodLogMethod.VOICE.storageKey,
                        FoodLogMethod.MANUAL.storageKey
                    )
                ),
                AddMenuGroupConfig(
                    name = "Reuse Meal",
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
                json.decodeFromString<AddMenuConfig>(raw).sanitized()
            }.getOrDefault(Default)
        }

        fun encode(config: AddMenuConfig): String =
            json.encodeToString(serializer(), config.sanitized())

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
