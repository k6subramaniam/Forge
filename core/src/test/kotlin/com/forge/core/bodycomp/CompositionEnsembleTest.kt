package com.forge.core.bodycomp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.math.sqrt

class CompositionEnsembleTest {

    private val day: LocalDate = LocalDate.of(2026, 11, 10)
    private val accuracy = MethodAccuracy.PROVISIONAL

    private fun est(method: CompositionMethod, fat: Double, date: LocalDate = day) =
        BodyFatEstimate(method, date, fat, accuracy.of(method))

    @Test
    fun `independent members fuse by inverse variance`() {
        // BIA 20 (SE 4.5), photo 26 (SE 4.5), Navy 22 (SE 4.0).
        // Weights 1/20.25, 1/20.25, 1/16 → fused 22.612, SE √(1/Σw) = 2.490.
        val result = CompositionEnsemble.combine(
            day,
            listOf(
                est(CompositionMethod.WRIST_BIA, 20.0),
                est(CompositionMethod.PHOTO, 26.0),
                est(CompositionMethod.NAVY_TAPE, 22.0),
            ),
        )!!
        assertEquals(CompositionMethod.ENSEMBLE, result.estimate.method)
        assertEquals(CompositionLevel.MOLECULAR, result.estimate.level)
        assertEquals(22.6124, result.estimate.fatPercent, 1e-4)
        assertEquals(2.4902, result.estimate.standardErrorPp, 1e-4)
        assertEquals(1.0, result.members.sumOf { it.weightShare }, 1e-9)
        assertEquals(0.3876, result.members.single { it.method == CompositionMethod.NAVY_TAPE }.weightShare, 1e-4)
    }

    @Test
    fun `two equations off one tape count as one witness`() {
        // Navy 20 and RFM 24 both read the same tape. They average to 22, but the
        // error stays at one tape's 4.0, not 4/√2: a misplaced tape moves both.
        val result = CompositionEnsemble.combine(
            day,
            listOf(est(CompositionMethod.NAVY_TAPE, 20.0), est(CompositionMethod.RFM_TAPE, 24.0)),
        )!!
        assertEquals(22.0, result.estimate.fatPercent, 1e-9)
        assertEquals(4.0, result.estimate.standardErrorPp, 1e-9)
        assertTrue(CompositionCaveat.SINGLE_SOURCE in result.caveats)
        result.members.forEach { assertEquals(0.5, it.weightShare, 1e-9) }
    }

    @Test
    fun `members of one source with different errors carry their correlated error`() {
        // Navy SE 1 and RFM SE 2 (as after calibration): weights 0.8 / 0.2.
        // Fully correlated errors give 0.8·1 + 0.2·2 = 1.2, not the best member's 1.0.
        val result = CompositionEnsemble.combine(
            day,
            listOf(
                BodyFatEstimate(CompositionMethod.NAVY_TAPE, day, 20.0, 1.0),
                BodyFatEstimate(CompositionMethod.RFM_TAPE, day, 25.0, 2.0),
            ),
        )!!
        assertEquals(21.0, result.estimate.fatPercent, 1e-9)
        assertEquals(1.2, result.estimate.standardErrorPp, 1e-9)
    }

    @Test
    fun `an independent witness narrows the interval`() {
        // Tape (22, SE 4.0) + photo (26, SE 4.5): fused 23.766, SE 2.990 < 4.0.
        val result = CompositionEnsemble.combine(
            day,
            listOf(
                est(CompositionMethod.NAVY_TAPE, 20.0),
                est(CompositionMethod.RFM_TAPE, 24.0),
                est(CompositionMethod.PHOTO, 26.0),
            ),
        )!!
        assertEquals(23.7655, result.estimate.fatPercent, 1e-4)
        assertEquals(2.9896, result.estimate.standardErrorPp, 1e-4)
        assertFalse(CompositionCaveat.SINGLE_SOURCE in result.caveats)
    }

    @Test
    fun `provisional accuracies reproduce the comparison sheet's uncalibrated band`() {
        // IronLog-vs-DEXA: "~±4–5 pp uncalibrated". All four home methods,
        // uncalibrated, give a 95% half-width of 1.96 × 2.490 = 4.88 pp.
        val result = CompositionEnsemble.combine(
            day,
            CompositionMethod.entries.filter { it.isEnsembleMember }.map { est(it, 22.0) },
        )!!
        val halfWidth = BodyFatEstimate.Z_95 * result.estimate.standardErrorPp
        assertTrue(halfWidth in 4.0..5.0, "uncalibrated half-width $halfWidth should sit in ±4–5 pp")
        assertTrue(CompositionCaveat.UNCALIBRATED in result.caveats)
    }

    @Test
    fun `calibration corrects each member and narrows the result`() {
        // Three anchors on one machine. BIA reads 4 pp high (gaps −4, −3, −5 →
        // SE 1.155); Navy reads 1 pp low (gaps +1, +1.5, +0.5 → SE 0.577).
        val clinic = DexaScanner(DexaBrand.HOLOGIC, "Liberty Village")
        val scanDays = listOf(day.minusDays(240), day.minusDays(120), day.minusDays(1))
        val scans = scanDays.zip(listOf(18.0, 17.0, 16.0)).map { (d, fat) -> DexaScan(d, clinic, fat) }
        val history = scanDays.zip(listOf(22.0, 20.0, 21.0)).map { (d, fat) -> est(CompositionMethod.WRIST_BIA, fat, d) } +
            scanDays.zip(listOf(17.0, 15.5, 15.5)).map { (d, fat) -> est(CompositionMethod.NAVY_TAPE, fat, d) }
        val calibration = DexaCalibration.calibrate(scans, history)

        // Today: BIA 25 → 21, Navy 19 → 20. Weights 0.75 / 3 (shares 0.2 / 0.8),
        // fused 20.2. Both offsets came from the same three scans, so they share
        // those scans' error, σ²/3 with σ = DXA repeat precision. Treated as
        // independent, the SE would be √(1/3.75) = 0.516. Keeping the shared part
        // adds (1 − 0.2² − 0.8²)·σ²/3, for 0.543.
        val result = CompositionEnsemble.combine(
            day,
            listOf(est(CompositionMethod.WRIST_BIA, 25.0), est(CompositionMethod.NAVY_TAPE, 19.0)),
            calibration,
        )!!
        assertEquals(20.2, result.estimate.fatPercent, 1e-9)
        val dxa2 = MethodAccuracy.DXA_REPEAT_SE_PP * MethodAccuracy.DXA_REPEAT_SE_PP
        val expected = sqrt(1.0 / (0.75 + 3.0) + (1.0 - 0.04 - 0.64) * dxa2 / 3)
        assertEquals(expected, result.estimate.standardErrorPp, 1e-9)
        assertTrue(result.estimate.standardErrorPp > sqrt(1.0 / (0.75 + 3.0)))
        assertTrue(result.estimate.calibrated)
        assertTrue(result.members.all { it.calibrated })
        assertFalse(CompositionCaveat.UNCALIBRATED in result.caveats)
    }

    @Test
    fun `sources calibrated on the same scans keep those scans' shared error`() {
        // Three sources, each calibrated on the same three scans with perfectly
        // consistent gaps, so each sits at the DXA-repeat floor σ. Their offsets
        // all carry the scans' error (σ²/3 apiece, the same σ²/3 for all), so it
        // can't be averaged away: SE² = σ²/3 + (σ² − σ²/3)/3 = 5σ²/9, not σ²/3.
        val clinic = DexaScanner(DexaBrand.HOLOGIC, "Liberty Village")
        val scanDays = listOf(day.minusDays(240), day.minusDays(120), day.minusDays(1))
        val scans = scanDays.map { DexaScan(it, clinic, 18.0) }
        val history = scanDays.flatMap { d ->
            listOf(
                est(CompositionMethod.WRIST_BIA, 20.0, d),
                est(CompositionMethod.NAVY_TAPE, 17.0, d),
                est(CompositionMethod.PHOTO, 21.0, d),
            )
        }
        val calibration = DexaCalibration.calibrate(scans, history)

        val result = CompositionEnsemble.combine(
            day,
            listOf(
                est(CompositionMethod.WRIST_BIA, 22.0),
                est(CompositionMethod.NAVY_TAPE, 19.0),
                est(CompositionMethod.PHOTO, 23.0),
            ),
            calibration,
        )!!
        val sigma = MethodAccuracy.DXA_REPEAT_SE_PP
        assertEquals(20.0, result.estimate.fatPercent, 1e-9)
        assertEquals(sigma * sqrt(5.0) / 3, result.estimate.standardErrorPp, 1e-9)
        // Never below what the shared anchors alone leave.
        assertTrue(result.estimate.standardErrorPp > sigma / sqrt(3.0))
    }

    @Test
    fun `sources calibrated on different scans fuse as independent`() {
        // BIA learned from scans 1–3, photo from scans 4–6 (same machine): no
        // scan error in common, so two σ-floor sources fuse to σ/√2.
        val clinic = DexaScanner(DexaBrand.HOLOGIC, "Liberty Village")
        val scanDays = (1..6).map { day.minusDays(60L * (7 - it)) }
        val scans = scanDays.map { DexaScan(it, clinic, 18.0) }
        val history = scanDays.take(3).map { est(CompositionMethod.WRIST_BIA, 20.0, it) } +
            scanDays.drop(3).map { est(CompositionMethod.PHOTO, 21.0, it) }
        val calibration = DexaCalibration.calibrate(scans, history)

        val result = CompositionEnsemble.combine(
            day,
            listOf(est(CompositionMethod.WRIST_BIA, 22.0), est(CompositionMethod.PHOTO, 23.0)),
            calibration,
        )!!
        assertEquals(20.0, result.estimate.fatPercent, 1e-9)
        assertEquals(MethodAccuracy.DXA_REPEAT_SE_PP / sqrt(2.0), result.estimate.standardErrorPp, 1e-9)
    }

    @Test
    fun `body mass splits into fat mass and fat-free mass`() {
        val result = CompositionEnsemble.combine(
            day,
            listOf(est(CompositionMethod.NAVY_TAPE, 20.0)),
            bodyMassKg = 80.0,
        )!!
        val split = result.split!!
        assertEquals(16.0, split.fatMassKg, 1e-9)
        assertEquals(64.0, split.fatFreeMassKg, 1e-9)
        assertEquals(80.0, split.fatMassKg + split.fatFreeMassKg, 1e-9)

        assertNull(CompositionEnsemble.combine(day, listOf(est(CompositionMethod.NAVY_TAPE, 20.0)))!!.split)
    }

    @Test
    fun `watch BIA always carries its hydration and wrist caveats`() {
        val withBia = CompositionEnsemble.combine(day, listOf(est(CompositionMethod.WRIST_BIA, 21.0)))!!
        assertTrue(CompositionCaveat.BIA_HYDRATION in withBia.caveats)
        assertTrue(CompositionCaveat.WRIST_BIA_NOT_LAB_BIA in withBia.caveats)

        val tapeOnly = CompositionEnsemble.combine(day, listOf(est(CompositionMethod.NAVY_TAPE, 21.0)))!!
        assertFalse(CompositionCaveat.BIA_HYDRATION in tapeOnly.caveats)
    }

    @Test
    fun `a DXA scan is an anchor, not a member`() {
        val scan = DexaScan(day, DexaScanner(DexaBrand.HOLOGIC, "Liberty Village"), 18.0)
        assertThrows<IllegalArgumentException> {
            CompositionEnsemble.combine(day, listOf(scan.asEstimate()))
        }
    }

    @Test
    fun `nothing to fuse gives no estimate`() {
        assertNull(CompositionEnsemble.combine(day, emptyList()))
    }

    @Test
    fun `only the requested day's readings are fused`() {
        // Codex P2 (#6): yesterday's 30% must not pull today's 20% to a "25% today".
        val readings = listOf(
            est(CompositionMethod.WRIST_BIA, 30.0, day.minusDays(1)),
            est(CompositionMethod.WRIST_BIA, 20.0, day),
        )
        val today = CompositionEnsemble.combine(day, readings)!!
        assertEquals(20.0, today.estimate.fatPercent, 1e-9)
        assertEquals(1, today.members.size)

        assertNull(CompositionEnsemble.combine(day.plusDays(1), readings))
    }

    @Test
    fun `fat mass split rejects impossible inputs`() {
        assertThrows<IllegalArgumentException> { FatMassSplit.of(0.0, 20.0) }
        assertThrows<IllegalArgumentException> { FatMassSplit.of(80.0, 100.0) }
    }
}
