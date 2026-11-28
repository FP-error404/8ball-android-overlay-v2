package com.poolpredictor2.vision

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Posisi bola putih dalam koordinat RELATIF frame (0..1).
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
 * 1. Skor "cakram putih" memakai integral image: bagian dalam bola padat putih-krem,
 *    cincin di sekelilingnya nyaris tidak putih. Piksel kebiruan tidak dihitung, jadi
 *    bola/garis buatan overlay sendiri tidak ikut terdeteksi.
 * 2. Kandidat dipilih dengan non-max suppression, lalu DIVERIFIKASI: bola putih asli
 *    tidak punya warna jenuh di dalamnya, sedangkan bola belang 9-15 punya pita berwarna.
 *    Ini mencegah bola belang yang area putihnya besar dianggap bola putih.
 * 3. Jika tidak ada kandidat yang lolos (misalnya bola putih masuk lubang), hasilnya null.
 *
 * Hanya membaca buffer; tidak mengubah apa pun.
 */
object CueBallDetector {

    // Radius bagian putih bola dan radius visual bola, relatif terhadap lebar meja
    private const val RADIUS_TABLE_FRACTION = 0.0122f
    private const val BALL_TABLE_FRACTION = 0.0145f

    private const val INNER_MIN = 0.65f
    private const val RING_MAX = 0.30f
    private const val COLORED_MAX = 0.06f
    private const val COLOR_SPREAD_MIN = 85
    private const val MAX_VERIFIED = 8
    private const val STEP = 2

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

        val tableW = (r - l).toFloat()
        val whiteRadiusPx = RADIUS_TABLE_FRACTION * tableW
        val ballRadiusPx = BALL_TABLE_FRACTION * tableW
        val si = max(2, Math.round(1.4f * whiteRadiusPx / STEP))
        val sm = max(si + 2, Math.round(2.2f * whiteRadiusPx / STEP))
        val so = max(sm + 2, Math.round(3.4f * whiteRadiusPx / STEP))
        val margin = so / 2
        if (gw <= so || gh <= so) return null

        val innerArea = (si * si).toFloat()
        val ringArea = (so * so - sm * sm).toFloat()

        // ---- Kumpulkan kandidat yang lolos skor cakram ----
        val candCx = ArrayList<Int>()
        val candCy = ArrayList<Int>()
        val candScore = ArrayList<Float>()
        for (cy in margin until gh - margin) {
            for (cx in margin until gw - margin) {
                val inner = boxSum(cy - si / 2, cx - si / 2, si) / innerArea
                if (inner < INNER_MIN) continue
                val wm = boxSum(cy - sm / 2, cx - sm / 2, sm)
                val wo = boxSum(cy - so / 2, cx - so / 2, so)
                val ring = (wo - wm) / ringArea
                if (ring > RING_MAX) continue
                candCx.add(cx)
                candCy.add(cy)
                candScore.add(inner - ring)
            }
        }
        if (candScore.isEmpty()) return null

        val order = candScore.indices.sortedByDescending { candScore[it] }

        // ---- Non-max suppression + verifikasi warna ----
        val minSep = 2f * ballRadiusPx
        val minSep2 = minSep * minSep
        val keptX = ArrayList<Int>()
        val keptY = ArrayList<Int>()
        val verifyR = Math.round(0.85f * ballRadiusPx)

        var bestRank = -1f
        var bestPx = -1
        var bestPy = -1
        var bestScore = 0f

        for (i in order) {
            val px = l + candCx[i] * STEP
            val py = t + candCy[i] * STEP
            var near = false
            for (k in keptX.indices) {
                val dx = (px - keptX[k]).toFloat()
                val dy = (py - keptY[k]).toFloat()
                if (dx * dx + dy * dy < minSep2) { near = true; break }
            }
            if (near) continue
            keptX.add(px)
            keptY.add(py)

            val coloredFraction = coloredFraction(
                buf, width, height, rowStride, pixelStride, px, py, verifyR
            )
            if (coloredFraction >= 0f && coloredFraction <= COLORED_MAX) {
                val rank = candScore[i] - 2f * coloredFraction
                if (rank > bestRank) {
                    bestRank = rank
                    bestPx = px
                    bestPy = py
                    bestScore = candScore[i]
                }
            }
            if (keptX.size >= MAX_VERIFIED) break
        }
        if (bestPx < 0) return null

        // ---- Haluskan: titik berat piksel putih di sekitar kandidat (piksel penuh) ----
        val win = Math.round(whiteRadiusPx * 1.1f)
        var sumX = 0L
        var sumY = 0L
        var count = 0
        val y0 = max(t, bestPy - win)
        val y1 = min(b - 1, bestPy + win)
        val x0 = max(l, bestPx - win)
        val x1 = min(r - 1, bestPx + win)
        val win2 = win.toLong() * win
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = (x - bestPx).toLong()
                val dy = (y - bestPy).toLong()
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
            radius = whiteRadiusPx / width,
            score = bestScore
        )
    }

    /** Pecahan piksel berwarna jenuh di dalam cakram. -1 jika cakram keluar dari frame. */
    private fun coloredFraction(
        buf: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        cx: Int,
        cy: Int,
        rd: Int
    ): Float {
        if (cx - rd < 0 || cy - rd < 0 || cx + rd >= width || cy + rd >= height) return -1f
        var total = 0
        var colored = 0
        val rd2 = rd * rd
        for (dy in -rd..rd) {
            for (dx in -rd..rd) {
                if (dx * dx + dy * dy > rd2) continue
                val idx = (cy + dy) * rowStride + (cx + dx) * pixelStride
                if (idx < 0 || idx + 2 >= buf.limit()) continue
                val rr = buf.get(idx).toInt() and 0xFF
                val gg = buf.get(idx + 1).toInt() and 0xFF
                val bb = buf.get(idx + 2).toInt() and 0xFF
                total++
                if (max(rr, max(gg, bb)) - min(rr, min(gg, bb)) > COLOR_SPREAD_MIN) colored++
            }
        }
        return if (total == 0) -1f else colored.toFloat() / total
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
