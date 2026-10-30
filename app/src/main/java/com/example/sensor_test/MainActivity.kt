package com.example.sensor_test

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.work.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 백그라운드 걸음 수 측정 PoC 테스트용 메인 액티비티
 */
class MainActivity : ComponentActivity() {

    // 화면 상단에 표시되는 배터리 제한 없음 / 사용하지 않는 앱 관리 / 사용 정보 접근 실시간 상태 텍스트
    private var status by mutableStateOf("")

    // 화면 하단에 표시되는 probe.log 파일 텍스트 내용
    private var log by mutableStateOf("")

    // 단일 권한 요청(신체 활동, 알림) 결과를 수신하고 상태 텍스트를 갱신하는 런처
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    // 설정 화면(배터리 최적화, 사용하지 않는 앱 관리, 사용 정보 접근) 이동 후 돌아왔을 때 상태를 갱신하는 런처
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(
                Modifier
                    .systemBarsPadding()
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                // 현재 앱 상태 요약 표시 (배터리 제한 없음 여부 / 사용하지 않는 앱 관리 여부 / 사용 정보 접근 여부)
                Text(status)
                Spacer(Modifier.height(8.dp))

                // 버튼 1: 신체 활동 권한(ACTIVITY_RECOGNITION) 요청 (Android 10 / API 29 이상)
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 29) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(this@MainActivity, "신체 활동 권한이 이미 허용되어 있습니다.", Toast.LENGTH_SHORT).show()
                        } else {
                            permLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Android 9 이하 버전은 신체 활동 런타임 권한이 필요 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("1. 신체 활동 권한") }

                // 버튼 2: 알림 권한(POST_NOTIFICATIONS) 요청 (Android 13 / API 33 이상, FGS 알림 관찰용)
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(this@MainActivity, "알림 권한이 이미 허용되어 있습니다.", Toast.LENGTH_SHORT).show()
                        } else {
                            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Android 12 이하 버전은 알림 런타임 권한이 필요 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("2. 알림 권한") }

                // 버튼 3: 배터리 "제한 없음" 원탭 시스템 팝업 호출
                Button(onClick = { requestBatteryExemption() }) { Text("3. 배터리 제한 없음 요청(원탭)") }

                // 버튼 4: '사용하지 않는 앱 관리(권한 삭제/휴면)' 설정 화면 이동
                Button(onClick = { openUnusedAppRestrictionsSettings() }) { Text("4. '사용하지 않는 앱 관리' 끄러 가기") }

                // 버튼 4-1: '사용 정보 접근' 권한 설정 화면 이동
                Button(onClick = { openUsageAccess() }) { Text("4-1. '사용 정보 접근' 권한 켜러 가기") }

                // 버튼 5: WorkManager 15분 주기 작업 등록
                Button(onClick = { registerPeriodic() }) { Text("5. 주기 작업 등록(15분)") }

                // 버튼 6: WorkManager 1회 수동 즉시 실행
                Button(onClick = { runOnce() }) { Text("6. 지금 1회 실행") }

                // 새로고침 및 로그 지우기 조작 버튼 행
                Row {
                    Button(onClick = { refresh() }) { Text("새로고침") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { File(filesDir, "probe.log").delete(); refresh() }) { Text("로그 지우기") }
                }

                Spacer(Modifier.height(8.dp))
                // 수집된 probe.log 텍스트 로그 출력 영역
                Text(log, Modifier.verticalScroll(rememberScrollState()))
            }
        }
    }

    /**
     * 배터리 최적화 제외 (제한 없음) 원탭 시스템 팝업을 요청하는 메서드
     * 이미 '제한 없음' 상태인 경우 안내 토스트 표시 후 반환
     */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "이미 배터리 최적화 제외 (제한 없음) 상태입니다.", Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        settingsLauncher.launch(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    /**
     * '사용하지 않는 앱 관리(권한 삭제/앱 휴면)' 상태를 확인하고
     * 켜짐(제한 있음) 상태일 때만 관련 시스템 설정 화면으로 이동하는 메서드
     */
    private fun openUnusedAppRestrictionsSettings() {
        val f = PackageManagerCompat.getUnusedAppRestrictionsStatus(this)
        f.addListener({
            try {
                when (f.get()) {
                    UnusedAppRestrictionsConstants.DISABLED -> {
                        Toast.makeText(this, "이미 '사용하지 않는 앱 관리'가 꺼짐(정상) 상태입니다.", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> {
                        Toast.makeText(this, "이 기기는 '사용하지 않는 앱 관리' 기능을 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        settingsLauncher.launch(
                            IntentCompat.createManageUnusedAppRestrictionsIntent(this@MainActivity, packageName)
                        )
                    }
                }
            } catch (_: Exception) {
                settingsLauncher.launch(
                    IntentCompat.createManageUnusedAppRestrictionsIntent(this@MainActivity, packageName)
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * '사용 정보 접근(Usage Access)' 권한 허용 여부 확인
     */
    private fun isUsageAccessGranted(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * '사용 정보 접근' 권한 설정 화면으로 이동하는 메서드
     * (우리 앱 스위치 화면 시도 ➔ 실패 시 목록 화면으로 폴백)
     */
    private fun openUsageAccess() {
        if (isUsageAccessGranted()) {
            Toast.makeText(this, "이미 '사용 정보 접근' 권한이 허용되어 있습니다.", Toast.LENGTH_SHORT).show()
            refresh()
            return
        }
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))
        try {
            settingsLauncher.launch(direct) // 우리 앱 스위치 화면 시도
        } catch (_: ActivityNotFoundException) {
            settingsLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) // 실패 시 목록 화면
        }
    }

    /**
     * WorkManager에 15분 주기 걸음 수 수집 작업(StepProbeWorker)을 등록하는 메서드 (UPDATE 정책 적용)
     */
    private fun registerPeriodic() {
        val req = PeriodicWorkRequestBuilder<StepProbeWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf("src" to "periodic")).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("probe", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /**
     * WorkManager에 StepProbeWorker를 1회 수동 즉시 실행 요청하는 메서드
     */
    private fun runOnce() {
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<StepProbeWorker>()
                .setInputData(workDataOf("src" to "once")).build()
        )
    }

    /**
     * 현재 앱의 배터리 제한 없음 여부, 사용하지 않는 앱 관리 상태, 사용 정보 접근 상태, probe.log 내용을 갱신하는 메서드
     */
    private fun refresh() {
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val usageAccess = if (isUsageAccessGranted()) "허용됨" else "미허용(4-1번 누르기)"
        val f = PackageManagerCompat.getUnusedAppRestrictionsStatus(this)
        f.addListener({
            val unused = when (f.get()) {
                UnusedAppRestrictionsConstants.DISABLED -> "꺼짐(정상)"
                UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> "기능 없음"
                UnusedAppRestrictionsConstants.ERROR -> "확인 실패"
                else -> "켜짐(4번으로 끄기)"
            }
            status = "배터리 제한 없음: ${if (exempt) "예" else "아니오(3번 누르기)"}\n" +
                     "사용하지 않는 앱 관리: $unused\n" +
                     "사용 정보 접근: $usageAccess"
        }, ContextCompat.getMainExecutor(this))
        log = readLog(this)
    }

    // 앱 화면이 보여짐 (Worker 로그의 sinceAppMin 산출용: FG 상태)
    override fun onStart() { super.onStart(); markStop(this, -1L) }
    
    // 화면 재진입 시 상태 새로고침
    override fun onResume() { super.onResume(); refresh() }
    
    // 앱 화면을 떠남 (Worker 로그의 sinceAppMin 산출용: 이탈 시각 ms 저장)
    override fun onStop() { super.onStop(); markStop(this, System.currentTimeMillis()) }
}

/**
 * 앱 화면 이탈 시각(lastStopMs)을 SharedPreference에 기록하는 함수
 */
private fun markStop(ctx: Context, v: Long) =
    ctx.getSharedPreferences("probe", Context.MODE_PRIVATE).edit().putLong("lastStopMs", v).apply()

/**
 * 내부 저장소의 probe.log 파일 내용을 읽어오는 함수
 */
private fun readLog(ctx: Context): String =
    File(ctx.filesDir, "probe.log").takeIf { it.exists() }?.readText() ?: "(로그 없음)"
