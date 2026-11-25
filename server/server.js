// Jalankan di Termux: SECRET=rahasia node server.js
// Tanpa dependency. Ganti bagian "STORE" dengan SQLite bot lu.
const http = require('http');
const SECRET = process.env.SECRET || 'ganti-ini';
const PORT = process.env.PORT || 3000;
const TTL_MS = 15 * 60 * 1000;

// ---- STORE contoh (in-memory). Ganti ke tabel orders di SQLite ----
const orders = new Map(); // id -> { price, total, status, expiresAt }

function pending() {
  const now = Date.now();
  return [...orders.values()].filter(o => o.status === 'pending' && o.expiresAt > now);
}

// Buat order + total unik (harga + kode 1..999, tidak boleh kembar dengan order pending)
function createOrder(id, price) {
  const used = new Set(pending().map(o => o.total));
  let total, tries = 0;
  do { total = price + 1 + Math.floor(Math.random() * 999); } while (used.has(total) && ++tries < 2000);
  if (used.has(total)) throw new Error('kode unik habis');
  orders.set(id, { id, price, total, status: 'pending', expiresAt: Date.now() + TTL_MS });
  return orders.get(id);
}

// Dipanggil saat pembayaran masuk terdeteksi
function onPaid(order) {
  console.log('LUNAS:', order.id, order.total);
  // TODO: kirim produk / notif ke user lewat bot lu di sini
}

function markPaid(amount) {
  const hit = pending().filter(o => o.total === amount);
  if (hit.length !== 1) return { matched: false, count: hit.length };
  hit[0].status = 'paid';
  onPaid(hit[0]);
  return { matched: true, id: hit[0].id };
}
// -------------------------------------------------------------------

http.createServer((req, res) => {
  if (req.method !== 'POST' || req.url !== '/notif') { res.writeHead(404); return res.end(); }
  if (req.headers['x-secret'] !== SECRET) { res.writeHead(401); return res.end(); }
  let raw = '';
  req.on('data', d => { raw += d; if (raw.length > 10000) req.destroy(); });
  req.on('end', () => {
    try {
      const n = JSON.parse(raw);
      const amount = Number(n.amount);
      if (!Number.isInteger(amount) || amount <= 0) throw new Error('amount');
      const r = markPaid(amount);
      if (!r.matched) console.log('TIDAK COCOK (cek manual):', amount, n.source, n.title);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(r));
    } catch (e) { res.writeHead(400); res.end(); }
  });
}).listen(PORT, '127.0.0.1', () => console.log('listen', PORT));

module.exports = { createOrder, markPaid };
