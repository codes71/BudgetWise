package app.budgetwise.data.db

import androidx.room.TypeConverter
import app.budgetwise.data.model.Source
import app.budgetwise.data.model.SyncState
import app.budgetwise.nlu.TxType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * LocalDate and YearMonth are stored as ISO strings (YYYY-MM-DD, YYYY-MM) so they sort lexically. Instant is stored as
 * epoch milliseconds so ordering is numeric. Enums are stored by name.
 */
class Converters {
    @TypeConverter fun dateToString(v: LocalDate): String = v.toString()
    @TypeConverter fun stringToDate(v: String): LocalDate = LocalDate.parse(v)
    @TypeConverter fun yearMonthToString(v: YearMonth): String = v.toString()
    @TypeConverter fun stringToYearMonth(v: String): YearMonth = YearMonth.parse(v)
    @TypeConverter fun instantToLong(v: Instant): Long = v.toEpochMilli()
    @TypeConverter fun longToInstant(v: Long): Instant = Instant.ofEpochMilli(v)
    @TypeConverter fun txTypeToString(v: TxType): String = v.name
    @TypeConverter fun stringToTxType(v: String): TxType = TxType.valueOf(v)
    @TypeConverter fun sourceToString(v: Source): String = v.name
    @TypeConverter fun stringToSource(v: String): Source = Source.valueOf(v)
    @TypeConverter fun syncStateToString(v: SyncState): String = v.name
    @TypeConverter fun stringToSyncState(v: String): SyncState = SyncState.valueOf(v)
    @TypeConverter fun opToString(v: OutboxOp): String = v.name
    @TypeConverter fun stringToOp(v: String): OutboxOp = OutboxOp.valueOf(v)
}
