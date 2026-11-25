package com.notifapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import org.json.JSONArray

class MainActivity : Activity() {
    private lateinit var hist: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = Cfg.prefs(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        fun edit(h: String, k: String, def: String = "", pw: Boolean = false) = EditText(this).apply {
            hint = h; setText(p.getString(k, def))
            if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }

        val tok = edit("Telegram bot token (opsional)", "tok", pw = true)
        val chat = edit("Telegram chat ID (opsional)", "chat")
        val wh = edit("Webhook URL, mis. http://127.0.0.1:3000/notif", "wh")
        val sec = edit("Webhook secret", "sec", pw = true)
        val pk = edit("Package dipantau (pisah koma)", "pkgs", Cfg.DEFAULT_PKGS)
        hist = TextView(this)

        root.addView(btn("Buka Notification Access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        listOf(tok, chat, wh, sec, pk).forEach { root.addView(it) }
        root.addView(btn("Simpan") {
            p.edit().putString("tok", tok.text.toString().trim())
                .putString("chat", chat.text.toString().trim())
                .putString("wh", wh.text.toString().trim())
                .putString("sec", sec.text.toString().trim())
                .putString("pkgs", pk.text.toString().trim().ifEmpty { Cfg.DEFAULT_PKGS })
                .apply()
            Toast.makeText(this, "Tersimpan", Toast.LENGTH_SHORT).show()
        })
        root.addView(btn("Refresh riwayat") { render() })
        root.addView(btn("Hapus riwayat") { p.edit().remove("h").apply(); render() })
        root.addView(hist)
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val arr = JSONArray(Cfg.prefs(this).getString("h", "[]"))
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val src = o.optString("p", o.optString("a", "?"))
            sb.append("${o.getString("t")} • $src\n${o.getString("ti")}\n${o.getString("tx")}\n\n")
        }
        hist.text = if (sb.isEmpty()) "Belum ada notifikasi." else sb.toString()
    }
}
