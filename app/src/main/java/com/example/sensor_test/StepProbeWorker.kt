package com.example.sensor_test

import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import kotlin.coroutines.resume

class StepProbeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        val value: Float? = if (sensor == null) null else withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Float> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values[0])
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }

        val lastStop = ctx.getSharedPreferences("probe", Context.MODE_PRIVATE)
            .getLong("lastStopMs", 0L)
        val sinceApp = when {
            lastStop < 0L -> "FG"                       // 앱 화면이 떠 있음
            lastStop == 0L -> "NA"                      // 기록 없음
            else -> ((System.currentTimeMillis() - lastStop) / 60000).toString()
        }

        val bucket = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            usm.appStandbyBucket.toString()
        } else {
            "NA"
        }

        val line = listOf(
            LocalDateTime.now().toString(),
            "src=${inputData.getString("src") ?: "?"}",
            "steps=${value?.toLong() ?: (if (sensor == null) "NO_SENSOR" else "TIMEOUT")}",
            "interactive=${pm.isInteractive}",
            "doze=${pm.isDeviceIdleMode}",
            "bucket=$bucket",
            "uptimeMin=${SystemClock.elapsedRealtime() / 60000}",
            "sinceAppMin=$sinceApp"
        ).joinToString(" ") + "\n"

        File(ctx.filesDir, "probe.log").appendText(line)
        Log.i("StepProbe", line.trim())
        return Result.success()
    }
}
