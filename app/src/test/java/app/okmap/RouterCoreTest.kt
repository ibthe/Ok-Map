package app.okmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterCoreTest {
    @Test
    fun overlappingViaWayRestrictionsUseFailureLinks() {
        val automaton = RestrictionAutomaton(listOf(
            SequenceRestriction(1, intArrayOf(10, 20, 30, 99), intArrayOf()),
            SequenceRestriction(1, intArrayOf(20, 30, 40), intArrayOf())
        ))

        var state = 0
        for (arc in intArrayOf(10, 20, 30)) state = automaton.next(state, arc)

        // The second restriction starts inside the first restriction's prefix.
        assertTrue(automaton.blocks(state, 40))
        assertFalse(automaton.blocks(state, 98))
    }

    @Test
    fun onlyRestrictionAllowsOnlyItsExitArcs() {
        val automaton = RestrictionAutomaton(listOf(
            SequenceRestriction(2, intArrayOf(1, 2), intArrayOf(3, 4))
        ))
        val state = automaton.next(automaton.next(0, 1), 2)

        assertFalse(automaton.blocks(state, 3))
        assertFalse(automaton.blocks(state, 4))
        assertTrue(automaton.blocks(state, 5))
    }

    @Test
    fun overlappingOnlyRestrictionsIntersectAllowedExits() {
        val automaton = RestrictionAutomaton(listOf(
            SequenceRestriction(2, intArrayOf(10, 20), intArrayOf(30, 31)),
            SequenceRestriction(2, intArrayOf(20), intArrayOf(31, 32))
        ))
        val state = automaton.next(automaton.next(0, 10), 20)

        // Both the full prefix and its suffix are active: only their common exit is legal.
        assertTrue(automaton.blocks(state, 30))
        assertFalse(automaton.blocks(state, 31))
        assertTrue(automaton.blocks(state, 32))
    }

    @Test
    fun remainingEtaInterpolatesActualSegmentTravelTime() {
        val route = Route(
            doubleArrayOf(0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 2.0),
            doubleArrayOf(0.0, 100.0, 300.0),
            doubleArrayOf(0.0, 10.0, 50.0),
            emptyList(),
            50.0
        )

        assertEquals(20.0, route.remainingSeconds(200.0), 1e-6)
        assertEquals(0.0, route.remainingSeconds(400.0), 1e-6)
    }
}
