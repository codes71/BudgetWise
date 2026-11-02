package app.budgetwise.ui.receipt

import androidx.lifecycle.SavedStateHandle
import app.budgetwise.data.model.EntryItem
import app.budgetwise.data.model.Source
import app.budgetwise.data.repo.NewTransaction
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeCategorySource
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.testTransaction
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val day = LocalDate.parse("2026-11-02")
    private val milk = EntryItem("Fresh milk 1L", 2, 4500)
    private val bread = EntryItem("Bread", 2200)
    private val soap = EntryItem("Dish soap 2L", 12000)
    private val groceries = entry("Groceries", milk, bread)
    private val other = entry("Other", soap)
    private val rice = testTransaction("Rice", 3600, day)
    private val store = FakeTransactionStore(listOf(groceries, other, rice))

    /** An entry saved from the Ocean Supercenter receipt, at what its [items] come to. */
    private fun entry(category: String, vararg items: EntryItem) =
        testTransaction("Ocean Supercenter, $category", items.sumOf { it.amountKyat }, day, category = category)
            .copy(source = Source.SCAN, receiptId = RECEIPT, merchant = "Ocean Supercenter", items = items.toList())

    private fun TestScope.vm() = ReceiptViewModel(
        store, FakeCategorySource(), FakeSettingsStore(), SavedStateHandle(mapOf(ReceiptViewModel.RECEIPT_ID to RECEIPT)),
    ).also { vm -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} } }

    private fun saved(category: String, amount: Long, description: String, vararg items: EntryItem) = NewTransaction(
        type = TxType.EXPENSE, amountKyat = amount, category = category, description = description, date = day,
        source = Source.SCAN, transcript = null, receiptId = RECEIPT, merchant = "Ocean Supercenter", items = items.toList(),
    )

    @Test
    fun showsTheReceiptsEntriesLargestFirst() = runTest {
        val s = vm().state.value

        assertFalse(s.loading)
        assertEquals(listOf(other, groceries), s.entries)
        assertEquals(23200L, s.totalKyat)
        assertEquals("Ocean Supercenter", s.merchant)
        assertEquals(day, s.date)
        assertFalse(s.gone)
    }

    @Test
    fun anotherCategoryReplacesTheEntryAndKeepsTheRest() = runTest {
        val vm = vm()

        vm.move(groceries.clientId, "Health") { "Ocean Supercenter, $it" }

        val (old, new) = store.replaced.single()
        assertEquals(listOf(groceries.clientId), old)
        assertEquals(listOf(saved("Health", 11200, "Ocean Supercenter, Health", milk, bread)), new)
        assertEquals(setOf("Health", "Other"), vm.state.value.entries.map { it.category }.toSet())
    }

    @Test
    fun aDescriptionTheUserWroteStaysWhenTheCategoryChanges() = runTest {
        store.rows.value = listOf(groceries.copy(description = "Weekly shop"), other)
        val vm = vm()

        vm.move(groceries.clientId, "Health") { "Ocean Supercenter, $it" }

        assertEquals("Weekly shop", store.replaced.single().second.single().description)
    }

    @Test
    fun deletingTheReceiptDeletesEveryEntryInOneGo() = runTest {
        val vm = vm()

        vm.delete()

        val (old, new) = store.replaced.single()
        assertEquals(setOf(groceries.clientId, other.clientId), old.toSet())
        assertTrue(new.isEmpty())
        assertTrue("the screen closes", vm.state.value.gone)
        assertEquals(listOf(rice), store.rows.value)
    }

    @Test
    fun changeItemsSavesOneEntryPerCategoryAtWhatItsItemsComeTo() = runTest {
        val vm = vm()
        vm.startEditing()
        assertEquals(listOf("Other", "Groceries"), vm.state.value.editing?.map { it.category })
        assertFalse("nothing changed yet", vm.state.value.canSave)

        vm.setCount(1, 0, 1) // milk: 2 to 1
        vm.setCount(1, 1, 0) // bread: not bought
        vm.setPrice(0, 0, 11000) // soap: 12,000 to 11,000
        assertEquals(3, vm.state.value.changedItems)
        assertEquals(4500L + 11000L, vm.state.value.editedTotal)
        vm.saveItems()

        val (old, new) = store.replaced.single()
        assertEquals(setOf(groceries.clientId, other.clientId), old.toSet())
        assertEquals(
            listOf(
                saved("Other", 11000, "Ocean Supercenter, Other", EntryItem("Dish soap 2L", 1, 11000)),
                saved("Groceries", 4500, "Ocean Supercenter, Groceries", EntryItem("Fresh milk 1L", 1, 4500)),
            ),
            new,
        )
        assertNull("back to the receipt", vm.state.value.editing)
        assertEquals(15500L, vm.state.value.totalKyat)
    }

    @Test
    fun aCategoryWithNothingLeftIsDropped() = runTest {
        val vm = vm()
        vm.startEditing()

        vm.setCount(0, 0, 0)
        vm.saveItems()

        assertEquals(listOf("Groceries"), store.replaced.single().second.map { it.category })
        assertEquals(listOf(groceries.items), store.replaced.single().second.map { it.items })
    }

    @Test
    fun leavingEveryItemAtZeroSavesNothing() = runTest {
        val vm = vm()
        vm.startEditing()

        vm.setCount(0, 0, 0)
        vm.setCount(1, 0, 0)
        vm.setCount(1, 1, 0)
        vm.saveItems()

        assertFalse(vm.state.value.canSave)
        assertTrue(store.replaced.isEmpty())
    }

    @Test
    fun theReceiptIsGoneOnceItsEntriesAre() = runTest {
        val vm = vm()
        assertFalse(vm.state.value.gone)

        // Deleted from the list, on the web, or another account signed in.
        store.rows.value = listOf(rice)

        assertTrue(vm.state.value.gone)
    }

    private companion object {
        const val RECEIPT = "r1"
    }
}
