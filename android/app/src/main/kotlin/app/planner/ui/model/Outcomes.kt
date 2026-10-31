package app.planner.ui.model

import app.planner.engine.ports.ImportReport
import app.planner.engine.ports.ProposalOutcome

/** What a screen does with a [ProposalOutcome]: open the approval card, or say why nothing was proposed. */
data class OutcomeUi(val openChangeSetId: String?, val message: String?)

object Outcomes {
    fun describe(o: ProposalOutcome): OutcomeUi = when (o) {
        is ProposalOutcome.Proposed -> OutcomeUi(o.changeSet.id, null)
        is ProposalOutcome.NeedsChoice ->
            OutcomeUi(null, "${o.question} (${o.candidates.size} matches). Ask in chat to pick one.")
        is ProposalOutcome.Rejected ->
            OutcomeUi(null, "Not proposed: " + o.violations.joinToString("; ").ifBlank { "it breaks a planning rule" })
        is ProposalOutcome.NothingToDo -> OutcomeUi(null, o.reason)
    }

    fun importMessage(r: ImportReport): String {
        val parts = buildList {
            add("${r.imported} imported")
            if (r.updated > 0) add("${r.updated} updated")
            if (r.needsALook > 0) add("${r.needsALook} ${if (r.needsALook == 1) "needs" else "need"} a look")
        }
        return if (r.imported == 0 && r.updated == 0 && r.needsALook == 0) "No template items found" else parts.joinToString(", ")
    }
}
