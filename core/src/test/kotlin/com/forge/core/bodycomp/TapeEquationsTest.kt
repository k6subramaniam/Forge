package com.forge.core.bodycomp

import com.forge.core.model.Sex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.log10

class TapeEquationsTest {

    private val day: LocalDate = LocalDate.of(2026, 11, 10)

    @Test
    fun `navy equation for a man matches the hand calculation`() {
        // 495 / (1.0324 − 0.19077·log10(90 − 38) + 0.15456·log10(180)) − 450 = 19.812
        val bf = TapeEquations.navyBodyFatPercent(Sex.MALE, heightCm = 180.0, neckCm = 38.0, waistCm = 90.0)
        assertEquals(19.812, bf, 0.001)
    }

    @Test
    fun `navy equation for a woman matches the hand calculation`() {
        // 495 / (1.29579 − 0.35004·log10(72 + 98 − 32) + 0.22100·log10(165)) − 450 = 27.426
        val bf = TapeEquations.navyBodyFatPercent(
            Sex.FEMALE, heightCm = 165.0, neckCm = 32.0, waistCm = 72.0, hipCm = 98.0,
        )
        assertEquals(27.426, bf, 0.001)
    }

    @Test
    fun `metric navy form agrees with the original inch form`() {
        // Guards the metric constants against typos: Hodgdon & Beckett's inch
        // equations, fed the same bodies in inches, land within half a point.
        val inch = 2.54
        fun maleInch(waist: Double, neck: Double, height: Double) =
            86.010 * log10(waist - neck) - 70.041 * log10(height) + 36.76
        fun femaleInch(waist: Double, hip: Double, neck: Double, height: Double) =
            163.205 * log10(waist + hip - neck) - 97.684 * log10(height) - 78.387

        listOf(Triple(90.0, 38.0, 180.0), Triple(100.0, 40.0, 178.0), Triple(80.0, 36.0, 170.0)).forEach { (w, n, h) ->
            val metric = TapeEquations.navyBodyFatPercent(Sex.MALE, h, n, w)
            val imperial = maleInch(w / inch, n / inch, h / inch)
            assertTrue(abs(metric - imperial) < 0.5, "men $w/$n/$h: metric $metric vs inch $imperial")
        }
        listOf(listOf(72.0, 98.0, 32.0, 165.0), listOf(80.0, 104.0, 34.0, 168.0)).forEach { (w, hip, n, h) ->
            val metric = TapeEquations.navyBodyFatPercent(Sex.FEMALE, h, n, w, hip)
            val imperial = femaleInch(w / inch, hip / inch, n / inch, h / inch)
            assertTrue(abs(metric - imperial) < 0.5, "women $w/$hip/$n/$h: metric $metric vs inch $imperial")
        }
    }

    @Test
    fun `relative fat mass matches the published formula`() {
        // Men: 64 − 20 × 180/90 = 24. Women: 64 − 20 × 165/80 + 12 = 34.75.
        assertEquals(24.0, TapeEquations.rfmBodyFatPercent(Sex.MALE, 180.0, 90.0), 1e-9)
        assertEquals(34.75, TapeEquations.rfmBodyFatPercent(Sex.FEMALE, 165.0, 80.0), 1e-9)
    }

    @Test
    fun `a full tape session gives both members, named and with their accuracy`() {
        val tape = TapeMeasurement(day, neckCm = 38.0, waistAtNavelCm = 90.0, waistAtIliacCrestCm = 90.0)
        val estimates = TapeEquations.estimates(tape, Sex.MALE, heightCm = 180.0)

        assertEquals(listOf(CompositionMethod.NAVY_TAPE, CompositionMethod.RFM_TAPE), estimates.map { it.method })
        assertEquals(19.812, estimates[0].fatPercent, 0.001)
        assertEquals(24.0, estimates[1].fatPercent, 1e-9)
        estimates.forEach {
            assertEquals(CompositionLevel.MOLECULAR, it.level)
            assertEquals(MethodAccuracy.PROVISIONAL.of(it.method), it.standardErrorPp)
            assertEquals(day, it.date)
        }
    }

    @Test
    fun `a site an equation was not derived on is never substituted`() {
        // A man's tape with the natural waist (the women's Navy site) but no
        // navel abdomen: Navy is not computed from the wrong site.
        val tape = TapeMeasurement(day, neckCm = 38.0, naturalWaistCm = 86.0, waistAtIliacCrestCm = 90.0)
        val methods = TapeEquations.estimates(tape, Sex.MALE, heightCm = 180.0).map { it.method }
        assertEquals(listOf(CompositionMethod.RFM_TAPE), methods)
    }

    @Test
    fun `navy for women needs the hip`() {
        val noHip = TapeMeasurement(day, neckCm = 32.0, naturalWaistCm = 72.0)
        assertTrue(TapeEquations.estimates(noHip, Sex.FEMALE, heightCm = 165.0).isEmpty())

        val withHip = noHip.copy(hipCm = 98.0)
        val methods = TapeEquations.estimates(withHip, Sex.FEMALE, heightCm = 165.0).map { it.method }
        assertEquals(listOf(CompositionMethod.NAVY_TAPE), methods)
    }

    @Test
    fun `navy rejects a waist no larger than the neck`() {
        assertThrows<IllegalArgumentException> {
            TapeEquations.navyBodyFatPercent(Sex.MALE, heightCm = 180.0, neckCm = 40.0, waistCm = 40.0)
        }
    }

    @Test
    fun `an implausible result is dropped, not clamped`() {
        // RFM with a 50 cm waist at 180 cm: 64 − 20 × 3.6 = −8. No estimate.
        val tape = TapeMeasurement(day, waistAtIliacCrestCm = 50.0)
        assertTrue(TapeEquations.estimates(tape, Sex.MALE, heightCm = 180.0).isEmpty())
    }
}
