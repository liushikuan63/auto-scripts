package com.farmbot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.farmbot.engine.FarmEngine

class WateringAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WateringAlarmReceiver"
        const val ACTION_WATERING_ALARM = "com.farmbot.action.WATERING_ALARM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WATERING_ALARM || intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "收到闹钟广播: ${intent.action}")
            val engine = FarmEngine.getInstance(context)
            engine.triggerWateringNow()
        } else {
            Log.w(TAG, "收到未知广播: ${intent.action}")
        }
    }
}