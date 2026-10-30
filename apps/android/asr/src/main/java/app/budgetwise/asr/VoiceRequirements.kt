package app.budgetwise.asr

import android.app.ActivityManager
import android.content.Context

/**
 * Whether this phone can run voice. The two models peak at 1.55 GB for a short phrase and 1.76 GB for 20 s (Xiaomi 14,
 * 2026-10-31), and a 4 GB emulator ran both without Android closing them; 3 GB phones could not be tested. Android
 * reports less than the advertised memory (a 4 GB phone shows about 3.6 to 3.9 GB, a 3 GB one about 2.7 to 2.9 GB), so
 * the line sits between the two.
 */
object VoiceRequirements {
    /** Total memory as Android reports it; advertised 4 GB phones are above it, 3 GB ones below. */
    const val MIN_TOTAL_RAM_BYTES = 3_300_000_000L

    /** What the screens say voice needs, in advertised gigabytes. */
    const val ADVERTISED_GB = 4

    fun supported(totalRamBytes: Long, lowRamDevice: Boolean): Boolean =
        !lowRamDevice && totalRamBytes >= MIN_TOTAL_RAM_BYTES

    fun supported(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return supported(memory.totalMem, manager.isLowRamDevice)
    }
}
