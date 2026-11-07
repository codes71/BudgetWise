package app.budgetwise.ui.receipt

import androidx.lifecycle.SavedStateHandle
import app.budgetwise.TransactionStore
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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

    /** [saved] stands for what Android keeps of the screen when the process ends; share it to start again from it. */
    private fun TestScope.vm(
        saved: SavedStateHandle = SavedStateHandle(mapOf(ReceiptViewModel.RECEIPT_ID to RECEIPT)),
        transactions: TransactionStore = store,
    ) = ReceiptViewModel(
        transactions, FakeCategorySource(), FakeSettingsStore(), saved,
    ).also { vm -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} } }

    private fun TestScope.events(vm: ReceiptViewModel): List<ReceiptEvent> {
        val events = mutableListOf<ReceiptEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    /** The app's descriptions in English, the language it is in now, and in Burmese. */
    private val english = DescriptionLanguage("%1\$s, %2\$s") { it }
    private val burmese = DescriptionLanguage("%1\$s၊ %2\$s") { mapOf("Groceries" to "ကုန်စုံ", "Health" to "ကျန်းမာရေး")[it] ?: it }
    private val descriptions = EntryDescriptions(english, listOf(english, burmese))

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

        vm.move(groceries.clientId, "Health", descriptions)

        val (old, new) = store.replaced.single()
        assertEquals(listOf(groceries.clientId), old)
        assertEquals(listOf(saved("Health", 11200, "Ocean Supercenter, Health", milk, bread)), new)
        assertEquals(setOf("Health", "Other"), vm.state.value.entries.map { it.category }.toSet())
    }

    @Test
    fun aDescriptionTheUserWroteStaysWhenTheCategoryChanges() = runTest {
        store.rows.value = listOf(groceries.copy(description = "Weekly shop"), other)
        val vm = vm()

        vm.move(groceries.clientId, "Health", descriptions)

        assertEquals("Weekly shop", store.replaced.single().second.single().description)
    }

    @Test
    fun aDescriptionWrittenInTheOtherLanguageIsWrittenAgain() = runTest {
        // Scanned in Burmese, moved in English.
        store.rows.value = listOf(groceries.copy(description = "Ocean Supercenter၊ ကုန်စုံ"), other)
        vm().move(groceries.clientId, "Health", descriptions)
        assertEquals("Ocean Supercenter, Health", store.replaced.last().second.single().description)

        // Scanned in English, moved in Burmese.
        store.rows.value = listOf(groceries, other)
        vm().move(groceries.clientId, "Health", EntryDescriptions(burmese, listOf(english, burmese)))
        assertEquals("Ocean Supercenter၊ ကျန်းမာရေး", store.replaced.last().second.single().description)
    }

    @Test
    fun aShopNameCutWhenItWasStoredStillMatchesItsDescription() = runTest {
        val shop = "Ocean Supercenter ".repeat(8).trim() // 143 characters; stored as its first 100
        store.rows.value = listOf(groceries.copy(merchant = shop.take(100), description = "$shop, Groceries"), other)

        vm().move(groceries.clientId, "Health", descriptions)

        assertEquals("${shop.take(100)}, Health", store.replaced.single().second.single().description)
    }

    @Test
    fun movingAnEntryIntoACategoryTheReceiptHasJoinsTheEntryThere() = runTest {
        val vm = vm()

        vm.move(other.clientId, "Groceries", descriptions)

        assertEquals(
            listOf(groceries.clientId, other.clientId) to listOf(saved("Groceries", 23200, "Ocean Supercenter, Groceries", milk, bread, soap)),
            store.replaced.single(),
        )
        assertEquals(1, vm.state.value.entries.size)
    }

    @Test
    fun movingAnEntryWhoseItemsWouldNotFitTheEntryThereKeepsThemApart() = runTest {
        val many = List(60) { EntryItem("item $it", 100) }
        store.rows.value = listOf(entry("Groceries", *many.toTypedArray()), entry("Other", *many.toTypedArray()))
        val vm = vm()

        vm.move(store.rows.value[1].clientId, "Groceries", descriptions)

        assertEquals(listOf("Groceries", "Groceries"), vm.state.value.entries.map { it.category })
        assertEquals(listOf(60, 60), vm.state.value.entries.map { it.items.size })
    }

    @Test
    fun deleteHandsTheReceiptsEntriesToTheListForItsUndo() = runTest {
        val vm = vm()
        val events = events(vm)

        vm.delete()

        val delete = events.single() as ReceiptEvent.Delete
        assertEquals(RECEIPT, delete.receiptId)
        assertEquals(setOf(groceries.clientId, other.clientId), delete.clientIds.toSet())
        assertTrue("the list deletes them, after its Undo", store.replaced.isEmpty())
        assertFalse(vm.state.value.gone)
    }

    @Test
    fun deleteHandsOverTheEntriesRoomHasNowNotTheOnesFirstShown() = runTest {
        val vm = vm()
        val events = events(vm)
        vm.move(groceries.clientId, "Health", descriptions) // a new id for the moved entry

        vm.delete()

        assertEquals(vm.state.value.entries.map { it.clientId }.toSet(), (events.single() as ReceiptEvent.Delete).clientIds.toSet())
    }

    @Test
    fun withoutTheListTheReceiptIsDeletedAtOnce() = runTest {
        val vm = vm()

        vm.deleteNow(listOf(groceries.clientId, other.clientId))

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
        assertFalse(vm.state.value.allLeftOut)

        vm.setCount(0, 0, 0)
        vm.setCount(1, 0, 0)
        vm.setCount(1, 1, 0)
        vm.saveItems()

        assertTrue("Delete this receipt is offered instead", vm.state.value.allLeftOut)
        assertFalse(vm.state.value.canSave)
        assertTrue(store.replaced.isEmpty())
    }

    @Test
    fun aReceiptWithoutItemsHasNothingForChangeItems() = runTest {
        store.rows.value = listOf(groceries.copy(items = emptyList()), other.copy(items = emptyList()))
        assertFalse(vm().state.value.canChangeItems)

        store.rows.value = listOf(groceries, other.copy(items = emptyList()))
        assertTrue(vm().state.value.canChangeItems)
    }

    @Test
    fun entriesOfOneCategoryWithTooManyItemsTogetherAreChangedApart() = runTest {
        val many = List(60) { EntryItem("item $it", 100) }
        store.rows.value = listOf(entry("Groceries", *many.toTypedArray()), entry("Groceries", *many.toTypedArray()))
        val vm = vm()

        vm.startEditing()
        assertEquals(listOf(60, 60), vm.state.value.editing?.map { it.items.size })
        vm.setCount(0, 0, 2)
        vm.saveItems()

        assertEquals("every item is kept", listOf(60, 60), store.replaced.single().second.map { it.items.size })
    }

    @Test
    fun changeItemsSavesEveryEntryOnTheReceiptsOneDate() = runTest {
        // Saved across two days before a receipt kept one date.
        store.rows.value = listOf(groceries.copy(date = day.minusDays(1)), other)
        val vm = vm()
        assertEquals(day, vm.state.value.date)

        vm.startEditing()
        vm.setCount(1, 0, 1)
        vm.saveItems()

        assertEquals(setOf(day), store.replaced.single().second.map { it.date }.toSet())
    }

    @Test
    fun aReceiptChangedElsewhereIsNotWrittenOver() = runTest {
        val vm = vm()
        vm.startEditing()
        vm.setCount(1, 0, 1)
        assertTrue(vm.state.value.canSave)

        // A sync brings another phone's change: Other moved to Household.
        val household = other.copy(clientId = "household", category = "Household")
        store.rows.value = listOf(groceries, household, rice)

        assertTrue(vm.state.value.receiptChanged)
        assertFalse(vm.state.value.canSave)
        vm.saveItems()
        assertTrue(store.replaced.isEmpty())
        // "Saved as" still names what the lines were built from.
        assertEquals(23200L, vm.state.value.editingBase)

        // Check again: the lines afresh, from the receipt as it is now.
        vm.startEditing()
        assertFalse(vm.state.value.receiptChanged)
        assertEquals(listOf("Household", "Groceries"), vm.state.value.editing?.map { it.category })
    }

    @Test
    fun aSaveThatFindsTheReceiptChangedWritesNothing() = runTest {
        // Changed after the screen's last answer: the store's own check, in its transaction, refuses the save.
        val changedJustNow = object : TransactionStore by store {
            override suspend fun replaceReceiptForOwner(owner: String, receiptId: String, old: Set<String>, inputs: List<NewTransaction>): List<String>? = null
        }
        val vm = vm(transactions = changedJustNow)
        vm.startEditing()
        vm.setCount(1, 0, 1)
        assertFalse(vm.state.value.receiptChanged)

        vm.saveItems()

        assertTrue(store.replaced.isEmpty())
        assertTrue(vm.state.value.receiptChanged)
        assertFalse(vm.state.value.saving)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun changeItemsOutlivesTheProcessWhileItsEntriesStayTheSame() = runTest {
        val saved = SavedStateHandle(mapOf(ReceiptViewModel.RECEIPT_ID to RECEIPT))
        val first = vm(saved)
        first.startEditing()
        first.setCount(1, 0, 1)

        val second = vm(saved)
        assertEquals(first.state.value.editing, second.state.value.editing)
        assertEquals(1, second.state.value.changedItems)

        // Changed by a sync before the screen came back: the receipt itself shows, not stale lines.
        store.rows.value = listOf(groceries, other.copy(clientId = "household", category = "Household"))
        assertNull(vm(saved).state.value.editing)

        // Closed (or saved), nothing is kept.
        store.rows.value = listOf(groceries, other)
        val third = vm(saved)
        third.startEditing()
        third.stopEditing()
        assertNull(vm(saved).state.value.editing)
    }

    private fun TestScope.restoreWhileOwnerChangesTo(nextOwner: String?) {
        val saved = SavedStateHandle(mapOf(ReceiptViewModel.RECEIPT_ID to RECEIPT))
        vm(saved).startEditing()
        assertTrue(saved.contains("receipt_editing"))
        val read = CompletableDeferred<Unit>()
        val delayed = object : TransactionStore by store {
            override fun observeReceipt(receiptId: String) = flow {
                read.await()
                emit(listOf(groceries, other))
            }
        }
        val restored = vm(saved, delayed)
        store.writableOwner = nextOwner
        read.complete(Unit)
        assertNull(restored.state.value.editing)
        assertNull(saved.get<String>("receipt_editing"))
        assertTrue(store.replaced.isEmpty())
    }

    @Test
    fun accountSwitchWhileRestoringReceiptEditsDiscardsTheDraftWithoutCrashing() = runTest {
        restoreWhileOwnerChangesTo("another-owner")
    }

    @Test
    fun accountDeletionWhileRestoringReceiptEditsDiscardsTheDraftWithoutCrashing() = runTest {
        restoreWhileOwnerChangesTo(null)
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
