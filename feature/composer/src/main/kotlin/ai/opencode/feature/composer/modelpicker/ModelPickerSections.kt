package ai.opencode.feature.composer.modelpicker

import ai.opencode.server.model.ModelVisibility
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.model

/**
 * Port of iOS `ModelPickerSection` (`Composer/ModelPicker/ModelPickerSections.swift`).
 *
 * The model list's sections (D-20 round 7, its list from round 6 option 1): Favorites, then one
 * section per provider, as the TUI and desktop app group them. A model several providers offer
 * is listed under each, so the row you tap is the provider. Searching drops Favorites and
 * matches names and providers.
 */
data class ModelPickerSection(val kind: Kind, val models: List<OpenCodeModel>) {
    sealed interface Kind {
        data object Favorites : Kind
        data object Recent : Kind
        data object AllModels : Kind
        data object SearchResults : Kind
        data class Provider(val id: String) : Kind
    }

    val id: String
        get() = when (kind) {
            Kind.Favorites -> "favorites"
            Kind.Recent -> "recent"
            Kind.AllModels -> "all"
            Kind.SearchResults -> "search"
            is Kind.Provider -> "provider-${kind.id}"
        }

    val title: String
        get() = when (kind) {
            Kind.Favorites -> "Favorites"
            Kind.Recent -> "Recent"
            Kind.AllModels -> "All Models"
            Kind.SearchResults -> ""
            is Kind.Provider -> ModelProviderGroup.title(kind.id)
        }

    companion object {
        /**
         * [recents] (most recent first) adds a Recent section of up to [recentLimit] models
         * after Favorites, leaving out favorites (New Model Picker, OC-469). Searching drops both.
         */
        fun sections(
            models: List<OpenCodeModel>,
            favorites: Set<String>,
            filter: String = "",
            recents: List<String> = emptyList(),
            recentLimit: Int = 0,
        ): List<ModelPickerSection> {
            val needle = filter.trim()
            // Searching, providers come in the order of their best match.
            val shown = ModelSearch.rank(models, needle, favorites)
            val sections = mutableListOf<ModelPickerSection>()
            if (needle.isEmpty()) {
                val favored = shown.filter { it.id in favorites }
                if (favored.isNotEmpty()) sections += ModelPickerSection(Kind.Favorites, favored)
                val byID = LinkedHashMap<String, OpenCodeModel>()
                for (model in models) byID.putIfAbsent(model.id, model)
                val recent = recents.asSequence()
                    .filter { it !in favorites }
                    .mapNotNull { byID[it] }
                    .take(maxOf(recentLimit, 0))
                    .toList()
                if (recent.isNotEmpty()) sections += ModelPickerSection(Kind.Recent, recent)
            }
            // Providers in the order the server lists them.
            val byProvider = LinkedHashMap<String, MutableList<OpenCodeModel>>()
            for (model in shown) byProvider.getOrPut(model.providerID) { mutableListOf() } += model
            byProvider.forEach { (id, list) -> sections += ModelPickerSection(Kind.Provider(id), list) }
            return sections
        }

        /**
         * Fused + Stack sections: Favorites, then Recent (up to [recentLimit], keeping the current
         * model visible if it isn't in Favorites), then All Models for the remaining catalog
         * entries. Searching returns a single flat ranked section.
         */
        fun fusedSections(
            models: List<OpenCodeModel>,
            favorites: Set<String>,
            recents: List<String> = emptyList(),
            currentModelID: String? = null,
            filter: String = "",
            recentLimit: Int = 3,
            includeAllModels: Boolean = true,
        ): List<ModelPickerSection> {
            val needle = filter.trim()
            if (needle.isNotEmpty()) {
                val ranked = ModelSearch.rank(models, needle, favorites, recents, parseLevel = true)
                if (ranked.isEmpty()) return emptyList()
                return listOf(ModelPickerSection(Kind.SearchResults, ranked.take(24)))
            }
            val byID = LinkedHashMap<String, OpenCodeModel>()
            for (model in models) byID.putIfAbsent(model.id, model)
            val favModels = models.filter { it.id in favorites }
            val favIDs = favModels.mapTo(HashSet()) { it.id }

            var recentModels = mutableListOf<OpenCodeModel>()
            val seenRecent = HashSet<String>()
            for (id in recents) {
                if (id in favIDs) continue
                if (!seenRecent.add(id)) continue
                val model = byID[id] ?: continue
                recentModels += model
                if (recentModels.size >= maxOf(0, recentLimit)) break
            }
            if (currentModelID != null && currentModelID !in favIDs && recentModels.none { it.id == currentModelID }) {
                byID[currentModelID]?.let { currentModel ->
                    recentModels.add(0, currentModel)
                    if (recentLimit > 0 && recentModels.size > recentLimit) {
                        recentModels = recentModels.take(recentLimit).toMutableList()
                    }
                }
            }

            val sections = mutableListOf<ModelPickerSection>()
            if (favModels.isNotEmpty()) sections += ModelPickerSection(Kind.Favorites, favModels)
            if (recentModels.isNotEmpty()) sections += ModelPickerSection(Kind.Recent, recentModels)
            if (includeAllModels) {
                val shownIDs = favIDs + recentModels.map { it.id }
                val remaining = models.filter { it.id !in shownIDs }
                if (remaining.isNotEmpty()) sections += ModelPickerSection(Kind.AllModels, remaining)
            }
            return sections
        }
    }
}

/**
 * The model picker's inputs, as `SessionFeature.State` holds them on iOS. The iOS file extends
 * `SessionFeature.State` (and its `Store`) with the picker's derived values; `SessionFeature`
 * sits above this module, so the session area builds this value from its state and the pickers
 * read it. Every property here is the iOS computed property of the same name
 * (`SessionFeature+State.swift` `currentModelName`, `currentModelLabel`, `pickerModels`,
 * `hiddenModelCount`, `filteredModels`, and `ModelPickerSections.swift`'s extension).
 */
data class ModelPickerState(
    val models: List<OpenCodeModel> = emptyList(),
    val currentModel: OpenCodeModelRef? = null,
    val favoriteModelIDs: Set<String> = emptySet(),
    val recentModelIDs: List<String> = emptyList(),
    /** `modelVariants` (model ID → remembered variant, `"default"` for the default). */
    val modelVariants: Map<String, String> = emptyMap(),
    val modelFilter: String = "",
    val isLoadingModels: Boolean = false,
    val modelsLoadError: String? = null,
    /** `modelVisibility`: this server's visibility while Hide Unused Models is on; null lists the whole catalog. */
    val modelVisibility: ModelVisibility? = null,
    val showsAllModels: Boolean = false,
) {
    /** The current model by its display name, never its slug once the catalog has it. */
    val currentModelName: String
        get() {
            val ref = currentModel ?: return "Model"
            return models.model(ref)?.name ?: ref.id
        }

    /** The current model's catalog entry. */
    val currentModelInfo: OpenCodeModel? get() = currentModel?.let { models.model(it) }

    /** "Claude Haiku 5.5 · High": the model and its reasoning variant, if any. */
    val currentModelLabel: String
        get() {
            val variant = currentModel?.variant
            if (variant == null || variant == "default") return currentModelName
            return "$currentModelName · ${ModelVariant.label(variant)}"
        }

    /** The models the Today and Sheet pickers list: the visible ones and the current model, or all of them after Show All. */
    val pickerModels: List<OpenCodeModel>
        get() {
            val visibility = modelVisibility ?: return models
            if (showsAllModels) return models
            return visibility.visible(models, currentModelInfo?.id)
        }

    /**
     * How many models Show All adds; zero hides the button. While any are hidden the pickers keep
     * Show All instead of an empty state, so hiding every model never strands the picker.
     */
    val hiddenModelCount: Int
        get() {
            val visibility = modelVisibility ?: return 0
            return models.size - visibility.visible(models, currentModelInfo?.id).size
        }

    val isFilteringModels: Boolean get() = modelFilter.trim { it == ' ' || it == '\t' }.isNotEmpty()

    val filteredModels: List<OpenCodeModel> get() = ModelSearch.rank(pickerModels, modelFilter, favoriteModelIDs)

    val favoriteModels: List<OpenCodeModel> get() = filteredModels.filter { it.id in favoriteModelIDs }

    val unfavoriteModels: List<OpenCodeModel> get() = filteredModels.filter { it.id !in favoriteModelIDs }

    /** The model list as the picker shows it. */
    val modelSections: List<ModelPickerSection>
        get() = ModelPickerSection.sections(models, favoriteModelIDs, modelFilter)

    /**
     * The New Model Picker's sheet: Favorites, then Recent from [recents] (the sheet passes the
     * list as it opened, so it never reorders while open), then each provider.
     */
    fun modelSheetSections(recents: List<String>, recentLimit: Int): List<ModelPickerSection> =
        ModelPickerSection.sections(pickerModels, favoriteModelIDs, modelFilter, recents, recentLimit)

    /** The Fused + Stack sections (`Favorites`, `Recent`, `All Models`, or flat search matches). */
    fun fusedModelSections(recentLimit: Int = 3, includeAllModels: Boolean = true): List<ModelPickerSection> =
        ModelPickerSection.fusedSections(
            models = models,
            favorites = favoriteModelIDs,
            recents = recentModelIDs,
            currentModelID = currentModelInfo?.id,
            filter = modelFilter,
            recentLimit = recentLimit,
            includeAllModels = includeAllModels,
        )

    /** The current model's levels, when it has any to choose between. */
    val currentLevelScale: ModelLevelScale?
        get() {
            val model = currentModelInfo ?: return null
            return ModelLevelScale(model.variants).takeIf { it.isAdjustable }
        }

    /** The current level; null is the model's default. */
    val currentLevel: String? get() = currentModel?.variant?.takeIf { it != "default" }

    /** Compact name for the composer chip (`"Opus 5.5"` for `"Claude Opus 5.5"`). */
    val currentModelShortName: String get() = currentModelInfo?.shortName ?: currentModelName

    /** The level word shown on the chip (`"High"`, `"Default"`, or null when the current model has no adjustable variants). */
    val currentChipLevelLabel: String?
        get() {
            if (currentLevelScale == null) return null
            return ModelLevelScale.label(currentLevel)
        }

    /**
     * The level shown for [model] on its row (and picked when tapping its name): a level parsed
     * from the search query when searching, else the active level for the current model, else
     * the remembered variant in [modelVariants], else null (`"Default"`).
     */
    fun rememberedLevel(model: OpenCodeModel): String? {
        val scale = ModelLevelScale(model.variants)
        if (!scale.isAdjustable) return null
        if (isFilteringModels) {
            val parsed = ModelSearch.parseLevelQuery(modelFilter).level
            if (parsed != null) return ModelSearch.resolveVariant(parsed, model)
        }
        if (currentModelInfo?.id == model.id) {
            return currentLevel?.takeIf { it in model.variants }
        }
        val saved = modelVariants[model.id]
        if (saved != null && saved != "default" && saved in model.variants) return saved
        return null
    }

    // The composer chip's words (`SessionView+ComposerControls.swift`), kept beside the values
    // they read so the session area's chip says exactly what iOS's does.

    /** The Sheet style's chip words before the chevron: `"<Short name> <Level>"` (no level without variants). */
    val sheetChipText: String
        get() = listOfNotNull(currentModelShortName, currentChipLevelLabel).joinToString(" ")

    /** The Sheet style's chip TalkBack value: `"Cirrus 4, High"`. */
    val sheetChipAccessibilityValue: String
        get() = listOfNotNull(currentModelShortName, currentChipLevelLabel).joinToString(", ")
}

/**
 * The picker actions iOS's views send to `SessionFeature` (the reducer lives in the session
 * area, `SessionFeature+Models.swift`). The pickers here call one lambda with these; the
 * session area maps each to its own action of the same name.
 */
sealed interface ModelPickerEvent {
    /** `.modelPicked`: keeps the current variant when the new model offers it; closes the picker. */
    data class ModelPicked(val model: OpenCodeModel) : ModelPickerEvent

    /** `.modelPickedWithLevel`: picks at [level] and closes the picker; [ticked] skips the selection haptic. */
    data class ModelPickedWithLevel(val model: OpenCodeModel, val level: String?, val ticked: Boolean = false) : ModelPickerEvent

    /** `.modelLevelPicked`: as [ModelPickedWithLevel] but the picker stays open. */
    data class ModelLevelPicked(val model: OpenCodeModel, val level: String?) : ModelPickerEvent

    /** `.modelRowLevelChanged`: remembers a row's level (and applies it when it's the current model). */
    data class ModelRowLevelChanged(val model: OpenCodeModel, val level: String?) : ModelPickerEvent

    /** `.variantPicked`: the current model at another level, picker stays open. */
    data class VariantPicked(val variant: String?) : ModelPickerEvent

    data class ToggleFavoriteModel(val model: OpenCodeModel) : ModelPickerEvent

    /** `$store.modelFilter` binding. */
    data class FilterChanged(val text: String) : ModelPickerEvent

    data object ModelSearchSubmitted : ModelPickerEvent
    data object ShowAllModelsToggled : ModelPickerEvent

    /** `.modelListOpened` (also Try Again). */
    data object ModelListOpened : ModelPickerEvent

    /** `.modelListClosed`: however the picker closed; clears its search. */
    data object ModelListClosed : ModelPickerEvent

    /** `store.activePicker = nil` (Done, Cancel, swiped away). */
    data object Dismiss : ModelPickerEvent
}
