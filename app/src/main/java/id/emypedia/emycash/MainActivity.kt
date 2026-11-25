package id.emypedia.emycash

import android.Manifest
import android.animation.ObjectAnimator
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

class MainActivity : Activity() {
    private val BG = Color.WHITE
    private val SURFACE = Color.parseColor("#F5F7FA")
    private val INK = Color.parseColor("#0E1B2C")
    private val MUTED = Color.parseColor("#667085")
    private val FAINT = Color.parseColor("#98A2B3")
    private val LINE = Color.parseColor("#E6E9EE")
    private val OK = Color.parseColor("#0A7D55")
    private val AMBER = Color.parseColor("#B26A00")
    private val RED = Color.parseColor("#B42318")

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var todayText: TextView
    private lateinit var todaySub: TextView
    private lateinit var historyPage: ScrollView
    private lateinit var settingsPage: ScrollView
    private lateinit var navHist: TextView
    private lateinit var navSet: TextView
    private lateinit var navIndicator: View
    private lateinit var navBar: FrameLayout
    private lateinit var testResult: TextView
    private val fields = LinkedHashMap<String, EditText>()
    private val handler = Handler(Looper.getMainLooper())
    private val rendered = HashSet<Long>()
    private var firstRender = true
    private var lastSig = ""
    private var tabIdx = 0
    private lateinit var acctBox: LinearLayout
    private lateinit var acctLine: TextView
    private lateinit var updateLine: TextView
    private var lastRefresh = 0L
    private var busy = false
    private val ticker = object : Runnable {
        override fun run() {
            render()
            val t = System.currentTimeMillis()
            if (t - lastRefresh > 60000) { lastRefresh = t; refreshStatus(false) }
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
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun lp(top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT) =
        LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun divider() = View(this).apply { setBackgroundColor(LINE) }
    private fun divLp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    // Efek tekan halus untuk tombol/baris
    private fun View.pressable() {
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f).alpha(0.85f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).start()
            }
            false
        }
    }

    private fun fadeIn(v: View, delay: Long = 0) {
        v.alpha = 0f
        v.translationY = dp(8).toFloat()
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(240)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun wallet(label: String, color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(View(this@MainActivity).apply { background = rounded(color, 2) }, LinearLayout.LayoutParams(dp(8), dp(8)))
        addView(tv(label, 13f, INK, true).apply { setPadding(dp(8), 0, 0, 0) })
    }

    private fun status(label: String, color: Int, pulse: Boolean = false) = tv("\u25CF  $label", 13f, color, true).apply {
        if (pulse) ObjectAnimator.ofFloat(this, "alpha", 1f, 0.35f).apply {
            duration = 800; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE; start()
        }
    }

    private fun button(label: String, filled: Boolean, textColor: Int = if (filled) Color.WHITE else INK, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            transformationMethod = null
            stateListAnimator = null
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = if (filled) rounded(INK, 6) else rounded(BG, 6, LINE)
            pressable()
            setOnClickListener { onClick() }
        }

    private fun walletOf(pkg: String, title: String, text: String): Pair<String, Int> {
        if (pkg.startsWith("com.whatsapp")) return Pair("WhatsApp", Color.parseColor("#25D366"))
        return when (detectMethod(pkg, title, text)) {
            "dana" -> Pair("DANA", Color.parseColor("#118EEA"))
            "shopeepay" -> Pair("ShopeePay", Color.parseColor("#EE4D2D"))
            "gopay" -> Pair("GoPay", Color.parseColor("#00AA13"))
            else -> Pair(pkg.substringAfterLast('.'), FAINT)
        }
    }

    private fun listenerOn(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun openAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun copy(t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("invoice", t))
        toast("Disalin: $t")
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val p = Cfg.prefs(this)
        tabIdx = b?.getInt("tab", 0) ?: 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }

        // ---- header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        header.addView(tv("Emy Cash", 20f, INK, true))
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener {
                if (!listenerOn()) openAccess() else { Cfg.rebind(this@MainActivity); toast("Menyambungkan ulang…") }
            }
        }
        statusDot = View(this).apply { background = rounded(Color.GRAY, 10) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)))
        statusText = tv("", 13f, MUTED).apply { setPadding(dp(8), 0, 0, 0) }
        statusRow.addView(statusText)
        header.addView(statusRow, lp(4, ViewGroup.LayoutParams.WRAP_CONTENT))
        acctLine = tv("", 12f, MUTED)
        header.addView(acctLine, lp(2))
        updateLine = tv("", 12f, AMBER, true).apply { visibility = View.GONE }
        header.addView(updateLine, lp(6))
        root.addView(header, lp())
        root.addView(divider(), divLp())

        // ---- halaman riwayat ----
        todayText = tv("Rp 0", 28f, INK, true)
        todaySub = tv("Belum ada pembayaran lunas hari ini", 13f, MUTED)
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            addView(tv("Lunas hari ini", 13f, MUTED))
            addView(todayText, lp(6))
            addView(todaySub, lp(4))
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val histActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
            addView(button("Hapus riwayat", false, RED) {
                p.edit().remove("h").apply(); rendered.clear(); firstRender = true; lastSig = ""; render()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val histRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(summary, lp())
            addView(divider(), divLp())
            addView(listBox, lp())
            addView(histActions, lp())
        }
        historyPage = ScrollView(this).apply { addView(histRoot); isVerticalScrollBarEnabled = false }

        // ---- halaman pengaturan ----
        fun field(box: LinearLayout, label: String, key: String, pw: Boolean = false, ph: String = "") {
            box.addView(tv(label, 12f, MUTED), lp(14))
            val e = EditText(this).apply {
                setText(p.getString(key, ""))
                hint = ph
                setHintTextColor(FAINT)
                setTextColor(INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setSingleLine()
                inputType = if (pw) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(BG, 6, LINE)
                setOnFocusChangeListener { v, f -> (v.background as GradientDrawable).setStroke(dp(1), if (f) INK else LINE) }
            }
            fields[key] = e
            box.addView(e, lp(6))
        }
        fun section(title: String, desc: String? = null): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(tv(title, 15f, INK, true))
            if (desc != null) addView(tv(desc, 13f, MUTED).apply { setLineSpacing(0f, 1.15f) }, lp(4))
        }

        val setBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val s0 = section("Akun Emy Cash")
        acctBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        s0.addView(acctBox, lp(4))
        setBox.addView(s0, lp())
        setBox.addView(divider(), divLp())

        val s1 = section("Izin notifikasi", "Emy Cash perlu Notification Access untuk membaca notifikasi pembayaran. Kalau status di atas bukan hijau, sambungkan ulang.")
        s1.addView(button("Buka Notification Access", true) { openAccess() }, lp(12))
        s1.addView(button("Sambungkan ulang listener", false) { Cfg.rebind(this); toast("Menyambungkan ulang…"); lastSig = ""; handler.postDelayed({ render() }, 800) }, lp(8))
        s1.addView(button("Izinkan berjalan di latar belakang", false) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { toast("Tidak tersedia di perangkat ini") }
        }, lp(8))
        setBox.addView(s1, lp())
        setBox.addView(divider(), divLp())

        val s2 = section("Server (mode manual)", "Terisi otomatis saat Anda masuk dengan akun. Ubah hanya untuk server sendiri atau mode pemilik dengan Secret. Cukup isi alamat server, /notif ditambahkan otomatis.")
        field(s2, "Alamat server", "wh", ph = Cfg.DEFAULT_SERVER)
        field(s2, "Kunci (Secret)", "sec", pw = true)
        testResult = tv("", 13f, MUTED)
        s2.addView(button("Tes koneksi", false) { testServer() }, lp(14))
        s2.addView(testResult, lp(8))
        s2.addView(button("Buka halaman order", false) {
            val u = Uri.parse(fields["wh"]!!.text.toString().trim())
            if (u.scheme != null && u.authority != null) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.scheme + "://" + u.authority + "/"))) } catch (_: Exception) {}
            } else toast("Isi alamat server dulu")
        }, lp(8))
        setBox.addView(s2, lp())
        setBox.addView(divider(), divLp())

        val s3 = section("Deteksi pembayaran")
        val wideRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val wideTxt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Pantau semua notifikasi pembayaran", 14f, INK, true))
            addView(tv("Aplikasi yang belum ada di daftar tetap dibaca kalau notifikasinya jelas pembayaran masuk. Chat tidak pernah dibaca sebagai pembayaran.", 12f, MUTED).apply { setLineSpacing(0f, 1.15f) }, lp(2))
        }
        wideRow.addView(wideTxt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        wideRow.addView(Switch(this).apply {
            isChecked = Cfg.wide(this@MainActivity)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("wide", on).apply() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        s3.addView(wideRow, lp(12))
        s3.addView(tv("Package tambahan (pisah koma, * di akhir = semua berawalan itu). Package yang terdeteksi otomatis ikut tercatat di sini.", 12f, MUTED).apply { setLineSpacing(0f, 1.15f) }, lp(16))
        field(s3, "Package", "pkgsx")
        setBox.addView(s3, lp())
        setBox.addView(divider(), divLp())

        val s4 = section("Telegram (opsional)")
        field(s4, "Bot token", "tok", pw = true)
        field(s4, "Chat ID", "chat")
        val waRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        waRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Teruskan notifikasi WhatsApp", 14f, INK, true))
            addView(tv("Nonaktif secara bawaan demi privasi. Pesan chat tidak pernah dihitung sebagai pembayaran.", 12f, MUTED).apply { setLineSpacing(0f, 1.15f) }, lp(2))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        waRow.addView(Switch(this).apply {
            isChecked = p.getBoolean("wa", false)
            setOnCheckedChangeListener { _, on -> p.edit().putBoolean("wa", on).apply() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        s4.addView(waRow, lp(16))
        setBox.addView(s4, lp())
        setBox.addView(divider(), divLp())

        val s5 = section("Tentang")
        s5.addView(tv("Emy Cash ${versionInfo().first}", 13f, MUTED), lp(6))
        s5.addView(button("Cek pembaruan", false) { toast("Memeriksa…"); refreshStatus(true) }, lp(12))
        s5.addView(button("Buka situs Emy Cash", false) { openUrl(serverBase() + "/") }, lp(8))
        setBox.addView(s5, lp())

        val saveWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(28)) }
        saveWrap.addView(button("Simpan pengaturan", true) {
            val e = p.edit()
            for ((k, v) in fields) {
                var t = v.text.toString().trim()
                if (k == "wh" && t.isNotEmpty()) { t = Cfg.hookUrl(t); v.setText(t) }
                e.putString(k, t)
            }
            e.apply()
            toast("Tersimpan")
        }, lp())
        setBox.addView(saveWrap, lp())
        settingsPage = ScrollView(this).apply { addView(setBox); isVerticalScrollBarEnabled = false }

        // ---- konten + navigasi bawah ----
        val content = FrameLayout(this)
        content.addView(historyPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(settingsPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(divider(), divLp())

        navBar = FrameLayout(this)
        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun navItem(label: String, idx: Int): TextView = tv(label, 14f, MUTED, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { selectTab(idx, true) }
        }
        navHist = navItem("Riwayat", 0)
        navSet = navItem("Pengaturan", 1)
        navRow.addView(navHist, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navRow.addView(navSet, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navIndicator = View(this).apply { setBackgroundColor(INK) }
        navBar.addView(navRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        navBar.addView(navIndicator, FrameLayout.LayoutParams(0, dp(2)))
        root.addView(navBar, lp())

        setContentView(root)
        renderAccount()
        renderAcctLine()
        navBar.post {
            navIndicator.layoutParams = (navIndicator.layoutParams as FrameLayout.LayoutParams).apply { width = navBar.width / 2 }
            selectTab(tabIdx, false)
        }
    }

    override fun onSaveInstanceState(o: Bundle) {
        super.onSaveInstanceState(o)
        o.putInt("tab", tabIdx)
    }

    private fun selectTab(i: Int, animate: Boolean) {
        tabIdx = i
        val show = if (i == 0) historyPage else settingsPage
        val hide = if (i == 0) settingsPage else historyPage
        show.animate().cancel(); hide.animate().cancel()
        if (animate) {
            hide.animate().alpha(0f).setDuration(120).withEndAction {
                if (tabIdx == i) { hide.visibility = View.GONE; hide.alpha = 1f }
            }.start()
            show.alpha = 0f
            show.translationY = dp(10).toFloat()
            show.visibility = View.VISIBLE
            show.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            navIndicator.animate().translationX((i * navBar.width / 2).toFloat()).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            show.visibility = View.VISIBLE; show.alpha = 1f; show.translationY = 0f
            hide.visibility = View.GONE; hide.alpha = 1f
            navIndicator.translationX = (i * navBar.width / 2).toFloat()
        }
        navHist.setTextColor(if (i == 0) INK else MUTED)
        navSet.setTextColor(if (i == 1) INK else MUTED)
    }

    private fun testServer() {
        val base = Api.base(fields["wh"]!!.text.toString())
        val sec = fields["sec"]!!.text.toString().trim()
        if (base == null) {
            testResult.setTextColor(RED); testResult.text = "Isi alamat server dulu, mis. ${Cfg.DEFAULT_SERVER}"; return
        }
        testResult.setTextColor(MUTED); testResult.text = "Menguji…"
        Thread {
            val r = Api.call("GET", "$base/app/status", sec)
            val ok = r.ok
            val msg = when {
                r.ok -> "Server tersambung" + (r.json().optString("email").takeIf { it.isNotEmpty() }?.let { ", akun $it" } ?: "")
                r.code == 401 -> "Server terjangkau, tetapi kunci salah atau sudah dicabut"
                r.code == 404 -> "Server terjangkau, tetapi belum mendukung Emy Cash (HTTP 404). Perbarui gateway ke versi terbaru."
                else -> Api.message(r)
            }
            runOnUiThread {
                testResult.setTextColor(if (ok) OK else RED)
                testResult.text = msg
            }
        }.start()
    }

    // ---------- akun Emy Cash ----------
    private fun versionInfo(): Pair<String, Int> = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        Pair(pi.versionName ?: "", pi.versionCode)
    } catch (_: Exception) { Pair("", 0) }

    private fun serverBase(): String =
        Api.base(fields["wh"]?.text?.toString() ?: Cfg.prefs(this).getString("wh", "") ?: "") ?: Cfg.DEFAULT_SERVER

    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) { toast("Tidak bisa membuka tautan") }
    }

    private fun acct(): JSONObject =
        try { JSONObject(Cfg.prefs(this).getString("acct", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun fmtDate(t: Long): String = SimpleDateFormat("d MMM yyyy", Locale("id", "ID")).format(Date(t))

    private fun accountSummary(a: JSONObject): String {
        if (a.length() == 0) return "Memuat status…"
        val sb = StringBuilder()
        if (a.optBoolean("owner")) sb.append("Akun pemilik")
        else if (a.optBoolean("active")) sb.append("Paket ${a.optString("plan")} aktif sampai ${fmtDate(a.optLong("exp"))}")
        else sb.append("Langganan tidak aktif. Perpanjang di dashboard supaya pembayaran dicocokkan.")
        val m = a.optString("merchants")
        sb.append("\n").append(if (m.isBlank()) "Belum ada merchant QRIS. Daftarkan di dashboard." else "Merchant: $m")
        return sb.toString()
    }

    private fun editBox(hint: String, secret: Boolean, email: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(FAINT)
        setTextColor(INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setSingleLine()
        inputType = when {
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            else -> InputType.TYPE_CLASS_TEXT
        }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(BG, 6, LINE)
        setOnFocusChangeListener { v, f -> (v.background as GradientDrawable).setStroke(dp(1), if (f) INK else LINE) }
    }

    private fun renderAccount() {
        val p = Cfg.prefs(this)
        acctBox.removeAllViews()
        val email = p.getString("email", "") ?: ""
        if (email.isEmpty()) {
            acctBox.addView(tv("Masuk dengan akun Emy Cash. Alamat server dan kunci terisi otomatis, tanpa salin-tempel.", 13f, MUTED).apply { setLineSpacing(0f, 1.15f) }, lp())
            val em = editBox("Email", false, true)
            val pw = editBox("Password", true)
            val msg = tv("", 13f, RED)
            acctBox.addView(em, lp(12))
            acctBox.addView(pw, lp(8))
            acctBox.addView(button("Masuk", true) { login(em.text.toString().trim(), pw.text.toString(), msg) }, lp(12))
            acctBox.addView(msg, lp(8))
            acctBox.addView(button("Daftar akun baru", false) { openUrl(serverBase() + "/dashboard#daftar") }, lp(8))
        } else {
            val a = acct()
            acctBox.addView(tv(email, 15f, INK, true), lp())
            acctBox.addView(tv(accountSummary(a), 13f, if (a.length() == 0 || a.optBoolean("active")) MUTED else AMBER).apply { setLineSpacing(0f, 1.15f) }, lp(4))
            acctBox.addView(button("Perbarui status", false) { refreshStatus(true) }, lp(12))
            acctBox.addView(button("Buka dashboard", false) { openUrl(serverBase() + "/dashboard") }, lp(8))
            acctBox.addView(button("Keluar", false, RED) { logout() }, lp(8))
        }
    }

    private fun renderAcctLine() {
        val p = Cfg.prefs(this)
        val email = p.getString("email", "") ?: ""
        val err = p.getString("acct_err", "") ?: ""
        val a = acct()
        val line: Pair<String, Int> = when {
            err.isNotEmpty() -> Pair(err, RED)
            email.isNotEmpty() -> Pair(
                email + " · " + when {
                    a.length() == 0 -> "memuat…"
                    a.optBoolean("owner") -> "pemilik"
                    a.optBoolean("active") -> "paket ${a.optString("plan")} aktif"
                    else -> "langganan tidak aktif"
                },
                if (a.length() == 0 || a.optBoolean("active")) MUTED else AMBER
            )
            (p.getString("sec", "") ?: "").isNotBlank() -> Pair("Mode manual", MUTED)
            else -> Pair("Belum masuk. Buka Pengaturan untuk masuk akun", AMBER)
        }
        acctLine.text = line.first
        acctLine.setTextColor(line.second)
        val uv = a.optString("upd_ver")
        if (uv.isNotEmpty()) {
            updateLine.text = "Versi $uv tersedia. Ketuk untuk unduh"
            updateLine.visibility = View.VISIBLE
            updateLine.setOnClickListener { openUrl(a.optString("upd_url")) }
        } else updateLine.visibility = View.GONE
    }

    private fun login(email: String, pw: String, msg: TextView) {
        if (busy) return
        if (email.isEmpty() || pw.isEmpty()) { msg.setTextColor(RED); msg.text = "Isi email dan password"; return }
        busy = true
        msg.setTextColor(MUTED)
        msg.text = "Masuk…"
        val base = serverBase()
        Thread {
            val body = JSONObject().put("email", email).put("password", pw).put("device_name", Build.MODEL ?: "Android")
            val r = Api.call("POST", "$base/app/login", null, body)
            runOnUiThread {
                busy = false
                val key = r.json().optString("device_key")
                if (r.ok && key.isNotEmpty()) {
                    Cfg.prefs(this).edit().putString("email", email).putString("wh", "$base/notif").putString("sec", key)
                        .putString("acct", "{}").remove("acct_err").apply()
                    fields["wh"]?.setText("$base/notif")
                    fields["sec"]?.setText(key)
                    lastSig = ""
                    renderAccount()
                    renderAcctLine()
                    toast("Berhasil masuk")
                    refreshStatus(false)
                } else {
                    msg.setTextColor(RED)
                    msg.text = if (r.code == 404) "Server belum mendukung Emy Cash (HTTP 404). Perbarui gateway ke versi terbaru." else Api.message(r)
                }
            }
        }.start()
    }

    private fun logout() {
        val p = Cfg.prefs(this)
        val base = serverBase()
        val key = p.getString("sec", "") ?: ""
        Thread { if (key.isNotBlank()) Api.call("POST", "$base/app/logout", key) }.start()
        p.edit().remove("email").remove("acct").remove("sec").remove("acct_err").apply()
        fields["sec"]?.setText("")
        lastSig = ""
        renderAccount()
        renderAcctLine()
        toast("Sudah keluar")
    }

    // Ambil status langganan, merchant, dan versi terbaru dari server; sekalian kirim heartbeat
    private fun refreshStatus(manual: Boolean) {
        val p = Cfg.prefs(this)
        val key = p.getString("sec", "") ?: ""
        val base = Api.base(p.getString("wh", "") ?: "")
        if (key.isBlank() || base == null) {
            if (manual) toast("Masuk dulu, atau isi server dan kunci")
            return
        }
        val cur = versionInfo().second
        Thread {
            val r = Api.call("GET", "$base/app/status", key)
            if (r.ok) {
                val j = r.json()
                val names = ArrayList<String>()
                val ms = j.optJSONArray("merchants")
                if (ms != null) for (i in 0 until ms.length()) names.add(ms.getJSONObject(i).optString("name"))
                val a = JSONObject().put("active", j.optBoolean("active")).put("plan", j.optString("plan_name"))
                    .put("exp", j.optLong("plan_expires_at")).put("owner", j.optBoolean("owner")).put("merchants", names.joinToString(", "))
                val app = j.optJSONObject("app")
                if (app != null && app.optBoolean("available") && app.optInt("version_code") > cur) {
                    val du = app.optString("download_url")
                    a.put("upd_ver", app.optString("version")).put("upd_url", if (du.startsWith("/")) base + du else du)
                }
                p.edit().putString("acct", a.toString()).remove("acct_err").apply()
                Cfg.heartbeat(applicationContext)
            } else if (r.code == 401) {
                p.edit().putString("acct_err", "Sesi berakhir. Masuk lagi.").apply()
            } else if (r.code == 404) {
                p.edit().putString("acct_err", "Server belum diperbarui (HTTP 404)").apply()
            }
            runOnUiThread {
                renderAccount()
                renderAcctLine()
                if (manual) toast(if (r.ok) "Status diperbarui" else Api.message(r))
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        lastSig = ""
        // izin aktif tetapi listener tidak tersambung (sering terjadi setelah update/hemat baterai): sambungkan ulang
        if (listenerOn() && !NotifService.connected) Cfg.rebind(this)
        ticker.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun render() {
        val on = listenerOn()
        val conn = NotifService.connected
        val raw = Cfg.prefs(this).getString("h", "[]") ?: "[]"
        val sig = raw + on + conn
        if (sig == lastSig) return
        lastSig = sig

        val (label, color) = when {
            !on -> Pair("Belum aktif. Ketuk untuk membuka izin", RED)
            !conn -> Pair("Izin aktif, belum tersambung. Ketuk untuk sambungkan ulang", AMBER)
            else -> Pair("Listener aktif", OK)
        }
        statusText.text = label
        statusText.setTextColor(if (on && conn) MUTED else color)
        (statusDot.background as GradientDrawable).setColor(color)

        val arr = JSONArray(raw)
        // ringkasan lunas hari ini
        var sum = 0L
        var cnt = 0
        val t0 = startOfToday()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("st") == "paid" && o.optLong("i") >= t0) { sum += o.optLong("am"); cnt++ }
        }
        todayText.text = rupiah(sum)
        todayText.setTextColor(if (sum > 0) OK else INK)
        todaySub.text = if (cnt == 0) "Belum ada pembayaran lunas hari ini" else "$cnt pembayaran lunas"

        listBox.removeAllViews()
        if (arr.length() == 0) {
            listBox.addView(tv(if ((Cfg.prefs(this).getString("sec", "") ?: "").isBlank()) "Belum masuk.\nBuka Pengaturan, masuk dengan akun Emy Cash, lalu aktifkan izin notifikasi." else "Belum ada notifikasi.\nPembayaran masuk akan muncul di sini.", 14f, MUTED).apply {
                setPadding(dp(20), dp(32), dp(20), dp(32))
                setLineSpacing(0f, 1.2f)
            }, lp())
            return
        }
        var animIdx = 0L
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optLong("i")
            val pkg = o.optString("p", o.optString("a", "?"))
            val title = o.optString("ti")
            val text = o.optString("tx")
            val st = o.optString("st")
            val inv = o.optString("inv")
            val info = o.optString("info")
            val (wl, wc) = walletOf(pkg, title, text)
            val amount: Long? =
                if (o.has("am")) o.optLong("am")
                else if (pkg.startsWith("com.whatsapp")) null
                else parseAmount(title, text)
            val chip: Triple<String, Int, Boolean>? = when (st) {
                "paid" -> Triple("Lunas", OK, false)
                "nomatch" -> Triple("Tidak cocok", AMBER, false)
                "fail" -> Triple("Gagal kirim", RED, false)
                "sending" -> Triple("Mengirim", MUTED, true)
                "local" -> Triple("Tercatat", MUTED, false)
                "unmon" -> Triple("Belum dipantau", AMBER, false)
                else -> null
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(wallet(wl, wc), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(tv(o.optString("t"), 12f, FAINT))
            row.addView(top, lp())

            if (amount != null) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                line.addView(tv(rupiah(amount), 22f, if (st == "paid") OK else INK, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (chip != null) line.addView(status(chip.first, chip.second, chip.third))
                row.addView(line, lp(8))
            } else if (chip != null) {
                row.addView(status(chip.first, chip.second, chip.third), lp(8))
            }
            if (inv.isNotEmpty()) {
                row.addView(tv("Invoice  $inv", 13f, INK, true).apply {
                    setPadding(0, dp(4), 0, dp(4)); setOnClickListener { copy(inv) }
                }, lp(4))
            }
            if (info.isNotEmpty()) row.addView(tv(info, 12f, if (st == "fail") RED else MUTED), lp(2))

            // detail ringkas; ketuk baris untuk membuka teks lengkap
            val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (title.isNotEmpty()) detail.addView(tv(title, 14f, INK, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp())
            if (text.isNotEmpty()) detail.addView(tv(text, 13f, MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }, lp(3))
            val pkgLine = tv(pkg, 11f, FAINT).apply { visibility = View.GONE }
            detail.addView(pkgLine, lp(6))
            row.addView(detail, lp(10))
            var open = false
            row.setOnClickListener {
                open = !open
                for (k in 0 until detail.childCount) {
                    val c = detail.getChildAt(k) as TextView
                    if (c === pkgLine) {
                        c.visibility = if (open) View.VISIBLE else View.GONE
                    } else {
                        c.maxLines = if (open) 50 else if (k == 0) 1 else 2
                    }
                }
            }
            if (st == "unmon") {
                row.addView(button("Pantau package ini", true) {
                    Cfg.addPackage(this@MainActivity, pkg)
                    Toast.makeText(this@MainActivity, "Dipantau: $pkg. Bayar lagi untuk mencoba.", Toast.LENGTH_LONG).show()
                }, lp(12))
            }
            listBox.addView(row, lp())
            listBox.addView(divider(), divLp())

            // animasi hanya untuk entri baru (bukan saat render ulang tiap 3 detik)
            if (!firstRender && rendered.add(id)) { fadeIn(row, animIdx * 40); animIdx++ } else rendered.add(id)
        }
        firstRender = false
    }
}
