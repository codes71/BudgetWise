package app.budgetwise.asr

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/** Hardware facts used to size the llama.cpp thread pool and to gate the larger Raj variant. */
data class DeviceProfile(val totalRamBytes: Long, val coreMaxKhz: List<Int>, val socModel: String) {

    /** Cores within 0.85 x the fastest core, clamped to 2..4. Falls back to 2 without frequency data. */
    fun threads(): Int {
        val fastest = coreMaxKhz.maxOrNull() ?: return MIN_THREADS
        if (fastest <= 0) return MIN_THREADS
        val big = coreMaxKhz.count { it >= fastest * BIG_CORE_RATIO }
        return big.coerceIn(MIN_THREADS, MAX_THREADS)
    }

    /** Raj 1.7B needs about 8 GB phones. The on-device benchmark gate (G2) is a release-time check. */
    fun eligible(v: RajVariant): Boolean = when (v) {
        RajVariant.RAJ_0_6B -> true
        RajVariant.RAJ_1_7B -> totalRamBytes >= MIN_RAM_FOR_1_7B
    }

    companion object {
        private const val MIN_THREADS = 2
        private const val MAX_THREADS = 4
        private const val BIG_CORE_RATIO = 0.85
        private const val MIN_RAM_FOR_1_7B = 7_500_000_000L

        fun detect(context: Context): DeviceProfile {
            val memInfo = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.getMemoryInfo(memInfo)
            return DeviceProfile(memInfo.totalMem, readCoreMaxKhz(), readSocModel())
        }

        private fun readCoreMaxKhz(): List<Int> = runCatching {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
                .orEmpty()
                .sortedBy { it.name.removePrefix("cpu").toInt() }
                .mapNotNull { runCatching { File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toInt() }.getOrNull() }
        }.getOrDefault(emptyList())

        private fun readSocModel(): String = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL
            } else {
                val sp = Class.forName("android.os.SystemProperties")
                sp.getMethod("get", String::class.java, String::class.java)
                    .invoke(null, "ro.board.platform", "") as String
            }
        }.getOrNull().orEmpty()
    }
}
