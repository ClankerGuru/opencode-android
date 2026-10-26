package ai.opencode.feature.composer

import ai.opencode.core.store.TestStore
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePermissionRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Port of iOS `RequestAnswerTests.swift` (D-31 B: a pending request's panel grows out of the
 * composer, and the composer's row is your own answer). Only `RequestDockFeature`'s own suite
 * lives here: the rest drive `SessionFeature`/`HomeFeature` (`:feature:session`, drawer) or
 * probe UIKit text views (see docs/parity/composer.md "Tests").
 */
class RequestAnswerTests {
    private val json = Json { ignoreUnknownKeys = true }

    private fun form(id: String = "frm_answer", fields: String): OpenCodeFormRequest = json.decodeFromString(
        OpenCodeFormRequest.serializer(),
        """{"id":"$id","sessionID":"ses_answer","title":"Questions","metadata":{"kind":"question"},"fields":$fields}""",
    )

    private val twoQuestions: OpenCodeFormRequest
        get() = form(
            fields = """[{"key":"q0","type":"string","title":"Interface","description":"Which?","options":[{"value":"Native","label":"Native"}],"custom":true},{"key":"q1","type":"multiselect","title":"Notes","description":"Which notes?","options":[{"value":"Parking","label":"Parking"}],"custom":true}]""",
        )

    private val shell = OpenCodePermissionRequest(id = "per_answer", sessionID = "ses_answer", action = "shell", resources = listOf("rm -rf .lab-output"), save = listOf("rm *"))

    @Test fun requestDockFeatureReducesSelectionPagingAndAnswerEditsExhaustively() = runTest {
        val twoQuestions = twoQuestions
        val store = TestStore(
            RequestDockFeature.State<Unit>(
                permissionRequests = listOf(shell),
                formRequests = listOf(twoQuestions),
                formAnswers = mapOf(twoQuestions.id to twoQuestions.defaultAnswers),
                requestErrors = mapOf(twoQuestions.id to "Required"),
                failedReplyIDs = setOf(twoQuestions.id),
            ),
            RequestDockFeature(),
            this,
        )

        store.send(RequestDockFeature.Action.ReviewRequestSelected(shell.id)) {
            it.copy(selectedRequestID = shell.id)
        }
        store.send(RequestDockFeature.Action.ReviewRequestSelected(twoQuestions.id)) {
            it.copy(selectedRequestID = twoQuestions.id)
        }
        store.send(RequestDockFeature.Action.QuestionPageChanged(requestID = twoQuestions.id, index = 1)) {
            it.copy(questionPageIndices = it.questionPageIndices + (twoQuestions.id to 1))
        }
        store.send(
            RequestDockFeature.Action.FormValueChanged(formID = twoQuestions.id, key = "q1", value = OpenCodeFormValue.Strings(listOf("Parking"))),
        ) {
            it.copy(
                formAnswers = it.formAnswers + (twoQuestions.id to (it.formAnswers.getValue(twoQuestions.id) + ("q1" to OpenCodeFormValue.Strings(listOf("Parking"))))),
                requestErrors = it.requestErrors - twoQuestions.id,
                failedReplyIDs = it.failedReplyIDs - twoQuestions.id,
            )
        }
        store.finish()
    }
}
