package com.apiapk.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 用户解锁屏幕广播接收器 - 在用户解锁手机后重启后台监控服务。
 * 某些厂商（小米、华为等）会在锁屏后杀掉后台服务，
 * 此接收器在用户回来时自动恢复服务。
 */
class UserPresentReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UserPresentReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_USER_PRESENT) {
            Log.i(TAG, "User present, checking if services need restart")
            val store = com.apiapk.model.ConversationStore(context)
            val config = store.loadConfig()

            if (config.autoStart && !ApiServerService.isRunning) {
                Log.i(TAG, "Auto-restarting API server after screen unlock")
                com.apiapk.service.AICaptureService.setConversationStore(store)
                val serverIntent = Intent(context, ApiServerService::class.java)
                context.startForegroundService(serverIntent)
            }
        }
    }
}
