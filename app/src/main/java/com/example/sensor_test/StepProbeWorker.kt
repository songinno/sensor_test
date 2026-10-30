package com.example.sensor_test

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalDateTime
import kotlin.coroutines.resume

class StepProbeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        val charging = bm.isCharging

        // 1) 몇 초짜리 FGS로 승격 (배터리 '제한 없음'일 때만 백그라운드에서 허용됨)
        val fgs = try {
            setForeground(foregroundInfo(ctx))
            "OK"
        } catch (t: Throwable) {
            "DENIED(${t.javaClass.simpleName})"   // 예외가 없거나 권한이 없으면 여기로
        }

        // 2) 걸음 센서 읽기 (최대 10초, 실제 걸린 시간은 readMs로 기록)
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val t0 = SystemClock.elapsedRealtime()
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
        val readMs = SystemClock.elapsedRealtime() - t0

        // 3) 앱을 떠난 뒤 경과 분
        val lastStop = ctx.getSharedPreferences("probe", Context.MODE_PRIVATE)
            .getLong("lastStopMs", 0L)
        val sinceApp = when {
            lastStop < 0L -> "FG"
            lastStop == 0L -> "NA"
            else -> ((System.currentTimeMillis() - lastStop) / 60000).toString()
        }

        // 4) 마지막 잠금해제(USER_PRESENT) 이후 경과 분
        val lastUnlockMs = ctx.getSharedPreferences("probe", Context.MODE_PRIVATE)
            .getLong("lastUnlockMs", 0L)
        val lastUnlockMin = when {
            lastUnlockMs == 0L -> "NA"
            else -> ((System.currentTimeMillis() - lastUnlockMs) / 60000).toString()
        }

        val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            usm.appStandbyBucket.toString()
        } else {
            "NA"
        }

        val line = listOf(
            LocalDateTime.now().toString(),
            "ver=5",
            "src=${inputData.getString("src") ?: "?"}",
            "steps=${value?.toLong() ?: (if (sensor == null) "NO_SENSOR" else "TIMEOUT")}",
            "readMs=$readMs",
            "fgs=$fgs",
            "exempt=$exempt",
            "charging=$charging",
            "interactive=${pm.isInteractive}",
            "doze=${pm.isDeviceIdleMode}",
            "bucket=$bucket",
            "uptimeMin=${SystemClock.elapsedRealtime() / 60000}",
            "sinceAppMin=$sinceApp",
            "lastUnlockMin=$lastUnlockMin"
        ).joinToString(" ") + "\n"

        File(ctx.filesDir, "probe.log").appendText(line)
        Log.i("StepProbe", line.trim())
        return Result.success()   // 끝나면 FGS와 알림이 자동으로 내려감
    }

    private fun foregroundInfo(ctx: Context): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH, "걸음 확인(테스트)", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("걸음 수 확인 중")
            .setSilent(true)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34)
            ForegroundInfo(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        else
            ForegroundInfo(NOTI_ID, n)
    }

    companion object {
        const val CH = "probe_fgs"
        const val NOTI_ID = 7001
    }
}
