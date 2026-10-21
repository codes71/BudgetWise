package app.budgetwise.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BudgetWiseDb::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration1To2KeepsTransactionsAndOutboxAndAddsBudgetTable() {
        helper.createDatabase("mig", 1).apply {
            execSQL(
                "INSERT INTO tx (clientId, serverId, type, amountKyat, category, description, date, source, transcript, " +
                    "updatedAt, syncState, deleted) VALUES ('c1', NULL, 'EXPENSE', 1200, 'Other', 'tea', '2026-10-21', " +
                    "'MANUAL', NULL, 1000, 'PENDING', 0)",
            )
            execSQL("INSERT INTO outbox (op, clientId, attempts) VALUES ('CREATE', 'c1', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig", 2, true, MIGRATION_1_2)
        db.query("SELECT clientId, amountKyat FROM tx").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("c1", it.getString(0))
            assertEquals(1200L, it.getLong(1))
        }
        db.query("SELECT COUNT(*) FROM outbox").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.execSQL("INSERT INTO budget (category, limitKyat) VALUES ('Other', 5000)")
        db.query("SELECT limitKyat FROM budget").use { it.moveToFirst(); assertEquals(5000L, it.getLong(0)) }
    }
}
