package app.budgetwise.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun migration2To3KeepsEveryRowWithoutAnOwnerUntilAnAccountClaimsIt() {
        helper.createDatabase("mig3", 2).apply {
            execSQL(
                "INSERT INTO tx (clientId, serverId, type, amountKyat, category, description, date, source, transcript, " +
                    "updatedAt, syncState, deleted) VALUES ('c1', NULL, 'EXPENSE', 1200, 'Other', 'tea', '2026-10-21', " +
                    "'MANUAL', NULL, 1000, 'PENDING', 0)",
            )
            execSQL(
                "INSERT INTO tx (clientId, serverId, type, amountKyat, category, description, date, source, transcript, " +
                    "updatedAt, syncState, deleted) VALUES ('c2', 'srv-2', 'INCOME', 5000, 'Income', 'pay', '2026-10-20', " +
                    "'WEB', NULL, 900, 'SYNCED', 0)",
            )
            execSQL("INSERT INTO outbox (op, clientId, attempts) VALUES ('CREATE', 'c1', 1)")
            execSQL("INSERT INTO budget (category, limitKyat) VALUES ('Groceries', 150000)")
            close()
        }

        val db = helper.runMigrationsAndValidate("mig3", 3, true, MIGRATION_2_3)

        db.query("SELECT clientId, ownerId, amountKyat, syncState FROM tx ORDER BY clientId").use {
            assertEquals(2, it.count)
            it.moveToFirst()
            assertEquals("c1", it.getString(0))
            assertTrue(it.isNull(1))
            assertEquals(1200L, it.getLong(2))
            assertEquals("PENDING", it.getString(3))
            it.moveToNext()
            assertEquals("c2", it.getString(0))
            assertEquals("SYNCED", it.getString(3))
        }
        db.query("SELECT clientId, ownerId, attempts FROM outbox").use {
            it.moveToFirst()
            assertEquals("c1", it.getString(0))
            assertTrue(it.isNull(1))
            assertEquals(1, it.getInt(2))
        }
        db.query("SELECT ownerId, category, limitKyat FROM budget").use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertTrue(it.isNull(0))
            assertEquals("Groceries", it.getString(1))
            assertEquals(150000L, it.getLong(2))
        }
        // Two accounts can now cache the same category.
        db.execSQL("INSERT INTO budget (ownerId, category, limitKyat) VALUES ('u1', 'Groceries', 1)")
        db.execSQL("INSERT INTO budget (ownerId, category, limitKyat) VALUES ('u2', 'Groceries', 2)")
        db.query("SELECT COUNT(*) FROM budget").use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
    }

    @Test
    fun aVersion1DatabaseMigratesAllTheWay() {
        helper.createDatabase("mig13", 1).apply {
            execSQL("INSERT INTO outbox (op, clientId, attempts) VALUES ('CREATE', 'c1', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("mig13", 3, true, MIGRATION_1_2, MIGRATION_2_3)
        db.query("SELECT COUNT(*) FROM outbox WHERE ownerId IS NULL").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }
}
