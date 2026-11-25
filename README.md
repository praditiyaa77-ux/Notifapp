# Emy Cash (Android)

Aplikasi yang membaca notifikasi pembayaran masuk (DANA Bisnis, GoPay Merchant, ShopeePay) dan mengirimkannya ke gateway Emy Cash supaya order QRIS otomatis berstatus lunas. Dulu bernama NotifApp.

## Pemakaian
1. Pasang APK, buka Emy Cash, **Pengaturan > Akun Emy Cash**, masuk dengan email dan password akun gateway (daftar di web bila belum punya). Alamat server dan kunci perangkat terisi otomatis.
2. Ketuk **Buka Notification Access** dan aktifkan Emy Cash. Ketuk **Izinkan berjalan di latar belakang**.
3. Header menampilkan status listener, akun, paket aktif, dan pemberitahuan versi baru. Riwayat memuat tiap notifikasi: LUNAS, TIDAK COCOK, GAGAL KIRIM, dan seterusnya.

Mode manual (pemilik/server sendiri): isi **Alamat server** dan **Kunci (Secret)** di bagian Server, lalu **Tes koneksi**. Server bawaan: `https://pay.emypedia.my.id`.

## Build (GitHub Actions)
Push ke GitHub; workflow `Build Emy Cash APK` membuat `EmyCash.apk` (artifact) dan, untuk branch `main`/`master`, memperbarui rilis `latest`. Tautan tetap: `https://github.com/AKUN/REPO/releases/latest/download/EmyCash.apk`. Pasang tautan itu sebagai `APK_URL` di server, atau unggah APK dengan `deploy/unggah-apk.sh` (proyek server).

**Tanda tangan tetap (disarankan).** Tanpa keystore, tiap build memakai kunci debug yang berbeda sehingga pembaruan di atas versi lama gagal ("aplikasi tidak terpasang"). Jalankan `tools/buat-keystore.sh` sekali, isi secret `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, dan simpan cadangan `emycash.jks`.

Naikkan `versionCode` dan `versionName` di `app/build.gradle.kts` setiap rilis, lalu unggah APK dengan angka yang sama agar app menawarkan pembaruan.

## Perubahan dari NotifApp
- Nama dan paket baru: Emy Cash, `id.emypedia.emycash` (terpasang terpisah dari NotifApp; hapus NotifApp lama dan matikan Notification Access-nya agar tidak mengirim ganda).
- **Tes koneksi memakai `/app/status`** (sebelumnya `/api/state` yang tidak ada di server baru, sumber HTTP 404). Pesan error kini membedakan 401, 402, 404, dan server tidak terjangkau.
- Masuk dengan akun, status langganan dan merchant, heartbeat ke server, pemberitahuan versi baru.
- Alamat tanpa skema dilengkapi otomatis (`https://`, atau `http://` untuk jaringan lokal).
- Privasi: WhatsApp tidak lagi dipantau secara bawaan (saklar di bagian Telegram).

Catatan: perubahan ini ditulis tanpa compiler lokal; bila build gagal, kirim log error dari GitHub Actions.
