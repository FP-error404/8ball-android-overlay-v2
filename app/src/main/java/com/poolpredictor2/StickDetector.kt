package com.poolpredictor2.vision

import java.nio.ByteBuffer
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Arah bidik dari stik biliar asli.
 * aimAngleDeg memakai konvensi yang sama dengan slider Angle: atan2(dy, dx) pada koordinat
 * layar (sumbu y ke bawah), 0 = ke kanan, 90 = ke bawah, rentang -180..180.
 */
data class StickState(
    val aimAngleDeg: Float,
    val votes: Int
)

/**
 * Deteksi stik biliar (kayu/krem-oranye) dan arah bidiknya.
 *
 * Ini Hough Line Transform yang DIBATASI melalui titik bola putih: garis stik pasti
 * melewati bola putih, jadi ruang parameter cukup satu dimensi (sudut).
 * Tiap piksel warna stik memberi satu suara pada sudutnya terhadap bola putih;
 * puncak histogram = arah stik. Arah bidik = kebalikan arah stik (stik ada di belakang bola).
 *
 * Lebih ringan daripada Hough penuh dua dimensi dan tidak butuh library native.
 * Hanya membaca buffer; tidak mengubah apa pun.
 */
object StickDetector {

    private const val BALL_TABLE_FRACTION = 0.0145f
    private const val STEP = 2
    private const val BINS = 720              // 0,5 derajat per bin
    private const val MIN_VOTES = 25

    fun detect(
        buf: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        cueX: Float,
        cueY: Float,
        tableLeft: Int,
        tableTop: Int,
        tableRight: Int,
        tableBottom: Int
    ): StickState? {
        val tableW = (tableRight - tableLeft).toFloat()
        if (tableW < 64f) return null

        // Area pencarian: dari sedikit di kiri meja sampai tepi kanan frame (stik bisa keluar
        // meja di sisi kanan), tanpa baris di atas meja agar ikon HUD tidak ikut terhitung.
        val x0 = max(0, (tableLeft - 0.12f * tableW).toInt())
        val x1 = width
        val y0 = max(0, tableTop)
        val y1 = min(height, (tableBottom + 0.05f * tableW).toInt())

        val minDist = 1.6f * BALL_TABLE_FRACTION * tableW
        val maxDist = 1.3f * tableW

        val counts = IntArray(BINS)
        val offsetSum = FloatArray(BINS)

        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val idx = y * rowStride + x * pixelStride
                if (idx >= 0 && idx + 2 < buf.limit() && isStick(buf, idx)) {
                    val dx = x - cueX
                    val dy = y - cueY
                    val d = hypot(dx, dy)
                    if (d > minDist && d < maxDist) {
                        var ang = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                        if (ang >= 180f) ang -= 360f
                        var bin = ((ang + 180f) / 0.5f).toInt()
                        if (bin >= BINS) bin -= BINS
                        if (bin < 0) bin = 0
                        val center = bin * 0.5f - 180f + 0.25f
                        counts[bin]++
                        offsetSum[bin] += ang - center
                    }
                }
                x += STEP
            }
            y += STEP
        }

        // ---- Puncak histogram (jendela 5 bin, melingkar) ----
        var peak = -1
        var peakVotes = 0
        for (i in 0 until BINS) {
            var s = 0
            for (k in -2..2) s += counts[(i + k + BINS) % BINS]
            if (s > peakVotes) {
                peakVotes = s
                peak = i
            }
        }
        if (peak < 0 || peakVotes < MIN_VOTES) return null

        // ---- Haluskan: rata-rata sudut di sekitar puncak (+-3 bin ~ 1,5 derajat) ----
        var total = 0
        var weighted = 0f
        for (j in -3..3) {
            val i = (peak + j + BINS) % BINS
            val c = counts[i]
            if (c == 0) continue
            total += c
            weighted += c * (j * 0.5f) + offsetSum[i]
        }
        if (total < MIN_VOTES) return null

        val peakCenter = peak * 0.5f - 180f + 0.25f
        val stickDir = peakCenter + weighted / total   // arah dari bola ke stik
        var aim = stickDir + 180f                       // arah bidik = berlawanan
        while (aim > 180f) aim -= 360f
        while (aim <= -180f) aim += 360f
        return StickState(aimAngleDeg = aim, votes = total)
    }

    /** Warna krem-oranye stik: r > g > b dengan rasio tertentu (membedakan dari bola dan rel). */
    private fun isStick(buf: ByteBuffer, idx: Int): Boolean {
        val r = buf.get(idx).toInt() and 0xFF
        val g = buf.get(idx + 1).toInt() and 0xFF
        val b = buf.get(idx + 2).toInt() and 0xFF
        if (r < 150 || r > 235) return false
        if (g * 100 < r * 66 || g * 100 > r * 88) return false
        if (b * 100 < r * 42 || b * 100 > r * 76) return false
        return true
    }
}
