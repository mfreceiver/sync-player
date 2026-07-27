package cn.vectory.syncplayer.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic unit tests for [deriveFirstRunState].
 *
 * Asserts the four-state mapping from (hasSource, hasRelation, hasMedia) booleans.
 * No Android, no Flow, no DB — only the derivation function.
 *
 * Per the project testing convention: assertions are on enums, not user-facing text.
 */
class FirstRunStateTest {

    @Test
    fun noSource_noRelation_noMedia_needsSource() {
        assertEquals(
            FirstRunState.NEEDS_SOURCE,
            deriveFirstRunState(hasSource = false, hasRelation = false, hasMedia = false)
        )
    }

    @Test
    fun hasSource_noRelation_noMedia_needsRelation() {
        assertEquals(
            FirstRunState.NEEDS_RELATION,
            deriveFirstRunState(hasSource = true, hasRelation = false, hasMedia = false)
        )
    }

    @Test
    fun hasSource_hasRelation_noMedia_needsSync() {
        assertEquals(
            FirstRunState.NEEDS_SYNC,
            deriveFirstRunState(hasSource = true, hasRelation = true, hasMedia = false)
        )
    }

    @Test
    fun hasSource_hasRelation_hasMedia_ready() {
        assertEquals(
            FirstRunState.READY,
            deriveFirstRunState(hasSource = true, hasRelation = true, hasMedia = true)
        )
    }

    // ── Precedence: source is checked before relation before media ──

    @Test
    fun noSource_butHasRelation_stillNeedsSource() {
        // Logically unreachable in production (a relation requires a source), but the
        // derivation function is pure and must short-circuit on source first regardless.
        assertEquals(
            FirstRunState.NEEDS_SOURCE,
            deriveFirstRunState(hasSource = false, hasRelation = true, hasMedia = false)
        )
    }

    @Test
    fun noSource_butHasMedia_stillNeedsSource() {
        assertEquals(
            FirstRunState.NEEDS_SOURCE,
            deriveFirstRunState(hasSource = false, hasRelation = false, hasMedia = true)
        )
    }

    @Test
    fun hasSource_noRelation_butHasMedia_stillNeedsRelation() {
        // Precedence: relation is checked before media.
        assertEquals(
            FirstRunState.NEEDS_RELATION,
            deriveFirstRunState(hasSource = true, hasRelation = false, hasMedia = true)
        )
    }

    @Test
    fun allFourStatesAreDistinct() {
        // Sanity: the enum has exactly four values, and each input combination maps to
        // a different one along the canonical path.
        val canonical = listOf(
            deriveFirstRunState(false, false, false),
            deriveFirstRunState(true, false, false),
            deriveFirstRunState(true, true, false),
            deriveFirstRunState(true, true, true)
        )
        assertEquals(4, canonical.toSet().size)
    }
}
