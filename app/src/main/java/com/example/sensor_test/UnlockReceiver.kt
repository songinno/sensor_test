package com.example.sensor_test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 사용자 잠금해제(USER_PRESENT) 이벤트를 수신하여
 * 마지막 잠금해제 시각(lastUnlockMs)을 SharedPreferences에 기록하는 리시버
 */
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            context.getSharedPreferences("probe", Context.MODE_PRIVATE)
                .edit()
                .putLong("lastUnlockMs", System.currentTimeMillis())
                .apply()
        }
    }
}
