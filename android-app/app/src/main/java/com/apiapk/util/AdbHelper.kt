package com.apiapk.util

import android.util.Log
import com.apiapk.model.AIConversation
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * ADB辅助工具 - 封装Android ADB命令操作，提供设备连接管理、Shell命令执行和应用操作功能。
 * 通过Runtime.exec调用系统ADB工具，支持设备发现、应用启动、输入模拟等操作。
 * 用于实现通过API向AI应用发送消息的自动化流程。
 */
class AdbHelper {

    companion object {
        private const val TAG = "AdbHelper"
        private const val ADB_TIMEOUT = 10000L
    }

    data class CommandResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    /**
     * 检查ADB是否在设备上可用。Android设备本身通过su权限可以使用adb命令，
     * 或者通过toybox/busybox等工具提供shell功能。
     */
    fun isAdbAvailable(): Boolean {
        return try {
            val result = executeCommand("which adb")
            result.success && result.stdout.trim().isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取已连接的ADB设备列表。在手机本地运行时，通常返回本地设备信息。
     */
    fun getDevices(): List<Map<String, String>> {
        val result = executeCommand("adb devices -l")
        if (!result.success) {
            return listOf(mapOf(
                "status" to "local",
                "model" to android.os.Build.MODEL,
                "device" to android.os.Build.DEVICE,
                "note" to "Running locally on device"
            ))
        }

        val devices = mutableListOf<Map<String, String>>()
        val lines = result.stdout.lines().drop(1) // 跳过 "List of devices attached" 标题

        for (line in lines) {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 2) {
                devices.add(mapOf(
                    "serial" to parts[0],
                    "status" to parts[1]
                ))
            }
        }

        if (devices.isEmpty()) {
            devices.add(mapOf(
                "status" to "local",
                "model" to android.os.Build.MODEL,
                "note" to "No external ADB devices; running locally"
            ))
        }

        return devices
    }

    /**
     * 执行ADB shell命令。在本地设备上直接通过Runtime执行shell命令。
     */
    fun executeShell(command: String): CommandResult {
        return try {
            val process = ProcessBuilder()
                .command("sh", "-c", command)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(ADB_TIMEOUT, TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                CommandResult(false, stdout, "Command timed out", -1)
            } else {
                CommandResult(process.exitValue() == 0, stdout, stderr, process.exitValue())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: ${e.message}")
            CommandResult(false, "", e.message ?: "Unknown error", -1)
        }
    }

    /**
     * 通过ADB向目标AI应用发送消息。使用input text和按键事件模拟用户输入。
     * 流程：1. 启动目标应用 2. 等待应用加载 3. 模拟文本输入 4. 发送回车键
     */
    fun sendViaAdb(appType: AIConversation.AppType, message: String): Boolean {
        return try {
            // 步骤1: 启动目标应用
            val launchResult = executeShell(
                "monkey -p ${appType.packageName} -c android.intent.category.LAUNCHER 1"
            )
            if (!launchResult.success) {
                // 降级方案：使用am start
                executeShell("am start -n ${appType.packageName}/.MainActivity")
            }

            // 步骤2: 等待应用加载
            Thread.sleep(1500)

            // 步骤3: 模拟点击输入框（需要各应用的输入框坐标，这里使用通用方法）
            executeShell("input tap 540 1800") // 通用输入框位置

            Thread.sleep(500)

            // 步骤4: 输入文本（使用input text命令，对特殊字符进行转义）
            val escapedMessage = message
                .replace(" ", "%s")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("&", "\\&")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("|", "\\|")
                .replace(";", "\\;")
                .replace("<", "\\<")
                .replace(">", "\\>")
                .replace("!", "\\!")

            val inputResult = executeShell("input text '$escapedMessage'")
            if (!inputResult.success) {
                Log.w(TAG, "input text failed, trying alternative method")
                // 使用ADB键盘输入法
                executeShell("ime set com.android.adbkeyboard/.AdbIME")
                executeShell("am broadcast -a ADB_INPUT_TEXT --es msg '$escapedMessage'")
            }

            Thread.sleep(300)

            // 步骤5: 发送回车或点击发送按钮
            executeShell("input keyevent 66") // KEYCODE_ENTER
            Thread.sleep(200)

            Log.i(TAG, "Message sent to ${appType.displayName} via ADB")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message via ADB: ${e.message}")
            false
        }
    }

    /**
     * 执行通用ADB命令。
     */
    fun executeCommand(command: String): CommandResult {
        return try {
            Log.d(TAG, "Executing: $command")

            val process = ProcessBuilder()
                .command("sh", "-c", command)
                .redirectErrorStream(false)
                .start()

            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            val stdout = StringBuilder()
            val stderr = StringBuilder()

            var line: String?
            while (stdoutReader.readLine().also { line = it } != null) {
                stdout.append(line).append("\n")
            }
            while (stderrReader.readLine().also { line = it } != null) {
                stderr.append(line).append("\n")
            }

            val finished = process.waitFor(ADB_TIMEOUT, TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                CommandResult(false, stdout.toString(), "Command timed out after ${ADB_TIMEOUT}ms", -1)
            } else {
                val result = CommandResult(
                    process.exitValue() == 0,
                    stdout.toString().trim(),
                    stderr.toString().trim(),
                    process.exitValue()
                )
                Log.d(TAG, "Command result: success=${result.success}, stdout=${result.stdout.take(200)}")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command execution error: ${e.message}")
            CommandResult(false, "", e.message ?: "Execution failed", -1)
        }
    }

    /**
     * 获取设备系统信息，用于调试和状态报告。
     */
    fun getDeviceInfo(): Map<String, String> {
        return mapOf(
            "model" to android.os.Build.MODEL,
            "device" to android.os.Build.DEVICE,
            "brand" to android.os.Build.BRAND,
            "sdk" to android.os.Build.VERSION.SDK_INT.toString(),
            "version" to android.os.Build.VERSION.RELEASE
        )
    }

    /**
     * 检查目标AI应用是否已安装。
     */
    fun isAppInstalled(packageName: String): Boolean {
        val result = executeShell("pm list packages $packageName")
        return result.success && result.stdout.contains(packageName)
    }

    /**
     * 获取目标应用的版本信息。
     */
    fun getAppVersion(packageName: String): String {
        val result = executeShell("dumpsys package $packageName | grep versionName")
        return if (result.success) {
            result.stdout.trim()
        } else {
            "Unknown"
        }
    }

    /**
     * 强制停止目标应用。
     */
    fun forceStopApp(packageName: String): Boolean {
        return executeShell("am force-stop $packageName").success
    }

    /**
     * 清除目标应用数据。
     */
    fun clearAppData(packageName: String): Boolean {
        return executeShell("pm clear $packageName").success
    }
}
