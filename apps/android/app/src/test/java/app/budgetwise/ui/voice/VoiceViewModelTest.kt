package app.budgetwise.ui.voice

import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.Source
import app.budgetwise.data.net.ApiResult
import app.budgetwise.data.net.NETWORK_ERROR_CODE
import app.budgetwise.data.repo.VoiceEntry
import app.budgetwise.data.repo.VoiceMetric
import app.budgetwise.data.repo.VoiceParse
import app.budgetwise.data.repo.VoiceQuestion
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.FakeBudgetSource
import app.budgetwise.testing.FakeCategorySource
import app.budgetwise.testing.FakeMicrophone
import app.budgetwise.testing.FakeSettingsStore
import app.budgetwise.testing.FakeSpeech
import app.budgetwise.testing.FakeTransactionStore
import app.budgetwise.testing.FakeVoiceParser
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.testTransaction
import app.budgetwise.voice.MicUnavailableException
import app.budgetwise.voice.RecordingEnd
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Yangon")
    private val today = LocalDate.parse("2026-10-30")
    private val clock = Clock.fixed(today.atTime(9, 0).atZone(zone).toInstant(), zone)
    private val mic = FakeMicrophone()
    private val speech = FakeSpeech()
    private val parser = FakeVoiceParser()
    private val settings = FakeSettingsStore()
    private val store = FakeTransactionStore()
    private val budgets = FakeBudgetSource()

    private val coffee = VoiceEntry(TxType.EXPENSE, 3000, "Food and Drink Item", "ကော်ဖီ", today, dateGuessed = true)
    private val taxi = VoiceEntry(TxType.EXPENSE, 5000, "Transport", "တက္ကစီ", today.minusDays(1), dateGuessed = false)

    private fun TestScope.start(): Pair<VoiceViewModel, MutableList<VoiceEvent>> {
        val vm = VoiceViewModel(mic, speech, parser, settings, store, budgets, FakeCategorySource(), clock)
        val events = mutableListOf<VoiceEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        return vm to events
    }

    private val VoiceViewModel.phase get() = state.value.phase

    @Test
    fun whatWasSaidBecomesEntriesToCheckThenIsSavedAsVoice() = runTest {
        val (vm, events) = start()
        assertEquals(listOf<VoiceEvent>(VoiceEvent.NeedMic), events)
        assertEquals("the models load while the user speaks", 1, speech.warmUps)
        vm.listen()
        assertEquals(VoicePhase.Listening, vm.phase)
        parser.reply = ApiResult.Ok(VoiceParse.Add("ကော်ဖီ သုံးထောင်၊ တက္ကစီ ငါးထောင်", listOf(coffee, taxi)))

        vm.done()

        assertEquals(listOf(listOf("ကော်ဖီ သုံးထောင်", "ကော်ဖီ ၃၀၀၀")), parser.calls)
        assertTrue("the audio is zeroed once heard", mic.recordings.single().audio.all { it == 0f })
        val review = (vm.phase as VoicePhase.Review).review
        assertEquals(8000, review.totalKyat)
        assertEquals(1, review.guessedDates)

        vm.save()

        assertEquals(listOf(Source.VOICE, Source.VOICE), store.added.map { it.source })
        assertEquals(listOf(3000L, 5000L), store.added.map { it.amountKyat })
        assertEquals("ကော်ဖီ သုံးထောင်၊ တက္ကစီ ငါးထောင်", store.added.first().transcript)
        assertEquals(VoiceEvent.Saved, events.last())
    }

    @Test
    fun nothingHeardNeverReachesTheServer() = runTest {
        val (vm, _) = start()
        speech.texts = listOf("", "  ")
        vm.listen()
        vm.done()
        assertEquals(VoicePhase.Problem(VoiceProblem.NothingHeard), vm.phase)
        assertTrue(parser.calls.isEmpty())
    }

    @Test
    fun withoutAConnectionTheWordsAreKeptForTryAgain() = runTest {
        val (vm, _) = start()
        parser.reply = ApiResult.Failure(NETWORK_ERROR_CODE, "offline", retryable = true)
        vm.listen()
        vm.done()
        assertEquals(VoicePhase.Problem(VoiceProblem.Offline), vm.phase)

        parser.reply = ApiResult.Ok(VoiceParse.Add("h", listOf(coffee)))
        vm.retry()

        assertTrue(vm.phase is VoicePhase.Review)
        assertEquals("the same words, without speaking again", parser.calls[0], parser.calls[1])
        assertEquals(1, speech.heard.size)
    }

    @Test
    fun serverAndSessionFailuresHaveTheirOwnCards() = runTest {
        val (vm, _) = start()
        parser.reply = ApiResult.Failure("parse_failed", "x", retryable = true)
        vm.listen()
        vm.done()
        assertEquals(VoicePhase.Problem(VoiceProblem.Failed), vm.phase)

        parser.reply = ApiResult.Unauthorized("expired")
        vm.retry()
        assertEquals(VoicePhase.Problem(VoiceProblem.SignedOut), vm.phase)
    }

    @Test
    fun noAmountShowsWhatWasHeard() = runTest {
        val (vm, _) = start()
        parser.reply = ApiResult.Ok(VoiceParse.Other("ကော်ဖီ သောက်ခဲ့တယ်"))
        vm.listen()
        vm.done()
        assertEquals(VoicePhase.Problem(VoiceProblem.NoAmount("ကော်ဖီ သောက်ခဲ့တယ်")), vm.phase)
    }

    @Test
    fun aQuestionIsAnsweredFromThePhonesOwnEntries() = runTest {
        store.rows.value = listOf(
            testTransaction("ကော်ဖီ", 3000, today, category = "Food and Drink Item"),
            testTransaction("တက္ကစီ", 5000, today.minusDays(2), category = "Transport"),
            testTransaction("Last week", 9000, today.minusDays(7), category = "Transport"),
            testTransaction("Salary", 850000, today, type = TxType.INCOME, category = "Income"),
        )
        val (vm, _) = start()
        val week = VoiceQuestion(VoiceMetric.TOTAL, null, LocalDate.parse("2026-10-26"), today)
        parser.reply = ApiResult.Ok(VoiceParse.Question("ဒီအပတ် ဘယ်လောက် သုံးလဲ", week))
        vm.listen()
        vm.done()

        val answer = vm.phase as VoicePhase.Answer
        assertEquals(8000, answer.answer.totalKyat)
        assertEquals(listOf("Transport", "Food and Drink Item"), answer.answer.byCategory.map { it.category })
        assertEquals(false, answer.showEntries)
        vm.toggleAnswerEntries()
        assertEquals(true, (vm.phase as VoicePhase.Answer).showEntries)
    }

    @Test
    fun voiceTurnedOffShowsItsCardUntilTurnedOn() = runTest {
        settings.cloudParseEnabled.value = false
        val (vm, events) = start()
        assertEquals(VoicePhase.Problem(VoiceProblem.VoiceOff), vm.phase)
        assertTrue(events.isEmpty())

        vm.turnOnVoice()

        assertEquals(true, settings.cloudParseEnabled.value)
        assertEquals(listOf<VoiceEvent>(VoiceEvent.NeedMic), events)
    }

    @Test
    fun microphoneProblems() = runTest {
        val (vm, _) = start()
        vm.micDenied(canAsk = false)
        assertEquals(VoicePhase.Problem(VoiceProblem.MicDenied(canAsk = false)), vm.phase)

        mic.failure = MicUnavailableException("in use")
        vm.listen()
        assertEquals(VoicePhase.Problem(VoiceProblem.MicBusy), vm.phase)

        mic.failure = null
        vm.listen()
        mic.recordings.single().ended.value = RecordingEnd.ERROR
        assertEquals(VoicePhase.Problem(VoiceProblem.MicBusy), vm.phase)
        assertTrue(mic.recordings.single().cancelled)
    }

    @Test
    fun theTimeLimitKeepsWhatWasSaid() = runTest {
        val (vm, _) = start()
        parser.reply = ApiResult.Ok(VoiceParse.Add("h", listOf(coffee)))
        vm.listen()
        mic.recordings.single().ended.value = RecordingEnd.LIMIT
        assertTrue(vm.phase is VoicePhase.Review)
        assertTrue(mic.recordings.single().stopped)
    }

    @Test
    fun anEditedDayIsNoLongerAGuessAndNeverInTheFuture() = runTest {
        val (vm, _) = start()
        parser.reply = ApiResult.Ok(VoiceParse.Add("h", listOf(coffee, taxi.copy(date = today.plusDays(1)))))
        vm.listen()
        vm.done()
        var review = (vm.phase as VoicePhase.Review).review
        assertEquals("tomorrow becomes today", today, review.entries[1].date)

        vm.updateEntry(0, coffee.copy(amountKyat = 3500))
        review = (vm.phase as VoicePhase.Review).review
        assertEquals(true, review.entries[0].dateGuessed)
        vm.updateEntry(0, coffee.copy(date = today.minusDays(1)))
        review = (vm.phase as VoicePhase.Review).review
        assertEquals(false, review.entries[0].dateGuessed)
        vm.updateEntry(0, coffee.copy(type = TxType.INCOME))
        assertEquals("Income", (vm.phase as VoicePhase.Review).review.entries[0].category)

        vm.removeEntry(1)
        assertEquals(1, (vm.phase as VoicePhase.Review).review.entries.size)
    }

    @Test
    fun aFailedSaveKeepsTheReview() = runTest {
        val (vm, events) = start()
        parser.reply = ApiResult.Ok(VoiceParse.Add("h", listOf(coffee)))
        vm.listen()
        vm.done()
        store.addFailure = IllegalStateException("room")
        vm.save()
        val review = (vm.phase as VoicePhase.Review).review
        assertTrue(review.saveFailed)
        assertEquals(false, review.saving)
        assertTrue(events.none { it == VoiceEvent.Saved })
    }

    @Test
    fun aBudgetQuestionLooksAtTheWholeMonth() {
        val rows = listOf(
            testTransaction("Rent", 400000, today.withDayOfMonth(1), category = "Housing"),
            testTransaction("Lunch", 45000, today.minusDays(3), category = "Food and Drink Item"),
            testTransaction("September", 99000, today.minusMonths(1), category = "Food and Drink Item"),
        )
        val q = VoiceQuestion(VoiceMetric.OVER_BUDGET, null, today.minusDays(6), today)
        val result = answer(q, rows, listOf(Budget("Housing", 450000), Budget("Food and Drink Item", 40000)))
        assertEquals(today.withDayOfMonth(1), result.from)
        assertEquals(listOf("Food and Drink Item", "Housing"), result.byCategory.map { it.category })
        assertEquals(1, result.overCount)

        val one = answer(VoiceQuestion(VoiceMetric.LIST, "Housing", today.withDayOfMonth(1), today), rows, emptyList())
        assertEquals(listOf("Rent"), one.entries.map { it.description })
        assertEquals(400000, one.totalKyat)
    }
}
