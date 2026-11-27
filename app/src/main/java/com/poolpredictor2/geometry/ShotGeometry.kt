package com.poolpredictor2.geometry

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mesin kalkulasi geometri tembakan 8-Ball Pool (Android Overlay).
 *
 * ATURAN MUTLAK:
 * 1. Hanya menghasilkan maksimal 2 segmen garis:
 *    - Line 1: Cue Ball -> Titik Tumbukan (Ghost Ball)
 *    - Line 2: Target Ball -> Arah Pocket
 * 2. Panjang garis dipengaruhi langsung oleh nilai cuePower (0.0f - 1.0f).
 *    - Power kecil = garis berhenti di tengah jalan sebelum mencapai target/pocket.
 *    - Power cukup = garis penuh sampai titik tumbukan / pocket.
 * 3. Semua koordinat garis di-clip ketat di dalam batas meja (TableBounds).
 */
object ShotGeometry {

    data class Vec2(val x: Float, val y: Float) {
        operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
        operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
        operator fun times(scalar: Float) = Vec2(x * scalar, y * scalar)

        fun length(): Float = hypot(x, y)

        fun normalized(): Vec2 {
            val len = length()
            return if (len > 1e-5f) Vec2(x / len, y / len) else Vec2(0f, 0f)
        }

        fun dot(other: Vec2): Float = x * other.x + y * other.y
    }

    data class LineSegment(
        val start: Vec2,
        val end: Vec2
    )

    data class TableBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val diagonal: Float get() = hypot(width, height)
    }

    data class BallState(
        val number: Int,
        val position: Vec2,
        val isPocketed: Boolean = false
    )

    data class ShotPredictionResult(
        val cueLine: LineSegment?,        // Garis 1: Cue -> Ghost Ball (warna putih)
        val ghostBallCenter: Vec2?,       // Posisi ghost ball saat tumbukan
        val targetBallNumber: Int?,       // Nomor bola target (1..15) untuk pewarnaan garis 2
        val targetLine: LineSegment?      // Garis 2: Target Ball -> Pocket (warna sesuai bola)
    )

    fun calculateShot(
        cueBall: BallState,
        objectBalls: List<BallState>,
        pockets: List<Vec2>,
        tableBounds: TableBounds,
        ballRadius: Float,
        aimAngleRad: Float,
        cuePower: Float
    ): ShotPredictionResult {
        val clampedPower = cuePower.coerceIn(0.05f, 1.0f)
        val aimDir = Vec2(cos(aimAngleRad), sin(aimAngleRad)).normalized()

        val maxEnergyDistance = tableBounds.diagonal * 1.35f * (clampedPower * clampedPower * 0.85f + clampedPower * 0.15f)

        val collisionRadius = ballRadius * 2.0f
        var closestHitDist = Float.MAX_VALUE
        var hitTargetBall: BallState? = null
        var ghostCenter: Vec2? = null

        for (ball in objectBalls) {
            if (ball.isPocketed || ball.number == 0) continue

            val toBall = ball.position - cueBall.position
            val proj = toBall.dot(aimDir)
            if (proj <= 0f) continue

            val perpDistSq = toBall.dot(toBall) - proj * proj
            val minHitDistSq = collisionRadius * collisionRadius

            if (perpDistSq < minHitDistSq) {
                val offset = sqrt(max(0f, minHitDistSq - perpDistSq))
                val impactDist = proj - offset
                if (impactDist > 0f && impactDist < closestHitDist) {
                    closestHitDist = impactDist
                    hitTargetBall = ball
                    ghostCenter = cueBall.position + (aimDir * impactDist)
                }
            }
        }

        if (hitTargetBall == null || ghostCenter == null) {
            val rawCueEnd = cueBall.position + (aimDir * maxEnergyDistance)
            val clippedCue = clipLineToBounds(cueBall.position, rawCueEnd, tableBounds)
            return ShotPredictionResult(
                cueLine = clippedCue,
                ghostBallCenter = null,
                targetBallNumber = null,
                targetLine = null
            )
        }

        if (maxEnergyDistance <= closestHitDist) {
            val shortCueEnd = cueBall.position + (aimDir * maxEnergyDistance)
            val clippedShortCue = clipLineToBounds(cueBall.position, shortCueEnd, tableBounds)
            return ShotPredictionResult(
                cueLine = clippedShortCue,
                ghostBallCenter = null,
                targetBallNumber = null,
                targetLine = null
            )
        }

        val clippedCueLine = clipLineToBounds(cueBall.position, ghostCenter, tableBounds)
        val impactNormal = (hitTargetBall.position - ghostCenter).normalized()

        val cutEfficiency = max(0.15f, aimDir.dot(impactNormal))
        val remainingDistance = (maxEnergyDistance - closestHitDist) * cutEfficiency

        val targetTravelDistance = resolvePocketSnapDistance(
            targetPos = hitTargetBall.position,
            targetDir = impactNormal,
            pockets = pockets,
            maxAllowedDist = remainingDistance,
            pocketCaptureRadius = ballRadius * 1.4f
        )

        val rawTargetEnd = hitTargetBall.position + (impactNormal * targetTravelDistance)
        val clippedTargetLine = clipLineToBounds(hitTargetBall.position, rawTargetEnd, tableBounds)

        return ShotPredictionResult(
            cueLine = clippedCueLine,
            ghostBallCenter = if (isPointInsideBounds(ghostCenter, tableBounds)) ghostCenter else null,
            targetBallNumber = hitTargetBall.number,
            targetLine = clippedTargetLine
        )
    }

    private fun resolvePocketSnapDistance(
        targetPos: Vec2,
        targetDir: Vec2,
        pockets: List<Vec2>,
        maxAllowedDist: Float,
        pocketCaptureRadius: Float
    ): Float {
        var finalDist = maxAllowedDist
        for (pocket in pockets) {
            val toPocket = pocket - targetPos
            val proj = toPocket.dot(targetDir)
            if (proj <= 0f) continue

            val perpDistSq = toPocket.dot(toPocket) - proj * proj
            if (perpDistSq <= pocketCaptureRadius * pocketCaptureRadius) {
                if (proj < finalDist) {
                    finalDist = proj
                }
            }
        }
        return finalDist
    }

    private fun isPointInsideBounds(p: Vec2, b: TableBounds): Boolean {
        return p.x in b.left..b.right && p.y in b.top..b.bottom
    }

    fun clipLineToBounds(p0: Vec2, p1: Vec2, bounds: TableBounds): LineSegment? {
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y

        var t0 = 0.0f
        var t1 = 1.0f

        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(
            p0.x - bounds.left,
            bounds.right - p0.x,
            p0.y - bounds.top,
            bounds.bottom - p0.y
        )

        for (i in 0..3) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return null
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0f) {
                    if (t > t1) return null
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return null
                    if (t < t1) t1 = t
                }
            }
        }

        if (t0 > t1) return null

        val clippedStart = Vec2(p0.x + t0 * dx, p0.y + t0 * dy)
        val clippedEnd = Vec2(p0.x + t1 * dx, p0.y + t1 * dy)
        return LineSegment(clippedStart, clippedEnd)
    }
}
