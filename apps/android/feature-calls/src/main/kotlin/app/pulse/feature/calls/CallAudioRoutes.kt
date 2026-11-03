package app.pulse.feature.calls

/**
 * Section 15 item 4 - the pure route/focus decision kernels behind
 * [CallAudioManager], extracted so the earpiece/speaker and focus-loss
 * contracts are JVM-testable instead of device-only:
 *   - API 31+ routes through an EXPLICIT communication device when the
 *     requested route's device row exists (speaker OR earpiece),
 *   - the earpiece is the OS default: requesting it WITHOUT a device row
 *     CLEARS the override rather than refusing,
 *   - requesting the speaker WITHOUT a device row is a refusal, never a
 *     silent fallback to the earpiece,
 *   - below API 31 the legacy speaker toggle is the only path,
 *   - every focus LOSS variant (permanent, transient, can-duck) ends the
 *     call by policy; the caller distinguishes transient losses so a
 *     still-RINGING call is spared its own stack's ringback churn.
 *
 * The Int focus codes are AudioManager's wire values
 * (-1 AUDIOFOCUS_LOSS, -2 LOSS_TRANSIENT, -3 LOSS_TRANSIENT_CAN_DUCK,
 * +1 GAIN, ...) passed untyped so the kernel has no android dependency.
 */
internal enum class CallRouteStep { SET_COMMUNICATION_DEVICE, CLEAR_COMMUNICATION_DEVICE, LEGACY_SPEAKER_TOGGLE, REFUSE }

internal enum class CallFocusPolicy { IGNORE, END_CALL }

/** Focus loss codes that end the call. Anything else (gains) is ignored. */
internal fun callFocusPolicyFor(focusChange: Int): CallFocusPolicy =
    when (focusChange) {
        -1, -2, -3 -> CallFocusPolicy.END_CALL
        else -> CallFocusPolicy.IGNORE
    }

/** A transient loss is one the call may survive while it is still RINGING. */
internal fun focusLossIsTransient(focusChange: Int): Boolean = focusChange == -2 || focusChange == -3

/**
 * Which device step the manager must take for a speaker request.
 * `sdkInt` is Build.VERSION.SDK_INT; `requestedDevicePresent` is whether the
 * communication-device list carries the row for the REQUESTED route type
 * (speaker row when asking for the speaker, earpiece row when asking for
 * the earpiece).
 */
internal fun callSpeakerRouteStep(sdkInt: Int, wantSpeaker: Boolean, requestedDevicePresent: Boolean): CallRouteStep = when {
    sdkInt < 31 -> CallRouteStep.LEGACY_SPEAKER_TOGGLE
    requestedDevicePresent -> CallRouteStep.SET_COMMUNICATION_DEVICE
    wantSpeaker -> CallRouteStep.REFUSE
    else -> CallRouteStep.CLEAR_COMMUNICATION_DEVICE
}
