package com.cubeforge.stoneage.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Allocation-light BigNumber (mantissa * 10^exponent) built for idle factory scaling.
 * Normalized so mantissa is in [1.0, 10.0) whenever non-zero.
 */
data class BigNumber(val mantissa: Double, val exponent: Long) : Comparable<BigNumber> {

    companion object {
        val ZERO = BigNumber(0.0, 0L)
        val ONE = BigNumber(1.0, 0L)

        private val SUFFIXES = arrayOf(
            "", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc",
            "Ud", "Dd", "Td", "Qad", "Qid"
        )

        fun fromDouble(value: Double): BigNumber {
            if (!value.isFinite() || value == 0.0) return ZERO
            val sign = if (value < 0.0) -1.0 else 1.0
            val absVal = abs(value)
            val exp = floor(log10(absVal)).toLong()
            val man = (absVal / 10.0.pow(exp.toDouble())) * sign
            return normalize(man, exp)
        }

        fun normalize(m: Double, e: Long): BigNumber {
            if (!m.isFinite() || m == 0.0) return ZERO
            val sign = if (m < 0.0) -1.0 else 1.0
            var absM = abs(m)
            var exp = e
            if (absM >= 10.0 || absM < 1.0) {
                val shift = floor(log10(absM)).toLong()
                absM /= 10.0.pow(shift.toDouble())
                exp += shift
            }
            if (absM >= 10.0) {
                absM /= 10.0
                exp += 1L
            }
            return BigNumber(absM * sign, exp)
        }

        fun pow(base: Double, power: Int): BigNumber {
            if (power <= 0) return ONE
            if (base <= 0.0) return ZERO
            val logVal = power * log10(base)
            val exp = floor(logVal).toLong()
            val man = 10.0.pow(logVal - exp.toDouble())
            return normalize(man, exp)
        }
    }

    fun isZero(): Boolean = mantissa == 0.0

    operator fun plus(other: BigNumber): BigNumber {
        if (this.isZero()) return other
        if (other.isZero()) return this
        val diff = this.exponent - other.exponent
        return when {
            diff > 15L -> this
            diff < -15L -> other
            diff >= 0L -> normalize(this.mantissa + other.mantissa / 10.0.pow(diff.toDouble()), this.exponent)
            else -> normalize(other.mantissa + this.mantissa / 10.0.pow((-diff).toDouble()), other.exponent)
        }
    }

    operator fun minus(other: BigNumber): BigNumber {
        if (other.isZero()) return this
        return this + BigNumber(-other.mantissa, other.exponent)
    }

    operator fun times(other: BigNumber): BigNumber {
        if (this.isZero() || other.isZero()) return ZERO
        return normalize(this.mantissa * other.mantissa, this.exponent + other.exponent)
    }

    operator fun times(scalar: Double): BigNumber {
        if (this.isZero() || scalar == 0.0 || !scalar.isFinite()) return ZERO
        return this * fromDouble(scalar)
    }

    operator fun div(other: BigNumber): BigNumber {
        if (this.isZero() || other.isZero()) return ZERO
        return normalize(this.mantissa / other.mantissa, this.exponent - other.exponent)
    }

    fun clampNonNegative(): BigNumber = if (mantissa < 0.0) ZERO else this

    fun toDoubleSafe(): Double {
        if (isZero()) return 0.0
        if (exponent > 300L) return Double.MAX_VALUE
        if (exponent < -300L) return 0.0
        return mantissa * 10.0.pow(exponent.toDouble())
    }

    override fun compareTo(other: BigNumber): Int {
        if (this.isZero() && other.isZero()) return 0
        if (this.mantissa < 0.0 && other.mantissa >= 0.0) return -1
        if (this.mantissa >= 0.0 && other.mantissa < 0.0) return 1
        val sign = if (this.mantissa < 0.0) -1 else 1
        return when {
            this.exponent != other.exponent -> this.exponent.compareTo(other.exponent) * sign
            else -> this.mantissa.compareTo(other.mantissa)
        }
    }

    fun format(): String {
        if (isZero()) return "0"
        if (exponent < 3L) {
            val v = toDoubleSafe()
            return if (abs(v) >= 100.0) {
                String.format("%.0f", v)
            } else if (abs(v) >= 10.0) {
                String.format("%.1f", v)
            } else {
                String.format("%.2f", v)
            }
        }
        val group = (exponent / 3L).toInt()
        if (group < SUFFIXES.size) {
            val scaled = mantissa * 10.0.pow((exponent % 3L).toDouble())
            return String.format("%.2f%s", scaled, SUFFIXES[group])
        }
        return String.format("%.2fe%d", mantissa, exponent)
    }
}
