package com.poolpredictor2.vision

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Batas area kain meja pada sebuah frame, dalam koordinat RELATIF (0..1) terhadap frame.
 * Dengan koordinat relatif, hasil tetap benar walaupun resolusi capture diubah.
 * frameWidth/frameHeight dicatat hanya untuk mengetahui orientasi frame saat deteksi.
 */
data class TableRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val frameWidth: Int,
    val frameHeight: Int
) {
    val isLandscape: Boolean get() = frameWidth >= frameHeight
}

/**
 * Deteksi area kain biru meja 8 Ball Pool dari frame RGBA_8888.
 *
 * Langkah:
 * 1. Sampling grid kasar -> mask piksel "biru kain".
 * 2. Dilasi 3x3 supaya garis tipis (termasuk garis overlay sendiri) tidak memutus kain.
 * 3. Ambil komponen terhubung terbesar -> bounding box kasar.
 * 4. Validasi (rasio, ukuran, kepadatan).
 * 5. Haluskan tiap sisi dengan scanline piksel penuh + median.
 *
 * Hanya membaca buffer, tidak mengubah apa pun. Tanpa dependency tambahan.
 */
object TableDetector {

    private const val MIN_BLUE = 110
    private const val MIN_B_MINUS_R = 50
    private const val MIN_G_MINUS_R = 25

    private const val MIN_ASPECT = 1.4f
    private const val MAX_ASPECT = 2.6f
    private const val MIN_WIDTH_FRACTION = 0.40f
    private const val MIN_HEIGHT_FRACTION = 0.30f
    private const val MIN_FILL = 0.50f

    private const val SCAN_LINES = 40
    private const val MIN_EDGE_SAMPLES = 5
    private const val RUN_LENGTH = 4

    fun detect(
        buf: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): TableRegion? {
        if (width < 16 || height < 16) return null

        fun isCloth(x: Int, y: Int): Boolean {
            val idx = y * rowStride + x * pixelStride
            if (idx < 0 || idx + 2 >= buf.limit()) return false
            val r = buf.get(idx).toInt() and 0xFF
            val g = buf.get(idx + 1).toInt() and 0xFF
            val b = buf.get(idx + 2).toInt() and 0xFF
            return b >= MIN_BLUE &&
                (b - r) >= MIN_B_MINUS_R &&
                (g - r) >= MIN_G_MINUS_R &&
                b >= g
        }

        fun runH(x: Int, y: Int, dir: Int): Boolean {
            for (k in 0 until RUN_LENGTH) {
                if (!isCloth(x + dir * k, y)) return false
            }
            return true
        }

        fun runV(x: Int, y: Int, dir: Int): Boolean {
            for (k in 0 until RUN_LENGTH) {
                if (!isCloth(x, y + dir * k)) return false
            }
            return true
        }

        // ---- 1. Mask grid kasar ----
        val step = max(2, width / 300)
        val gw = width / step
        val gh = height / step
        val mask = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                mask[gy * gw + gx] = isCloth(gx * step, gy * step)
            }
        }

        // ---- 2. Dilasi 3x3 ----
        val dil = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                if (!mask[gy * gw + gx]) continue
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val ny = gy + dy
                        val nx = gx + dx
                        if (ny in 0 until gh && nx in 0 until gw) dil[ny * gw + nx] = true
                    }
                }
            }
        }

        // ---- 3. Komponen terhubung terbesar (8 arah, berdasarkan mask yang sudah didilasi) ----
        val visited = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        var bestCount = 0
        var bestMinX = 0
        var bestMaxX = 0
        var bestMinY = 0
        var bestMaxY = 0

        for (start in 0 until gw * gh) {
            if (!dil[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var count = 0
            var minX = gw
            var maxX = -1
            var minY = gh
            var maxY = -1
            while (sp > 0) {
                val cell = stack[--sp]
                val cy = cell / gw
                val cx = cell % gw
                if (mask[cell]) {
                    count++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                }
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val ny = cy + dy
                        val nx = cx + dx
                        if (ny < 0 || ny >= gh || nx < 0 || nx >= gw) continue
                        val n = ny * gw + nx
                        if (dil[n] && !visited[n]) {
                            visited[n] = true
                            stack[sp++] = n
                        }
                    }
                }
            }
            if (count > bestCount) {
                bestCount = count
                bestMinX = minX
                bestMaxX = maxX
                bestMinY = minY
                bestMaxY = maxY
            }
        }
        if (bestCount == 0) return null

        // ---- 4. Validasi ----
        val cellsW = bestMaxX - bestMinX + 1
        val cellsH = bestMaxY - bestMinY + 1
        val coarseW = cellsW * step
        val coarseH = cellsH * step
        val aspect = coarseW.toFloat() / coarseH.toFloat()
        val fill = bestCount.toFloat() / (cellsW * cellsH).toFloat()
        if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) return null
        if (coarseW < MIN_WIDTH_FRACTION * width) return null
        if (coarseH < MIN_HEIGHT_FRACTION * height) return null
        if (fill < MIN_FILL) return null

        val coarseL = bestMinX * step
        val coarseR = (bestMaxX + 1) * step
        val coarseT = bestMinY * step
        val coarseB = (bestMaxY + 1) * step

        // ---- 5. Haluskan tiap sisi (scanline piksel penuh, median) ----
        val win = 2 * step
        val lefts = ArrayList<Int>()
        val rights = ArrayList<Int>()
        val tops = ArrayList<Int>()
        val bottoms = ArrayList<Int>()

        for (i in 0 until SCAN_LINES) {
            val t = 0.2f + 0.6f * i / (SCAN_LINES - 1)

            val y = (coarseT + (coarseB - coarseT) * t).toInt().coerceIn(0, height - 1)
            // sisi kiri: dari luar ke dalam
            var x = max(0, coarseL - win)
            val xEnd = min(width - RUN_LENGTH, coarseL + win)
            while (x < xEnd) {
                if (runH(x, y, 1)) { lefts.add(x); break }
                x++
            }
            // sisi kanan: dari luar ke dalam
            x = min(width - 1, coarseR + win)
            val xMin = max(RUN_LENGTH, coarseR - win)
            while (x > xMin) {
                if (runH(x, y, -1)) { rights.add(x + 1); break }
                x--
            }

            val xx = (coarseL + (coarseR - coarseL) * t).toInt().coerceIn(0, width - 1)
            // sisi atas
            var yy = max(0, coarseT - win)
            val yEnd = min(height - RUN_LENGTH, coarseT + win)
            while (yy < yEnd) {
                if (runV(xx, yy, 1)) { tops.add(yy); break }
                yy++
            }
            // sisi bawah
            yy = min(height - 1, coarseB + win)
            val yMin = max(RUN_LENGTH, coarseB - win)
            while (yy > yMin) {
                if (runV(xx, yy, -1)) { bottoms.add(yy + 1); break }
                yy--
            }
        }

        val l = median(lefts) ?: coarseL
        val r = median(rights) ?: coarseR
        val t = median(tops) ?: coarseT
        val b = median(bottoms) ?: coarseB
        if (r <= l || b <= t) return null

        return TableRegion(
            left = l.toFloat() / width,
            top = t.toFloat() / height,
            right = r.toFloat() / width,
            bottom = b.toFloat() / height,
            frameWidth = width,
            frameHeight = height
        )
    }

    private fun median(values: MutableList<Int>): Int? {
        if (values.size < MIN_EDGE_SAMPLES) return null
        values.sort()
        return values[values.size / 2]
    }
}
