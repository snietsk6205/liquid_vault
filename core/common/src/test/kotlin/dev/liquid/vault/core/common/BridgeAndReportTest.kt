package dev.liquid.vault.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeAndReportTest {
    private val own = "dev.liquid.vault"
    private val pickers = setOf("com.google.android.documentsui")

    @Test
    fun bridgeAllowsItselfThePickerAndTheAllowedList() {
        assertTrue(BridgeRules.decide(BridgeCaller(own, 0), own, emptySet(), pickers) is BridgeDecision.Allowed)
        assertTrue(BridgeRules.decide(BridgeCaller("com.google.android.documentsui", 0), own, emptySet(), pickers) is BridgeDecision.Allowed)
        assertTrue(BridgeRules.decide(BridgeCaller("me.zhanghai.android.files", 0), own, setOf("me.zhanghai.android.files"), pickers) is BridgeDecision.Allowed)
    }

    @Test
    fun bridgeRefusesOthersAndUnknownCallers() {
        val other = BridgeRules.decide(BridgeCaller("com.other.app", 0), own, setOf("me.zhanghai.android.files"), pickers)
        assertTrue(other is BridgeDecision.Refused)
        assertTrue(other.reason.contains("com.other.app"))
        assertTrue(BridgeRules.decide(BridgeCaller(null, 0), own, emptySet(), pickers) is BridgeDecision.Refused)
    }

    @Test
    fun confirmationNeedsTheExactPhrase() {
        assertTrue(Confirm.matches("DESTROY VAULT", Confirm.DESTROY_VAULT))
        assertTrue(Confirm.matches("  DESTROY VAULT ", Confirm.DESTROY_VAULT))
        assertFalse(Confirm.matches("destroy vault", Confirm.DESTROY_VAULT))
        assertFalse(Confirm.matches("DESTROY", Confirm.DESTROY_VAULT))
        assertFalse(Confirm.matches("", Confirm.DESTROY_VAULT))
    }

    @Test
    fun everyCheckIdIsUniqueAndEveryGroupIsUsed() {
        val ids = PhaseZero.checks.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(PhaseZero.forSide(vaultSide = true).none { it.side == Side.PERSONAL })
        assertTrue(PhaseZero.forSide(vaultSide = false).none { it.side == Side.VAULT })
    }

    @Test
    fun reportShowsStatusSummaryAndEvidence() {
        val def = PhaseZero.def("S3")
        val result = CheckResult("S3", CheckStatus.PASS, "runs as the shell user", listOf("$ id\nuid=2000(shell)"), atMillis = 1)
        val text = Report.render("Liquid Vault report", listOf("Side" to "Personal"), listOf(def, PhaseZero.def("S1")), mapOf("S3" to result))
        assertTrue(text.contains("[PASS] S3 Run a command — runs as the shell user"))
        assertTrue(text.contains("    · uid=2000(shell)"))
        assertTrue(text.contains("[NOT RUN] S1"))
        assertTrue(text.contains("Totals: 1 NOT RUN, 1 PASS"))
    }

    @Test
    fun resultsSurviveAJsonRoundTrip() {
        val book = CheckBook("personal", 0).with(CheckResult("V0", CheckStatus.FAIL, "a work profile exists", listOf("user 11")))
        val back = VaultJson.decodeFromString(CheckBook.serializer(), VaultJson.encodeToString(CheckBook.serializer(), book))
        assertEquals(book, back)
    }

    @Test
    fun bootRecordsWithinAMinuteAreTheSameBoot() {
        val r = BootRecord("vault", 11, bootWallMillis = 1_000_000, recordedAtMillis = 1_050_000, profileOwner = true)
        assertTrue(r.sameBootAs(1_030_000))
        assertFalse(r.sameBootAs(1_200_000))
    }

    @Test
    fun exceptionsBecomeReadableEvidence() {
        val e = SecurityException("Admin is not affiliated", IllegalStateException("x"))
        assertEquals("SecurityException: Admin is not affiliated (cause: IllegalStateException: x)", e.asEvidence())
    }
}
