package ua.kpi.building_audit.domain.resources

data class ResourceSnapshot(
    val timestampMs: Long,
    /** Завантаження CPU процесом застосунку, % від усіх ядер. */
    val cpuPercent: Double,
    val memoryUsedPercent: Double,
    val memoryAvailableMb: Double,
    val appHeapMb: Double,
    val batteryPercent: Int,
    val charging: Boolean,
    val batteryTemperatureC: Double,
    val rxKbPerSec: Double,
    val txKbPerSec: Double,
    val storageFreeGb: Double,
    val storageTotalGb: Double
) {
    val storageUsedPercent: Double get() = if (storageTotalGb <= 0) 0.0 else (1 - storageFreeGb / storageTotalGb) * 100
}

/** Сирі лічильники одного опитування; відсотки й швидкості рахуються з різниці двох опитувань. */
data class RawCounters(val wallMs: Long, val cpuMs: Long, val rxBytes: Long, val txBytes: Long)

object ResourceMath {
    fun cpuPercent(previous: RawCounters, current: RawCounters, cores: Int): Double {
        val wall = current.wallMs - previous.wallMs
        if (wall <= 0 || cores <= 0) return 0.0
        return ((current.cpuMs - previous.cpuMs).toDouble() / (wall * cores) * 100).coerceIn(0.0, 100.0)
    }

    /** Кілобайти за секунду; від'ємна різниця (лічильник скинуто) дає 0. */
    fun rateKbPerSec(previousBytes: Long, currentBytes: Long, wallMs: Long): Double {
        if (wallMs <= 0) return 0.0
        return (currentBytes - previousBytes).coerceAtLeast(0) / 1024.0 / (wallMs / 1000.0)
    }
}

enum class ResourceLevel { NORMAL, WARNING, CRITICAL }

/** Пороги для кольорових індикаторів: [warning] і [critical] у тих самих одиницях, що й значення. */
data class LevelScale(val warning: Double, val critical: Double, val higherIsWorse: Boolean = true) {
    fun of(value: Double): ResourceLevel = if (higherIsWorse) {
        when {
            value >= critical -> ResourceLevel.CRITICAL
            value >= warning -> ResourceLevel.WARNING
            else -> ResourceLevel.NORMAL
        }
    } else {
        when {
            value <= critical -> ResourceLevel.CRITICAL
            value <= warning -> ResourceLevel.WARNING
            else -> ResourceLevel.NORMAL
        }
    }

    companion object {
        val CPU = LevelScale(50.0, 85.0)
        val MEMORY = LevelScale(75.0, 90.0)
        val BATTERY = LevelScale(30.0, 15.0, higherIsWorse = false)
        val STORAGE = LevelScale(80.0, 95.0)
    }
}
