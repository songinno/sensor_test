package com.example.sensor_test

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val healthPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    )

    private val requestHealthPermissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { grantedPermissions ->
            if (grantedPermissions.containsAll(healthPermissions)) {
                Toast.makeText(this, "Health Connect 권한이 모두 허용되었습니다.", Toast.LENGTH_SHORT).show()
                checkHealthConnectData()
            } else {
                Toast.makeText(this, "Health Connect 권한이 일부 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var log by remember { mutableStateOf(readLog(this)) }
            Column(Modifier.padding(16.dp).fillMaxSize()) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 29)
                        permLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }) { Text("1. 권한 요청") }

                Button(onClick = {
                    connectHealthConnect()
                }) { Text("건강 데이터 연결 (Health Connect)") }

                Button(onClick = {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (_: Exception) {
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            startActivity(intent)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "이미 배터리 최적화 제외(제한 없음) 상태입니다.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("배터리 최적화 제외 (제한 없음) 팝업") }

                Button(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }) { Text("앱 정보 화면 이동") }

                Button(onClick = {
                    val req = PeriodicWorkRequestBuilder<StepProbeWorker>(15, TimeUnit.MINUTES)
                        .setInputData(workDataOf("src" to "periodic")).build()
                    WorkManager.getInstance(this@MainActivity)
                        .enqueueUniquePeriodicWork("probe", ExistingPeriodicWorkPolicy.KEEP, req)
                }) { Text("2. 주기 작업 등록(15분)") }

                Button(onClick = {
                    WorkManager.getInstance(this@MainActivity).enqueue(
                        OneTimeWorkRequestBuilder<StepProbeWorker>()
                            .setInputData(workDataOf("src" to "once")).build()
                    )
                }) { Text("3. 지금 1회 실행") }

                Button(onClick = { log = readLog(this@MainActivity) }) { Text("로그 새로고침") }

                Button(onClick = {
                    File(filesDir, "probe.log").delete(); log = readLog(this@MainActivity)
                }) { Text("로그 지우기") }

                Text(log, Modifier.verticalScroll(rememberScrollState()))
            }
        }
    }

    private fun connectHealthConnect() {
        val sdkStatus = HealthConnectClient.getSdkStatus(this)
        when (sdkStatus) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                Toast.makeText(this, "이 기기는 Health Connect를 지원하지 않습니다.", Toast.LENGTH_LONG).show()
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Toast.makeText(this, "Health Connect 앱 설치/업데이트가 필요합니다. Play 스토어로 이동합니다.", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                        setPackage("com.android.vending")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                    }
                    startActivity(intent)
                }
            }
            HealthConnectClient.SDK_AVAILABLE -> {
                val healthConnectClient = HealthConnectClient.getOrCreate(this)
                lifecycleScope.launch {
                    val granted = healthConnectClient.permissionController.getGrantedPermissions()
                    if (granted.containsAll(healthPermissions)) {
                        checkHealthConnectData()
                    } else {
                        requestHealthPermissionLauncher.launch(healthPermissions)
                    }
                }
            }
        }
    }

    private fun checkHealthConnectData() {
        val healthConnectClient = HealthConnectClient.getOrCreate(this)
        lifecycleScope.launch {
            try {
                val now = Instant.now()
                val startTime = now.minus(Duration.ofDays(1))
                val response = healthConnectClient.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(startTime, now)
                    )
                )
                val steps = response[StepsRecord.COUNT_TOTAL] ?: 0L
                if (steps > 0) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Health Connect 연결 완료")
                        .setMessage("Health Connect 연동 성공!\n최근 24시간 동안 수집된 걸음 수: ${steps}보")
                        .setPositiveButton("확인", null)
                        .show()
                } else {
                    showSamsungHealthGuideDialog()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Health Connect 데이터 조회 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSamsungHealthGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("최근 걸음 수 기록 없음 (삼성 헬스 연동 안내)")
            .setMessage(
                "Health Connect 권한은 정상 허용되었으나 최근 걸음 수 기록이 0보입니다.\n\n" +
                        "삼성 헬스 앱에서 Health Connect로 걸음 수 쓰기가 허용되어 있는지 확인해 주세요.\n\n" +
                        "■ 삼성 헬스 설정 방법:\n" +
                        "1. 삼성 헬스 앱 실행\n" +
                        "2. 오른쪽 상단 점 3개(⋮) > 설정\n" +
                        "3. 'Health Connect' 선택\n" +
                        "4. '걸음 수' 쓰기 허용 ON"
            )
            .setPositiveButton("확인", null)
            .show()
    }

    // 앱을 떠난 시각 기록(Worker 로그의 sinceAppMin 계산용)
    override fun onStart() { super.onStart(); markStop(this, -1L) }                      // 화면에 보임
    override fun onStop() { super.onStop(); markStop(this, System.currentTimeMillis()) } // 화면을 떠남
}

private fun markStop(ctx: Context, v: Long) =
    ctx.getSharedPreferences("probe", Context.MODE_PRIVATE).edit().putLong("lastStopMs", v).apply()

private fun readLog(ctx: Context): String =
    File(ctx.filesDir, "probe.log").takeIf { it.exists() }?.readText() ?: "(로그 없음)"
