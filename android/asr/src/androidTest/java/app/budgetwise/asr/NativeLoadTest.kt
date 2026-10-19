package app.budgetwise.asr

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.budgetwise.asr.engine.RajNative
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLoadTest {

    @Test
    fun cpuBackendRegisteredOnDevice() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val description = RajNative.initBackendsOnce(context)

        assertTrue("no CPU backend in: $description", description.contains("CPU"))
        assertTrue("no android_armv variant in: $description", description.contains("android_armv"))
    }
}
