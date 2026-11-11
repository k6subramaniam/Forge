package com.forge.core.bodycomp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.math.sqrt

class DexaCalibrationTest {

    private val clinic = DexaScanner(DexaBrand.HOLOGIC, "Liberty Village")
    private val otherClinic = DexaScanner(DexaBrand.GE_LUNAR, "Midtown")
    private val d0: LocalDate = LocalDate.of(2026, 1, 15)

    private fun bia(date: LocalDate, fat: Double) =
        BodyFatEstimate(CompositionMethod.WRIST_BIA, date, fat, 4.5)

    private fun navy(date: LocalDate, fat: Double) =
        BodyFatEstimate(CompositionMethod.NAVY_TAPE, date, fat, 4.0)

    @Test
    fun `no scans means no calibration`() {
        val calibration = DexaCalibration.calibrate(emptyList(), listOf(bia(d0, 22.0)))
        assertSame(PersonalCalibration.NONE, calibration)
        val reading = bia(d0, 22.0)
        assertEquals(reading, calibration.apply(reading))
    }

    @Test
    fun `one scan sets the offset from nearby readings but keeps the error`() {
        // DEXA 18.0. BIA one day before (21.0) and two days after (23.0) pair:
        // mean 22.0 → offset −4.0. The reading five days out is outside the window.
        val scans = listOf(DexaScan(d0, clinic, 18.0))
        val readings = listOf(bia(d0.minusDays(1), 21.0), bia(d0.plusDays(2), 23.0), bia(d0.plusDays(5), 30.0))
        val calibration = DexaCalibration.calibrate(scans, readings)

        val biaCal = calibration.methods.getValue(CompositionMethod.WRIST_BIA)
        assertEquals(1, biaCal.anchorCount)
        assertEquals(-4.0, biaCal.offsetPp, 1e-9)
        assertNull(biaCal.calibratedStandardErrorPp, "one anchor can't measure the remaining error")

        val applied = calibration.apply(bia(d0.plusDays(30), 25.0))
        assertEquals(21.0, applied.fatPercent, 1e-9)
        assertEquals(4.5, applied.standardErrorPp, 1e-9)
        assertTrue(applied.calibrated)
    }

    @Test
    fun `three anchors measure the remaining error and narrow it`() {
        // Gaps DEXA − BIA of −4, −3, −5 → offset −4; residual SD 1.0;
        // calibrated SE = 1.0 × √(1 + 1/3) = 1.1547.
        val scans = listOf(
            DexaScan(d0, clinic, 18.0),
            DexaScan(d0.plusDays(120), clinic, 17.0),
            DexaScan(d0.plusDays(240), clinic, 16.0),
        )
        val readings = listOf(bia(d0, 22.0), bia(d0.plusDays(120), 20.0), bia(d0.plusDays(240), 21.0))
        val biaCal = DexaCalibration.calibrate(scans, readings).methods.getValue(CompositionMethod.WRIST_BIA)

        assertEquals(3, biaCal.anchorCount)
        assertEquals(-4.0, biaCal.offsetPp, 1e-9)
        assertEquals(sqrt(4.0 / 3.0), biaCal.calibratedStandardErrorPp!!, 1e-9)
    }

    @Test
    fun `scatter within a scan's window counts toward a single reading's error`() {
        // Three scans at 20%, each paired with BIA readings of 16% and 24%. Each
        // window averages to 20, so the anchor means agree perfectly, but any one
        // reading is ±4 off. The error applies to single readings, so it must
        // carry that scatter: gaps ±4 six times → variance 96/5. The anchor means
        // all agree (0, 0, 0), so the offset adds nothing: SE = √(96/5) = 4.382.
        val scanDays = (0..2).map { d0.plusDays(100L * it) }
        val scans = scanDays.map { DexaScan(it, clinic, 20.0) }
        val readings = scanDays.flatMap { listOf(bia(it.minusDays(1), 16.0), bia(it.plusDays(1), 24.0)) }
        val biaCal = DexaCalibration.calibrate(scans, readings).methods.getValue(CompositionMethod.WRIST_BIA)

        assertEquals(3, biaCal.anchorCount)
        assertEquals(0.0, biaCal.offsetPp, 1e-9)
        assertEquals(sqrt(96.0 / 5.0), biaCal.calibratedStandardErrorPp!!, 1e-9)
        assertTrue(biaCal.calibratedStandardErrorPp!! > 4.0)
    }

    @Test
    fun `many readings at one scan can't hide disagreement between scans`() {
        // Codex P2 (#6, follow-up): anchor gaps [+4], twenty × [0], [−4]. The offset
        // weights anchors equally (0), so its uncertainty must too: anchor-mean
        // variance 16 ÷ 3 anchors = 5.333. Reading scatter: 32 / 21 = 1.524.
        // SE = √(1.524 + 5.333) = 2.619. The reading-weighted pool alone gave 1.43.
        val scanDays = (0..2).map { d0.plusDays(100L * it) }
        val scans = scanDays.map { DexaScan(it, clinic, 20.0) }
        val readings = listOf(bia(scanDays[0], 16.0)) +
            (1..20).map { bia(scanDays[1], 20.0) } +
            listOf(bia(scanDays[2], 24.0))
        val biaCal = DexaCalibration.calibrate(scans, readings).methods.getValue(CompositionMethod.WRIST_BIA)
        assertEquals(0.0, biaCal.offsetPp, 1e-9)
        assertEquals(sqrt(32.0 / 21.0 + 16.0 / 3.0), biaCal.calibratedStandardErrorPp!!, 1e-9)
        assertTrue(biaCal.calibratedStandardErrorPp!! > sqrt(16.0 / 3.0))
    }

    @Test
    fun `a scan with many readings doesn't outweigh the others in the offset`() {
        // Scan A: one reading, gap −2. Scan B: four readings, each gap −6.
        // Mean of anchor means = −4 (not the −5.2 that pooling all readings gives).
        val scans = listOf(DexaScan(d0, clinic, 18.0), DexaScan(d0.plusDays(100), clinic, 18.0))
        val readings = listOf(bia(d0, 20.0)) + (0..3).map { bia(d0.plusDays(100), 24.0) }
        val biaCal = DexaCalibration.calibrate(scans, readings).methods.getValue(CompositionMethod.WRIST_BIA)
        assertEquals(-4.0, biaCal.offsetPp, 1e-9)
    }

    @Test
    fun `calibrated error never beats DXA's own repeat precision`() {
        // Perfectly consistent gaps (−2 each time) give residual SD 0, but a
        // reading can't agree with DXA more closely than DXA agrees with itself.
        val scans = (0..2).map { DexaScan(d0.plusDays(100L * it), clinic, 18.0) }
        val readings = (0..2).map { navy(d0.plusDays(100L * it), 20.0) }
        val navyCal = DexaCalibration.calibrate(scans, readings).methods.getValue(CompositionMethod.NAVY_TAPE)
        assertEquals(MethodAccuracy.DXA_REPEAT_SE_PP, navyCal.calibratedStandardErrorPp!!, 1e-12)
        // The offset is the mean over three scans, so each scan's error enters it at 1/3.
        assertEquals(scans, navyCal.anchors)
        assertEquals(scans.associateWith { 1.0 / 3 }, navyCal.anchorErrorLoadings)
    }

    @Test
    fun `anchors come only from the latest scan's machine`() {
        // Two older scans on another brand are set aside: brands differ by 1–2 pp.
        val scans = listOf(
            DexaScan(d0, otherClinic, 15.0),
            DexaScan(d0.plusDays(90), otherClinic, 15.5),
            DexaScan(d0.plusDays(180), clinic, 18.0),
        )
        val readings = listOf(bia(d0, 20.0), bia(d0.plusDays(90), 20.0), bia(d0.plusDays(180), 21.0))
        val calibration = DexaCalibration.calibrate(scans, readings)

        assertEquals(clinic, calibration.scanner)
        assertEquals(2, calibration.setAsideScans.size)
        val biaCal = calibration.methods.getValue(CompositionMethod.WRIST_BIA)
        assertEquals(1, biaCal.anchorCount)
        assertEquals(listOf(scans.last()), biaCal.anchors)
        assertEquals(-3.0, biaCal.offsetPp, 1e-9)
    }

    @Test
    fun `a method with no reading near any scan stays uncalibrated`() {
        val scans = listOf(DexaScan(d0, clinic, 18.0))
        val calibration = DexaCalibration.calibrate(scans, listOf(bia(d0, 21.0), navy(d0.plusDays(20), 19.0)))
        assertFalse(CompositionMethod.NAVY_TAPE in calibration.methods)

        val photo = BodyFatEstimate(CompositionMethod.PHOTO, d0, 24.0, 4.5)
        assertEquals(photo, calibration.apply(photo))
    }

    @Test
    fun `calibration learns from raw home readings only`() {
        val scans = listOf(DexaScan(d0, clinic, 18.0))
        assertThrows<IllegalArgumentException> {
            DexaCalibration.calibrate(scans, listOf(bia(d0, 21.0).copy(calibrated = true)))
        }
        assertThrows<IllegalArgumentException> {
            DexaCalibration.calibrate(scans, listOf(scans.single().asEstimate()))
        }
    }

    @Test
    fun `a scan reads as a DXA estimate with DXA's criterion error`() {
        val scan = DexaScan(d0, clinic, 18.0, fatMassKg = 14.4, leanSoftTissueKg = 62.6, boneMineralContentKg = 3.0)
        val estimate = scan.asEstimate()
        assertEquals(CompositionMethod.DXA, estimate.method)
        assertEquals(CompositionLevel.MOLECULAR, estimate.level)
        assertEquals(2.0 / 1.96, estimate.standardErrorPp, 1e-12)
    }
}
