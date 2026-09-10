package app.pulse.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.ui.PulseMotion
import app.pulse.ui.PulsePalette
import app.pulse.ui.pulsePress
import app.pulse.ui.rememberPressSource

/**
 * LiveUpdate surfaces — same emerald design language as the rest of Pulse,
 * Compose-native mechanics (AnimatedVisibility springs, pulsePress, haptics).
 * Two shapes:
 *  - [UpdaterBanner] — slim strip above the Chats list; renders NOTHING when
 *    there is no update (GS-style pill).
 *  - [UpdaterDetail] — the Profile "App updates" card body with progress.
 */
@Composable
fun UpdaterBanner(modifier: Modifier = Modifier) {
    val state by LiveUpdater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val interaction = rememberPressSource()

    AnimatedVisibility(
        visible = state !is UpdateState.Idle,
        enter = expandVertically(animationSpec = PulseMotion.soft()) + fadeIn(animationSpec = PulseMotion.soft()),
        exit = shrinkVertically(animationSpec = PulseMotion.snappy()) + fadeOut(animationSpec = PulseMotion.snappy()),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .pulsePress(interaction)
                .clickable(interactionSource = interaction, indication = null) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    LiveUpdater.beginInstallFlow(context)
                },
        ) {
            when (val s = state) {
                is UpdateState.Available -> Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = PulsePalette.Emerald,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Pulse ${s.versionName} is ready",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            s.notes ?: "Tap to download and install",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Text(
                        "GET",
                        color = PulsePalette.Emerald,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
                is UpdateState.Downloading -> Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Downloading update — ${s.percent}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "resumable",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    UpdateProgressBar(s.percent)
                }
                is UpdateState.Ready -> Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = PulsePalette.Emerald,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Opening installer…", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
                is UpdateState.Installing -> Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = PulsePalette.Emerald,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Installing — confirm the system dialog",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
                is UpdateState.Failed -> Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = PulsePalette.Amber,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        s.reason,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (s is UpdateState.Failed && needsPermission(context)) "OPEN SETTINGS" else "RESUME",
                        color = PulsePalette.Emerald,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
                UpdateState.Idle -> Unit
            }
        }
    }
}

/** Profile "App updates" card — installed version, live state, progress. */
@Composable
fun UpdaterDetail(modifier: Modifier = Modifier) {
    val state by LiveUpdater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val interaction = rememberPressSource()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Pulse ${LiveUpdater.installedVersionLabel(context) ?: "?"}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "versionCode ${LiveUpdater.installedVersionCode(context)} · LiveUpdate channel (repo CDN)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "CHECK NOW",
                color = PulsePalette.Emerald,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        LiveUpdater.beginInstallFlow(context)
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        when (val s = state) {
            is UpdateState.Available -> Text(
                "v${s.versionName} available — tap CHECK NOW to download",
                style = MaterialTheme.typography.bodySmall,
                color = PulsePalette.Emerald,
            )
            is UpdateState.Downloading -> {
                UpdateProgressBar(s.percent)
                Text(
                    "${s.percent}% — interruptions resume from the exact byte",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is UpdateState.Ready -> Text(
                "Ready — the system installer is opening",
                style = MaterialTheme.typography.bodySmall,
                color = PulsePalette.Emerald,
            )
            is UpdateState.Installing -> Text(
                "Handed to the system installer — confirm to finish",
                style = MaterialTheme.typography.bodySmall,
                color = PulsePalette.Emerald,
            )
            is UpdateState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = PulsePalette.Amber,
                    modifier = Modifier.weight(1f),
                )
                if (needsPermission(context)) {
                    Text(
                        "OPEN SETTINGS",
                        color = PulsePalette.Emerald,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { LiveUpdater.openInstallPermissionSettings(context) },
                    )
                }
            }
            UpdateState.Idle -> Text(
                "You are on the latest release",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateProgressBar(percent: Int) {
    val animated by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = PulseMotion.snappy(),
        label = "updateProgress",
    )
    LinearProgressIndicator(
        progress = { animated },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)),
        color = PulsePalette.Emerald,
        trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    )
}

private fun needsPermission(context: android.content.Context): Boolean = runCatching {
    !context.packageManager.canRequestPackageInstalls()
}.getOrDefault(false)
