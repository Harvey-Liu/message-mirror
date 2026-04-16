package lol.arian.notifmirror

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.Telephony
import io.flutter.plugin.common.MethodChannel
class SmsObserver(
    private val ctx: Context,
    private val channel: MethodChannel
) : ContentObserver(Handler(ctx.mainLooper)) {
    companion object {
        private const val SMS_MIRROR_DEDUP_MS = 2000L
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        try { LogStore.append(ctx, "SmsObserver onChange uri=${uri?.toString()}") } catch (_: Exception) {}
        val cursor = ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DEFAULT_SORT_ORDER
        ) ?: return

        cursor.use {
            if (it.moveToFirst()) {
                val from = it.getString(0) ?: ""
                val body = it.getString(1) ?: ""
                val date = it.getLong(2)

                val mirrorText = CodeMirrorHelper.buildMirrorText(body)

                if (!mirrorText.isNullOrEmpty()) {
                    try {
                        val prefs = ctx.getSharedPreferences("msg_mirror", Context.MODE_PRIVATE)
                        val lastMirror = prefs.getString("last_sms_mirror", "") ?: ""
                        val lastTime = prefs.getLong("last_sms_time", 0L)
                        val now = System.currentTimeMillis()
                        if (!(mirrorText == lastMirror && now - lastTime < SMS_MIRROR_DEDUP_MS)) {
                            prefs.edit()
                                .putString("last_sms_mirror", mirrorText)
                                .putLong("last_sms_time", now)
                                .apply()
                            val displayTitle = if (from.isNotBlank()) "message - $from" else "unknown sender"
                            CodeMirrorHelper.sendMirrorNotification(ctx, displayTitle, mirrorText)
                            LogStore.append(ctx, "SMS mirror notify sent: from='$from' len=${mirrorText.length}")
                        } else {
                            LogStore.append(ctx, "SMS mirror skipped: dedup hit")
                        }
                    } catch (e: Exception) {
                        LogStore.append(ctx, "SMS mirror notify failed: ${e.message}")
                    }
                } else {
                    try { LogStore.append(ctx, "SMS mirror skipped: no code transformed") } catch (_: Exception) {}
                }
                
                val bodyToSend = mirrorText ?: body
                channel.invokeMethod("onSms", mapOf("from" to from, "body" to bodyToSend, "date" to date))
            }
        }
    }
}
