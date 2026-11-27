package com.poolpredictor2.vision

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Posisi bola putih dalam koordinat RELATIF frame (0-1).
 * radius dinyatakan relatif terhadap LEBAR frame.
 */
data class CueBallState(
    val x: Float,
    val y: Float,
    val radius: Float,
    val score: Float
)

/**
 * Deteksi bola putih (cue ball) di dalam area meja.
 *
 * Pendekatan: skor "cakram putih" memakai integral image.
 * - Piksel dianggap putih-krem jika terang dan TIDAK kebiruan (b <= r + 6). Syarat ini
 *   membuat bola/garis buatan overlay sendiri (putih bercampur kain biru) tidak ikut terhitung.
 * - Untuk tiap titik kandidat dihitung rasio putih di persegi dalam (di dalam bola)
 *   dan di cincin luar (sekitar bola). Bola putih = dalam padat putih, luar nyaris tidak putih.
 * - Cara ini tahan terhadap garis bidik game yang memotong bola, karena tidak butuh
 *   bentuk bola yang utuh.
 *
 * Hanya membaca buffer; tidak mengubah apa pun.
 */
object CueBallDetector {

    // Radius bagian putih bola relatif terhadap lebar meja (hasil ukur pada frame nyata)
    private const val RADIUS_TABLE_FRACTION = 0.0122f

    private const val INNER_MIN = 0.70f
    private const val RING_MAX = 0.30f
    private const val STEP = 2

    /**
     * @param roiLeft, roiTop, roiRight, roiBottom area pencarian (piksel frame), biasanya batas meja.
     */
    fun detect(
        buf: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        roiLeft: Int,
        roiTop: Int,
        roiRight: Int,
        roiBottom: Int
    ): CueBallState? {
        val l = max(0, roiLeft)
        val t = max(0, roiTop)
        val r = min(width, roiRight)
        val b = min(height, roiBottom)
        if (r - l < 64 || b - t < 64) return null

        val gw = (r - l) / STEP
        val gh = (b - t) / STEP

        // ---- Integral image dari mask putih-krem ----
        val iw = gw + 1
        val integral = IntArray((gh + 1) * iw)
        for (gy in 0 until gh) {
            var rowSum = 0
            val y = t + gy * STEP
            for (gx in 0 until gw) {
                val x = l + gx * STEP
                if (isWhite(buf, y * rowStride + x * pixelStride)) rowSum++
                integral[(gy + 1) * iw + (gx + 1)] = integral[gy * iw + (gx + 1)] + rowSum
            }
        }

        fun boxSum(y0: Int, x0: Int, side: Int): Int {
            val y1 = y0 + side
            val x1 = x0 + side
            return integral[y1 * iw + x1] - integral[y0 * iw + x1] -
                integral[y1 * iw + x0] + integral[y0 * iw + x0]
        }

        val radiusPx = RADIUS_TABLE_FRACTION * (r - l)
        val si = max(2, Math.round(1.4f * radiusPx / STEP))
        val sm = max(si + 2, Math.round(2.2f * radiusPx / STEP))
        val so = max(sm + 2, Math.round(3.4f * radiusPx / STEP))
        val margin = so / 2
        if (gw <= so || gh <= so) return null

        val innerArea = (si * si).toFloat()
        val ringArea = (so * so - sm * sm).toFloat()

        var bestScore = 0f
        var bestX = -1
        var bestY = -1

        for (cy in margin until gh - margin) {
            for (cx in margin until gw - margin) {
                val inner = boxSum(cy - si / 2, cx - si / 2, si) / innerArea
                if (inner < INNER_MIN) continue
                val wm = boxSum(cy - sm / 2, cx - sm / 2, sm)
                val wo = boxSum(cy - so / 2, cx - so / 2, so)
                val ring = (wo - wm) / ringArea
                if (ring > RING_MAX) continue
                val score = inner - ring
                if (score > bestScore) {
                    bestScore = score
                    bestX = cx
                    bestY = cy
                }
            }
        }
        if (bestX < 0) return null

        // ---- Haluskan: titik berat piksel putih di sekitar puncak (resolusi piksel penuh) ----
        val cxPx = l + bestX * STEP
        val cyPx = t + bestY * STEP
        val win = Math.round(radiusPx * 1.1f)
        var sumX = 0L
        var sumY = 0L
        var count = 0
        val y0 = max(t, cyPx - win)
        val y1 = min(b - 1, cyPx + win)
        val x0 = max(l, cxPx - win)
        val x1 = min(r - 1, cxPx + win)
        val win2 = win.toLong() * win
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = (x - cxPx).toLong()
                val dy = (y - cyPx).toLong()
                if (dx * dx + dy * dy > win2) continue
                if (isWhite(buf, y * rowStride + x * pixelStride)) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }
        if (count < 10) return null

        return CueBallState(
            x = (sumX.toFloat() / count) / width,
            y = (sumY.toFloat() / count) / height,
            radius = radiusPx / width,
            score = bestScore
        )
    }

    private fun isWhite(buf: ByteBuffer, idx: Int): Boolean {
        if (idx < 0 || idx + 2 >= buf.limit()) return false
        val r = buf.get(idx).toInt() and 0xFF
        val g = buf.get(idx + 1).toInt() and 0xFF
        val b = buf.get(idx + 2).toInt() and 0xFF
        if (r < 200 || g < 195 || b < 165) return false
        if (b > r + 6) return false
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        return (mx - mn) <= 70
    }
}
