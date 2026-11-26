package com.example.xrhighlight.geometry

import androidx.xr.runtime.math.Vector3
import kotlin.math.abs
import kotlin.math.acos

/** Angle between two vectors in radians. */
fun angleBetween(a: Vector3, b: Vector3): Float {
    val al = a.length
    val bl = b.length
    if (al < 1e-5f || bl < 1e-5f) return 0f
    return acos((a.dot(b) / (al * bl)).coerceIn(-1f, 1f))
}

fun median(v: List<Float>): Float = v.sorted()[v.size / 2]

/** Component-wise median. */
fun median3(v: List<Vector3>): Vector3 =
    Vector3(median(v.map { it.x }), median(v.map { it.y }), median(v.map { it.z }))

/** Solves the 3x3 system a·x = b (row-major [a]) with Cramer's rule; null if singular. */
fun solve3(a: DoubleArray, b: DoubleArray): Vector3? {
    fun det(m: DoubleArray) = m[0] * (m[4] * m[8] - m[5] * m[7]) -
        m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
    val d = det(a)
    if (abs(d) < 1e-12) return null
    fun col(k: Int) = a.copyOf().also { m -> for (i in 0..2) m[i * 3 + k] = b[i] }
    return Vector3((det(col(0)) / d).toFloat(), (det(col(1)) / d).toFloat(), (det(col(2)) / d).toFloat())
}
