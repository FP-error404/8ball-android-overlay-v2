# 8ball-android-overlay-v2

Project Android Overlay khusus **8 Ball Pool** (terpisah sepenuhnya dari Project 1 PC).

## Arsitektur & Aturan Kunci (LOCKED)
1. **Platform Khusus Android**: Menggunakan Android `WindowManager` overlay (`TYPE_APPLICATION_OVERLAY`) tanpa dependensi kode desktop/PC.
2. **Visualisasi 2 Garis Saja (No Auto-Play)**:
   - **Garis 1**: Cue ball menuju titik tumbukan (*ghost ball*).
   - **Garis 2**: Target ball menuju *pocket*.
   - Tidak ada fitur *auto-aim*, *auto-shoot*, maupun *auto-play*.
3. **Pewarnaan Garis Sesuai Bola**:
   - Cue line: Putih (`#FFFFFF`).
   - Target line: Mengikuti warna bola sasaran (1 Kuning, 2 Biru, 3 Merah, 4 Ungu, 5 Oranye, 6 Hijau, 7 Maroon, 8 Hitam, 9-15 Strip dengan warna dasar masing-masing + indikator strip).
4. **Power-Aware Trajectory**:
   - Panjang garis pantulan/tumbukan berbanding lurus dengan *cue power* (0.0f - 1.0f). Power kecil menghasilkan garis pendek yang berhenti di tengah meja; power yang cukup baru mencapai *pocket*.
5. **Table Bounds Clipping**:
   - Seluruh rendering garis dipotong (*clipped*) secara presisi di dalam batas *cushion* meja menggunakan Liang-Barsky clipping sehingga tidak pernah keluar area meja.
6. **Dynamic JSON Theme Engine**:
   - Konfigurasi warna dimuat dari `app/src/main/assets/themes.json` melalui `ThemeManager.kt` tanpa hardcode di UI/Canvas.
   - Tema default: **Blood Moon** (`#0B080A`, `#171014`, `#B00020`, `#FF1744`).
7. **Mock Data Mode**:
   - Saat ini berjalan dengan data simulasi (*mock*) untuk pengujian geometri, clipping, power scaling, dan tema.
   - Seluruh alamat/offset memori game disiapkan kosong di `Config.kt` untuk diisi di tahap integrasi berikutnya.

## Struktur Folder
```text
8ball-android-overlay-v2/
├── README.md
└── app/
    └── src/
        └── main/
            ├── assets/
            │   └── themes.json
            └── java/
                └── com/
                    └── poolpredictor2/
                        ├── Config.kt
                        ├── ThemeManager.kt
                        ├── PredictorService.kt
                        └── geometry/
                            └── ShotGeometry.kt
```
