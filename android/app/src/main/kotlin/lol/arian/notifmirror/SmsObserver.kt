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

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        try { LogStore.append(ctx, "SmsObserver onChange uri=${uri?.toString()}") } catch (_: Exception) {}
        val cursor = ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DEFAULT_SORT_ORDER
        ) ?: return

        cursor.use {
            if (it.moveToFirst()) {
                val from = it.getString(0)
                val body = it.getString(1)
                val date = it.getLong(2)
                
                // 尝试进行验证码替换
                val mirrorText = CodeMirrorHelper.buildMirrorText(body)
                
                // 如果有替换后的文本，发送镜像通知给手表
                if (!mirrorText.isNullOrEmpty()) {
                    try {
                        val displayTitle = "短信 - $from"
                        CodeMirrorHelper.sendMirrorNotification(ctx, displayTitle, mirrorText)
                        LogStore.append(ctx, "SMS mirror notify sent: from='$from' len=${mirrorText.length}")
                    } catch (e: Exception) {
                        LogStore.append(ctx, "SMS mirror notify failed: ${e.message}")
                    }
                } else {
                    try { LogStore.append(ctx, "SMS mirror skipped: no code transformed") } catch (_: Exception) {}
                }
                
                // 传递给 Flutter（使用替换后的文本或原始文本）
                val bodyToSend = mirrorText ?: body
                channel.invokeMethod("onSms", mapOf("from" to from, "body" to bodyToSend, "date" to date))
            }
        }
    }
}
