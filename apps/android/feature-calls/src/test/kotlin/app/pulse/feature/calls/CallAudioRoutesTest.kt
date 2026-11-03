package app.pulse.feature.calls

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 15 item 4 - the earpiece/speaker and focus-loss contracts as pure
 * kernels (see CallAudioRoutes.kt). Real route switching on wired/Bluetooth
 * devices remains a device gate; the DECISION table is CI-verifiable:
 *   - every focus LOSS variant ends the call (privacy-first, no silent
 *     microphone resume), gains are ignored,
 *   - transient losses are distinguishable so a still-RINGING call can be
 *     spared its own telecom ringback churn,
 *   - API 31+ routes through the explicit requested device when present,
 *     clears the override for an earpiece without a device row, and REFUSES
 *     a speaker request with no device row instead of silently ear-piecing,
 *   - below 31 the legacy toggle is the only path.
 */
class CallAudioRoutesTest {
    @Test
    fun `every focus loss variant ends the call and gains are ignored`() {
        assertEquals(CallFocusPolicy.END_CALL, callFocusPolicyFor(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(CallFocusPolicy.END_CALL, callFocusPolicyFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(CallFocusPolicy.END_CALL, callFocusPolicyFor(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(CallFocusPolicy.IGNORE, callFocusPolicyFor(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(CallFocusPolicy.IGNORE, callFocusPolicyFor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT))
        assertEquals(CallFocusPolicy.IGNORE, callFocusPolicyFor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK))
    }

    @Test
    fun `transient losses are distinguishable for the ringing sparing`() {
        assertTrue(focusLossIsTransient(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertTrue(focusLossIsTransient(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertFalse(focusLossIsTransient(AudioManager.AUDIOFOCUS_LOSS))
        assertFalse(focusLossIsTransient(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun `api 31 plus routes through the requested device when its row exists`() {
        assertEquals(
            CallRouteStep.SET_COMMUNICATION_DEVICE,
            callSpeakerRouteStep(sdkInt = 31, wantSpeaker = true, requestedDevicePresent = true),
        )
        assertEquals(
            CallRouteStep.SET_COMMUNICATION_DEVICE,
            callSpeakerRouteStep(sdkInt = 34, wantSpeaker = false, requestedDevicePresent = true),
        )
    }

    @Test
    fun `the earpiece without a device row clears the override`() {
        assertEquals(
            CallRouteStep.CLEAR_COMMUNICATION_DEVICE,
            callSpeakerRouteStep(sdkInt = 31, wantSpeaker = false, requestedDevicePresent = false),
        )
    }

    @Test
    fun `a speaker request with no speaker row is a refusal never a silent earpiece`() {
        assertEquals(
            CallRouteStep.REFUSE,
            callSpeakerRouteStep(sdkInt = 31, wantSpeaker = true, requestedDevicePresent = false),
        )
        assertEquals(
            CallRouteStep.REFUSE,
            callSpeakerRouteStep(sdkInt = 33, wantSpeaker = true, requestedDevicePresent = false),
        )
    }

    @Test
    fun `below api 31 the legacy speaker toggle is the only path`() {
        assertEquals(
            CallRouteStep.LEGACY_SPEAKER_TOGGLE,
            callSpeakerRouteStep(sdkInt = 28, wantSpeaker = true, requestedDevicePresent = true),
        )
        assertEquals(
            CallRouteStep.LEGACY_SPEAKER_TOGGLE,
            callSpeakerRouteStep(sdkInt = 30, wantSpeaker = false, requestedDevicePresent = false),
        )
    }
}
