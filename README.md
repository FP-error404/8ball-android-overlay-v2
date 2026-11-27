# PoolPredictor2 - Android - From Scratch

Project 2 - Khusus Android. Project 1 tetap khusus PC, tidak dicampur.

## LOCK - Jangan diubah di tengah jalan
- **2 garis doang, no Auto Play**
  1. Garis cue ball -> titik tumbukan (ghost ball)
  2. Garis target ball -> pocket
- **Warna garis = warna bola, bukan putih doang**
  - Cue = putih
  - Target = warna bola target (1 kuning, 2 biru, 3 merah, 4 ungu, 5 oranye, 6 hijau, 7 maroon, 8 hitam, 9-15 strip)
- **Garis ngikutin power cue, bukan full power doang**
  - Power kecil = garis pendek, berhenti di mana bola bakal berhenti
  - Power pas = garis baru sampe pocket
  - Power gede = garis sampe pocket
- **Garis di-clip di dalam meja**, tidak keluar tata surya
- **Tema bisa diganti lewat JSON**, tidak hardcode di kode
- **Untuk belajar baca data**, data awal masih mock / manual. Memory reading dipelajari belakangan.

## Struktur awal
```
app/src/main/java/.../Config.kt
app/src/main/java/.../ThemeManager.kt
app/src/main/java/.../geometry/ShotGeometry.kt
app/src/main/res/values/themes.json
```

## Tahapan
1. Minggu 1-2: Skeleton + tema + 2 garis mock
2. +1 minggu: Power-aware
3. +2-4 minggu: Belajar baca data (offset / pointer chain) - tiap update game 8 Ball Pool (rata-rata 9 hari) offset bisa berubah, jadi offset taruh di config, bukan hardcode
