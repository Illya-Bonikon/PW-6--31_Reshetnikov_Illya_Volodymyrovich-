package ua.kpi.building_audit.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import ua.kpi.building_audit.domain.resources.RawCounters
import ua.kpi.building_audit.domain.resources.ResourceMath
import ua.kpi.building_audit.domain.resources.ResourceSnapshot

/** Опитує системні лічильники з фонового потоку; головний потік лише отримує готові знімки. */
class ResourceMonitor(private val context: Context) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val cores = Runtime.getRuntime().availableProcessors()

    fun snapshots(periodMs: Long = 1_000): Flow<ResourceSnapshot> = flow {
        var previous = counters()
        while (true) {
            delay(periodMs)
            val current = counters()
            emit(snapshot(previous, current))
            previous = current
        }
    }.flowOn(Dispatchers.IO)  // Default може бути зайнятий навантажувальним тестом

    private fun counters() = RawCounters(
        wallMs = SystemClock.elapsedRealtime(),
        cpuMs = Process.getElapsedCpuTime(),
        rxBytes = TrafficStats.getUidRxBytes(Process.myUid()),
        txBytes = TrafficStats.getUidTxBytes(Process.myUid())
    )

    private fun snapshot(previous: RawCounters, current: RawCounters): ResourceSnapshot {
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val storage = StatFs(context.filesDir.path)
        val wall = current.wallMs - previous.wallMs
        return ResourceSnapshot(
            timestampMs = System.currentTimeMillis(),
            cpuPercent = ResourceMath.cpuPercent(previous, current, cores),
            memoryUsedPercent = (1 - memory.availMem.toDouble() / memory.totalMem) * 100,
            memoryAvailableMb = memory.availMem / MB,
            appHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / MB,
            batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            batteryTemperatureC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0,
            rxKbPerSec = ResourceMath.rateKbPerSec(previous.rxBytes, current.rxBytes, wall),
            txKbPerSec = ResourceMath.rateKbPerSec(previous.txBytes, current.txBytes, wall),
            storageFreeGb = storage.availableBytes / GB,
            storageTotalGb = storage.totalBytes / GB
        )
    }

    private companion object {
        const val MB = 1024.0 * 1024
        const val GB = MB * 1024
    }
}
