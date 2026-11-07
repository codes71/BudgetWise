package app.budgetwise.data.repo

import androidx.room.withTransaction
import app.budgetwise.data.model.DailySpend
import app.budgetwise.data.model.EntryItem
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.data.db.OutboxOp
import app.budgetwise.data.sync.SyncOutcome
import app.budgetwise.data.text.BurmeseTextNormalizer
import app.budgetwise.nlu.TxType
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransactionRepositoryTest {
    private lateinit var h: RepoHarness
    private val repo get() = h.transactions

    @Before fun setUp() { h = RepoHarness() }
    @After fun tearDown() { h.close() }

    private fun input(amount: Long, source: Source = Source.MANUAL) = NewTransaction(
        type = TxType.EXPENSE, amountKyat = amount, category = "Groceries", description = "rice",
        date = LocalDate.parse("2026-10-21"), source = source, transcript = if (source == Source.VOICE) "text" else null,
    )

    @Test
    fun aQueuedScanSaveCannotBecomeTheNextAccountsEntry() = runBlocking {
        val expectedOwner = h.sync.session.writableUserId!!
        val resume = CompletableDeferred<Unit>()
        val saving = async(start = CoroutineStart.UNDISPATCHED) {
            resume.await()
            try {
                repo.addAll(listOf(input(100, Source.SCAN)), expectedOwner)
                fail("the scan belongs to the account that opened it")
            } catch (_: IllegalStateException) { }
        }
        h.sync.signInSecond()
        resume.complete(Unit)
        saving.await()
        assertTrue(h.sync.tx.observeAll(expectedOwner).first().isEmpty())
        assertTrue(h.sync.tx.observeAll(h.sync.session.writableUserId!!).first().isEmpty())
        assertTrue(h.sync.pendingOps().isEmpty())
    }

    @Test
    fun aScanSaveWaitingForRoomPinsItsOwnerThroughTheActualWrite() = runBlocking {
        val expectedOwner = h.sync.session.writableUserId!!
        val holdingRoom = CompletableDeferred<Unit>()
        val releaseRoom = CompletableDeferred<Unit>()
        val busy = async(Dispatchers.IO) {
            h.sync.db.withTransaction { holdingRoom.complete(Unit); releaseRoom.await() }
        }
        holdingRoom.await()
        // Start inline until withTransaction waits for Room, after acquiring the account-transition lock.
        val saving = async(start = CoroutineStart.UNDISPATCHED) {
            repo.addAll(listOf(input(100, Source.SCAN)), expectedOwner)
        }
        val switching = async(start = CoroutineStart.UNDISPATCHED) { h.sync.signInSecond() }
        assertFalse("the switch waits for the owner-bound Room write", switching.isCompleted)
        releaseRoom.complete(Unit)
        busy.await()
        val ids = saving.await()
        switching.await()
        assertEquals(1, ids.size)
        assertEquals(expectedOwner, h.sync.tx.byClientId(ids.single())!!.ownerId)
        assertTrue(h.sync.tx.observeAll(h.sync.session.writableUserId!!).first().isEmpty())
    }

    @Test
    fun queuedReplaceReceiptAndDeleteCannotUseTheNextAccount() = runBlocking {
        val owner = h.sync.session.writableUserId!!
        val id = repo.addAll(listOf(input(100))).single()
        h.sync.signInSecond()
        for (write in listOf<suspend () -> Unit>(
            { repo.replace(listOf(id), listOf(input(200)), owner) },
            { repo.replaceReceipt("receipt", setOf(id), listOf(input(200)), owner) },
            { repo.delete(id, owner) },
        )) {
            try { write(); fail("a stale form must not write after its owner changes") } catch (_: IllegalStateException) { }
        }
        assertEquals(100L, h.sync.tx.byClientId(id)!!.amountKyat)
        // Even a current-account delete by an old clientId must preserve the other account's row.
        repo.delete(id)
        assertNotNull(h.sync.tx.byClientId(id))
    }

    @Test
    fun aReplaceWaitingForRoomRetainsTheOpeningOwnerThroughEveryStoreCall() = runBlocking {
        val owner = h.sync.session.writableUserId!!
        val id = repo.addAll(listOf(input(100))).single()
        val holdingRoom = CompletableDeferred<Unit>()
        val releaseRoom = CompletableDeferred<Unit>()
        val busy = async(Dispatchers.IO) { h.sync.db.withTransaction { holdingRoom.complete(Unit); releaseRoom.await() } }
        holdingRoom.await()
        val saving = async(start = CoroutineStart.UNDISPATCHED) { repo.replace(listOf(id), listOf(input(200)), owner) }
        val switching = async(start = CoroutineStart.UNDISPATCHED) { h.sync.signInSecond() }
        assertFalse(switching.isCompleted)
        releaseRoom.complete(Unit); busy.await()
        val result = saving.await() as ReplaceResult.Saved
        switching.await()
        assertEquals(owner, h.sync.tx.byClientId(result.clientIds.single())!!.ownerId)
        assertEquals(200L, h.sync.tx.byClientId(result.clientIds.single())!!.amountKyat)
        assertTrue(h.sync.tx.observeAll(h.sync.session.writableUserId!!).first().isEmpty())
    }

    @Test
    fun addAllWritesRowsAndOutboxAtomically() = runBlocking {
        // Force the second outbox insert to fail: the first row and op must roll back with it.
        h.sync.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_outbox BEFORE INSERT ON outbox WHEN (SELECT COUNT(*) FROM outbox) >= 1 " +
                "BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )
        try {
            repo.addAll(listOf(input(100), input(200)))
            fail("expected the outbox insert to fail")
        } catch (_: Exception) {
        }
        assertEquals(0, repo.observe().first().size)
        assertEquals(0, h.sync.pendingOps().size)
        assertTrue("no sync is requested for a failed write", h.work("sync").isEmpty())
    }

    @Test
    fun aScannedReceiptKeepsItsIdShopAndLines() = runBlocking {
        val receipt = UUID.randomUUID().toString()
        val lines = listOf(EntryItem("Fresh milk 1L", 2, 4500), EntryItem("Bread", 2200))
        val id = repo.addAll(listOf(input(11200, Source.SCAN).copy(receiptId = receipt, merchant = "Ocean", items = lines))).single()

        val row = repo.get(id)!!
        assertEquals(Source.SCAN, row.source)
        assertEquals(receipt, row.receiptId)
        assertEquals("Ocean", row.merchant)
        assertEquals(lines, row.items)
        assertEquals(listOf(id), repo.observeReceipt(receipt).first().map { it.clientId })
    }

    private fun saved(result: ReplaceResult): List<String> = (result as ReplaceResult.Saved).clientIds

    private suspend fun ops() = h.sync.pendingOps().map { it.op to it.clientId }

    @Test
    fun replaceDeletesTheOldEntryAndSavesTheNewOneTogether() = runBlocking {
        val old = repo.addAll(listOf(input(600))).single()
        val new = saved(repo.replace(listOf(old), listOf(input(700)))).single()

        val rows = repo.observe().first()
        assertEquals(listOf(new), rows.map { it.clientId })
        assertEquals(700L, rows.single().amountKyat)
        // Never synced, the old row and its CREATE are simply gone: one CREATE is left, for the new row.
        assertEquals(listOf(OutboxOp.CREATE), h.sync.pendingOps().map { it.op })
        assertEquals(null, repo.get(old))
    }

    @Test
    fun aReceiptIsReplacedOnlyWhileItsEntriesAreTheOnesTheEditStartedFrom() = runBlocking {
        val receipt = UUID.randomUUID().toString()
        fun line(amount: Long) = input(amount, Source.SCAN).copy(receiptId = receipt, merchant = "Ocean")
        val (groceries, other) = repo.addAll(listOf(line(11200), line(12000)))
        // A sync brings another phone's change to the receipt: Other moved to Household, as a new entry.
        val household = saved(repo.replace(listOf(other), listOf(line(12000).copy(category = "Household")))).single()

        assertEquals(null, repo.replaceReceipt(receipt, setOf(groceries, other), listOf(line(10000))))
        assertEquals("nothing is written", setOf(groceries, household), repo.observeReceipt(receipt).first().map { it.clientId }.toSet())

        val saved = repo.replaceReceipt(receipt, setOf(groceries, household), listOf(line(10000))).orEmpty()
        assertEquals(saved, repo.observeReceipt(receipt).first().map { it.clientId })
    }

    @Test
    fun aChangedReceiptGoesUpBeforeItsEntriesAreDeleted() = runBlocking {
        val receipt = UUID.randomUUID().toString()
        fun line(amount: Long) = input(amount, Source.SCAN).copy(receiptId = receipt, merchant = "Ocean")
        h.sync.seedSynced("groceries")
        h.sync.tx.upsert(h.sync.tx.byClientId("groceries")!!.copy(receiptId = receipt, merchant = "Ocean"))

        val changed = repo.replaceReceipt(receipt, setOf("groceries"), listOf(line(10000))).orEmpty().single()

        // As any change: the new entry first, the original's DELETE behind it, in one edit.
        assertEquals(listOf(OutboxOp.CREATE to changed, OutboxOp.DELETE to "groceries"), ops())
        h.sync.server.invalidClientIds = setOf(changed)
        h.sync.engine.syncOnce()
        assertTrue("refused, so the original stays on the server", "groceries" in h.sync.server.rows)
        assertNotNull(repo.get("groceries"))
    }

    @Test
    fun changingAWebEntryKeepsItOnTheServerWithTheChange() = runBlocking {
        val web = h.sync.seedSynced("web-1") // added on the website: source "web"
        assertEquals(Source.WEB, repo.get(web.clientId)!!.source)

        val changed = saved(repo.replace(listOf(web.clientId), listOf(input(4200, Source.WEB)))).single()

        // The change goes up first; the web copy is deleted only once the server took it.
        assertEquals(listOf(OutboxOp.CREATE to changed, OutboxOp.DELETE to web.clientId), ops())
        assertTrue(h.sync.engine.syncOnce(fullPull = true) is SyncOutcome.Success)
        val row = h.sync.server.rows.values.single()
        assertEquals(changed, row.clientId)
        assertEquals("manual", row.source)
        assertEquals(4200.0, row.amount, 0.0)
        assertEquals(listOf(changed), repo.observe().first().map { it.clientId })
        assertTrue(h.sync.pendingOps().isEmpty())
    }

    @Test
    fun aChangeTheServerRefusesLeavesTheOriginalOnTheServerAndInTheList() = runBlocking {
        h.sync.seedSynced("orig")
        val changed = saved(repo.replace(listOf("orig"), listOf(input(4200)))).single()
        h.sync.server.invalidClientIds = setOf(changed)

        val outcome = h.sync.engine.syncOnce()

        assertEquals(listOf(changed), (outcome as SyncOutcome.PermanentFailure).clientIds)
        assertTrue("orig" in h.sync.server.rows)
        assertTrue(h.sync.server.deletes.isEmpty())
        val back = h.sync.tx.byClientId("orig")!!
        assertFalse(back.deleted)
        assertEquals(SyncState.SYNCED, back.syncState)
        assertEquals(SyncState.FAILED, h.sync.tx.byClientId(changed)!!.syncState)
        assertTrue(h.sync.pendingOps().isEmpty())
        // The server still lists it, so a full pull keeps it.
        h.sync.engine.syncOnce(fullPull = true)
        assertNotNull(repo.get("orig"))
    }

    @Test
    fun anEntryChangedTwiceBeforeASyncIsDeletedOnlyAfterTheLastChange() = runBlocking {
        h.sync.seedSynced("orig")
        val first = saved(repo.replace(listOf("orig"), listOf(input(4200)))).single()
        val second = saved(repo.replace(listOf(first), listOf(input(4300)))).single()

        // The first change never left the phone, so it is simply gone; the original's DELETE now waits on the second.
        assertEquals(listOf(OutboxOp.CREATE to second, OutboxOp.DELETE to "orig"), ops())
        h.sync.server.invalidClientIds = setOf(second)
        h.sync.engine.syncOnce()

        assertEquals(setOf("orig"), h.sync.server.rows.keys)
        assertNotNull(repo.get("orig"))
    }

    @Test
    fun aChangeUnderAHostingPage404KeepsTheOldEntryOutOfTheList() = runBlocking {
        h.sync.seedSynced("orig")
        val changed = saved(repo.replace(listOf("orig"), listOf(input(4200)))).single()
        h.sync.server.htmlStatusOnDelete = 404

        assertEquals(SyncOutcome.RetryLater, h.sync.engine.syncOnce())
        assertEquals(setOf("orig", changed), h.sync.server.rows.keys)
        // A full pull still lists the original, which stays deleted here: the amount never counts twice.
        h.sync.engine.syncOnce(fullPull = true)
        assertEquals(listOf(changed), repo.observe().first().map { it.clientId })

        h.sync.server.htmlStatusOnDelete = null
        h.sync.engine.syncOnce(fullPull = true)
        assertEquals(setOf(changed), h.sync.server.rows.keys)
        assertEquals(listOf(changed), repo.observe().first().map { it.clientId })
        assertTrue(h.sync.pendingOps().isEmpty())
    }

    @Test
    fun aChangedKPayEntryKeepsItsIdSoScanningItAgainFindsIt() = runBlocking {
        val kpay = "8757c8d9-1367-4ee0-b09b-80b464aba7a0"
        val payment = input(150000, Source.SCAN).copy(clientId = kpay)
        repo.addAll(listOf(payment))
        h.sync.engine.syncOnce()
        val serverId = h.sync.tx.byClientId(kpay)!!.serverId

        assertEquals(listOf(kpay), saved(repo.replace(listOf(kpay), listOf(payment.copy(category = "Transport")))))

        // Rewritten in place: deleted on the server first, then created again under the same id.
        assertEquals(listOf(OutboxOp.DELETE to kpay, OutboxOp.CREATE to kpay), ops())
        assertEquals(serverId, h.sync.pendingOps().first().serverId)
        assertEquals("Transport", repo.get(kpay)!!.category)
        // The same history scanned again: the payment is saved already, before the sync and after it.
        assertTrue(repo.addAll(listOf(payment)).isEmpty())
        assertTrue(h.sync.engine.syncOnce() is SyncOutcome.Success)
        assertEquals(listOf(serverId), h.sync.server.deletes)
        assertEquals("Transport", h.sync.server.rows.getValue(kpay).category)
        assertTrue(repo.addAll(listOf(payment)).isEmpty())
        assertEquals(1, h.sync.server.rows.size)
        assertEquals(listOf(kpay), repo.observe().first().map { it.clientId })
    }

    @Test
    fun aKPayEntryChangedTwiceOrDeletedBeforeASyncIsStillDeletedOnTheServer() = runBlocking {
        val kpay = "8757c8d9-1367-4ee0-b09b-80b464aba7a0"
        val payment = input(150000, Source.SCAN).copy(clientId = kpay)
        repo.addAll(listOf(payment))
        h.sync.engine.syncOnce()
        val serverId = h.sync.tx.byClientId(kpay)!!.serverId

        repo.replace(listOf(kpay), listOf(payment.copy(category = "Transport")))
        repo.replace(listOf(kpay), listOf(payment.copy(category = "Health")))

        // The second change replaces the unsent first one; the DELETE of what the server has stays first.
        assertEquals(listOf(OutboxOp.DELETE to kpay, OutboxOp.CREATE to kpay), ops())
        assertEquals(serverId, h.sync.pendingOps().first().serverId)
        h.sync.engine.syncOnce(fullPull = true)
        assertEquals("Health", h.sync.server.rows.getValue(kpay).category)
        assertEquals(1, h.sync.server.rows.size)

        // Changed, then swiped away before the sync: the server's copy is deleted too, not pulled back later.
        repo.replace(listOf(kpay), listOf(payment.copy(category = "Transport")))
        repo.delete(kpay)
        assertEquals(listOf(OutboxOp.DELETE to kpay), ops())
        h.sync.engine.syncOnce(fullPull = true)
        assertTrue(h.sync.server.rows.isEmpty())
        assertTrue(repo.observe().first().isEmpty())
    }

    @Test
    fun aKPayEntryWhoseCreateWentOutWithoutAReplyIsChangedUnderANewId() = runBlocking {
        val kpay = "8757c8d9-1367-4ee0-b09b-80b464aba7a0"
        val payment = input(150000, Source.SCAN).copy(clientId = kpay)
        repo.addAll(listOf(payment))
        h.sync.server.dropResponseOnPost = 1
        assertEquals(SyncOutcome.RetryLater, h.sync.engine.syncOnce()) // the server has it, the phone does not know

        val changed = saved(repo.replace(listOf(kpay), listOf(payment.copy(category = "Transport")))).single()

        // In place, the resent CREATE would get the stored payment back over the change.
        assertTrue(changed != kpay)
        h.sync.server.dropResponseOnPost = null
        h.sync.engine.syncOnce()
        assertEquals(setOf(changed), h.sync.server.rows.keys)
        assertEquals("Transport", h.sync.server.rows.getValue(changed).category)
    }

    @Test
    fun changingAnEntryThatIsGoneWritesNothing() = runBlocking {
        h.sync.seedSynced("gone")
        h.sync.server.rows.remove("gone") // deleted on the web
        h.sync.engine.syncOnce(fullPull = true) // and pulled away while the form was open
        assertNull(repo.get("gone"))

        assertEquals(ReplaceResult.Gone, repo.replace(listOf("gone"), listOf(input(4200))))

        assertTrue(repo.observe().first().isEmpty())
        assertTrue(h.sync.pendingOps().isEmpty())
        assertTrue("no sync is requested for nothing", h.work("sync").isEmpty())
    }

    @Test
    fun changingADeletedOrAnotherAccountsEntryWritesNothing() = runBlocking {
        h.sync.seedSynced("tomb")
        repo.delete("tomb") // deleted here, waiting for the server
        val (mine) = repo.addAll(listOf(input(100)))

        assertEquals(ReplaceResult.Gone, repo.replace(listOf("tomb"), listOf(input(4200))))
        h.sync.signInSecond()
        assertEquals(ReplaceResult.Gone, repo.replace(listOf(mine), listOf(input(4200))))

        assertEquals(listOf(OutboxOp.DELETE to "tomb", OutboxOp.CREATE to mine), ops())
        assertTrue(h.sync.pendingOps("u2").isEmpty())
    }

    @Test
    fun aReplaceWithNothingNewDeletesWhatIsLeft() = runBlocking {
        val (a, b) = repo.addAll(listOf(input(100), input(200)))
        repo.delete(a)

        assertEquals(ReplaceResult.Saved(emptyList()), repo.replace(listOf(a, b), emptyList()))

        assertTrue(repo.observe().first().isEmpty())
        assertTrue(h.sync.pendingOps().isEmpty())
    }

    @Test
    fun aFixedClientIdSavedTwiceIsStoredOnce() = runBlocking {
        val kpay = "8757c8d9-1367-4ee0-b09b-80b464aba7a0"
        val first = repo.addAll(listOf(input(150000).copy(clientId = kpay)))
        val second = repo.addAll(listOf(input(150000).copy(clientId = kpay), input(2500)))
        assertEquals(listOf(kpay), first)
        // Only the new row is reported, so Undo after the second save never deletes the first one.
        assertEquals(1, second.size)
        assertTrue(second.single() != kpay)
        assertEquals(2, repo.observe().first().size)
        assertEquals("one create per stored row", 2, h.sync.pendingOps().size)
    }

    @Test
    fun aFixedClientIdDeletedHereCanBeSavedAgain() = runBlocking {
        val kpay = "8757c8d9-1367-4ee0-b09b-80b464aba7a0"
        repo.addAll(listOf(input(150000).copy(clientId = kpay)))
        repo.delete(kpay)
        // Deleted before it synced, the row is gone at once, so the same slip saved again is a new entry.
        val again = repo.addAll(listOf(input(150000).copy(clientId = kpay)))
        assertEquals(1, again.size)
        assertEquals(1, repo.observe().first().size)
    }

    @Test
    fun addAllNormalizesDescriptionAndTranscriptAndConvertsZawgyi() = runBlocking {
        val zawgyi = "ျမန္မာဘာသာ"
        val unicode = "မြန်မာဘာသာ"
        repo.addAll(
            listOf(
                input(100, Source.VOICE).copy(description = "  Grab \t ခ   ၅၀၀၀ ", transcript = "  $zawgyi  "),
                input(200).copy(description = zawgyi),
            ),
        )
        val rows = repo.observe().first().sortedBy { it.amountKyat }
        assertEquals("Grab ခ ၅၀၀၀", rows[0].description)
        assertEquals(unicode, rows[0].transcript)
        assertEquals(unicode, rows[1].description)
        assertEquals(null, rows[1].transcript)
    }

    @Test
    fun addAllRefusesADescriptionThatConvertsPastTheServerLimit() = runBlocking {
        // Short as typed (Zawgyi), longer once converted to Unicode: the stored text is what the server counts.
        val zawgyi = "ဒီေန႔ ေဈးမွာ ဆန္ဝယ္ခဲ့တယ္" + "ၻ".repeat(100)
        assertTrue(zawgyi.length <= TransactionRepository.MAX_DESCRIPTION)
        assertTrue(BurmeseTextNormalizer.prepare(zawgyi).length > TransactionRepository.MAX_DESCRIPTION)
        try {
            repo.addAll(listOf(input(100), input(200).copy(description = zawgyi)))
            fail("expected the converted description to be refused")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("nothing is stored, not even the valid row", 0, repo.observe().first().size)
        assertEquals(0, h.sync.pendingOps().size)
        assertTrue(h.work("sync").isEmpty())
    }

    @Test
    fun addAllGeneratesUniqueUuidV4ClientIds() = runBlocking {
        val ids = repo.addAll(List(20) { input(100L + it) })
        assertEquals(20, ids.toSet().size)
        ids.forEach { assertEquals(4, UUID.fromString(it).version()) }
    }

    @Test
    fun addAllStoresPendingRowsWithCreateOpsAndSchedulesOneSync() = runBlocking {
        val ids = repo.addAll(listOf(input(500, Source.VOICE), input(700)))
        val rows = repo.observe().first()
        assertEquals(setOf(500L, 700L), rows.map { it.amountKyat }.toSet())
        assertTrue(rows.all { it.syncState == SyncState.PENDING && it.serverId == null })
        assertEquals(Source.VOICE, rows.first { it.amountKyat == 500L }.source)
        assertEquals("text", rows.first { it.amountKyat == 500L }.transcript)
        val ops = h.sync.pendingOps()
        assertEquals(ids, ops.map { it.clientId })
        assertTrue(ops.all { it.op == OutboxOp.CREATE })
        assertEquals(1, h.work("sync").size)
        assertEquals(2, repo.observeUnsyncedCount().first())
    }

    @Test
    fun addAllWithNoInputsDoesNothing() = runBlocking {
        assertEquals(emptyList<String>(), repo.addAll(emptyList()))
        assertTrue(h.work("sync").isEmpty())
    }

    @Test
    fun deleteOfNeverSyncedRowDropsRowAndOpAndSchedulesSync() = runBlocking {
        val (id) = repo.addAll(listOf(input(100)))
        repo.delete(id)
        assertEquals(0, repo.observe().first().size)
        assertEquals(0, h.sync.pendingOps().size)
        assertEquals("the sync queued by addAll covers the delete", 1, h.work("sync").size)
    }

    @Test
    fun deleteOfSyncedRowQueuesDeleteOp() = runBlocking {
        val synced = h.sync.seedSynced("c-synced")
        repo.delete(synced.clientId)
        assertEquals(0, repo.observe().first().size)
        val op = h.sync.pendingOps().single()
        assertEquals(OutboxOp.DELETE, op.op)
        assertEquals(synced.serverId, op.serverId)
        assertEquals(1, h.work("sync").size)
    }

    @Test
    fun readsAndWritesBelongToTheActiveAccount() = runBlocking {
        val (mine) = repo.addAll(listOf(input(100)))
        assertEquals("u1", h.sync.tx.byClientId(mine)!!.ownerId)
        assertEquals("u1", h.sync.pendingOps("u1").single().ownerId)

        h.sync.signInSecond()
        assertTrue(repo.observe().first().isEmpty())
        assertEquals(0, repo.observeUnsyncedCount().first())
        val (theirs) = repo.addAll(listOf(input(200)))
        assertEquals("u2", h.sync.tx.byClientId(theirs)!!.ownerId)
        assertEquals(listOf(theirs), repo.observe().first().map { it.clientId })

        h.sync.session.signIn(h.sync.server.token, h.sync.now.plusSeconds(3600), app.budgetwise.data.sync.SyncHarness.USER)
        assertEquals(listOf(mine), repo.observe().first().map { it.clientId })
    }

    @Test
    fun dailySpendIsTheActiveAccountsOnly() = runBlocking {
        val october = LocalDate.parse("2026-10-01") to LocalDate.parse("2026-10-31")
        repo.addAll(listOf(input(100), input(250)))
        assertEquals(
            listOf(DailySpend(LocalDate.parse("2026-10-21"), "Groceries", 350)),
            repo.observeDailySpend(october.first, october.second).first(),
        )

        h.sync.signInSecond()
        assertTrue("another account's spending is not counted", repo.observeDailySpend(october.first, october.second).first().isEmpty())

        h.sync.session.signOut(keepSession = false)
        assertTrue(repo.observeDailySpend(october.first, october.second).first().isEmpty())
    }

    @Test
    fun addAllRefusesWhenNoAccountIsSignedIn() = runBlocking {
        h.sync.session.signOut(keepSession = false)
        try {
            repo.addAll(listOf(input(100)))
            fail("expected no owner")
        } catch (_: IllegalStateException) {
        }
        assertTrue(h.sync.tx.observeAll("u1").first().isEmpty())
    }
}
