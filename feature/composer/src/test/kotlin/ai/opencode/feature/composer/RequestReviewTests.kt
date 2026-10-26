package ai.opencode.feature.composer

import ai.opencode.server.model.OpenCodeFormOption
import ai.opencode.server.model.OpenCodeFormValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of iOS `RequestReviewTests.swift`: the answer-reading helpers the dock owns. The
 * `SessionFeature` reply/refresh flows live with `:feature:session`, typed numbers with
 * `:server` (`OpenCodeFormValue`), and the settled-question row with `:feature:transcript`
 * (see docs/parity/composer.md "Tests").
 */
class RequestReviewTests {
    // MARK: Reading answers

    @Test fun dockAnswersReadOtherAndReadiness() {
        val options = listOf(OpenCodeFormOption(value = "Parking", label = "Parking"))
        assertEquals("7:50", RequestDockAnswer.customText(OpenCodeFormValue.Str("7:50"), options))
        assertEquals("", RequestDockAnswer.customText(OpenCodeFormValue.Str("Parking"), options))
        assertEquals("Sam", RequestDockAnswer.customText(OpenCodeFormValue.Strings(listOf("Parking", "Sam")), options))
        assertFalse(RequestDockAnswer.hasAnswer(OpenCodeFormValue.Str("  ")))
        assertFalse(RequestDockAnswer.hasAnswer(OpenCodeFormValue.Strings(emptyList())))
        assertFalse(RequestDockAnswer.hasAnswer(null))
        assertTrue(RequestDockAnswer.hasAnswer(OpenCodeFormValue.Strings(listOf("Parking"))))
        assertTrue(RequestDockAnswer.isSelected("true", OpenCodeFormValue.Boolean(true)))
    }
}
