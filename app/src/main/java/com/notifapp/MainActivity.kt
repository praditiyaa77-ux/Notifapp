package com.notifapp

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray

class MainActivity : Activity() {
    private val BG = Color.parseColor("#F4F6FB")
    private val INK = Color.parseColor("#12203A")
    private val MUTED = Color.parseColor("#6B7690")
    private val LINE = Color.parseColor("#E3E8F2")
    private val PRIMARY = Color.parseColor("#1F4FD8")
    private val OK = Color.parseColor("#12A150")

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var historyPage: ScrollView
    private lateinit var settingsPage: ScrollView
    private lateinit var tabHist: TextView
    private lateinit var tabSet: TextView
    private val fields = LinkedHashMap<String, EditText>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun lp(top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(Color.WHITE, 16, LINE)
    }

    private fun chip(label: String, color: Int) = tv(label, 11f, Color.WHITE, true).apply {
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = rounded(color, 20)
    }

    private fun button(label: String, color: Int, textColor: Int = Color.WHITE, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(color, 12, if (color == Color.WHITE) LINE else null)
            setOnClickListener { onClick() }
        }

    private fun walletOf(pkg: String, title: String, text: String): Pair<String, Int> {
        if (pkg.startsWith("com.whatsapp")) return Pair("WhatsApp", Color.parseColor("#25D366"))
        return when (detectMethod(pkg, title, text)) {
            "dana" -> Pair("DANA", Color.parseColor("#118EEA"))
            "shopeepay" -> Pair("ShopeePay", Color.parseColor("#EE4D2D"))
            "gopay" -> Pair("GoPay", Color.parseColor("#00AA13"))
            else -> Pair(pkg.substringAfterLast('.'), MUTED)
        }
    }

    private fun rupiah(n: Long) = "Rp " + String.format("%,d", n).replace(',', '.')

    private fun listenerOn(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun openAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.parseColor("#14307F")
        val p = Cfg.prefs(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        // ---- header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#14307F"), Color.parseColor("#1F4FD8"))
            )
        }
        header.addView(tv("NotifApp", 24f, Color.WHITE, true))
        header.addView(tv("Pemantau pembayaran QRIS", 13f, Color.parseColor("#C7D4FF")))
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.parseColor("#26FFFFFF"), 20)
            setOnClickListener { openAccess() }
        }
        statusDot = View(this).apply { background = rounded(Color.GRAY, 10) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusText = tv("", 13f, Color.WHITE, true).apply { setPadding(dp(8), 0, 0, 0) }
        statusRow.addView(statusText)
        header.addView(statusRow, lp(14, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(header, lp())

        // ---- tabs ----
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        fun tab(label: String, f: () -> Unit) = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { f() }
        }
        tabHist = tab("Riwayat") { selectTab(0) }
        tabSet = tab("Pengaturan") { selectTab(1) }
        tabs.addView(tabHist)
        tabs.addView(tabSet, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        root.addView(tabs, lp())

        // ---- halaman riwayat ----
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Refresh", Color.WHITE, INK) { render() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(button("Hapus riwayat", Color.WHITE, Color.parseColor("#D92D20")) {
            p.edit().remove("h").apply(); render()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        val histWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        histWrap.addView(actions, lp())
        val histRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        histRoot.addView(histWrap, lp())
        histRoot.addView(listBox, lp())
        historyPage = ScrollView(this).apply { addView(histRoot) }

        // ---- halaman pengaturan ----
        fun field(box: LinearLayout, label: String, key: String, def: String = "", pw: Boolean = false) {
            box.addView(tv(label, 12f, MUTED, true), lp(10))
            val e = EditText(this).apply {
                setText(p.getString(key, def))
                setTextColor(INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setSingleLine()
                if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(Color.parseColor("#F8FAFD"), 10, LINE)
            }
            fields[key] = e
            box.addView(e, lp(4))
        }
        val setBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)) }

        val c1 = card()
        c1.addView(tv("Izin notifikasi", 15f, INK, true))
        c1.addView(tv("Aktifkan NotifApp di Notification Access agar bisa membaca notifikasi.", 12f, MUTED), lp(4))
        c1.addView(button("Buka Notification Access", PRIMARY) { openAccess() }, lp(10))
        setBox.addView(c1, lp())

        val c2 = card()
        c2.addView(tv("Webhook order", 15f, INK, true))
        c2.addView(tv("Pembayaran masuk dikirim ke server order (Termux/VPS).", 12f, MUTED), lp(4))
        field(c2, "URL", "wh")
        field(c2, "Secret", "sec", pw = true)
        c2.addView(button("Buka halaman order", Color.WHITE, PRIMARY) {
            val u = Uri.parse(fields["wh"]!!.text.toString().trim())
            if (u.scheme != null && u.authority != null) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.scheme + "://" + u.authority + "/"))) } catch (_: Exception) {}
            } else Toast.makeText(this, "Isi URL webhook dulu", Toast.LENGTH_SHORT).show()
        }, lp(12))
        setBox.addView(c2, lp(12))

        val c3 = card()
        c3.addView(tv("Telegram (opsional)", 15f, INK, true))
        field(c3, "Bot token", "tok", pw = true)
        field(c3, "Chat ID", "chat")
        setBox.addView(c3, lp(12))

        val c4 = card()
        c4.addView(tv("Package dipantau", 15f, INK, true))
        c4.addView(tv("Pisah koma. Tanda * di akhir = semua package berawalan itu.", 12f, MUTED), lp(4))
        field(c4, "Daftar package", "pkgs2", Cfg.DEFAULT_PKGS)
        setBox.addView(c4, lp(12))

        setBox.addView(button("Simpan", PRIMARY) {
            val e = p.edit()
            for ((k, v) in fields) {
                var t = v.text.toString().trim()
                if (k == "pkgs2" && t.isEmpty()) t = Cfg.DEFAULT_PKGS
                e.putString(k, t)
            }
            e.apply()
            Toast.makeText(this, "Tersimpan", Toast.LENGTH_SHORT).show()
        }, lp(16))
        settingsPage = ScrollView(this).apply { addView(setBox) }

        root.addView(historyPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(settingsPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        selectTab(0)
    }

    private fun selectTab(i: Int) {
        historyPage.visibility = if (i == 0) View.VISIBLE else View.GONE
        settingsPage.visibility = if (i == 1) View.VISIBLE else View.GONE
        fun style(t: TextView, on: Boolean) {
            t.setTextColor(if (on) Color.WHITE else MUTED)
            t.background = rounded(if (on) PRIMARY else Color.TRANSPARENT, 20)
        }
        style(tabHist, i == 0)
        style(tabSet, i == 1)
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val on = listenerOn()
        statusText.text = if (on) "Listener aktif" else "Belum aktif, ketuk untuk mengaktifkan"
        (statusDot.background as GradientDrawable).setColor(
            if (on) Color.parseColor("#4ADE80") else Color.parseColor("#F87171")
        )
        listBox.removeAllViews()
        val arr = JSONArray(Cfg.prefs(this).getString("h", "[]"))
        if (arr.length() == 0) {
            listBox.addView(tv("Belum ada notifikasi.", 14f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            }, lp())
            return
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pkg = o.optString("p", o.optString("a", "?"))
            val title = o.optString("ti")
            val text = o.optString("tx")
            val (label, color) = walletOf(pkg, title, text)
            val amount = if (pkg.startsWith("com.whatsapp")) null else parseAmount(title, text)

            val c = card()
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(chip(label, color))
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(tv(o.optString("t"), 11f, MUTED))
            c.addView(top, lp())
            if (amount != null) c.addView(tv("+ " + rupiah(amount), 18f, OK, true), lp(8))
            if (title.isNotEmpty()) c.addView(tv(title, 14f, INK, true), lp(if (amount != null) 2 else 8))
            if (text.isNotEmpty()) c.addView(tv(text, 13f, MUTED), lp(2))
            c.addView(tv(pkg, 10f, Color.parseColor("#A3ACBF")), lp(6))
            listBox.addView(c, lp(if (i == 0) 0 else 10))
        }
    }
}
