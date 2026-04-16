package lol.arian.notifmirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.app.Notification

object CodeMirrorHelper {
    private const val MIRROR_CHANNEL_ID = "mirror_code_channel"
    private const val MIRROR_NOTIFICATION_TAG = "mirror_code"
    private const val MIRROR_NOTIFICATION_ID = 10086
    private const val MIRROR_TIMEOUT_MS = 45_000L

    fun formatSafeCode(code: String): String {
        return when {
            // 纯数字
            code.all { it.isDigit() } -> code.chunked(2).joinToString("-")

            // 字母+数字
            code.any { it.isLetter() } && code.any { it.isDigit() } ->
                code.map { "$it" }.joinToString(" ")

            else -> code
        }
    }

    private fun replaceCodeByRegex(text: String, regex: Regex): Pair<String, Boolean> {
        var changed = false
        val replaced = regex.replace(text) { m ->
            val safe = formatSafeCode(m.value)
            if (safe != m.value) changed = true
            safe
        }
        return replaced to changed
    }

    fun buildMirrorText(raw: String): String? {
        if (raw.isBlank()) return null

        var changed = false
        var out = raw

        // 1) 关键词 + 验证码片段
        val keywordRegex = Regex(
            "(?i)(code|验证码|otp|password|动态码|校验码)([^A-Za-z0-9]{0,10})([A-Za-z0-9]{4,10})"
        )
        out = keywordRegex.replace(out) { m ->
            val code = m.groupValues[3]
            val safe = formatSafeCode(code)
            if (safe != code) changed = true
            m.groupValues[1] + m.groupValues[2] + safe
        }

        // 2) 纯数字 4~8 位（如 123456 -> 12-34-56）
        val digitRegex = Regex("\\b\\d{4,8}\\b")
        val (afterDigit, digitChanged) = replaceCodeByRegex(out, digitRegex)
        out = afterDigit
        changed = changed || digitChanged

        // 3) 字母+数字混合 6~10 位（如 A1B2C3 -> A 1 B 2 C 3）
        val mixRegex = Regex("\\b(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,10}\\b")
        val (afterMix, mixChanged) = replaceCodeByRegex(out, mixRegex)
        out = afterMix
        changed = changed || mixChanged

        return if (changed) out else null
    }

    fun ensureMirrorChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        val existing = manager.getNotificationChannel(MIRROR_CHANNEL_ID)
        if (existing != null) return

        val c = NotificationChannel(
            MIRROR_CHANNEL_ID,
            "Watch Mirror",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            setShowBadge(false)
            description = "用于把验证码镜像到手表（静默、会自动过期）"
        }
        manager.createNotificationChannel(c)
    }

    fun sendMirrorNotification(context: Context, title: String, content: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureMirrorChannel(context, manager)

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, MIRROR_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        @Suppress("DEPRECATION")
        builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_LOW)

        if (Build.VERSION.SDK_INT >= 26) {
            builder.setTimeoutAfter(MIRROR_TIMEOUT_MS)
        }

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 19) {
            manager.notify(MIRROR_NOTIFICATION_TAG, MIRROR_NOTIFICATION_ID, notification)
        } else {
            @Suppress("DEPRECATION")
            manager.notify(MIRROR_NOTIFICATION_ID, notification)
        }
    }
}
