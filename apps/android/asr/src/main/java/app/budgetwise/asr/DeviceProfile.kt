package app.budgetwise.asr

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/** Hardware facts the device test and the demo log next to their timings. */
data class DeviceProfile(val totalRamBytes: Long, val coreMaxKhz: List<Int>, val socModel: String) {

    companion object {
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
