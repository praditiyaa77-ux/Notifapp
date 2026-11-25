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
    // Daftar package bisa diubah di app (kolom "Package dipantau"). Cek package asli di riwayat.
    // Tanda * di akhir = cocok semua package berawalan itu (mis. id.dana* untuk DANA/DANA Bisnis)
    const val DEFAULT_PKGS = "com.whatsapp,com.whatsapp.w4b,com.gojek*,com.shopee.id,id.dana*"
    fun prefs(c: Context) = c.getSharedPreferences("n", Context.MODE_PRIVATE)
    fun pkgs(c: Context): List<String> =
        (prefs(c).getString("pkgs2", DEFAULT_PKGS) ?: DEFAULT_PKGS)
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun monitored(c: Context, pkg: String): Boolean =
        pkgs(c).any { if (it.endsWith("*")) pkg.startsWith(it.dropLast(1)) else pkg == it }
}

// dana | shopeepay | gopay (null kalau tidak dikenali). Dipakai server untuk mencocokkan order per metode.
fun detectMethod(pkg: String, title: String, text: String): String? {
    val s = "$title $text"
    return when {
        pkg == "com.shopee.id" -> "shopeepay"
        pkg.startsWith("com.gojek") -> "gopay"
        pkg.startsWith("id.dana") -> "dana"
        s.contains("DANA", true) -> "dana"
        s.contains("QRIS statis", true) -> "gopay"
        s.contains("Pembayaran sebesar", true) -> "shopeepay"
        else -> null
    }
}

// Hanya notifikasi PEMBAYARAN MASUK. Notif "kamu barusan bayar" (keluar) diabaikan.
fun parseAmount(title: String, text: String): Long? {
    val s = "$title $text"
    if (!Regex("(?i)diterima|masuk").containsMatchIn(s)) return null
    if (Regex("(?i)barusan bayar|berhasil bayar").containsMatchIn(s)) return null
    val m = Regex("(?i)Rp\\s?([0-9][0-9.]*)(?:,[0-9]{1,2})?").find(s) ?: return null
    return m.groupValues[1].replace(".", "").toLongOrNull()
}

class NotifService : NotificationListenerService() {
    private val seen = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (!Cfg.monitored(this, pkg)) return
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: e.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        val now = System.currentTimeMillis()
        val sig = pkg + title + text
        synchronized(seen) {
            val last = seen[sig]
            if (last != null && now - last < 15000) return
            seen[sig] = now
            seen.entries.removeAll { now - it.value > 120000 }
        }

        val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(now))
        val p = Cfg.prefs(this)
        val arr = JSONArray(p.getString("h", "[]"))
        val list = ArrayList<JSONObject>()
        list.add(JSONObject().put("t", time).put("p", pkg).put("ti", title).put("tx", text))
        for (i in 0 until minOf(arr.length(), 299)) list.add(arr.getJSONObject(i))
        p.edit().putString("h", JSONArray(list).toString()).apply()

        sendTelegram("[$pkg] $title\n$text")
        if (!pkg.startsWith("com.whatsapp")) {
            parseAmount(title, text)?.let { sendWebhook(it, detectMethod(pkg, title, text) ?: "", pkg, title, text, now) }
        }
    }

    private fun sendWebhook(amount: Long, method: String, pkg: String, title: String, text: String, ts: Long) {
        val p = Cfg.prefs(this)
        val url = p.getString("wh", "") ?: ""
        val secret = p.getString("sec", "") ?: ""
        if (url.isBlank()) return
        val body = JSONObject().put("amount", amount).put("method", method).put("source", pkg)
            .put("title", title).put("text", text).put("ts", ts).toString()
        Thread {
            for (i in 1..3) {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("X-Secret", secret)
                    c.outputStream.use { it.write(body.toByteArray()) }
                    val code = c.responseCode
                    c.disconnect()
                    if (code in 200..299) break
                } catch (_: Exception) {}
                try { Thread.sleep(3000L * i) } catch (_: Exception) {}
            }
        }.start()
    }

    private fun sendTelegram(msg: String) {
        val p = Cfg.prefs(this)
        val token = p.getString("tok", "") ?: ""
        val chat = p.getString("chat", "") ?: ""
        if (token.isBlank() || chat.isBlank()) return
        Thread {
            try {
                val c = URL("https://api.telegram.org/bot$token/sendMessage").openConnection() as HttpURLConnection
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
