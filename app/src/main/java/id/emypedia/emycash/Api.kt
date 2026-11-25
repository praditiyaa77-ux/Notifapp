package id.emypedia.emycash

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Klien HTTP kecil untuk API gateway Emy Cash (login, status, heartbeat).
object Api {
    class Res(val code: Int, val body: String, val err: String? = null) {
        val ok: Boolean get() = code in 200..299
        fun json(): JSONObject = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
    }

    // Alamat tanpa skema dilengkapi: jaringan lokal -> http, selain itu https.
    fun normalize(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t.contains("://")) return t
        val local = Regex("^(localhost|127\\.|10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.)")
        return (if (local.containsMatchIn(t)) "http://" else "https://") + t
    }

    // skema://host[:port] dari alamat yang diketik pengguna, atau null kalau tidak valid
    fun base(raw: String): String? {
        val t = normalize(raw)
        if (t.isEmpty()) return null
        val u = Uri.parse(t)
        val scheme = u.scheme
        val authority = u.authority
        return if (scheme != null && authority != null) "$scheme://$authority" else null
    }

    fun call(method: String, url: String, key: String? = null, body: JSONObject? = null): Res {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/json")
            if (key != null) c.setRequestProperty("X-Secret", key)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            Res(code, text)
        } catch (e: Exception) {
            Res(0, "", e.javaClass.simpleName)
        }
    }

    // Pesan dari server ({"error":{"message":...}}) atau penjelasan umum per kode HTTP
    fun message(r: Res): String {
        if (r.code == 0) return "Server tidak terjangkau (${r.err}). Cek alamat server dan koneksi internet."
        val m = r.json().optJSONObject("error")?.optString("message") ?: ""
        if (m.isNotEmpty()) return m
        return when (r.code) {
            401 -> "Kunci atau password salah"
            402 -> "Langganan tidak aktif"
            404 -> "Alamat tidak ditemukan (HTTP 404). Periksa alamat server atau perbarui gateway."
            429 -> "Terlalu banyak percobaan, coba lagi sebentar"
            else -> "Server menjawab HTTP ${r.code}"
        }
    }
}
