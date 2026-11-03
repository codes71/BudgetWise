package app.budgetwise.reminders

import app.budgetwise.data.model.Budget
import app.budgetwise.data.model.EndCheck
import app.budgetwise.data.model.MonthPlan
import app.budgetwise.nlu.TxType
import app.budgetwise.testing.testTransaction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision "post now, and with what", and when the runs are due. */
class MonthEndReminderTest {
    private val october = YearMonth.of(2026, 10)
    private val november1 = LocalDate.parse("2026-11-01")
    private val account = "u1"

    /** October as on the board: 2,000,000 Ks, of which 1,656,500 Ks was spent, so 343,500 Ks left. */
    private val plan = MonthPlan(october, 2_000_000, 0, listOf(Budget("Housing", 500_000)))
    private val rows = listOf(
        testTransaction("rent", 500_000, LocalDate.parse("2026-10-01"), category = "Housing"),
        testTransaction("food", 1_156_500, LocalDate.parse("2026-10-20"), category = "Food and Drink Item"),
        // Neither counts: income, and spending in November.
        testTransaction("salary", 2_000_000, LocalDate.parse("2026-10-01"), type = TxType.INCOME, category = "Income"),
        testTransaction("today", 9_000, november1),
    )

    private fun decide(
        run: ReminderRun = ReminderRun.Daily,
        account: String? = this.account,
        today: LocalDate = november1,
        time: LocalTime = LocalTime.of(9, 0),
        plan: MonthPlan? = this.plan,
        posted: Posted = Posted(),
    ) = monthEndReminder(run, account, today.atTime(time), plan, rows, posted)

    @Test
    fun theDailyRunPostsLastMonthsLeftoverLikeTheEndCheck() {
        assertEquals(MonthEndReminder(account, october, 343_500, offerTonight = true), decide())
        // Any day of the month after, not only the 1st: the phone may have been off.
        assertEquals(343_500L, decide(today = LocalDate.parse("2026-11-17"))?.leftKyat)
    }

    @Test
    fun signedOutNothing() {
        assertNull(decide(account = null))
        assertNull(decide(run = ReminderRun.Tonight(account, october), account = null))
    }

    @Test
    fun noPlanNothing() {
        assertNull(decide(plan = null))
        // A plan for another month (this one, planned ahead) is not last month's.
        assertNull(decide(plan = plan.copy(month = YearMonth.of(2026, 11))))
    }

    @Test
    fun aMonthWithItsEndCheckNeverGetsAPost() {
        val checked = plan.copy(endCheck = EndCheck(343_500, Instant.EPOCH))
        assertNull(decide(plan = checked))
        assertNull(decide(run = ReminderRun.Tonight(account, october), plan = checked, posted = Posted(daily = true)))
    }

    @Test
    fun onlyFromTheFirstOfTheNextMonth() {
        // On 31 October the daily run is about September, and October's tonight post waits for October to end.
        val lastDay = LocalDate.parse("2026-10-31")
        assertNull(decide(today = lastDay))
        assertNull(decide(run = ReminderRun.Tonight(account, october), today = lastDay))
        assertEquals(october, decide(today = november1)?.month)
    }

    @Test
    fun theDailyPostOnlyOnce() {
        assertNull(decide(posted = Posted(daily = true)))
        assertNull(decide(posted = Posted(daily = true, tonight = true)))
    }

    @Test
    fun tonightPostsOnceMoreWithoutOfferingTonightAgain() {
        val tonight = ReminderRun.Tonight(account, october)

        assertEquals(MonthEndReminder(account, october, 343_500, offerTonight = false), decide(run = tonight, posted = Posted(daily = true)))
        // Two posts a month at most.
        assertNull(decide(run = tonight, posted = Posted(daily = true, tonight = true)))
        // Only for the account that asked: another one signed in since gets nothing.
        assertNull(decide(run = tonight, account = "u2", posted = Posted(daily = true)))
        // Still about October after midnight, even in December.
        assertEquals(october, decide(run = tonight, today = LocalDate.parse("2026-12-01"), posted = Posted(daily = true))?.month)
    }

    @Test
    fun aMonthThatWentOverIsWordedOverBudget() {
        val reminder = decide(plan = plan.copy(newMoneyKyat = 1_600_000))!!
        assertEquals(-56_500L, reminder.leftKyat)
        assertTrue(reminder.over)
        assertFalse(decide()!!.over)
        // Exactly spent is 0 left, not over.
        assertFalse(decide(plan = plan.copy(newMoneyKyat = 1_656_500))!!.over)
    }

    @Test
    fun theCarryCountsTowardsTheTotal() {
        assertEquals(623_500L, decide(plan = plan.copy(carriedOverKyat = 280_000))?.leftKyat)
    }

    @Test
    fun postedTellsWhichRunIsDone() {
        assertTrue(Posted(daily = true).has(ReminderRun.Daily))
        assertFalse(Posted(daily = true).has(ReminderRun.Tonight(account, october)))
        assertTrue(Posted(tonight = true).has(ReminderRun.Tonight(account, october)))
    }

    @Test
    fun theDailyRunPostsOnlyInTheDaytime() {
        // A run Doze held into the night, or one aimed at 9:00 in the zone the phone has just left, waits.
        for (night in listOf("21:00", "23:30", "00:00", "03:00", "08:59:59")) {
            assertNull(night, decide(time = LocalTime.parse(night)))
        }
        for (day in listOf("09:00", "12:00", "20:59:59")) {
            assertEquals(day, october, decide(time = LocalTime.parse(day))?.month)
        }
        // Tonight's post was asked for: an hour after 20:00 still posts, up to 22:00.
        assertEquals(october, decide(run = ReminderRun.Tonight(account, october), time = LocalTime.of(21, 30), posted = Posted(daily = true))?.month)
        assertFalse(ReminderRun.Daily.postsAt(ReminderRun.DAY_END))
        assertTrue(ReminderRun.Daily.postsAt(ReminderRun.DAY_START))
    }

    @Test
    fun aTonightRunHeldPastTenAtNightIsDropped() {
        val tonight = ReminderRun.Tonight(account, october)
        val asked = Posted(daily = true)

        for (time in listOf("20:00", "21:59:59")) {
            assertEquals(time, october, decide(run = tonight, time = LocalTime.parse(time), posted = asked)?.month)
        }
        // Doze held it to 22:00 or later, or past midnight into the small hours.
        for (late in listOf("22:00", "23:30", "00:30", "03:00", "08:59:59")) {
            assertNull(late, decide(run = tonight, time = LocalTime.parse(late), posted = asked))
            assertNull(late, decide(run = tonight, today = november1.plusDays(1), time = LocalTime.parse(late), posted = asked))
        }
        assertFalse(tonight.postsAt(ReminderRun.TONIGHT_END))
        // The daily run's hours are its own.
        assertTrue(ReminderRun.Daily.postsAt(LocalTime.of(20, 30)))
    }

    @Test
    fun theNextNineOClockAcrossMidnightAndTheMonthsEnd() {
        val zone = ZoneId.of("Asia/Yangon")
        val nine = LocalTime.of(9, 0)

        // Just before midnight on the month's last day: 9:00 on the 1st.
        val lastNight = ZonedDateTime.of(LocalDate.parse("2026-10-31"), LocalTime.of(23, 59), zone)
        assertEquals(Duration.ofHours(9).plusMinutes(1), MonthEndScheduler.untilNext(nine, lastNight))
        assertEquals(LocalDate.parse("2026-11-01").atTime(nine), lastNight.plus(MonthEndScheduler.untilNext(nine, lastNight)).toLocalDateTime())
        // Just after it: the same morning.
        val justAfter = ZonedDateTime.of(LocalDate.parse("2026-11-01"), LocalTime.of(0, 1), zone)
        assertEquals(Duration.ofHours(8).plusMinutes(59), MonthEndScheduler.untilNext(nine, justAfter))
    }

    @Test
    fun theNextNineOClockStaysNineAcrossSummerTime() {
        val zone = ZoneId.of("Europe/London")
        val nine = LocalTime.of(9, 0)

        // Clocks go forward at 1:00 on 29 March 2026: from 21:00 the night before, 11 hours to 9:00 local.
        val spring = ZonedDateTime.of(LocalDate.parse("2026-03-28"), LocalTime.of(21, 0), zone)
        assertEquals(Duration.ofHours(11), MonthEndScheduler.untilNext(nine, spring))
        // Back at 2:00 on 25 October 2026: 13 hours.
        val autumn = ZonedDateTime.of(LocalDate.parse("2026-10-24"), LocalTime.of(21, 0), zone)
        assertEquals(Duration.ofHours(13), MonthEndScheduler.untilNext(nine, autumn))
        for (start in listOf(spring, autumn)) {
            assertEquals(nine, start.plus(MonthEndScheduler.untilNext(nine, start)).toLocalTime())
        }
    }

    @Test
    fun aChangeOfTimeZoneIsCaughtByTheNextRun() {
        val nine = LocalTime.of(9, 0)
        // Scheduled in Yangon at 9:00 for tomorrow's 9:00 there.
        val yangon = ZonedDateTime.of(LocalDate.parse("2026-11-01"), nine, ZoneId.of("Asia/Yangon"))
        val due = yangon.plus(MonthEndScheduler.untilNext(nine, yangon))

        // The phone is now in London, where that is 2:30 at night: the run does not post, and the next is that 9:00.
        val inLondon = due.withZoneSameInstant(ZoneId.of("Europe/London"))
        assertEquals(LocalTime.of(2, 30), inLondon.toLocalTime())
        assertFalse(ReminderRun.Daily.postsAt(inLondon.toLocalTime()))
        assertEquals(Duration.ofHours(6).plusMinutes(30), MonthEndScheduler.untilNext(nine, inLondon))
    }

    @Test
    fun theDailyRunIsAimedAtTheNextNineOClock() {
        val zone = ZoneId.of("Asia/Yangon")
        val nine = LocalTime.of(9, 0)
        fun at(time: String) = ZonedDateTime.of(LocalDate.parse("2026-11-01"), LocalTime.parse(time), zone)

        assertEquals(Duration.ofMinutes(90), MonthEndScheduler.untilNext(nine, at("07:30")))
        // At 9:00 or after, tomorrow's.
        assertEquals(Duration.ofHours(24), MonthEndScheduler.untilNext(nine, at("09:00")))
        assertEquals(Duration.ofHours(12).plusMinutes(30), MonthEndScheduler.untilNext(nine, at("20:30")))
    }

    @Test
    fun tonightIsEightOClockOrAnHourFromLater() {
        val zone = ZoneId.of("Asia/Yangon")
        fun at(time: String) = ZonedDateTime.of(LocalDate.parse("2026-11-01"), LocalTime.parse(time), zone)

        assertEquals(Duration.ofHours(11), MonthEndScheduler.untilTonight(at("09:00")))
        assertEquals(Duration.ofMinutes(1), MonthEndScheduler.untilTonight(at("19:59")))
        assertEquals(Duration.ofHours(1), MonthEndScheduler.untilTonight(at("20:00")))
        assertEquals(Duration.ofHours(1), MonthEndScheduler.untilTonight(at("23:30")))
    }

    @Test
    fun aWorkerKnowsTonightsRunFromItsData() {
        assertEquals(ReminderRun.Tonight(account, october), MonthEndWorker.runOf(MonthEndWorker.tonightData(account, october)))
        assertEquals(ReminderRun.Daily, MonthEndWorker.runOf(androidx.work.Data.EMPTY))
    }
}
