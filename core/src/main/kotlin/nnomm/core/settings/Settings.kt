package nnomm.core.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import nnomm.core.model.*

@Serializable
data class SettingDefinition(
    val cat: String,
    val key: String,
    val label: String = "",
    val help: String = "",
    val type: String,
    val options: List<String> = emptyList(),
    val ov: Boolean = false,
    val profileOnly: Boolean = false,
    val danger: Boolean = false,
    val actions: List<String> = emptyList(),
    val placeholder: String? = null,
    @kotlinx.serialization.SerialName("val") val defaultValue: JsonPrimitive? = null,
)

private val seed by lazy {
    Json.parseToJsonElement(
            object {}.javaClass.getResourceAsStream("/nnomm/settings.json")!!.bufferedReader().use {
                it.readText()
            }
        )
        .jsonObject
}
val SETTINGS: List<SettingDefinition> by lazy {
    Json.decodeFromJsonElement(seed.getValue("settings"))
}
val CATS: List<Pair<String, String>> by lazy {
    seed.getValue("categories").jsonArray.map {
        it.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content }
    }
}
val KEYS: List<Pair<String, String>> by lazy {
    seed.getValue("keys").jsonArray.map {
        it.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content }
    }
}

fun defaultSettings() =
    SettingsValues(SETTINGS.mapNotNull { d -> d.defaultValue?.let { d.key to it } }.toMap())

fun settingHelp(d: SettingDefinition, s: AppState): String =
    when (d.key) {
        "loader" ->
            if (s.game.loaderPresent)
                "BepInEx ${s.game.loaderVersion?:"(version unknown)"}. Mods need it to run."
            else "NNOMM installs BepInEx when you turn on your first mod, and tells you."
        "cache" ->
            (s.storeUnusedMb?.let { "$it MB. " }.orEmpty() +
                "Old versions that no profile uses. Clear removes only unused stored files.")
        "gamePath" ->
            s.game.version?.takeUnless { it == "unknown" }?.let { "Nuclear Option $it found here." }
                ?: "Game version is unknown."
        "store" ->
            "Each version of a mod is stored once here, also when several profiles use it. ${s.storeUsedMb} MB used."
        "appUpdate" -> "Version ${s.appVersion}."
        else -> d.help
    }

fun setSetting(s: AppState, key: String, value: JsonPrimitive, profile: String? = null): AppState {
    val d = SETTINGS.firstOrNull { it.key == key } ?: return s
    if (profile != null) {
        val old = s.settings.overrides[profile].orEmpty()
        if (key in old || d.profileOnly)
            return s.copy(
                settings =
                    s.settings.copy(
                        overrides = s.settings.overrides + (profile to (old + (key to value)))
                    )
            )
        if (d.ov) return s
    }
    val result = s.copy(settings = s.settings.copy(general = s.settings.general + (key to value)))
    return if (key == "showProfiles" && value.content == "false")
        nnomm.core.rules.restoreTrouble(result).copy(profileId = "default")
    else result
}

fun ovset(s: AppState, key: String, p: String): AppState {
    if (SETTINGS.none { it.key == key && it.ov }) return s
    val v = s.settings.general[key] ?: return s
    return s.copy(
        settings =
            s.settings.copy(
                overrides =
                    s.settings.overrides + (p to (s.settings.overrides[p].orEmpty() + (key to v)))
            )
    )
}

fun ovreset(s: AppState, key: String, p: String) =
    s.copy(
        settings =
            s.settings.copy(
                overrides = s.settings.overrides + (p to (s.settings.overrides[p].orEmpty() - key))
            )
    )

fun ovclear(s: AppState, p: String) =
    s.copy(
        settings =
            s.settings.copy(
                overrides =
                    s.settings.overrides +
                        (p to
                            s.settings.overrides[p].orEmpty().filterKeys { k ->
                                SETTINGS.any { it.key == k && it.profileOnly }
                            })
            )
    )
