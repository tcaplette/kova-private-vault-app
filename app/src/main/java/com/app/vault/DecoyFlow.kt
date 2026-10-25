package com.app.vault

/**
 * The order the decoy flow is walked in — `decoy-tooling`.
 *
 * **Deliberately not `Onboarding.Step`.** That enum is first run's: it is persisted,
 * it drives `Onboarding.finish`, and the decoy is one entry inside it. This flow is
 * reachable from Settings as well as from that entry, runs to five steps of its own,
 * and is never resumed — a flow whose position survived the process would be a record
 * that it ran, which `decoy-tooling` forbids.
 *
 * Nothing here is written down. The position lives in the activity for as long as the
 * flow is on screen and dies with it.
 */
object DecoyFlow {

    enum class Step {
        /** What a decoy is, and that its photos will be seen by someone else. */
        Intro,

        /** The picker, and whether what came back can carry a plausible history. */
        Photos,

        /** Name, passphrase, confirm — what this vault is called and how it opens. */
        Identity,

        /** What is about to happen, who is authorising it, and what else to keep. */
        Build,

        /** The switch that has just happened, and the vault as it will be found. */
        Handover;

        /** The next step, or null at the end. */
        val next: Step? get() = entries.getOrNull(ordinal + 1)

        /** The previous step, or null at the start. */
        val previous: Step? get() = entries.getOrNull(ordinal - 1)

        /** "Step 2 of 5", for the frame. One-based, because people count from one. */
        val position: Int get() = ordinal + 1
    }

    /** How many steps there are, for the frame and its progress rail. */
    val TOTAL = Step.entries.size

    /**
     * Whether [step] may be left by going back.
     *
     * False at [Step.Intro], where back leaves the flow instead, and false at
     * [Step.Handover], where the vault already exists and the steps behind it describe
     * work that cannot be undone — a Back there would offer to re-choose photos for a
     * vault that has already been filled with them.
     */
    fun canGoBack(step: Step): Boolean = step != Step.Intro && step != Step.Handover
}
