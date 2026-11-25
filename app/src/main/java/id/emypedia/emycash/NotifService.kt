package id.emypedia.emycash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
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
    // Tanda * di akhir = cocok semua package berawalan itu. Package tambahan (pref "pkgsx") diisi dari app.
    const val DEFAULT_PKGS =
        "com.gojek*,com.gopay*,com.shopee*,com.shopeepay*,id.dana*,com.dana*"
    const val DEFAULT_SERVER = "https://gateway.emypedia.my.id"
    const val CHANNEL = "pay"
    private val lock = Any()

    fun prefs(c: Context) = c.getSharedPreferences("n", Context.MODE_PRIVATE)

    // WhatsApp hanya dipantau kalau pengguna mengaktifkannya sendiri (privasi); pesan chat tidak pernah dihitung pembayaran.
    fun pkgs(c: Context): List<String> {
        val wa = if (prefs(c).getBoolean("wa", false)) ",com.whatsapp,com.whatsapp.w4b" else ""
        return (DEFAULT_PKGS + wa + "," + (prefs(c).getString("pkgsx", "") ?: ""))
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    // Kabari server bahwa perangkat ini aktif (dashboard menampilkan "terakhir aktif" dan status listener)
    fun heartbeat(c: Context) {
        val p = prefs(c)
        val base = Api.base(p.getString("wh", "") ?: "") ?: return
        val key = p.getString("sec", "") ?: ""
        if (key.isBlank()) return
        val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        val body = JSONObject().put("listener", NotifService.connected).put("version", ver)
        Thread { Api.call("POST", "$base/app/heartbeat", key, body) }.start()
    }

    // Mode luas (bawaan aktif): package yang belum dikenal tetap dipantau kalau notifikasinya jelas
    // berupa pembayaran masuk (bukan pesan chat). Package tersebut otomatis dicatat ke daftar.
    fun wide(c: Context) = prefs(c).getBoolean("wide", true)

    // Aplikasi chat/pesan tidak pernah dipantau lewat mode luas
    fun isChatPkg(pkg: String) = pkg.startsWith("com.whatsapp") || pkg.startsWith("org.telegram") ||
        pkg.startsWith("com.google.android.apps.messaging") || pkg.startsWith("com.android.mms") ||
        pkg.startsWith("com.facebook") || pkg.startsWith("com.instagram") || pkg == "jp.naver.line.android"

    // Paksa Android menyambung ulang listener (dipakai kalau izin aktif tapi listener tidak tersambung)
    fun rebind(c: Context) {
        try {
            val cn = ComponentName(c, NotifService::class.java)
            val pm = c.packageManager
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            if (Build.VERSION.SDK_INT >= 24) NotificationListenerService.requestRebind(cn)
        } catch (_: Exception) {}
    }

    // Alamat webhook: kalau pengguna hanya mengisi alamat server, tambahkan /notif
    fun hookUrl(raw: String): String {
        val u = Api.normalize(raw)
        if (u.isEmpty()) return u
        val noQuery = u.substringBefore("?")
        val afterScheme = noQuery.substringAfter("://", noQuery)
        val path = if (afterScheme.contains("/")) afterScheme.substringAfter("/") else ""
        return if (path.isEmpty()) noQuery.trimEnd('/') + "/notif" else u
    }

    fun monitored(c: Context, pkg: String): Boolean =
        pkgs(c).any { if (it.endsWith("*")) pkg.startsWith(it.dropLast(1)) else pkg == it }

    fun addPackage(c: Context, pkg: String) {
        val p = prefs(c)
        val cur = p.getString("pkgsx", "") ?: ""
        if (cur.split(",").map { it.trim() }.contains(pkg)) return
        p.edit().putString("pkgsx", if (cur.isBlank()) pkg else "$cur,$pkg").apply()
    }

    fun addEntry(c: Context, o: JSONObject) = synchronized(lock) {
        val p = prefs(c)
        val arr = JSONArray(p.getString("h", "[]"))
        val list = ArrayList<JSONObject>()
        list.add(o)
        for (i in 0 until minOf(arr.length(), 299)) list.add(arr.getJSONObject(i))
        p.edit().putString("h", JSONArray(list).toString()).apply()
    }

    fun updateEntry(c: Context, id: Long, status: String, inv: String, info: String) = synchronized(lock) {
        val p = prefs(c)
        val arr = JSONArray(p.getString("h", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optLong("i") == id) {
                o.put("st", status).put("inv", inv).put("info", info)
                break
            }
        }
        p.edit().putString("h", arr.toString()).apply()
    }
}

// Pola notifikasi pembayaran masuk yang dikenal (ShopeePay, GoPay Merchant, DANA Bisnis)
val PAY_HINT = Regex(
    "(?i)pembayaran sebesar\\s*rp|pembayaran\\s+(qris\\s+)?[a-z ]*diterima|pembayaran\\s+masuk|diterima\\s+dana\\s+bisnis|" +
        "saldo\\s+masuk|uang\\s+masuk|menerima\\s+pembayaran|kamu\\s+menerima|dana\\s+masuk|qris[a-z ]*diterima"
)

// dana | shopeepay | gopay (null kalau tidak dikenali). Dipakai server untuk mencocokkan order per metode.
fun detectMethod(pkg: String, title: String, text: String): String? {
    val s = "$title $text"
    return when {
        pkg.startsWith("com.shopee") -> "shopeepay"
        pkg.startsWith("com.gojek") || pkg.startsWith("com.gopay") -> "gopay"
        pkg.startsWith("id.dana") || pkg.startsWith("com.dana") -> "dana"
        s.contains("DANA", true) -> "dana"
        s.contains("ShopeePay", true) || s.contains("Shopee", true) -> "shopeepay"
        s.contains("GoPay", true) || s.contains("Gojek", true) -> "gopay"
        s.contains("QRIS statis", true) || s.contains("QRIS dinamis", true) -> "gopay"
        s.contains("Pembayaran sebesar", true) -> "shopeepay"
        else -> null
    }
}

fun methodName(m: String?): String = when (m) {
    "dana" -> "DANA"
    "shopeepay" -> "ShopeePay"
    "gopay" -> "GoPay"
    else -> "Pembayaran"
}

fun rupiah(n: Long) = "Rp " + String.format("%,d", n).replace(',', '.')

// Hanya notifikasi PEMBAYARAN MASUK. Notif "kamu barusan bayar" (keluar) diabaikan.
fun parseAmount(title: String, text: String): Long? {
    val s = "$title $text"
    if (!Regex("(?i)diterima|masuk|menerima").containsMatchIn(s)) return null
    if (Regex("(?i)barusan bayar|berhasil bayar").containsMatchIn(s)) return null
    // Ambil nominal Rp pertama yang bukan "saldo ..." (supaya sisa saldo tidak terbaca sebagai pembayaran)
    for (m in Regex("(?i)Rp\\.?\\s?([0-9][0-9.]*)(?:,[0-9]{1,2})?").findAll(s)) {
        val before = s.substring(maxOf(0, m.range.first - 14), m.range.first)
        if (Regex("(?i)saldo|bonus|cashback|poin").containsMatchIn(before)) continue
        val v = m.groupValues[1].trimEnd('.').replace(".", "").toLongOrNull() ?: continue
        if (v > 0) return v
    }
    return null
}

// Package Shopee juga mengirim notifikasi non-pembayaran, jadi wajib cocok pola ShopeePay.
fun isPayment(pkg: String, title: String, text: String): Boolean {
    if (parseAmount(title, text) == null) return false
    if (pkg.startsWith("com.shopee")) return PAY_HINT.containsMatchIn("$title $text")
    return true
}

class NotifService : NotificationListenerService() {
    companion object {
        @Volatile var connected = false
    }

    private val seen = HashMap<String, Long>()

    override fun onListenerConnected() {
        connected = true
        Cfg.heartbeat(applicationContext)
    }

    override fun onListenerDisconnected() {
        connected = false
        if (Build.VERSION.SDK_INT >= 24) {
            try { NotificationListenerService.requestRebind(ComponentName(this, NotifService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val n = sbn.notification
        val e = n.extras
        val title = (e.getCharSequence(Notification.EXTRA_TITLE)
            ?: e.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString() ?: ""
        var text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (text.isEmpty()) {
            text = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")
                ?: n.tickerText?.toString() ?: ""
        }
        if (title.isEmpty() && text.isEmpty()) return

        // Pesan chat tidak pernah dianggap pembayaran (mencegah pemalsuan lewat pesan)
        val isMsg = n.category == Notification.CATEGORY_MESSAGE ||
            (e.getString(Notification.EXTRA_TEMPLATE) ?: "").contains("MessagingStyle")

        val now = System.currentTimeMillis()
        val sig = pkg + title + text
        synchronized(seen) {
            val last = seen[sig]
            if (last != null && now - last < 15000) return
            seen[sig] = now
            seen.entries.removeAll { now - it.value > 120000 }
        }

        val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(now))
        val entry = JSONObject().put("i", now).put("t", time).put("p", pkg).put("ti", title).put("tx", text)

        // Mode luas: package belum dikenal tetapi jelas pembayaran masuk -> ikut dipantau dan diingat
        var watched = Cfg.monitored(this, pkg)
        if (!watched && !isMsg && Cfg.wide(this) && !Cfg.isChatPkg(pkg) &&
            PAY_HINT.containsMatchIn("$title $text") && parseAmount(title, text) != null) {
            Cfg.addPackage(this, pkg)
            watched = true
        }

        if (!watched) {
            // Package belum dipantau: hanya dicatat sebagai petunjuk kalau mirip notifikasi pembayaran,
            // TIDAK dikirim ke server. Pengguna memilih sendiri "Pantau package ini" di app.
            val amt = parseAmount(title, text)
            if (!isMsg && amt != null && PAY_HINT.containsMatchIn("$title $text")) {
                Cfg.addEntry(this, entry.put("am", amt).put("st", "unmon"))
                postNotif("Notifikasi pembayaran belum dipantau", "$pkg · buka Emy Cash lalu tekan \"Pantau package ini\"")
            }
            return
        }

        val amount: Long? =
            if (isMsg || pkg.startsWith("com.whatsapp") || !isPayment(pkg, title, text)) null
            else parseAmount(title, text)
        val method = if (amount != null) (detectMethod(pkg, title, text) ?: "") else ""
        val hook = (Cfg.prefs(this).getString("wh", "") ?: "").isNotBlank()
        if (amount != null) entry.put("am", amount).put("st", if (hook) "sending" else "local")
        Cfg.addEntry(this, entry)

        sendTelegram("[$pkg] $title\n$text")
        if (amount != null) {
            if (hook) sendWebhook(now, amount, method, pkg, title, text)
            else postNotif("Kamu menerima saldo ${rupiah(amount)}", methodName(method))
        }
    }

    private fun sendWebhook(id: Long, amount: Long, method: String, pkg: String, title: String, text: String) {
        val ctx = applicationContext
        val p = Cfg.prefs(this)
        val url = Cfg.hookUrl(p.getString("wh", "") ?: "")
        val secret = p.getString("sec", "") ?: ""
        val body = JSONObject().put("amount", amount).put("method", method).put("source", pkg)
            .put("title", title).put("text", text).put("ts", id).toString()
        Thread {
            var status = "fail"
            var inv = ""
            var info = "tidak terhubung"
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
                    if (code in 200..299) {
                        val resp = c.inputStream.bufferedReader().use { it.readText() }
                        c.disconnect()
                        val j = try { JSONObject(resp) } catch (_: Exception) { JSONObject() }
                        if (j.optBoolean("matched")) {
                            status = "paid"; inv = j.optString("invoice"); info = ""
                        } else {
                            status = "nomatch"; info = ""
                        }
                        break
                    }
                    c.disconnect()
                    info = when (code) {
                        401 -> "Kunci salah atau dicabut (HTTP 401). Masuk lagi di Emy Cash"
                        402 -> "Langganan tidak aktif (HTTP 402). Perpanjang di dashboard"
                        404 -> "Alamat server salah atau gateway belum diperbarui (HTTP 404)"
                        else -> "HTTP $code"
                    }
                    if (code in 400..499) break
                } catch (e: Exception) {
                    info = "Server tidak terjangkau (${e.javaClass.simpleName})"
                }
                try { Thread.sleep(3000L * i) } catch (_: Exception) {}
            }
            Cfg.updateEntry(ctx, id, status, inv, info)
            val name = methodName(method)
            when (status) {
                "paid" -> postNotif("Kamu menerima saldo ${rupiah(amount)}", "$name · $inv · LUNAS")
                "nomatch" -> postNotif("Saldo masuk ${rupiah(amount)}, tidak cocok order", "$name · cek manual di Emy Cash")
                else -> postNotif("Saldo masuk ${rupiah(amount)}, gagal kirim ke server", "$name · $info")
            }
        }.start()
    }

    private fun postNotif(title: String, body: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(Cfg.CHANNEL, "Pembayaran masuk", NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val nt = Notification.Builder(this, Cfg.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % 1000000000L).toInt(), nt)
        } catch (_: Exception) {}
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
