package ai.opencode.feature.session

import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.seams.RequestDockAnswer
import ai.opencode.feature.session.seams.fitsDock
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeFormField
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import java.text.DecimalFormat

// Port of iOS `Composer/RequestAnswer.swift`: the request dock's state and reducer, the bar's
// modes, and the `SessionFeature.State` members that make the composer a pending request's
// answer. Its state carries `SessionFeature.State.DraftState`, so it lands in this module.

/** iOS `RequestDockFeature`. */
class RequestDockFeature : Reducer<RequestDockFeature.State, RequestDockFeature.Action> {
    data class State(
        val permissionRequests: List<OpenCodePermissionRequest> = emptyList(),
        val formRequests: List<OpenCodeFormRequest> = emptyList(),
        val formAnswers: Map<String, Map<String, OpenCodeFormValue>> = emptyMap(),
        val respondingRequestIDs: Set<String> = emptySet(),
        val questionPageIndices: Map<String, Int> = emptyMap(),
        val requestLoadError: String? = null,
        val requestLoadAttempt: LoadAttempt? = null,
        val requestFailureAwaitingReconnect: Boolean = false,
        val requestsConnectionIsAvailable: Boolean = true,
        val requestErrors: Map<String, String> = emptyMap(),
        /** While a pending request docks, the composer is its answer, and what was typed for the session waits here. */
        val parkedComposer: SessionFeature.State.DraftState? = null,
        /** The bar's text for each question page or request it has answered ("form/field", or the request), so paging and switching keep it. */
        val answerDrafts: Map<String, String> = emptyMap(),
        /** Whose words the bar holds now (an [answerTarget]). */
        val answerShownTarget: String? = null,
        /** The bar's text as last loaded or applied: an edit that only adds or removes Return's newline isn't a new answer. */
        val answerBarBaseline: String = "",
        /** The pending request the dock shows; null shows the first. */
        val selectedRequestID: String? = null,
        /** Requests whose last reply didn't reach the server: their dock reads “Couldn’t send …” until the next attempt or edit. */
        val failedReplyIDs: Set<String> = emptySet(),
        /** A successful reply must not become actionable again through an older poll. */
        val completedRequestIDs: Set<String> = emptySet(),
    ) {
        enum class LoadAttempt { initial, reconnect, manual }

        /** The docked form's current page: the form, its active field, and the page. */
        data class DockedFormPage(val form: OpenCodeFormRequest, val field: OpenCodeFormField?, val page: Int, val count: Int)

        val isAnswering: Boolean get() = parkedComposer != null

        val hasPendingRequests: Boolean get() = permissionRequests.isNotEmpty() || formRequests.isNotEmpty()

        val reviewRequestIDs: List<String> get() = formRequests.map { it.id } + permissionRequests.map { it.id }

        val waitingSessionIDs: Set<String>
            get() = (formRequests.map { it.sessionID }.filter { it != "global" } + permissionRequests.map { it.sessionID }).toSet()

        val dockRequestID: String?
            get() {
                val selected = selectedRequestID
                if (selected != null && selected in reviewRequestIDs) return selected
                return reviewRequestIDs.firstOrNull()
            }

        fun isOnlyReturn(text: String): Boolean {
            if (!isAnswering) return false
            return text == answerBarBaseline + "\n"
        }

        val dockedFormPage: DockedFormPage?
            get() {
                val id = dockRequestID ?: return null
                val form = formRequests.firstOrNull { it.id == id } ?: return null
                val fields = form.activeFields(formAnswers[form.id] ?: emptyMap())
                val count = if (form.isQuestion) fields.size else minOf(fields.size, 1)
                val page = minOf(questionPageIndices[form.id] ?: 0, maxOf(count - 1, 0))
                return DockedFormPage(form, fields.getOrNull(page), page, count)
            }

        val answerTarget: String?
            get() {
                val id = dockRequestID ?: return null
                val page = dockedFormPage
                if (page != null && page.form.fitsDock && page.field != null) return "${page.form.id}/${page.field.key}"
                return id
            }

        fun requestBar(draft: String): RequestBar? {
            if (!isAnswering) return null
            val id = dockRequestID ?: return null
            if (permissionRequests.any { it.id == id }) return RequestBar.Decline(RequestBar.Verb.deny)
            val (form, field, page, count) = dockedFormPage ?: return null
            if (!form.fitsDock || field == null) return RequestBar.Decline(RequestBar.Verb.dismiss)
            val value = formAnswers[form.id]?.get(field.key)
            val isLast = page >= count - 1
            val ready = RequestDockAnswer.hasAnswer(value)
            val options = RequestDockAnswer.options(field)
            if (!(options.isEmpty() || field.custom == true)) return RequestBar.Choose(isLast = isLast, ready = ready)
            return RequestBar.Answer(
                multiple = field.type == OpenCodeFormFieldType.Multiselect, isLast = isLast, ready = ready,
                isCustom = RequestDockAnswer.isCustom(draft, value),
            )
        }

        fun answerText(target: String?): String {
            target ?: return ""
            answerDrafts[target]?.let { return it }
            val page = dockedFormPage ?: return ""
            val field = page.field ?: return ""
            if ("${page.form.id}/${field.key}" != target) return ""
            val value = formAnswers[page.form.id]?.get(field.key)
            if (value is OpenCodeFormValue.Number) return DecimalFormat("#,##0.###").format(value.value)
            return RequestDockAnswer.customText(value, RequestDockAnswer.options(field))
        }

        fun applyBarEdit(draft: String): State {
            if (!isAnswering || requestBar(draft) !is RequestBar.Answer) return this
            val page = dockedFormPage ?: return this
            val field = page.field ?: return this
            if (page.form.id in respondingRequestIDs) return this
            return copy(answerBarBaseline = draft).setCustomAnswer(draft, field, page.form.id)
        }

        /** Returns the next state and whether the docked form's answers changed. */
        fun toggleCustomAnswer(draft: String): Pair<State, Boolean> {
            val before = dockedFormPage?.let { formAnswers[it.form.id] }
            val bar = requestBar(draft) as? RequestBar.Answer ?: return this to false
            val page = dockedFormPage ?: return this to false
            val field = page.field ?: return this to false
            if (page.form.id in respondingRequestIDs) return this to false
            val form = page.form
            val next = if (!bar.isCustom) {
                setCustomAnswer(draft, field, form.id)
            } else {
                val value: OpenCodeFormValue? = if (field.type == OpenCodeFormFieldType.Multiselect) {
                    OpenCodeFormValue.Strings(RequestDockAnswer.knownValues(formAnswers[form.id]?.get(field.key), field))
                } else {
                    null
                }
                setFormAnswer(form.id, field.key, value)
            }
            return next to (next.dockedFormPage?.let { next.formAnswers[it.form.id] } != before)
        }

        fun setFormAnswer(formID: String, key: String, value: OpenCodeFormValue?): State {
            val answers = formAnswers[formID] ?: return this
            val updated = if (value == null) answers - key else answers + (key to value)
            return copy(
                formAnswers = formAnswers + (formID to updated),
                requestErrors = requestErrors - formID,
                failedReplyIDs = failedReplyIDs - formID,
            )
        }

        private fun setCustomAnswer(text: String, field: OpenCodeFormField, formID: String): State {
            val value: OpenCodeFormValue? = when (field.type) {
                OpenCodeFormFieldType.Multiselect -> OpenCodeFormValue.Strings(
                    RequestDockAnswer.knownValues(formAnswers[formID]?.get(field.key), field) +
                        (if (text.isBlank()) emptyList() else listOf(text)),
                )
                OpenCodeFormFieldType.Number, OpenCodeFormFieldType.Integer -> OpenCodeFormValue.typedNumber(text)
                else -> OpenCodeFormValue.Str(text)
            }
            return setFormAnswer(formID, field.key, value)
        }

        fun changeQuestionPage(requestID: String, index: Int): State {
            if (requestID in respondingRequestIDs) return this
            val count = formRequests.firstOrNull { it.id == requestID }?.activeFields(formAnswers[requestID] ?: emptyMap())?.size ?: 0
            if (index !in 0 until count) return this
            return copy(questionPageIndices = questionPageIndices + (requestID to index))
        }

        /**
         * Removes requests matching [removing] and drops state for requests no longer in the review
         * list. Returns the pruned state and whether a selected request was cleared, so the caller can
         * close the requests sheet if open.
         */
        fun prune(
            removing: (PendingRequest) -> Boolean = { false },
            keepingSelection: Set<String> = emptySet(),
        ): Pair<State, Boolean> {
            val hadSelection = selectedRequestID != null
            val permissions = permissionRequests.filterNot { removing(PendingRequest.Permission(it)) }
            val forms = formRequests.filterNot { removing(PendingRequest.Form(it)) }
            var next = if (permissions.size != permissionRequests.size || forms.size != formRequests.size) {
                copy(permissionRequests = permissions, formRequests = forms)
            } else {
                this
            }
            val live = next.reviewRequestIDs.toSet()
            next = next.copy(
                formAnswers = SessionFeature.prune(next.formAnswers, live),
                questionPageIndices = SessionFeature.prune(next.questionPageIndices, live),
                requestErrors = SessionFeature.prune(next.requestErrors, live),
                failedReplyIDs = if (live.containsAll(next.failedReplyIDs)) next.failedReplyIDs else next.failedReplyIDs.intersect(live),
            )
            val selected = next.selectedRequestID
            if (selected != null && selected !in live && selected !in keepingSelection) next = next.copy(selectedRequestID = null)
            return next to (hadSelection && next.selectedRequestID == null)
        }
    }

    sealed interface Action {
        data class PermissionReplyTapped(val id: String, val reply: OpenCodePermissionReply, val message: String? = null) : Action
        data class ResponseFinished(val sessionID: String, val requestID: String, val error: OpenCodeClientError?) : Action
        data class ReviewRequestSelected(val id: String) : Action
        /** Opens the form in the review sheet instead of the dock. */
        data class SheetTapped(val id: String) : Action
        data object RefreshTapped : Action
        data object AutoRetry : Action
        data class QuestionPageChanged(val requestID: String, val index: Int) : Action
        data class FormValueChanged(val formID: String, val key: String, val value: OpenCodeFormValue?) : Action
        data class FormSubmitTapped(val id: String) : Action
        /** Dismisses a pending form with `DELETE /api/form/{id}` so the agent's question tool receives a dismissal instead of hanging. */
        data class FormCancelTapped(val id: String, val message: String? = null) : Action
        /** Dismisses whichever request is in the dock: cancels a form/question or rejects a permission, carrying the bar's words as the reason. */
        data object DismissTapped : Action
        /** Your own answer's mark in the bar: picks or sets aside the bar's text. */
        data object CustomMarkTapped : Action
        /** Dictation into the answer bar finished and is ready to send for the request it was recorded for. */
        data class BarSent(val requestID: String) : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.ReviewRequestSelected -> {
            if (action.id !in state.reviewRequestIDs || state.respondingRequestIDs.isNotEmpty()) {
                Reduced(state)
            } else {
                Reduced(state.copy(selectedRequestID = action.id))
            }
        }
        is Action.QuestionPageChanged -> Reduced(state.changeQuestionPage(action.requestID, action.index))
        is Action.FormValueChanged -> {
            if (action.formID in state.respondingRequestIDs) Reduced(state)
            else Reduced(state.setFormAnswer(action.formID, action.key, action.value))
        }
        is Action.PermissionReplyTapped,
        is Action.ResponseFinished,
        is Action.SheetTapped,
        Action.RefreshTapped,
        Action.AutoRetry,
        is Action.FormSubmitTapped,
        is Action.FormCancelTapped,
        Action.DismissTapped,
        Action.CustomMarkTapped,
        is Action.BarSent,
        -> Reduced(state)
    }
}

/** What the composer's bar does while a pending request docks above it (D-31 B): its text is your own answer, or the reason a decline carries. */
sealed interface RequestBar {
    /** The bar's text answers the page: typing or dictating there picks it. */
    data class Answer(val multiple: Boolean, override val isLast: Boolean, val ready: Boolean, val isCustom: Boolean) : RequestBar
    /** Only the choices answer; the bar's text goes with × as a reason. */
    data class Choose(override val isLast: Boolean, val ready: Boolean) : RequestBar
    /** Deny (a permission) or Dismiss (a form for the sheet), carrying the bar's text as what to do instead. */
    data class Decline(val verb: Verb) : RequestBar {
        override val isLast: Boolean get() = true
    }

    enum class Verb(val rawValue: String) { deny("Deny"), dismiss("Dismiss") }

    val placeholder: String
        get() = when (this) {
            is Answer -> if (multiple) "Add your own" else "Answer in your own words"
            is Choose, is Decline -> "Tell it what to do instead"
        }

    /** The field's accessibility label. */
    val fieldLabel: String get() = if (this is Answer) "Your answer" else "Reason"

    val isReady: Boolean
        get() = when (this) {
            is Answer -> ready
            is Choose -> ready
            is Decline -> true
        }

    val isLast: Boolean
}

/** iOS `SessionFeature.State.AnswerContext`. */
data class AnswerContext(val wants: Boolean, val target: String?)

/** The composer is a pending request's answer. */
val State.isAnsweringRequest: Boolean get() = requestDock.isAnswering

/** A long paste becomes a Pasted Text card, and a pasted image an attachment, only in a message: an answer or a reason takes text. */
val State.takesPasteAttachments: Boolean get() = !isAnsweringRequest

/** A pending request takes the composer, except under a call (whose agent answers) and until any dictation into the message has finished. */
val State.wantsRequestDock: Boolean
    get() {
        if (!hasPendingRequests || callOwnsAudio || dictationSendPending) return false
        // A message still recording, or finalizing to send, goes as a message.
        return isAnsweringRequest || (!hasLiveDictation && dictationHold == null && sendingDictation == null)
    }

/** The draft differs from the bar's last answer only by Return's newline. */
fun State.isOnlyReturn(text: String): Boolean =
    returnKeySends.value && nonSendNewlineDraft != text && requestDock.isOnlyReturn(text)

/** The docked form's current page: the form, its active fields, and the page. */
val State.dockedFormPage: RequestDockFeature.State.DockedFormPage? get() = requestDock.dockedFormPage

/** Where the bar's text belongs: the docked page's field, or the request. */
val State.answerTarget: String? get() = requestDock.answerTarget

val State.requestBar: RequestBar? get() = requestDock.requestBar(draft)

/** The bar's text for [target]: what it held there, or the field's own typed answer. */
fun State.answerText(target: String?): String = requestDock.answerText(target)

/** The person typed or dictated in the bar: on an answer page, the words become the answer (beside the choices picked, for several). */
fun State.applyBarEdit(): State = copy(requestDock = requestDock.applyBarEdit(draft)).clearEditedUndoDraft()

fun State.clearEditedUndoDraft(): State {
    val current = parkedComposer?.text ?: draft
    val restored = undoSnapshot?.restoredDraft
    return if (restored != null && current != restored) withUndoSnapshot(null) else this
}

/** Your own answer's mark: it takes the bar's words as the answer, or sets them aside for the choices. Returns whether it changed. */
fun State.toggleCustomAnswer(): Pair<State, Boolean> {
    val (dock, changed) = requestDock.toggleCustomAnswer(draft)
    return copy(requestDock = dock) to changed
}

/** The session's own composer, parked while a request takes its place. */
val State.sessionComposer: State.DraftState get() = parkedComposer ?: composerWithoutDictation

fun State.setSessionComposer(composer: State.DraftState): State =
    if (parkedComposer != null) withParkedComposer(composer) else withDraftState(composer)

/** The composer becomes the answer: what was typed waits, and the bar shows what was written for the docked page. */
fun State.beginAnswering(): State {
    if (parkedComposer != null) return this
    return copy(autocomplete = autocomplete.closePopover())
        .withParkedComposer(draftState)
        .withDraftState(State.DraftState())
        .loadAnswerTarget()
}

/** The docked page or request changed: the bar keeps what it held for the last one and shows what it holds for this one. */
fun State.moveAnswer(): State {
    val shown = answerShownTarget
    val next = if (shown != null) withAnswerDrafts(answerDrafts + (shown to draft)) else this
    return next.loadAnswerTarget()
}

private fun State.loadAnswerTarget(): State {
    val target = answerTarget
    val text = answerText(target)
    return withAnswerShownTarget(target).withDraft(text).withAnswerBarBaseline(text)
}

/** The composer is the session's again, as it was before the request. Anything attached meanwhile stays. */
fun State.endAnswering(): State {
    val parked = parkedComposer ?: return this
    val restored = parked.copy(
        attachments = parked.attachments + pendingAttachments,
        files = parked.files + pendingFiles,
        location = parked.location ?: pendingLocation,
    )
    return updateRequestDock {
        it.copy(parkedComposer = null, answerDrafts = emptyMap(), answerShownTarget = null, answerBarBaseline = "")
    }.withDraftState(restored)
}

val State.answerContext: AnswerContext get() = AnswerContext(wantsRequestDock, answerTarget)

/** Whether the bar's words are the answer, rather than set aside for a choice. */
fun RequestDockAnswer.isCustom(text: String, value: OpenCodeFormValue?): Boolean {
    if (text.isBlank()) return false
    return when (value) {
        is OpenCodeFormValue.Str -> value.value == text
        is OpenCodeFormValue.Strings -> text in value.values
        is OpenCodeFormValue.Number -> true
        else -> false
    }
}
