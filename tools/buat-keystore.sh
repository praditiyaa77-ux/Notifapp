#!/usr/bin/env bash
# Buat keystore tetap SEKALI, simpan di tempat aman, lalu isi 4 secret di GitHub (Settings > Secrets > Actions):
#   KEYSTORE_B64 (isi base64 di bawah), KEYSTORE_PASSWORD, KEY_ALIAS (emycash), KEY_PASSWORD
set -e
read -rsp "Password keystore (min 6 karakter): " PW; echo
keytool -genkeypair -v -keystore emycash.jks -alias emycash -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$PW" -keypass "$PW" -dname "CN=Emy Cash, O=Emypedia, C=ID"
echo; echo "== KEYSTORE_B64 (salin seluruh baris ini) =="; base64 -w0 emycash.jks 2>/dev/null || base64 emycash.jks | tr -d '\n'; echo
echo "Jangan commit emycash.jks. Simpan cadangannya: kalau hilang, pengguna harus pasang ulang aplikasi."
