package app.budgetwise.di

import app.budgetwise.reminders.AndroidMonthEndNotifier
import app.budgetwise.reminders.DataStoreReminderStore
import app.budgetwise.reminders.DefaultMonthEndReminders
import app.budgetwise.reminders.MonthEndNotifier
import app.budgetwise.reminders.MonthEndReminders
import app.budgetwise.reminders.ReminderStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The month-end reminder (notification M1). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderModule {
    @Binds
    abstract fun reminderStore(impl: DataStoreReminderStore): ReminderStore

    @Binds
    abstract fun monthEndNotifier(impl: AndroidMonthEndNotifier): MonthEndNotifier

    @Binds
    abstract fun monthEndReminders(impl: DefaultMonthEndReminders): MonthEndReminders
}
