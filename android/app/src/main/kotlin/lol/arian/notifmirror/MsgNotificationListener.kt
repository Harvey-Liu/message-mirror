package lol.arian.notifmirror

import android.os.Bundle
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Intent
import android.content.Context
import io.flutter.plugin.common.MethodChannel

class MsgNotificationListener : NotificationListenerService() {
    companion object {
        const val ACTION = "lol.arian.notifmirror.NOTIF_EVENT"
        private const val MIRROR_CHANNEL_ID = "mirror_code_channel"
        private const val MIRROR_NOTIFICATION_TAG = "mirror_code"
        private const val MIRROR_NOTIFICATION_ID = 10086
        private const val MIRROR_TIMEOUT_MS = 45_000L
        @Volatile
        var channel: MethodChannel? = null
        private val pendingEvents: MutableList<Map<String, Any?>> = mutableListOf()

        fun setChannelAndFlush(ch: MethodChannel?) {
            channel = ch
            if (ch == null) return
            synchronized(pendingEvents) {
                try {
                    for (event in pendingEvents) {
                        ch.invokeMethod("onNotification", event)
                    }
                } catch (_: Exception) {}
                pendingEvents.clear()
            }
        }
    }

    private fun ensureMirrorChannel(manager: NotificationManager) {
        CodeMirrorHelper.ensureMirrorChannel(this, manager)
    }

    private fun sendMirrorNotification(title: String, content: String) {
        CodeMirrorHelper.sendMirrorNotification(this, title, content)
    }

    private fun buildMirrorText(raw: String): String? {
        return CodeMirrorHelper.buildMirrorText(raw)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { LogStore.append(this, "onNotificationPosted from ${sbn.packageName}") } catch (_: Exception) {}

        val n = sbn.notification ?: return
        val extras: Bundle = n.extras
        val app = sbn.packageName ?: ""

        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
        val lines = extras.getCharSequenceArray("android.textLines")?.joinToString("\n") { it.toString() } ?: ""

        val subText = extras.getCharSequence("android.subText")?.toString() ?: ""
        val summaryText = extras.getCharSequence("android.summaryText")?.toString() ?: ""
        val infoText = extras.getCharSequence("android.infoText")?.toString() ?: ""

        val people: String = try {
            val arr = extras.get("android.people.list") as? ArrayList<*> ?: arrayListOf<Any?>()
            if (arr.isEmpty()) "" else arr.joinToString(",") { it?.toString() ?: "" }
        } catch (_: Exception) { "" }

        val category = n.category ?: ""
        val priority = n.priority
        val channelId = try { if (android.os.Build.VERSION.SDK_INT >= 26) n.channelId ?: "" else "" } catch (_: Exception) { "" }
        val visibility = n.visibility
        val color = try { if (n.color != 0) String.format("#%08X", n.color) else "" } catch (_: Exception) { "" }
        val groupKey = try { extras.getString("android.support.groupKey") ?: "" } catch (_: Exception) { "" }
        val badgeIconType = try { if (android.os.Build.VERSION.SDK_INT >= 26) n.badgeIconType else -1 } catch (_: Exception) { -1 }
        val actionTitles: String = try { n.actions?.mapNotNull { it?.title?.toString() }?.joinToString("|") ?: "" } catch (_: Exception) { "" }

        val largeIconB64: String = try {
            val bmp = (extras.get("android.largeIcon") as? android.graphics.Bitmap)
                ?: run {
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        val ic = n.getLargeIcon()
                        if (ic != null) {
                            val dr = ic.loadDrawable(this)
                            if (dr != null) drawableToBitmap(dr) else null
                        } else null
                    } else null
                }
            if (bmp != null) android.util.Base64.encodeToString(toPngBytes(bmp), android.util.Base64.NO_WRAP) else ""
        } catch (_: Exception) { "" }

        val pictureB64: String = try {
            val bmp = extras.get("android.picture") as? android.graphics.Bitmap
            if (bmp != null) android.util.Base64.encodeToString(toPngBytes(bmp), android.util.Base64.NO_WRAP) else ""
        } catch (_: Exception) { "" }

        val textResolved = if (text.isNotEmpty()) text else if (bigText.isNotEmpty()) bigText else lines

        val isOngoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isOngoing) {
            try { LogStore.append(this, "skip ongoing notification for $app") } catch (_: Exception) {}
            return
        }

        val prefs = getSharedPreferences("msg_mirror", MODE_PRIVATE)
        val allowed = prefs.getStringSet("allowed_packages", setOf("com.google.android.apps.messaging", "com.tencent.mm", "org.telegram.messenger")) ?: setOf()

        if (allowed.isNotEmpty() && !allowed.contains(app)) {
            try { LogStore.append(this, "skip package $app (not allowed)") } catch (_: Exception) {}
            return
        }

        try { LogStore.append(this, "emit onNotification: title='$title' textLen=${textResolved.length}") } catch (_: Exception) {}

        // 构造镜像正文：保留原消息，其中特征验证码替换为可读格式
        var mirrorText: String? = null
        try {
            val fullMirror = buildMirrorText(textResolved)
            if (!fullMirror.isNullOrEmpty()) {
                val lastCode = prefs.getString("last_code", "") ?: ""
                val lastTime = prefs.getLong("last_time", 0L)
                val now = System.currentTimeMillis()
                if (!(fullMirror == lastCode && now - lastTime < 2000)) {
                    prefs.edit()
                        .putString("last_code", fullMirror)
                        .putLong("last_time", now)
                        .apply()
                    mirrorText = fullMirror
                    try { LogStore.append(this, "mirror_text for watch: $mirrorText") } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        if (!mirrorText.isNullOrEmpty()) {
            val displayTitle = if (title.isNotEmpty()) title else app
            sendMirrorNotification(displayTitle, mirrorText!!)
            try { LogStore.append(this, "mirror notify sent: title='$displayTitle' len=${mirrorText!!.length}") } catch (_: Exception) {}
        } else {
            try { LogStore.append(this, "mirror skipped: no code transformed, text='${textResolved.take(80)}'") } catch (_: Exception) {}
        }

        val intent = Intent(ACTION).apply {
            putExtra("app", app)
            putExtra("title", title)
            putExtra("text", textResolved)
            putExtra("when", sbn.postTime)
            putExtra("isGroupSummary", ((n.flags and 0x00000200) != 0))
            putExtra("subText", subText)
            putExtra("summaryText", summaryText)
            putExtra("bigText", bigText)
            putExtra("infoText", infoText)
            putExtra("people", people)
            putExtra("category", category)
            putExtra("priority", priority)
            putExtra("channelId", channelId)
            putExtra("groupKey", groupKey)
            putExtra("visibility", visibility)
            putExtra("color", color)
            putExtra("badgeIconType", badgeIconType)
            putExtra("actions", actionTitles)
            putExtra("largeIcon", largeIconB64)
            putExtra("picture", pictureB64)
            if (!mirrorText.isNullOrEmpty()) putExtra("mirror_text", mirrorText)
        }

        sendBroadcast(intent)

        val payload = mutableMapOf<String, Any?>(
            "app" to app,
            "title" to title,
            "text" to textResolved,
            "when" to sbn.postTime
        )
        if (!mirrorText.isNullOrEmpty()) payload["mirror_text"] = mirrorText

        val ch = channel
        if (ch != null) {
            ch.invokeMethod("onNotification", payload)
        } else {
            synchronized(pendingEvents) { pendingEvents.add(payload) }
            ApiSender.send(this, title, textResolved, sbn.postTime, mirrorText)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try { LogStore.append(this, "MsgListener onCreate") } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { LogStore.append(this, "MsgListener onDestroy") } catch (_: Exception) {}
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try { LogStore.append(this, "MsgListener onListenerConnected") } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try { LogStore.append(this, "MsgListener onListenerDisconnected") } catch (_: Exception) {}
    }
}

private fun toPngBytes(bmp: android.graphics.Bitmap): ByteArray {
    val stream = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
}

private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap {
    if (drawable is android.graphics.drawable.BitmapDrawable) {
        drawable.bitmap?.let { return it }
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bmp
}
