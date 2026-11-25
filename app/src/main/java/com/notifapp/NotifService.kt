package com.notifapp

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Cfg {
    // Verifikasi package name di HP kamu kalau ada yang tidak masuk
    val APPS = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WA Business",
        "com.gojek.app" to "GoPay",
        "com.gojek.gopaymerchant" to "GoPay Merchant",
        "com.shopee.id" to "ShopeePay"
    )
    fun prefs(c: Context) = c.getSharedPreferences("n", Context.MODE_PRIVATE)
}

class NotifService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = Cfg.APPS[sbn.packageName] ?: return
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
        val p = Cfg.prefs(this)
        val arr = JSONArray(p.getString("h", "[]"))
        val list = ArrayList<JSONObject>()
        list.add(JSONObject().put("t", time).put("a", app).put("ti", title).put("tx", text))
        for (i in 0 until minOf(arr.length(), 299)) list.add(arr.getJSONObject(i))
        p.edit().putString("h", JSONArray(list).toString()).apply()
        sendTelegram("[$app] $title\n$text")
    }

    private fun sendTelegram(msg: String) {
        val p = Cfg.prefs(this)
        val token = p.getString("tok", "") ?: ""
        val chat = p.getString("chat", "") ?: ""
        if (token.isBlank() || chat.isBlank()) return
        Thread {
            try {
                val u = URL("https://api.telegram.org/bot$token/sendMessage")
                val c = u.openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10000
                c.readTimeout = 10000
                val body = "chat_id=" + URLEncoder.encode(chat, "UTF-8") +
                    "&text=" + URLEncoder.encode(msg, "UTF-8")
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode
                c.disconnect()
            } catch (_: Exception) {}
        }.start()
    }
}
