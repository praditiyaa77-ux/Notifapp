package com.notifapp

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray

class MainActivity : Activity() {
    private val BG = Color.WHITE
    private val INK = Color.parseColor("#0E1B2C")
    private val MUTED = Color.parseColor("#667085")
    private val LINE = Color.parseColor("#E6E9EE")
    private val PRIMARY = Color.parseColor("#0E1B2C")
    private val OK = Color.parseColor("#0A7D55")
    private val AMBER = Color.parseColor("#B26A00")
    private val RED = Color.parseColor("#B42318")

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var historyPage: ScrollView
    private lateinit var settingsPage: ScrollView
    private lateinit var tabHist: TextView
    private lateinit var tabSet: TextView
    private lateinit var indHist: View
    private lateinit var indSet: View
    private val fields = LinkedHashMap<String, EditText>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastSig = ""
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 3000)
        }
    }

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
        fontFeatureSettings = "tnum"
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun lp(top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(16))
        addView(View(this@MainActivity).apply { setBackgroundColor(LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(16) })
    }

    private fun wallet(label: String, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(View(this@MainActivity).apply { background = rounded(color, 2) }, LinearLayout.LayoutParams(dp(8), dp(8)))
        addView(tv(label, 13f, INK, true).apply { setPadding(dp(8), 0, 0, 0) })
    }

    private fun status(label: String, color: Int) = tv("\u25CF  $label", 13f, color, true)

    private fun button(label: String, color: Int, textColor: Int = Color.WHITE, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            transformationMethod = null
            stateListAnimator = null
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(color, 6, if (color == Color.WHITE) LINE else null)
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

    private fun listenerOn(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun openAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    private fun copy(t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("invoice", t))
        Toast.makeText(this, "Disalin: $t", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val p = Cfg.prefs(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        // ---- header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(14))
        }
        header.addView(tv("NotifApp", 20f, INK, true))
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { openAccess() }
        }
        statusDot = View(this).apply { background = rounded(Color.GRAY, 10) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)))
        statusText = tv("", 13f, MUTED).apply { setPadding(dp(8), 0, 0, 0) }
        statusRow.addView(statusText)
        header.addView(statusRow, lp(6, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(header, lp())

        // ---- tabs ----
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        fun tab(label: String, f: () -> Unit, bind: (TextView, View) -> Unit): LinearLayout {
            val t = tv(label, 14f, MUTED, true).apply { setPadding(0, dp(10), 0, dp(10)) }
            val ind = View(this)
            bind(t, ind)
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setOnClickListener { f() }
                addView(t)
                addView(ind, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
            }
        }
        tabs.addView(tab("Riwayat", { selectTab(0) }) { t, v -> tabHist = t; indHist = v })
        tabs.addView(tab("Pengaturan", { selectTab(1) }) { t, v -> tabSet = t; indSet = v },
            lp(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(24) })
        root.addView(tabs, lp())
        root.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        // ---- halaman riwayat ----
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(24))
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Refresh", Color.WHITE, INK) { lastSig = ""; render() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(button("Hapus riwayat", Color.WHITE, RED) {
            p.edit().remove("h").apply(); lastSig = ""; render()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        val histWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0) }
        histWrap.addView(actions, lp())
        val histRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        histRoot.addView(histWrap, lp())
        histRoot.addView(listBox, lp())
        historyPage = ScrollView(this).apply { addView(histRoot) }

        // ---- halaman pengaturan ----
        fun field(box: LinearLayout, label: String, key: String, pw: Boolean = false) {
            box.addView(tv(label, 12f, MUTED), lp(12))
            val e = EditText(this).apply {
                setText(p.getString(key, ""))
                setTextColor(INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setSingleLine()
                if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(Color.WHITE, 6, LINE)
            }
            fields[key] = e
            box.addView(e, lp(4))
        }
        val setBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(32)) }

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
        setBox.addView(c2, lp())

        val c3 = card()
        c3.addView(tv("Telegram (opsional)", 15f, INK, true))
        field(c3, "Bot token", "tok", pw = true)
        field(c3, "Chat ID", "chat")
        setBox.addView(c3, lp())

        val c4 = card()
        c4.addView(tv("Package tambahan", 15f, INK, true))
        c4.addView(tv("Bawaan sudah mencakup WhatsApp, Gojek/GoPay, Shopee/ShopeePay, dan DANA. Tambahkan di sini (pisah koma, * di akhir = semua berawalan itu) kalau ada notifikasi yang belum terbaca. Package asli terlihat di riwayat.", 12f, MUTED), lp(4))
        field(c4, "Package", "pkgsx")
        setBox.addView(c4, lp())

        setBox.addView(button("Simpan", PRIMARY) {
            val e = p.edit()
            for ((k, v) in fields) e.putString(k, v.text.toString().trim())
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
        tabHist.setTextColor(if (i == 0) INK else MUTED)
        tabSet.setTextColor(if (i == 1) INK else MUTED)
        indHist.setBackgroundColor(if (i == 0) INK else Color.TRANSPARENT)
        indSet.setBackgroundColor(if (i == 1) INK else Color.TRANSPARENT)
    }

    override fun onResume() {
        super.onResume()
        lastSig = ""
        ticker.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun render() {
        val on = listenerOn()
        val raw = Cfg.prefs(this).getString("h", "[]") ?: "[]"
        val sig = raw + on
        if (sig == lastSig) return
        lastSig = sig

        statusText.text = if (on) "Listener aktif" else "Belum aktif, ketuk untuk mengaktifkan"
        (statusDot.background as GradientDrawable).setColor(
            if (on) OK else RED
        )
        statusText.setTextColor(if (on) MUTED else RED)
        listBox.removeAllViews()
        val arr = JSONArray(raw)
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
            val st = o.optString("st")
            val inv = o.optString("inv")
            val info = o.optString("info")
            val (label, color) = walletOf(pkg, title, text)
            val amount: Long? =
                if (o.has("am")) o.optLong("am")
                else if (pkg.startsWith("com.whatsapp")) null
                else parseAmount(title, text)
            val stChip: Pair<String, Int>? = when (st) {
                "paid" -> Pair("Lunas", OK)
                "nomatch" -> Pair("Tidak cocok", AMBER)
                "fail" -> Pair("Gagal kirim", RED)
                "sending" -> Pair("Mengirim", MUTED)
                "unmon" -> Pair("Belum dipantau", AMBER)
                else -> null
            }

            val c = card()
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(wallet(label, color))
            top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(tv(o.optString("t"), 12f, MUTED))
            c.addView(top, lp())
            if (amount != null) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                line.addView(tv(rupiah(amount), 24f, if (st == "paid") OK else INK, true))
                line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                if (stChip != null) line.addView(status(stChip.first, stChip.second))
                c.addView(line, lp(6))
            } else if (stChip != null) {
                c.addView(status(stChip.first, stChip.second), lp(6))
            }
            if (inv.isNotEmpty()) {
                c.addView(tv("Invoice  $inv", 13f, INK, true).apply { setOnClickListener { copy(inv) } }, lp(4))
            }
            if (info.isNotEmpty()) c.addView(tv(info, 12f, MUTED), lp(2))
            if (title.isNotEmpty()) c.addView(tv(title, 14f, INK, true), lp(8))
            if (text.isNotEmpty()) c.addView(tv(text, 13f, MUTED), lp(2))
            c.addView(tv(pkg, 11f, Color.parseColor("#98A2B3")), lp(6))
            if (st == "unmon") {
                c.addView(button("Pantau package ini", PRIMARY) {
                    Cfg.addPackage(this@MainActivity, pkg)
                    Toast.makeText(this@MainActivity, "Dipantau: $pkg. Bayar lagi untuk mencoba.", Toast.LENGTH_LONG).show()
                }, lp(10))
            }
            listBox.addView(c, lp())
        }
    }
}
