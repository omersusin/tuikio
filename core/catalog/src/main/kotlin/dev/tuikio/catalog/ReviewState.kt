package dev.tuikio.catalog

/**
 * Life of a community-suggested source or game. Discovery, listing and installability are
 * separate steps: [APPROVED_SOURCE] only means "listed"; an Install button additionally
 * needs the game itself to reach [VerificationStatus.STORE_READY].
 */
enum class ReviewState {
    /** Saved on one phone only. Never leaves the device without a separate action. */
    PERSONAL_SOURCE,
    SUBMITTED,
    PENDING_REVIEW,
    CHANGES_REQUESTED,
    APPROVED_SOURCE,
    COMPATIBILITY_TESTING,
    STORE_READY,
    REJECTED,
    DELISTED;

    val isTerminal: Boolean get() = this == REJECTED

    fun canMoveTo(next: ReviewState): Boolean = next in (TRANSITIONS[this] ?: emptySet())

    companion object {
        private val TRANSITIONS: Map<ReviewState, Set<ReviewState>> = mapOf(
            PERSONAL_SOURCE to setOf(SUBMITTED),
            SUBMITTED to setOf(PENDING_REVIEW, REJECTED),
            PENDING_REVIEW to setOf(CHANGES_REQUESTED, APPROVED_SOURCE, REJECTED),
            CHANGES_REQUESTED to setOf(PENDING_REVIEW, REJECTED),
            APPROVED_SOURCE to setOf(COMPATIBILITY_TESTING, DELISTED),
            COMPATIBILITY_TESTING to setOf(STORE_READY, APPROVED_SOURCE, DELISTED),
            STORE_READY to setOf(DELISTED, COMPATIBILITY_TESTING),
            DELISTED to setOf(PENDING_REVIEW),
            REJECTED to emptySet(),
        )

        fun parse(s: String): ReviewState? = values().firstOrNull { it.name == s }
    }
}
