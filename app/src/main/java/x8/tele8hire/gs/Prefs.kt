package x8.tele8hire.gs

import android.content.Context

/** تخزين محلي بسيط لتوكن FCM. */
object Prefs {
    private const val FILE = "t8"
    private const val KEY_TOKEN = "fcm_token"

    fun token(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_TOKEN, "") ?: ""

    fun setToken(ctx: Context, token: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, token).apply()
    }
}
