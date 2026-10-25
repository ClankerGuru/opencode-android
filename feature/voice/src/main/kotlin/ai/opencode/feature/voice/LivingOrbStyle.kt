package ai.opencode.feature.voice

import ai.opencode.core.sharing.SharedKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The Living Orb's shape (iOS `LivingOrbStyle`): Settings › Orb Style. */
@Serializable
@Suppress("EnumEntryName")
enum class LivingOrbStyle(val rawValue: String) {
    @SerialName("polarBloom") polarBloom("polarBloom"),
    @SerialName("livingClouds") livingClouds("livingClouds"),
    @SerialName("silkRibbons") silkRibbons("silkRibbons"),
    ;

    val id: String get() = rawValue

    val title: String
        get() = when (this) {
            polarBloom -> "Polar Bloom"
            livingClouds -> "Living Clouds"
            silkRibbons -> "Silk Ribbons"
        }

    companion object {
        fun fromRawValue(rawValue: String): LivingOrbStyle? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * Tuning can temporarily override the saved Settings choice. Reset restores it (iOS
 * `LivingOrbStyleOverride`). The tuning panel is not ported, so the baked override is
 * [settings] ([LivingOrbTunables.style]).
 */
@Suppress("EnumEntryName")
enum class LivingOrbStyleOverride(val rawValue: String) {
    settings("settings"), polarBloom("polarBloom"), livingClouds("livingClouds"), silkRibbons("silkRibbons");

    fun resolve(saved: LivingOrbStyle): LivingOrbStyle = LivingOrbStyle.fromRawValue(rawValue) ?: saved
}

private val livingOrbStyleKey = SharedKey.appStorage("living-orb-style", LivingOrbStyle.polarBloom)

/** iOS `SharedKey.livingOrbStyle`: `.appStorage("living-orb-style")`, Polar Bloom by default. */
val VoiceSharedKeys.livingOrbStyle: SharedKey<LivingOrbStyle> get() = livingOrbStyleKey
