package app.budgetwise.ui.onboarding

import app.budgetwise.data.repo.RegisterResult
import app.budgetwise.testing.FakeAccountActions
import app.budgetwise.testing.MainDispatcherRule
import app.budgetwise.testing.RegisterCall
import app.budgetwise.testing.TestUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CreateAccountViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val accounts = FakeAccountActions()

    private fun TestScope.collectEvents(vm: CreateAccountViewModel): List<CreateAccountEvent> {
        val events = mutableListOf<CreateAccountEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        return events
    }

    private fun CreateAccountViewModel.fill(email: String = " aye@example.com ", password: String = "teashop26") {
        onEmailChange(email)
        onPasswordChange(password)
    }

    @Test
    fun aNewAccountIsCreatedSignedInAndThePasswordDropped() = runTest {
        accounts.onRegister = { RegisterResult.Success(TestUser) }
        val vm = CreateAccountViewModel(accounts)
        val events = collectEvents(vm)

        vm.fill()
        vm.submit()

        // No name is asked; the server names the account after the email.
        assertEquals(listOf(RegisterCall("aye@example.com", "teashop26", "")), accounts.registers)
        assertEquals(listOf(CreateAccountEvent.SignedIn), events)
        assertTrue(vm.state.value.signedIn)
        assertEquals("", vm.state.value.password)
        assertFalse(vm.state.value.canSubmit)
    }

    @Test
    fun aShortPasswordOrAnEmailWithoutAnAtNeverReachTheServer() = runTest {
        val vm = CreateAccountViewModel(accounts)

        vm.fill(password = "tea2")
        vm.submit()
        assertEquals(CreateAccountError.ShortPassword, vm.state.value.error)

        vm.onPasswordChange("teashop26")
        assertNull("typing clears the field's error", vm.state.value.error)
        vm.onEmailChange("aye.example.com")
        vm.submit()
        assertEquals(CreateAccountError.InvalidEmail, vm.state.value.error)
        assertTrue(accounts.registers.isEmpty())
    }

    @Test
    fun aTakenEmailStaysUntilTheEmailChanges() = runTest {
        accounts.onRegister = { RegisterResult.EmailTaken }
        val vm = CreateAccountViewModel(accounts)

        vm.fill()
        vm.submit()
        assertEquals(CreateAccountError.EmailTaken, vm.state.value.error)
        assertFalse(vm.state.value.loading)

        vm.onPasswordChange("teashop27")
        assertEquals("the password is not what was wrong", CreateAccountError.EmailTaken, vm.state.value.error)
        vm.onEmailChange("aye2@example.com")
        assertNull(vm.state.value.error)
    }

    @Test
    fun answersAwayFromTheFieldsAreNotices() = runTest {
        val vm = CreateAccountViewModel(accounts)
        vm.fill()
        for ((result, error) in listOf(
            RegisterResult.NeedsInternet to CreateAccountError.NeedsInternet,
            RegisterResult.Failure("rate_limited", "Too many", retryable = true) to CreateAccountError.RateLimited,
            RegisterResult.Failure("http_503", "HTTP 503", retryable = true) to CreateAccountError.Server,
            RegisterResult.Failure("validation_failed", "Invalid request", retryable = false) to CreateAccountError.Generic,
        )) {
            accounts.onRegister = { result }
            vm.submit()
            assertEquals(error, vm.state.value.error)
        }
        accounts.onRegister = { throw IllegalStateException("keystore") }
        vm.submit()
        assertEquals(CreateAccountError.Generic, vm.state.value.error)
    }

    @Test
    fun aSecondTapWhileWaitingDoesNothing() = runTest {
        accounts.registerGate = CompletableDeferred()
        accounts.onRegister = { RegisterResult.Success(TestUser) }
        val vm = CreateAccountViewModel(accounts)
        vm.fill()

        vm.submit()
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        accounts.registerGate!!.complete(Unit)

        assertEquals(1, accounts.registers.size)
        assertTrue(vm.state.value.signedIn)
    }
}
