package app.planner.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BudgetInputTest {
    @Test
    fun `hours and minutes become total minutes`() {
        assertEquals(135, BudgetInput.toMinutes("2", "15"))
        assertEquals(120, BudgetInput.toMinutes("2", "0"))
        assertEquals(45, BudgetInput.toMinutes("0", "45"))
    }

    @Test
    fun `a blank field counts as zero`() {
        assertEquals(120, BudgetInput.toMinutes("2", ""))
        assertEquals(30, BudgetInput.toMinutes("", "30"))
        assertEquals(60, BudgetInput.toMinutes(" 1 ", " "))
    }

    @Test
    fun `a budget must be more than zero`() {
        assertNull(BudgetInput.toMinutes("0", "0"))
        assertNull(BudgetInput.toMinutes("", ""))
    }

    @Test
    fun `anything that is not a plain number is rejected`() {
        assertNull(BudgetInput.toMinutes("two", "0"))
        assertNull(BudgetInput.toMinutes("1", "3o"))
        assertNull(BudgetInput.toMinutes("-1", "30"))
        assertNull(BudgetInput.toMinutes("1.5", "0"))
    }

    @Test
    fun `minutes stay under an hour and the total under a day`() {
        assertNull(BudgetInput.toMinutes("1", "60"))
        assertEquals(59, BudgetInput.toMinutes("0", "59"))
        assertEquals(23 * 60 + 59, BudgetInput.toMinutes("23", "59"))
        assertNull(BudgetInput.toMinutes("24", "0"))
        assertNull(BudgetInput.toMinutes("99999999999", "0"))
    }

    @Test
    fun `total minutes split back into hours and minutes`() {
        assertEquals(2 to 15, BudgetInput.split(135))
        assertEquals(3 to 0, BudgetInput.split(180))
        assertEquals(0 to 45, BudgetInput.split(45))
    }
}
