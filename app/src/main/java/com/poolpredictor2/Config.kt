package com.poolpredictor2

/**
 * Konfigurasi pusat untuk Project 2 (Android Overlay Only).
 *
 * LOCKED RULES:
 * - Data awal menggunakan MOCK mode (bukan pembacaan memori live).
 * - Offset memori game dibiarkan kosong dan tidak boleh diisi nilai asal/ngarang.
 * - Tidak ada fitur Auto Play.
 */
object Config {
    const val PROJECT_ID = "8ball-android-overlay-v2"
    const val PLATFORM = "ANDROID_ONLY"

    // Mode operasi saat ini: true = gunakan data simulasi untuk validasi visual & geometri
    const val USE_MOCK_DATA = true

    // Batas maksimal garis yang digambar: hanya Cue -> Ghost Ball dan Target -> Pocket
    const val MAX_TRAJECTORY_LINES = 2

    // Frame rate target untuk overlay canvas
    const val OVERLAY_TARGET_FPS = 60L
    const val FRAME_INTERVAL_MS = 1000L / OVERLAY_TARGET_FPS

    // Overlay hanya digambar ulang saat ada perubahan (dirty), plus detak minimum ini
    // supaya perubahan tema tetap terlihat. Mengurangi beban komposisi jendela overlay.
    const val OVERLAY_HEARTBEAT_MS = 500L

    // Skala resolusi capture relatif terhadap layar nyata (0.5 = setengah lebar & tinggi).
    // Lebih kecil = lebih ringan untuk GPU/CPU. Deteksi memakai koordinat relatif,
    // jadi nilai ini bebas diubah. Naikkan ke 0.75/1.0 nanti jika deteksi bola butuh detail.
    const val CAPTURE_SCALE = 0.5f

    // Deteksi meja: jarak antar deteksi saat belum terkunci / sudah terkunci
    const val TABLE_DETECT_SEARCH_INTERVAL_MS = 250L
    const val TABLE_DETECT_LOCKED_INTERVAL_MS = 1000L

    // Toleransi perubahan (fraksi frame) agar posisi meja yang sudah terkunci tidak bergetar
    const val TABLE_LOCK_TOLERANCE = 0.012f

    // Jumlah deteksi gagal berturut-turut sebelum kunci meja dilepas
    const val TABLE_MISS_LIMIT = 4

    // Deteksi bola putih: jarak antar deteksi dan jumlah gagal berturut-turut sebelum dianggap hilang
    const val CUE_DETECT_INTERVAL_MS = 100L
    const val CUE_MISS_LIMIT = 6

    /**
     * Placeholder offset memori game (Android arm64-v8a).
     * Sengaja dikosongkan sampai dilakukan reverse-engineering/dump resmi.
     */
    object GameOffsets {
        const val LIB_NAME: String = ""
        const val BASE_WORLD_PTR: Long = 0L
        const val TABLE_MANAGER_OFFSET: Long = 0L
        const val BALL_ARRAY_OFFSET: Long = 0L
        const val BALL_STRIDE: Int = 0
        const val BALL_POS_X_OFFSET: Long = 0L
        const val BALL_POS_Y_OFFSET: Long = 0L
        const val BALL_NUMBER_OFFSET: Long = 0L
        const val BALL_STATE_OFFSET: Long = 0L
        const val CUE_ANGLE_OFFSET: Long = 0L
        const val CUE_POWER_OFFSET: Long = 0L
    }

    /**
     * Definisi warna bola standar 8-Ball Pool.
     * Cue ball = Putih (#FFFFFF).
     * Bola 1-8 = Solid, Bola 9-15 = Stripe (menggunakan warna dasar + flag isStripe = true).
     */
    data class BallVisualStyle(
        val number: Int,
        val colorHex: String,
        val isStripe: Boolean
    )

    fun getBallVisualStyle(ballNumber: Int): BallVisualStyle {
        return when (ballNumber) {
            0 -> BallVisualStyle(0, "#FFFFFF", false)   // Cue Ball - Putih
            1 -> BallVisualStyle(1, "#FDD835", false)   // Kuning
            2 -> BallVisualStyle(2, "#1E88E5", false)   // Biru
            3 -> BallVisualStyle(3, "#E53935", false)   // Merah
            4 -> BallVisualStyle(4, "#8E24AA", false)   // Ungu
            5 -> BallVisualStyle(5, "#FB8C00", false)   // Oranye
            6 -> BallVisualStyle(6, "#43A047", false)   // Hijau
            7 -> BallVisualStyle(7, "#6D1B2B", false)   // Maroon
            8 -> BallVisualStyle(8, "#212121", false)   // Hitam
            9 -> BallVisualStyle(9, "#FDD835", true)    // Strip Kuning
            10 -> BallVisualStyle(10, "#1E88E5", true)  // Strip Biru
            11 -> BallVisualStyle(11, "#E53935", true)  // Strip Merah
            12 -> BallVisualStyle(12, "#8E24AA", true)  // Strip Ungu
            13 -> BallVisualStyle(13, "#FB8C00", true)  // Strip Oranye
            14 -> BallVisualStyle(14, "#43A047", true)  // Strip Hijau
            15 -> BallVisualStyle(15, "#6D1B2B", true)  // Strip Maroon
            else -> BallVisualStyle(ballNumber, "#FFFFFF", false)
        }
    }
}
