package com.xelth.eckwms_movfast.trips

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Track estimation — the least-action interpolation from .eck/TRACK_ESTIMATION.md.
 *
 * A constant-velocity Kalman filter with innovation gating, followed by an
 * RTS (Rauch–Tung–Striebel) backward smoothing pass. This IS the closed-form
 * minimizer of  Σ residual²/σ²  +  ∫|a|² dt  under the "a car doesn't teleport"
 * dynamics — no training, no map, O(n), ~10 ms per trip on the PDA.
 *
 * The innovation gate rejects observations that are physically impossible given
 * the dynamics (Mahalanobis² > gate): mislocated cell towers (the 163-km Kassel
 * jump of 2026-07-06 had m² ≈ 28 000 against a gate of 9) drop out by physics
 * alone, no tower-quality heuristics needed on-device.
 *
 * All inputs are LOCATED observations (fused/gps fixes, or cells resolved via
 * the on-device tower cache) with an honest per-point σ in meters: fused →
 * accuracyM, cell → tower range. The relative σ automatically makes a 2 m GPS
 * fix dominate a 1500 m tower guess — one code path for both.
 *
 * Golden-tested against the Python prototype on the real 2026-07-06 tracks
 * (Eschborn→Karlsruhe 154.4 km / Karlsruhe→Speyer 57.3 km).
 */
object TrackEstimator {
    const val VERSION = "rts1"

    /** One located observation. [sigmaM] = 1-σ position uncertainty in meters. */
    data class Obs(val ts: Long, val lat: Double, val lng: Double, val sigmaM: Double)

    /**
     * Smoothing result. [path] holds one smoothed (lat, lng) per input obs, in
     * input order — rejected observations still get a smoothed position (the
     * dynamics coast through them). [distanceKm] is the length of the smoothed
     * polyline; [rejectedCount] is how many observations the gate refused.
     */
    data class Result(
        val path: List<LatLng>,
        val distanceKm: Double,
        val rejectedCount: Int,
        val version: String = VERSION
    )

    data class LatLng(val lat: Double, val lng: Double)

    // Process noise: how hard a car may accelerate (m/s², 1-σ). 2 m/s² covers
    // normal driving; hard braking briefly exceeds it, which only means the
    // filter trusts the measurements a bit more during the maneuver.
    private const val SIGMA_A = 2.0

    // Innovation gate on the 2-D Mahalanobis distance². chi²(2 dof, 99%) ≈ 9.2:
    // an observation needing a >3-σ combined miss is not a real reception.
    private const val GATE = 9.0

    /**
     * Run filter + smoother. Returns null when there are fewer than 3 usable
     * observations (nothing meaningful to smooth). Pure function, no Android
     * dependencies — unit-testable on the JVM.
     */
    fun smooth(obs: List<Obs>, sigmaA: Double = SIGMA_A, gate: Double = GATE): Result? {
        if (obs.size < 3) return null
        val n = obs.size

        // Local ENU meters around the first observation (flat-earth is fine for
        // a single trip; the smoothing error from projection is centimeters).
        val lat0 = obs[0].lat
        val lng0 = obs[0].lng
        val mLat = 111_320.0
        val mLng = 111_320.0 * cos(Math.toRadians(lat0))

        fun toXY(la: Double, lo: Double) = doubleArrayOf((lo - lng0) * mLng, (la - lat0) * mLat)
        fun toLL(x: Double, y: Double) = LatLng(lat0 + y / mLat, lng0 + x / mLng)

        // State: [px, py, vx, vy]. H picks the position.
        val xs = ArrayList<DoubleArray>(n)      // filtered state
        val Ps = ArrayList<Array<DoubleArray>>(n)
        val xps = ArrayList<DoubleArray>(n)     // predicted state (pre-update)
        val Pps = ArrayList<Array<DoubleArray>>(n)
        val Fs = ArrayList<Array<DoubleArray>>(n)

        var x = DoubleArray(4)
        val xy0 = toXY(obs[0].lat, obs[0].lng)
        x[0] = xy0[0]; x[1] = xy0[1]
        var P = diag4(sq(obs[0].sigmaM), sq(obs[0].sigmaM), sq(40.0), sq(40.0))

        var rejected = 0
        var tPrev = obs[0].ts

        for (i in 0 until n) {
            val o = obs[i]
            val dt = if (i == 0) 0.0 else ((o.ts - tPrev).coerceAtLeast(1L)) / 1000.0

            val F = eye4()
            F[0][2] = dt; F[1][3] = dt
            val q = sigmaA * sigmaA
            val dt2 = dt * dt
            val Q = arrayOf(
                doubleArrayOf(dt2 * dt2 / 4 * q, 0.0, dt2 * dt / 2 * q, 0.0),
                doubleArrayOf(0.0, dt2 * dt2 / 4 * q, 0.0, dt2 * dt / 2 * q),
                doubleArrayOf(dt2 * dt / 2 * q, 0.0, dt2 * q, 0.0),
                doubleArrayOf(0.0, dt2 * dt / 2 * q, 0.0, dt2 * q)
            )

            val xp = mulMV(F, x)
            val Pp = add(mulMM(mulMM(F, P), transpose(F)), Q)

            val z = toXY(o.lat, o.lng)
            val r = sq(o.sigmaM)
            // S = H Pp Hᵀ + R  — the top-left 2×2 of Pp plus R.
            val s00 = Pp[0][0] + r; val s01 = Pp[0][1]
            val s10 = Pp[1][0];     val s11 = Pp[1][1] + r
            val det = s00 * s11 - s01 * s10
            val i00 = s11 / det; val i01 = -s01 / det
            val i10 = -s10 / det; val i11 = s00 / det
            val dy0 = z[0] - xp[0]
            val dy1 = z[1] - xp[1]
            val m2 = dy0 * (i00 * dy0 + i01 * dy1) + dy1 * (i10 * dy0 + i11 * dy1)

            if (i > 0 && m2 > gate) {
                // Physically impossible reception — coast on the dynamics.
                rejected++
                x = xp; P = Pp
            } else {
                // K = Pp Hᵀ S⁻¹  (4×2): columns 0,1 of Pp times S⁻¹.
                val K = Array(4) { row ->
                    doubleArrayOf(
                        Pp[row][0] * i00 + Pp[row][1] * i10,
                        Pp[row][0] * i01 + Pp[row][1] * i11
                    )
                }
                x = DoubleArray(4) { row -> xp[row] + K[row][0] * dy0 + K[row][1] * dy1 }
                // P = (I − K H) Pp ; K H only touches columns 0,1 of the identity.
                val IKH = eye4()
                for (row in 0 until 4) {
                    IKH[row][0] -= K[row][0]
                    IKH[row][1] -= K[row][1]
                }
                P = mulMM(IKH, Pp)
            }

            xps.add(xp); Pps.add(Pp); xs.add(x); Ps.add(P); Fs.add(F)
            tPrev = o.ts
        }

        // RTS backward pass: xˢ(i) = x(i) + C (xˢ(i+1) − xp(i+1)),
        // C = P(i) F(i+1)ᵀ Pp(i+1)⁻¹.
        val xsm = arrayOfNulls<DoubleArray>(n)
        xsm[n - 1] = xs[n - 1]
        for (i in n - 2 downTo 0) {
            val ppInv = inv4(Pps[i + 1])
            if (ppInv == null) {
                xsm[i] = xs[i] // singular covariance — keep the filtered estimate
                continue
            }
            val C = mulMM(mulMM(Ps[i], transpose(Fs[i + 1])), ppInv)
            val d = DoubleArray(4) { r -> xsm[i + 1]!![r] - xps[i + 1][r] }
            xsm[i] = DoubleArray(4) { r -> xs[i][r] + (0 until 4).sumOf { c -> C[r][c] * d[c] } }
        }

        val path = xsm.map { v -> toLL(v!![0], v[1]) }
        var dist = 0.0
        for (i in 1 until n) dist += haversineKm(path[i - 1], path[i])
        return Result(path, dist, rejected)
    }

    /** Douglas-Peucker-free cheap decimation for upload payloads: keep every
     *  point that moves ≥ [minStepM] from the last kept one (plus endpoints). */
    fun decimate(path: List<LatLng>, minStepM: Double = 30.0): List<LatLng> {
        if (path.size <= 2) return path
        val out = ArrayList<LatLng>(path.size / 2)
        out.add(path.first())
        for (p in path.subList(1, path.size - 1)) {
            if (haversineKm(out.last(), p) * 1000.0 >= minStepM) out.add(p)
        }
        out.add(path.last())
        return out
    }

    fun haversineKm(a: LatLng, b: LatLng): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    // ── tiny fixed-size matrix helpers (no dependencies) ─────────────────────

    private fun sq(v: Double) = v * v

    private fun eye4() = Array(4) { r -> DoubleArray(4) { c -> if (r == c) 1.0 else 0.0 } }

    private fun diag4(a: Double, b: Double, c: Double, d: Double): Array<DoubleArray> {
        val m = Array(4) { DoubleArray(4) }
        m[0][0] = a; m[1][1] = b; m[2][2] = c; m[3][3] = d
        return m
    }

    private fun transpose(m: Array<DoubleArray>) =
        Array(4) { r -> DoubleArray(4) { c -> m[c][r] } }

    private fun mulMM(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(4) { r ->
            DoubleArray(4) { c ->
                a[r][0] * b[0][c] + a[r][1] * b[1][c] + a[r][2] * b[2][c] + a[r][3] * b[3][c]
            }
        }

    private fun mulMV(a: Array<DoubleArray>, v: DoubleArray): DoubleArray =
        DoubleArray(4) { r -> a[r][0] * v[0] + a[r][1] * v[1] + a[r][2] * v[2] + a[r][3] * v[3] }

    private fun add(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(4) { r -> DoubleArray(4) { c -> a[r][c] + b[r][c] } }

    /** 4×4 inverse via Gauss-Jordan with partial pivoting; null if singular. */
    private fun inv4(m: Array<DoubleArray>): Array<DoubleArray>? {
        val a = Array(4) { r -> m[r].copyOf() }
        val inv = eye4()
        for (col in 0 until 4) {
            var pivot = col
            for (r in col + 1 until 4) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[pivot][col])) pivot = r
            if (kotlin.math.abs(a[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val t = a[pivot]; a[pivot] = a[col]; a[col] = t
                val ti = inv[pivot]; inv[pivot] = inv[col]; inv[col] = ti
            }
            val d = a[col][col]
            for (c in 0 until 4) { a[col][c] /= d; inv[col][c] /= d }
            for (r in 0 until 4) {
                if (r == col) continue
                val f = a[r][col]
                if (f == 0.0) continue
                for (c in 0 until 4) { a[r][c] -= f * a[col][c]; inv[r][c] -= f * inv[col][c] }
            }
        }
        return inv
    }
}
