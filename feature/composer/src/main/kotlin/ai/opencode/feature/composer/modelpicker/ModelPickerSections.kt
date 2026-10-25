package ai.opencode.feature.composer.modelpicker

import ai.opencode.server.model.ModelVisibility
import ai.opencode.server.model.ModelProviderGroup
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.model
import java.text.Normalizer

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
 * Port of iOS `ModelSearch`: how both model pickers search (OC-331), like the TUI's. The query's
 * words, in any order, must each match the model's name or ID or its provider's name or ID, so
 * "opencode" lists that provider's models, "son 4.5" finds Claude Sonnet 4.5 and "anthropic
 * opus" Anthropic's Opus models. A word matches a whole word best, then the start of one, then a
 * version part ("5" in 4.5), then anywhere inside one; three letters or more may also skip
 * letters inside a word of the name ("snt" for Sonnet). Numbers match only where a number
 * starts, so "4.5" never matches a date in an ID. Results run best match first, then favorites,
 * then the server's order.
 */
object ModelSearch {
    private val levelSynonyms: Map<String, String> = mapOf(
        "off" to "none",
        "none" to "none",
        "minimal" to "minimal",
        "min" to "minimal",
        "low" to "low",
        "medium" to "medium",
        "med" to "medium",
        "high" to "high",
        "xhigh" to "xhigh",
        "max" to "max",
        "maximum" to "max",
        "ultra" to "ultra",
        "default" to "default",
    )

    data class ParsedLevelQuery(val terms: List<String>, val level: String?)

    data class ResolvedPick(val model: OpenCodeModel, val variant: String?, val hasExplicitLevel: Boolean)

    /**
     * Splits out a reasoning level word when paired with model search terms
     * (`"argon high"` → `(["argon"], "high")`, `"sonnet extra high"` → `(["sonnet"], "xhigh")`).
     */
    fun parseLevelQuery(query: String): ParsedLevelQuery {
        val raw = words(normalize(query))
        if (raw.size <= 1) return ParsedLevelQuery(raw, null)
        val terms = mutableListOf<String>()
        var level: String? = null
        var index = 0
        while (index < raw.size) {
            val word = raw[index]
            val next = raw.getOrNull(index + 1)
            if (level == null && word == "extra" && next == "high" && raw.size > 2) {
                level = "xhigh"
                index += 2
                continue
            }
            val mapped = levelSynonyms[word]
            if (level == null && mapped != null &&
                raw.withIndex().any { it.index != index && levelSynonyms[it.value] == null }
            ) {
                level = mapped
                index += 1
                continue
            }
            terms += word
            index += 1
        }
        return ParsedLevelQuery(terms, level)
    }

    /** Resolves a parsed level word against the variants [model] actually offers; `"default"` resolves to null. */
    fun resolveVariant(requested: String, model: OpenCodeModel): String? {
        if (requested == "default") return null
        val sorted = ModelVariant.sorted(model.variants.filter { it != "default" })
        if (sorted.isEmpty()) return null
        sorted.firstOrNull { it.equals(requested, ignoreCase = true) }?.let { return it }
        return when (requested.lowercase()) {
            "max", "ultra", "xhigh" -> sorted.last()
            "none", "minimal", "low" -> sorted.first()
            "medium" -> sorted[sorted.size / 2]
            else -> sorted.last()
        }
    }

    /** Top match for Enter in the search field, plus any explicit level parsed from the query. */
    fun resolvePick(
        models: List<OpenCodeModel>,
        query: String,
        favorites: Set<String> = emptySet(),
        recents: List<String> = emptyList(),
    ): ResolvedPick? {
        val top = rank(models, query, favorites, recents, parseLevel = true).firstOrNull() ?: return null
        val requested = parseLevelQuery(query).level
        if (requested != null) return ResolvedPick(top, resolveVariant(requested, top), true)
        return ResolvedPick(top, null, false)
    }

    private data class Ranked(
        val model: OpenCodeModel,
        val score: Double,
        val favorite: Boolean,
        val recentRank: Int,
        val index: Int,
    )

    /** [parseLevel] (the Fused + Stack picker only) also scores the query without a level word, favouring models that offer that level. */
    fun rank(
        models: List<OpenCodeModel>,
        query: String,
        favorites: Set<String> = emptySet(),
        recents: List<String> = emptyList(),
        parseLevel: Boolean = false,
    ): List<OpenCodeModel> {
        val allTerms = words(normalize(query))
        if (allTerms.isEmpty()) return models
        val parsed = if (parseLevel) parseLevelQuery(query) else ParsedLevelQuery(allTerms, null)
        val recentOrder = HashMap<String, Int>()
        recents.forEachIndexed { idx, id -> recentOrder.putIfAbsent(id, idx) }
        return models.mapIndexedNotNull { index, model ->
            val fullScore = score(model, allTerms)
            val requested = parsed.level
            val strippedScore: Double? = if (requested != null && parsed.terms.isNotEmpty()) {
                score(model, parsed.terms)?.let { base ->
                    val bonus = when {
                        requested == "default" -> 0.01
                        model.variants.any { it.equals(requested, ignoreCase = true) } -> 0.02
                        model.variants.isNotEmpty() -> 0.01
                        else -> 0.0
                    }
                    base + bonus
                }
            } else null
            val best = listOfNotNull(fullScore, strippedScore).maxOrNull() ?: return@mapIndexedNotNull null
            Ranked(model, best, model.id in favorites, recentOrder[model.id] ?: Int.MAX_VALUE, index)
        }.sortedWith { a, b ->
            when {
                a.score != b.score -> b.score.compareTo(a.score)
                a.favorite != b.favorite -> if (a.favorite) -1 else 1
                a.recentRank != b.recentRank -> a.recentRank.compareTo(b.recentRank)
                else -> a.index.compareTo(b.index)
            }
        }.map { it.model }
    }

    /** How well every term matches, from 0 to 1; null when any term doesn't. */
    fun score(model: OpenCodeModel, terms: List<String>): Double? {
        data class Field(val text: String, val weight: Double, val fuzzy: Boolean)
        val fields = listOf(
            Field(normalize(model.name), 1.0, true),
            Field(normalize(ModelProviderGroup.title(model.providerID)), 0.95, false),
            Field(normalize(model.providerID), 0.95, false),
            Field(normalize(model.key), 0.8, false),
            Field(normalize(model.modelID), 0.8, false),
            Field(normalize(model.canonicalProviderID ?: ""), 0.6, false),
        )
        var total = 0.0
        for (term in terms) {
            val best = fields.maxOf { match(term, it.text, it.fuzzy) * it.weight }
            if (best <= 0) return null
            total += best
        }
        return total / terms.size
    }

    /**
     * Lowercased words without accents or punctuation, keeping a version's dot: "Claude Sonnet
     * 4.5" and "claude-sonnet-4-5" both read "claude sonnet 4.5".
     */
    fun normalize(text: String): String {
        // iOS folds case, diacritics and width; NFKD + dropping combining marks does the same.
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
        val characters = decomposed.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase()
        val out = StringBuilder()
        for ((index, character) in characters.withIndex()) {
            if (character.isLetter() || character.isNumberLike()) {
                out.append(character)
            } else if (character in ".-_" && index > 0 && index + 1 < characters.length &&
                characters[index - 1].isNumberLike() && characters[index + 1].isNumberLike()
            ) {
                out.append('.')
            } else if (out.isNotEmpty() && out.last() != ' ') {
                out.append(' ')
            }
        }
        return if (out.lastOrNull() == ' ') out.substring(0, out.length - 1) else out.toString()
    }

    fun match(term: String, text: String, fuzzy: Boolean): Double {
        if (text.isEmpty()) return 0.0
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.any { it == term }) return 1.0
        if (words.any { it.startsWith(term) }) return 0.85
        for (index in 1 until text.length) {
            if (startsPart(text, index) && text.startsWith(term, index)) return 0.7
        }
        if (term.firstOrNull()?.isNumberLike() == true) return 0.0
        if (text.contains(term)) return 0.5
        if (text.replace(" ", "").contains(term)) return 0.4
        if (fuzzy && term.length >= 3 && words.any { it.firstOrNull() == term.first() && isSubsequence(term, it) }) return 0.25
        return 0.0
    }

    /** A version part or a run of digits inside a word: "5" in "4.5", "2" in "v2". */
    private fun startsPart(characters: String, index: Int): Boolean {
        val previous = characters[index - 1]
        val current = characters[index]
        if (previous == '.') return true
        return (previous.isLetter() && current.isNumberLike()) || (previous.isNumberLike() && current.isLetter())
    }

    private fun isSubsequence(term: String, word: String): Boolean {
        var remaining = 0
        for (character in word) {
            if (remaining < term.length && character == term[remaining]) {
                remaining += 1
                if (remaining == term.length) return true
            }
        }
        return remaining == term.length
    }

    private fun words(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }

    /** Swift's `Character.isNumber`: digits and other numeric characters. */
    private fun Char.isNumberLike(): Boolean = isDigit() || Character.getType(this).let {
        it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt()
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
