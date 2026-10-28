package app.budgetwise.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Version 2 adds the budget cache. Existing transactions and outbox rows are untouched. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `budget` (`category` TEXT NOT NULL, `limitKyat` INTEGER NOT NULL, PRIMARY KEY(`category`))",
        )
    }
}
