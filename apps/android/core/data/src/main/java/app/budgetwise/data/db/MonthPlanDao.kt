package app.budgetwise.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.time.YearMonth
import kotlinx.coroutines.flow.Flow

/** Months are `YYYY-MM` text, so BETWEEN and ORDER BY compare them in calendar order. */
@Dao
interface MonthPlanDao {
    @Query("DELETE FROM budget_month WHERE ownerId = :owner")
    suspend fun deleteFor(owner: String)

    @Query("SELECT * FROM budget_month WHERE ownerId = :owner AND month = :month")
    fun observe(owner: String, month: YearMonth): Flow<MonthPlanEntity?>

    /** Oldest first, both ends included. */
    @Query("SELECT * FROM budget_month WHERE ownerId = :owner AND month BETWEEN :from AND :to ORDER BY month")
    fun observeRange(owner: String, from: YearMonth, to: YearMonth): Flow<List<MonthPlanEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MonthPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<MonthPlanEntity>)

    @Query("DELETE FROM budget_month WHERE ownerId = :owner AND month BETWEEN :from AND :to")
    suspend fun deleteRange(owner: String, from: YearMonth, to: YearMonth)

    /** The server's answer for a range is the truth for it: a month it no longer has a plan for is removed. */
    @Transaction
    suspend fun replaceRange(owner: String, from: YearMonth, to: YearMonth, rows: List<MonthPlanEntity>) {
        deleteRange(owner, from, to)
        insertAll(rows)
    }
}
