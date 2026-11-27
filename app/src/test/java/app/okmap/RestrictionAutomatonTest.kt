package app.okmap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionAutomatonTest {
    private fun stateAfter(automaton: RestrictionAutomaton, vararg arcs: Int): Int {
        var state = 0
        for (arc in arcs) state = automaton.next(state, arc)
        return state
    }

    @Test
    fun noViaWayRestrictionBlocksOnlyTheForbiddenContinuation() {
        val automaton = RestrictionAutomaton(
            listOf(SequenceRestriction(type = 1, prefix = intArrayOf(10, 20, 30), allowed = intArrayOf()))
        )
        val state = stateAfter(automaton, 10, 20)

        assertTrue(automaton.blocks(state, 30))
        assertFalse(automaton.blocks(state, 40))
    }

    @Test
    fun onlyViaWayRestrictionAllowsItsContinuationAndRejectsOtherExits() {
        val automaton = RestrictionAutomaton(
            listOf(SequenceRestriction(type = 2, prefix = intArrayOf(10, 20), allowed = intArrayOf(30)))
        )
        val state = stateAfter(automaton, 10, 20)

        assertFalse(automaton.blocks(state, 30))
        assertTrue(automaton.blocks(state, 40))
    }

    @Test
    fun overlappingViaWaySuffixRemainsActiveAfterAhoCorasickFallback() {
        val automaton = RestrictionAutomaton(
            listOf(
                SequenceRestriction(type = 1, prefix = intArrayOf(10, 20, 30), allowed = intArrayOf()),
                SequenceRestriction(type = 1, prefix = intArrayOf(20, 30, 40), allowed = intArrayOf())
            )
        )
        val state = stateAfter(automaton, 10, 20, 30)

        assertTrue("the overlapping suffix 20 -> 30 must still block 40", automaton.blocks(state, 40))
    }

    @Test
    fun overlappingOnlyRestrictionsIntersectTheirAllowedContinuations() {
        val automaton = RestrictionAutomaton(
            listOf(
                SequenceRestriction(type = 2, prefix = intArrayOf(10, 20), allowed = intArrayOf(30, 40)),
                SequenceRestriction(type = 2, prefix = intArrayOf(20), allowed = intArrayOf(40, 50))
            )
        )
        val state = stateAfter(automaton, 10, 20)

        assertTrue("only the intersection is allowed", automaton.blocks(state, 30))
        assertFalse(automaton.blocks(state, 40))
        assertTrue(automaton.blocks(state, 50))
    }
}
