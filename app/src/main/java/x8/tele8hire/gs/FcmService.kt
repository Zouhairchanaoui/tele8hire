package x8.tele8hire.gs

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL

/**
 * يستقبل رسائل FCM (data فقط) ويبني الإشعار بنفسه:
 * - مجمّع حسب المحادثة بأسلوب MessagingStyle مع صورة المرسل
 * - الضغط عليه يفتح المحادثة مباشرة
 * - لا يظهر إذا كان التطبيق مفتوحاً (الواجهة تعرض الرسالة لحظياً)
 */
class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Prefs.setToken(this, token)
        MainActivity.deliverToken(token)
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        if (MainActivity.inForeground) return
        val d = msg.data
        val cid = d["conversation_id"] ?: return
        val body = d["body"] ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val sender = d["sender_name"]?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        val avatar = d["sender_avatar"]?.takeIf { it.startsWith("https://") }?.let { loadAvatar(it) }
        show(cid, d["sender_id"] ?: cid, sender, body, avatar)
    }

    private fun show(cid: String, senderId: String, sender: String, body: String, avatar: Bitmap?) {
        val nm = getSystemService(NotificationManager::class.java)
        val id = cid.hashCode()

        val person = Person.Builder()
            .setName(sender)
            .setKey(senderId)
            .apply { avatar?.let { setIcon(IconCompat.createWithBitmap(it)) } }
            .build()

        val existing = nm.activeNotifications.firstOrNull { it.id == id }?.notification
        val style = existing?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
            ?: NotificationCompat.MessagingStyle(Person.Builder().setName(getString(R.string.you)).build())
        style.addMessage(body, System.currentTimeMillis(), person)

        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversation_id", cid)
        }
        val pi = PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0F766E.toInt())
            .setStyle(style)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(GROUP)
            .setWhen(System.currentTimeMillis())
            .build()
        nm.notify(id, n)

        val home = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0F766E.toInt())
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(home)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()
        nm.notify(0, summary)
    }

    private fun loadAvatar(url: String): Bitmap? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
        }
        c.inputStream.use { BitmapFactory.decodeStream(it) }?.let { circle(it) }
    } catch (_: Exception) {
        null
    }

    private fun circle(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val size = 128
        val scaled = Bitmap.createScaledBitmap(sq, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    companion object {
        private const val GROUP = "t8_messages"
    }
}
