package com.forge.wear.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Two firm pulses: rest is over, time for the next set (blueprint 3.1). */
@Composable
fun rememberRestOverBuzz(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        val vibrator = context.vibrator()
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1)
        return@remember { vibrator?.vibrate(pattern) }
    }
}

private fun Context.vibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        getSystemService(Vibrator::class.java)
    }
