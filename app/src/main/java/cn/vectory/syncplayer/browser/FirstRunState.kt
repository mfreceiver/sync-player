package cn.vectory.syncplayer.browser

/**
 * Coarse onboarding state derived from the current library contents.
 *
 * Drives the first-run guidance card shown on the browser root when the library
 * is empty. Each value maps to exactly one "next step" the user should take:
 *
 * - [NEEDS_SOURCE]:   no sync sources configured yet.
 * - [NEEDS_RELATION]: at least one source exists, but no sync relation.
 * - [NEEDS_SYNC]:     a relation exists, but no media has been synced.
 * - [READY]:          media is available — normal browsing.
 *
 * Kept as a standalone enum (not nested in the ViewModel) so the derivation
 * logic can be unit-tested as a pure function with no Android/Flow dependencies.
 */
enum class FirstRunState {
    NEEDS_SOURCE,
    NEEDS_RELATION,
    NEEDS_SYNC,
    READY
}

/**
 * Pure derivation of [FirstRunState] from three boolean signals.
 *
 * The precedence is: source → relation → media. A user cannot create a relation
 * without a source, and cannot have synced media without a relation, so the
 * checks short-circuit in that order.
 */
internal fun deriveFirstRunState(
    hasSource: Boolean,
    hasRelation: Boolean,
    hasMedia: Boolean
): FirstRunState = when {
    !hasSource -> FirstRunState.NEEDS_SOURCE
    !hasRelation -> FirstRunState.NEEDS_RELATION
    !hasMedia -> FirstRunState.NEEDS_SYNC
    else -> FirstRunState.READY
}
