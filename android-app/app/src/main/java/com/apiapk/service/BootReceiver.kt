package com.apiapk.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.apiapk.model.ConversationStore

/**
 * 开机自启动广播接收器 - 在设备启动后自动启动API服务器和无障碍服务。
 * 需要用户在设置中预先开启自启动功能，并授予RECEIVE_BOOT_COMPLETED权限。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device booted, checking auto-start configuration")
            val store = ConversationStore(context)
            val config = store.loadConfig()

            if (config.autoStart) {
                Log.i(TAG, "Auto-start enabled, starting API server")
                AICaptureService.setConversationStore(store)
                val serverIntent = Intent(context, ApiServerService::class.java)
                context.startForegroundService(serverIntent)
            } else {
                Log.d(TAG, "Auto-start disabled, skipping")
            }
        }
    }
}
