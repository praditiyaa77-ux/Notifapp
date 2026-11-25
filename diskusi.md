# diskusi.md — serah terima proyek Emy Cash (dulu NotifApp) + Order QRIS

> 25 Nov 2026: NotifApp sudah diganti nama menjadi **Emy Cash** (paket `id.emypedia.emycash`). Bagian di bawah yang menyebut NotifApp berarti Emy Cash. Lihat bagian 14 untuk perubahan terbaru.

Dokumen ini untuk AI/developer lain yang melanjutkan proyek. Baca dulu seluruhnya sebelum mengubah apa pun.
Terakhir diperbarui: 25 Nov 2026 (perbaikan fee, pelunasan, deteksi notifikasi, UI).

## 1. Tujuan
Pemilik (penjual kecil, bot Telegram reseller) ingin **konfirmasi pembayaran QRIS otomatis tanpa API resmi**:
1. Customer membuat order, sistem memberi **invoice + total unik** (harga + biaya 5% + penyesuaian kecil hanya bila bentrok).
2. Customer scan QRIS (kini **dinamis**, nominal terkunci), lalu bayar.
3. Notifikasi "pembayaran masuk" di HP pemilik dibaca aplikasi Android, nominalnya dikirim ke server order, order otomatis jadi LUNAS.

Batasan dari pemilik: tanpa root/Shizuku, tanpa Android Studio (build lewat GitHub Actions), saat ini server jalan **lokal di Termux** (VPS nanti kalau ada).

## 2. Status
- **Berjalan dan teruji oleh pemilik:** APK ter-build di GitHub Actions; listener membaca notifikasi; webhook ke Termux; order, pencocokan, dan status LUNAS di halaman web; QR statis.
- **Baru, teruji di sandbox saja:** QRIS dinamis. Payload valid (CRC cocok) dan PNG terbaca decoder, tetapi **belum pernah discan oleh aplikasi e-wallet sungguhan**. Uji dulu dengan nominal kecil di ketiga dompet (DANA, ShopeePay, GoPay) sebelum dipakai customer. Kalau ada dompet yang menolak, hapus entri metode itu dari `qris/payloads.json` agar otomatis kembali ke gambar QRIS statis.
- **Belum dikerjakan:** versi publik multi-pengguna (lihat bagian 9), indikator hasil pengiriman webhook di app.

## 3. Arsitektur
```
Customer --scan QR--> e-wallet --> merchant (DANA / ShopeePay / GoPay Merchant)
                                          |
                           notifikasi "pembayaran masuk" di HP pemilik
                                          v
              NotifApp (APK, NotificationListenerService)
                  parse nominal + deteksi metode
                                          | POST /notif (header X-Secret)
                                          v
              Server order (Node, Termux)  <--- POST /api/order  <--- bot / halaman web
                  cocokkan (metode + total) -> order LUNAS
                                          ^
              bot polling GET /api/order/:invoice
```

## 4. Struktur file
Repo GitHub **notifapp/** (hanya APK, jangan taruh data QRIS di sini):
- `.github/workflows/build.yml`: build `gradle assembleDebug`, artifact `NotifApp-debug`.
- `app/src/main/java/com/notifapp/NotifService.kt`: `Cfg` (pref, daftar package), `parseAmount`, `detectMethod`, listener, `sendWebhook`, `sendTelegram`.
- `app/src/main/java/com/notifapp/MainActivity.kt`: UI programatik (tanpa XML layout): header status, tab Riwayat/Pengaturan.
- `app/src/main/res/`: ikon adaptive (lonceng + centang), `AndroidManifest.xml`.

Folder lokal Termux **termux-server/** (jangan masuk repo publik: berisi data QRIS pemilik):
- `server.js`: HTTP server tanpa dependency npm (Node 18+).
- `qr.js`: `toDynamic()` (QRIS statis ke dinamis) dan `qrPng()` (render PNG). Memakai `vendor/QRCode` (MIT, Kazuhiko Arase).
- `qris/payloads.json`: string payload QRIS statis per metode; `qris/<metode>.png|jpg`: gambar QRIS statis (fallback).
- `public/index.html`: halaman admin untuk membuat order dan memantau status.
- `bot-contoh.js`: contoh integrasi bot Telegraf. `uji-qris.js`: uji konversi QRIS.
- `data.json`: dibuat otomatis (order + pembayaran tak cocok).

## 5. Alur dan aturan pencocokan
- Total = harga + **biaya 5%** (dibulatkan ke atas, env `FEE_PCT`) + **penyesuaian unik** (`extra`). `extra` = 0 kalau total itu bebas; naik Rp1, Rp2, ... hanya kalau ada order **pending** lain di metode yang sama dengan total sama atau berdekatan (selisih minimal `MIN_GAP`, default Rp2, supaya salah ketik Rp1 tidak mengena order lain). Batas `MAX_EXTRA` (500); kalau penuh, order ditolak. Contoh Rp1: 2, lalu 4, 6 untuk order bersamaan.
- **Invoice** (`INV<yymmdd>-<6 hex>`) selalu unik (dicek ke data). Yang menentukan kecocokan pembayaran tetap total unik, karena notifikasi QRIS tidak membawa invoice. Field respons: `price`, `fee`, `extra`, `code` (= fee + extra, untuk kompatibilitas), `total`.
- Pencocokan: `(metode, total)` harus tepat satu order pending dan belum kedaluwarsa (TTL 15 menit, env `TTL_MIN`). Nol atau lebih dari satu kandidat: tidak dilunasi, dicatat di `unmatched` untuk dicek manual.
- Notifikasi QRIS **tidak membawa nomor invoice**. Tautan ke invoice hanya lewat total unik, itu keterbatasan platform, bukan bug.
- Dedupe di app: notifikasi identik dalam 15 detik dibuang. Webhook dicoba 3x (tidak diulang untuk HTTP 4xx). Server idempoten per `ts` notifikasi (retry mengembalikan hasil yang sama, tidak dihitung dua kali).
- **Notifikasi lokal app** (channel "Pembayaran masuk", perlu izin POST_NOTIFICATIONS di Android 13+): saat pembayaran terdeteksi, app memposting "Kamu menerima saldo Rp X" dengan nama dompet, invoice, dan status. Hasil webhook disimpan di riwayat: chip LUNAS (+ invoice, tap untuk menyalin), TIDAK COCOK, GAGAL KIRIM, MENGIRIM, BELUM DIPANTAU. Tanpa webhook, notifikasi tetap muncul tanpa invoice.

## 6. Format notifikasi yang diketahui
| Dompet | Judul | Isi | Package |
|---|---|---|---|
| ShopeePay | "Pembayaran sebesar RpX diterima" | "...telah diterima pada transaksi <id>" | **belum pasti**: pemilik menyebut `com.shopee.id`, tetapi notifikasi 25 Nov tidak terdeteksi. Diduga package app ShopeePay berbeda; cek chip BELUM DIPANTAU di riwayat |
| GoPay Merchant | "Pembayaran QRIS statis diterima" | "Rp X di <nama merchant>, ..." | `com.gojek.gopaymerchant` (terlihat di riwayat) |
| DANA Bisnis | "Pembayaran Masuk" | "RpX diterima DANA Bisnis." | berawalan `id.dana` (belum dipastikan) |

Diabaikan: notifikasi pembayaran **keluar** dari app GoPay biasa ("kamu barusan bayar ..."), terlihat di package `com.gojek.gopay`. Filter: harus mengandung "diterima" atau "masuk" dan tidak mengandung "barusan bayar" atau "berhasil bayar". WhatsApp tidak pernah dikirim ke webhook.
Package bawaan (kode, tidak disimpan di pref): `com.whatsapp,com.whatsapp.w4b,com.gojek*,com.shopee.id,com.shopeepay*,id.dana*` (`*` di akhir = awalan). Pengguna menambah lewat pref `pkgsx` ("Package tambahan" di Pengaturan).
**Diagnosis package:** notifikasi dari package yang BELUM dipantau tetapi cocok pola pembayaran (`PAY_HINT`) dicatat di riwayat dengan chip BELUM DIPANTAU dan tombol "Pantau package ini" (menambah ke `pkgsx`). Tidak pernah dikirim ke server tanpa pilihan pengguna.
**Anti-pemalsuan:** notifikasi bertipe pesan (`CATEGORY_MESSAGE` / MessagingStyle) tidak pernah dianggap pembayaran, supaya pelanggan tidak bisa memalsukan "pembayaran diterima" lewat chat. Package Shopee juga wajib cocok pola `PAY_HINT` karena app Shopee mengirim notifikasi non-pembayaran. Celah yang masih ada: app lain yang dipantau bisa mem-posting notifikasi palsu; jangan memantau package yang tidak perlu.

## 7. API server (header `X-Secret: <SECRET>` kecuali dicatat)
- `POST /api/order` `{price, method: dana|shopeepay|gopay, ref?}` mengembalikan `{invoice, price, fee, extra, code, total, status, expiresAt, qris, dynamic, ...}`.
- `GET /api/order/:invoice` (publik, tanpa secret) mengembalikan status `pending|paid|expired`.
- `GET /qris/:invoice.png` (publik) mengembalikan PNG QR dinamis (410 kalau order tidak pending). `GET /qris/:metode` mengembalikan gambar QRIS statis.
- `GET /api/state` mengembalikan 30 order terakhir + pembayaran tak cocok.
- `POST /notif` `{amount, method, source, title, text, ts}` dipanggil oleh app; balasan `{matched:true, invoice, total, price}` atau `{matched:false, candidates}`.
- Env: `SECRET`, `PORT` (3000), `HOST` (127.0.0.1), `TTL_MIN` (15), `FEE_PCT` (5), `MAX_EXTRA` (500), `MIN_GAP` (2). Jalankan: `SECRET=... node server.js`.

## 8. QRIS dinamis (cara kerja)
QRIS mengikuti EMVCo MPM. `toDynamic()` mem-parse TLV level atas, mengubah tag `01` dari `11` ke `12`, menyisipkan tag `54` (nominal, bilangan bulat rupiah) setelah tag `53`, lalu menghitung ulang CRC16-CCITT (poly 0x1021, init 0xFFFF) di tag `63`. CRC payload statis diverifikasi dulu. Level koreksi QR: M.
Risiko: ketentuan penyelenggara (PJP) bisa melarang QR hasil modifikasi, dan sebagian dompet bisa menolaknya. Ide lanjutan belum dikerjakan: mengisi sub-tag `62-01` (bill number) dengan invoice supaya muncul di riwayat merchant, perlu diuji per dompet.

## 9. Rencana versi publik (BELUM dikerjakan, perlu desain dulu)
Pemilik ingin proyek bisa dipakai pengguna lain. Hal yang harus diputuskan dan dikerjakan:
- **Multi-tenant**: tiap pengguna membawa QRIS dan server/secret sendiri (self-hosted) **atau** satu server bersama dengan akun, secret per akun, dan data terpisah. Rekomendasi awal: self-hosted (Termux/VPS sendiri), dana tidak lewat pihak lain.
- **Privasi (wajib sebelum publik)**: app sekarang membaca dan meneruskan notifikasi WhatsApp ke Telegram. Untuk publik, kurangi izin: hanya notifikasi pembayaran, hapus WhatsApp dari default, dan jangan menyimpan riwayat pesan pribadi.
- **Keamanan**: secret per pengguna, HTTPS (tunnel/VPS), rate limit, jangan commit `qris/` dan `data.json`, pertimbangkan HMAC pada webhook, pesan jelas ke pengguna soal risiko Notification Access.
- **Distribusi**: Google Play membatasi izin Notification Listener; rilis lewat GitHub Releases (APK debug/signed) lebih realistis. Perlu keystore rilis dan README.
- **Hukum/ToS**: pengguna hanya boleh memakai QRIS miliknya sendiri; periksa ketentuan penyelenggara dompet.

## 10. Backlog (urut prioritas)
1. Uji scan QR dinamis di tiga dompet, lalu perbaiki per dompet kalau perlu. Pastikan juga teks notifikasi GoPay Merchant untuk pembayaran QRIS dinamis tetap cocok dengan parser, dan pastikan package ShopeePay yang benar.
2. (selesai 25 Nov) Indikator hasil webhook di riwayat + notifikasi saldo masuk.
3. Pindahkan penyimpanan `data.json` ke SQLite dan hubungkan ke bot (bot memakai Node.js + Telegraf + SQLite).
4. Notifikasi heartbeat/alert kalau listener mati atau HP offline; kirim ulang notifikasi yang gagal saat HP kembali online.
5. Versi publik (bagian 9).

## 11. Catatan kerja
- Pemilik berbahasa Indonesia, gaya santai (lu/gue), memakai HP (jawaban ringkas, langkah jelas). Jangan menyimpan atau menulis ulang secret/token pemilik di file mana pun.
- Build workflow: **jangan** pakai `android-actions/setup-android` (gagal di runner terbaru); cukup terima lisensi SDK bawaan runner, lalu `gradle/actions/setup-gradle` (Gradle 8.7), JDK 17.
- Jangan jalankan `pkill -f "node server.js"` dari shell yang perintahnya mengandung teks itu (membunuh shell sendiri); pakai PID file.
- UI app sengaja programatik tanpa XML layout agar file sedikit; stack: Kotlin, minSdk 26, AGP 8.5.2, Kotlin 1.9.24.

## 12. Desain UI (25 Nov 2026)
UI sengaja dibuat datar dan fungsional (bukan gradient/kartu): latar putih, teks `#0E1B2C`, abu `#667085`, garis `#E6E9EE`; hijau `#0A7D55` hanya untuk uang masuk/lunas, kuning `#B26A00` untuk menunggu/perlu tindakan, merah `#B42318` untuk gagal. Satu keluarga huruf (sistem), hierarki lewat ukuran/berat, angka tabular. Dompet ditandai kotak warna kecil, status berupa titik + teks (huruf kecil, bukan pil). Radius 6 hanya untuk tombol/input; baris dipisah garis tipis. Web mengikuti mode gelap sistem; APK hanya terang. Jangan menambah gradient, bayangan, atau kartu bertumpuk. Perubahan desain hanya styling: logika, API, dan alur pembayaran tidak berubah.

## 13. Riwayat perubahan
- 25 Nov 2026: kode unik acak 1..999 diganti biaya 5% + penyesuaian unik minimal (permintaan pemilik: fee terlalu besar untuk nominal kecil).
- 25 Nov 2026: ShopeePay tidak terdeteksi. Perbaikan: package bawaan diperluas (`com.shopeepay*`), diagnosis package (BELUM DIPANTAU + tombol), parser membaca teks cadangan (lines/ticker), filter pesan chat; package bawaan dipindah dari pref ke kode (pref `pkgs2` lama tidak dipakai lagi, diganti `pkgsx` kosong).
- 25 Nov 2026: tambah notifikasi saldo masuk di app, invoice + status LUNAS di riwayat, riwayat auto-refresh tiap 3 detik, balasan `/notif` memuat total dan harga.
- 25 Nov 2026: redesign UI web (`public/index.html`) dan APK (`MainActivity.kt`, ikon) sesuai bagian 12; tanpa perubahan logika.
- 25 Nov 2026 (perbaikan):
  - **Fee membengkak**: penyesuaian unik sekarang `MAX_EXTRA` 15 (dulu 500) dan `MIN_GAP` 1 (dulu 2). Order ulang dengan `ref`+metode+nominal sama memakai order pending yang ada (tidak membuat order baru). Ada `POST /api/order/:invoice/cancel` (secret) dan tombol Batalkan di web; order > 3 hari dibersihkan. Biaya 5% tetap, hanya penyesuaian yang bisa naik.
  - **Pelunasan**: `/notif` kini juga mencocokkan lewat total saja kalau metode terdeteksi salah (asal tepat satu order), dan menerima pembayaran telat sampai `GRACE_MIN` (10 menit) setelah order kedaluwarsa. Web tidak lagi error saat order lunas (QR hanya dimuat saat pending, polling tahan error).
  - **Deteksi notifikasi**: package bawaan jadi `com.gojek*,com.gopay*,com.shopee*,com.shopeepay*,id.dana*,com.dana*` (sebelumnya `com.shopeepay.id*`, tidak cocok). Mode luas (pref `wide`, bawaan aktif): package asing yang notifikasinya jelas pembayaran masuk (bukan chat) otomatis dipantau dan dicatat ke `pkgsx`. Pola `PAY_HINT` diperluas; nominal "saldo ..." dilewati. Alamat webhook tanpa path otomatis diberi `/notif`. Listener punya status tersambung, auto-rebind, tombol sambungkan ulang, dan tombol izin latar belakang.
  - **UI**: web dan APK dirombak (flat, responsif, transisi halus). APK memakai navigasi bawah Riwayat/Pengaturan; halaman Pengaturan dibangun ulang. Dikerjakan tanpa compiler lokal: build lewat GitHub Actions dan laporkan error kalau ada.
- 25 Nov 2026 (gateway multi-pengguna, folder termux-server): `server.js` kini gateway multi-tenant (versi lama: `server-single.js`). Akun email+password (scrypt), sesi 7 hari, tiga kunci per akun (API key `gk_live_`, Notif key `nk_`, Webhook secret `whsec_`; disimpan sebagai hash kecuali secret webhook). Paket di `plans.js` (7/14/21/30/60/90 hari; 1 atau 3 merchant; kuota order/hari, rpm, TTL maks, riwayat). Merchant = satu QRIS per dompet (maks 3), disimpan sebagai payload teks. Langganan dibayar lewat order di akun `owner` (QRIS pemilik dari `qris/payloads.json`, notifikasi HP pemilik memakai `SECRET` sebagai Notif key) dan otomatis mengaktifkan paket saat LUNAS. Endpoint: `/auth/*`, `/me/*` (dashboard), `/v1/orders*`, `/v1/notif` (alias `/notif`, kompatibel NotifApp lama), `/v1/public/orders/:invoice`, `/qr/:invoice.png`, `/pay/:invoice`, `/admin/grant`. Webhook `order.paid` ber-HMAC-SHA256 (`X-Gateway-Signature`), retry 5x (timer retry hilang saat restart). Web: `index.html` (harga + dokumentasi), `dashboard.html`, `pay.html`. Env tambahan: `BASE_URL`, `TRUST_PROXY`, `ALLOW_PRIVATE_WEBHOOK`, `GRACE_MIN`. Belum: pembayaran ulang otomatis/pengingat kedaluwarsa, SQLite, reset password, verifikasi email, admin UI, retry webhook yang tahan restart, HTTPS (pakai reverse proxy/tunnel).

## 14. Emy Cash: perbaikan 404, app terintegrasi, unduhan (25 Nov 2026)
- **Akar 404**: "Tes koneksi" memanggil `GET /api/state`, endpoint yang hilang di `server.js` multi-pengguna. Perbaikan: app memakai `GET /app/status`; `/api/state`, `/api/order`, `/api/order/:inv`, `/qris/:inv.png` dikembalikan sebagai alias (khusus pemilik, header `X-Secret` = `SECRET`). Server juga menangani HEAD, trailing slash, `/health`, favicon, dan 404 HTML untuk browser. Bug regex alias `/qr/` ditemukan saat uji dan sudah diperbaiki.
- **App terintegrasi akun**: `POST /app/login` (email+password) menerbitkan kunci per perangkat `nk_...` (maks 5, hash di `user.devices`). App menyimpan `email`, `wh` (server + /notif), `sec` (kunci perangkat), `acct` (cache status). `GET /app/status`, `POST /app/heartbeat`, `POST /app/logout`; dashboard menampilkan perangkat dan tombol cabut (`DELETE /me/devices/:id`). `/v1/notif` menerima kunci akun, kunci perangkat, atau SECRET pemilik (`whoNotif`). Mode manual tetap ada.
- **Unduhan**: `downloads/EmyCash.apk` + `app.json` (versi), `GET /download/emy-cash.apk`, `GET /api/app`, `PUT /admin/apk` (unggah), atau env `APK_URL` (GitHub Releases `latest`). Web punya bagian `#unduh`; app menampilkan pembaruan bila `version_code` server lebih besar. Workflow GitHub kini `assembleRelease`, mendukung keystore tetap lewat secret, dan menerbitkan rilis `latest`.
- **Privasi**: WhatsApp tidak lagi di package bawaan (saklar `wa`, nonaktif).
- **Server**: retry webhook setelah restart, pembanding waktu-konstan, peringatan SECRET bawaan, `package.json`, `deploy/update.sh`, `deploy/unggah-apk.sh`.
- **Belum teruji**: kompilasi Kotlin (tanpa compiler lokal), dan tampilan app di perangkat. Server diuji end-to-end dengan Node di sandbox (login, status, heartbeat, notif, unggah APK, alias lama).
- **Backlog**: reset password dan verifikasi email, SQLite, alert saat heartbeat berhenti, admin UI, uji scan QR dinamis di tiga dompet.
