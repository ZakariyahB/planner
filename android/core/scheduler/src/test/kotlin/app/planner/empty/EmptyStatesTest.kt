package app.planner.empty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmptyStatesTest {
    @Test
    fun `an empty Today offers to fill the week from the template when there is one`() {
        val empty = EmptyStates.tasks(templateItems = 5, today = true)
        assertEquals("Nothing due today", empty.card.title)
        assertEquals(TemplateAction.GENERATE, empty.template)
        assertEquals("Use weekly template", empty.template.label)
        assertTrue("weekly template" in empty.card.body, empty.card.body)
    }

    @Test
    fun `without a template the second button leads to making one`() {
        val empty = EmptyStates.tasks(templateItems = 0, today = true)
        assertEquals(TemplateAction.SET_UP, empty.template)
        assertEquals("Set up weekly template", empty.template.label)
        assertTrue("every week" in empty.card.body, empty.card.body)
    }

    @Test
    fun `the Tasks tab says it has no tasks, not that nothing is due today`() {
        val empty = EmptyStates.tasks(templateItems = 2, today = false)
        assertEquals("No tasks yet", empty.card.title)
        assertFalse("today" in empty.card.body.lowercase(), empty.card.body)
    }

    @Test
    fun `an empty calendar says what is waiting and that Replan places it`() {
        val card = EmptyStates.calendar(openTasks = 3, calendarConnected = true)
        assertEquals("Nothing planned for these days", card.title)
        assertEquals("3 tasks are waiting. Replan places them around your commitments.", card.body)
        assertEquals("1 task is waiting. Replan places it around your commitments.", EmptyStates.calendar(1, true).body)
    }

    @Test
    fun `an empty calendar with no tasks says to add some first`() {
        val card = EmptyStates.calendar(openTasks = 0, calendarConnected = true)
        assertEquals("Add a task or use your weekly template, then Replan fits it around your commitments.", card.body)
    }

    @Test
    fun `an empty calendar reminds you to connect Google Calendar when it isn't`() {
        val card = EmptyStates.calendar(openTasks = 2, calendarConnected = false)
        assertTrue(card.body.endsWith("Connect Google Calendar in Settings so your classes are planned around too."), card.body)
        assertFalse("Connect Google Calendar" in EmptyStates.calendar(2, true).body)
    }

    @Test
    fun `generating from the template says what happened`() {
        assertEquals("Added 1 task", EmptyStates.generated(created = 1, templateItems = 4))
        assertEquals("Added 6 tasks", EmptyStates.generated(created = 6, templateItems = 4))
        assertEquals("Nothing new: those tasks already exist", EmptyStates.generated(created = 0, templateItems = 4))
    }

    @Test
    fun `generating from an empty template points to where to fill it in`() {
        assertEquals(
            "Your weekly template is empty. Add the things you repeat every week in Settings, under Weekly template.",
            EmptyStates.generated(created = 0, templateItems = 0),
        )
    }
}
