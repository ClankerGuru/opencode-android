package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.server.model.OpenCodeModel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Port of iOS `ModelVisibility` (`Composer/ModelPicker/ModelVisibility.swift`).
 *
 * Which models one server's pickers list (Hide Unused Models, OC-510). Kept on this phone, per
 * server. Hidden providers hide all their models; otherwise **Hide** lists every model but
 * [hiddenModelIDs], and **Only Show** lists just [shownModelIDs]. Each list is kept when
 * switching, so trying the other choice loses nothing.
 *
 * JSON matches iOS's `Codable` form (`mode` raw values, sets as arrays).
 */
@Serializable
data class ModelVisibility(
    val mode: Mode = Mode.Hide,
    val hiddenProviderIDs: Set<String> = emptySet(),
    val hiddenModelIDs: Set<String> = emptySet(),
    val shownModelIDs: Set<String> = emptySet(),
) {
    @Serializable
    enum class Mode(val rawValue: String) {
        @SerialName("hide") Hide("hide"),
        @SerialName("onlyShow") OnlyShow("onlyShow");

        val id: String get() = rawValue

        val title: String
            get() = when (this) {
                Hide -> "Hide Selected"
                OnlyShow -> "Only Show Selected"
            }
    }

    /**
     * Only Show lists just the chosen models [catalog] still offers; with none of them left
     * (nothing chosen yet, or only models the server has since dropped) it lists everything,
     * so stale choices never empty the picker.
     */
    fun isVisible(model: OpenCodeModel, catalog: List<OpenCodeModel>): Boolean =
        isVisible(model, liveShownIDs(catalog))

    private fun isVisible(model: OpenCodeModel, liveShownIDs: Set<String>): Boolean {
        if (model.providerID in hiddenProviderIDs) return false
        return when (mode) {
            Mode.Hide -> model.id !in hiddenModelIDs
            Mode.OnlyShow -> liveShownIDs.isEmpty() || model.id in liveShownIDs
        }
    }

    private fun liveShownIDs(catalog: List<OpenCodeModel>): Set<String> =
        shownModelIDs.intersect(catalog.mapTo(HashSet()) { it.id })

    /** Model and provider IDs this choice names that [catalog] no longer offers. Settings lists them so they can be forgotten. */
    fun staleEntries(catalog: List<OpenCodeModel>): StaleEntries {
        val modelIDs = catalog.mapTo(HashSet()) { it.id }
        val providerIDs = catalog.mapTo(HashSet()) { it.providerID }
        return StaleEntries(
            providerIDs = hiddenProviderIDs - providerIDs,
            modelIDs = (hiddenModelIDs + shownModelIDs) - modelIDs,
        )
    }

    /** iOS `mutating func forget(_:)`: returns the updated value. */
    fun forget(stale: StaleEntries): ModelVisibility = copy(
        hiddenProviderIDs = hiddenProviderIDs - stale.providerIDs,
        hiddenModelIDs = hiddenModelIDs - stale.modelIDs,
        shownModelIDs = shownModelIDs - stale.modelIDs,
    )

    data class StaleEntries(
        val providerIDs: Set<String> = emptySet(),
        val modelIDs: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean get() = providerIDs.isEmpty() && modelIDs.isEmpty()

        /** Sorted for display: providers first, then models. */
        val all: List<String> get() = providerIDs.sorted() + modelIDs.sorted()
    }

    /** Whether the model's own switch is on, whatever its provider's. */
    fun isModelOn(model: OpenCodeModel): Boolean = when (mode) {
        Mode.Hide -> model.id !in hiddenModelIDs
        Mode.OnlyShow -> model.id in shownModelIDs
    }

    /** iOS `mutating func setModel(_:on:)`: returns the updated value. */
    fun settingModel(model: OpenCodeModel, on: Boolean): ModelVisibility = when (mode) {
        Mode.Hide -> copy(hiddenModelIDs = if (on) hiddenModelIDs - model.id else hiddenModelIDs + model.id)
        Mode.OnlyShow -> copy(shownModelIDs = if (on) shownModelIDs + model.id else shownModelIDs - model.id)
    }

    /** iOS `mutating func setProvider(_:shown:)`: returns the updated value. */
    fun settingProvider(providerID: String, shown: Boolean): ModelVisibility =
        copy(hiddenProviderIDs = if (shown) hiddenProviderIDs - providerID else hiddenProviderIDs + providerID)

    /** [models] as the picker lists them, keeping the current model so its checkmark never vanishes. */
    fun visible(models: List<OpenCodeModel>, keeping: String?): List<OpenCodeModel> {
        val live = liveShownIDs(models)
        return models.filter { isVisible(it, live) || it.id == keeping }
    }
}

/** iOS `SharedKey` extensions declared in `ModelVisibility.swift`. */
object ModelVisibilitySharedKeys {
    /** Each saved server's model visibility, keyed by its persistence ID. */
    val modelVisibilityByServer = SharedKey.fileStorage<Map<String, ModelVisibility>>(
        FileDirectory.Documents, "model-visibility.json", emptyMap(),
    )
}
