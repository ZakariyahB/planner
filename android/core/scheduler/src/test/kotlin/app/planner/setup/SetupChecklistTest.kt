package app.planner.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupChecklistTest {
    private val nothing = SetupState(
        calendarConnected = false,
        healthConnected = false,
        fajrSet = false,
        notificationsAllowed = false,
        exactAlarmsAllowed = false,
        usageAccessGranted = false,
    )

    @Test
    fun `a fresh install has six things to do, in a friendly order`() {
        val items = SetupChecklist.items(nothing)
        assertEquals(
            listOf(SetupStep.CALENDAR, SetupStep.HEALTH, SetupStep.FAJR, SetupStep.NOTIFICATIONS, SetupStep.EXACT_ALARMS, SetupStep.USAGE_ACCESS),
            items.map { it.step },
        )
        assertTrue(items.none { it.done })
        assertEquals(0, SetupChecklist.doneCount(items))
    }

    @Test
    fun `finished steps are ticked`() {
        val items = SetupChecklist.items(nothing.copy(calendarConnected = true, fajrSet = true))
        assertEquals(setOf(SetupStep.CALENDAR, SetupStep.FAJR), items.filter { it.done }.map { it.step }.toSet())
        assertEquals(2, SetupChecklist.doneCount(items))
    }

    @Test
    fun `the checklist is complete only when everything is done`() {
        val all = nothing.copy(true, true, true, true, true, true)
        assertTrue(SetupChecklist.isComplete(SetupChecklist.items(all)))
        assertFalse(SetupChecklist.isComplete(SetupChecklist.items(all.copy(usageAccessGranted = false))))
    }

    @Test
    fun `a phone without Health Connect is not asked for it`() {
        val items = SetupChecklist.items(nothing.copy(healthAvailable = false))
        assertFalse(items.any { it.step == SetupStep.HEALTH })
        assertEquals(5, items.size)
    }

    @Test
    fun `progress is shown as a fraction of the steps that apply`() {
        val items = SetupChecklist.items(nothing.copy(calendarConnected = true, healthConnected = true))
        assertEquals(2.0 / 6, SetupChecklist.progress(items), 1e-9)
        assertEquals(1.0, SetupChecklist.progress(emptyList()), 1e-9)
    }

    @Test
    fun `every step explains itself and names its button`() {
        for (step in SetupStep.entries) {
            assertTrue(step.title.isNotBlank() && step.why.isNotBlank() && step.action.isNotBlank(), step.name)
        }
    }

    @Test
    fun `the next thing to do is the first step that is not done`() {
        assertEquals(SetupStep.HEALTH, SetupChecklist.next(SetupChecklist.items(nothing.copy(calendarConnected = true))))
        assertEquals(null, SetupChecklist.next(SetupChecklist.items(nothing.copy(true, true, true, true, true, true))))
    }
}
