package app.budgetwise.data.db

import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

/** Version 2 adds the budget cache. Existing transactions and outbox rows are untouched. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `budget` (`category` TEXT NOT NULL, `limitKyat` INTEGER NOT NULL, PRIMARY KEY(`category`))",
        )
    }
}

/**
 * Version 3 gives transactions, outbox ops and cached budgets an owner (the account's user id), so several accounts
 * share the phone. The saved session lives in the Keystore, out of reach of a migration, so existing rows keep a null
 * owner here and keep every value; [claimUnowned] then gives them to the account in the saved session when it loads,
 * or else to the next account that signs in online. The budget table is rebuilt because its key becomes
 * (owner, category).
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tx` ADD COLUMN `ownerId` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tx_ownerId` ON `tx` (`ownerId`)")
        db.execSQL("ALTER TABLE `outbox` ADD COLUMN `ownerId` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_ownerId` ON `outbox` (`ownerId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `budget_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ownerId` TEXT, " +
                "`category` TEXT NOT NULL, `limitKyat` INTEGER NOT NULL)",
        )
        db.execSQL("INSERT INTO `budget_new` (`ownerId`, `category`, `limitKyat`) SELECT NULL, `category`, `limitKyat` FROM `budget`")
        db.execSQL("DROP TABLE `budget`")
        db.execSQL("ALTER TABLE `budget_new` RENAME TO `budget`")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budget_ownerId_category` ON `budget` (`ownerId`, `category`)")
    }
}

/**
 * Gives every row without an owner (saved before several accounts) to [owner]: transactions, outbox ops and cached
 * budgets, in one transaction. Nothing is deleted except a cached budget [owner] already has a fresher copy of.
 * Called when the saved session loads and on every online sign-in; once rows have owners it changes nothing.
 */
suspend fun BudgetWiseDb.claimUnowned(owner: String) = withTransaction {
    transactionDao().claimUnowned(owner)
    outboxDao().claimUnowned(owner)
    budgetDao().claimUnowned(owner)
    budgetDao().deleteUnowned()
}
