package app.pulse.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pulse.ui.PulseMotion
import app.pulse.ui.pulsePress
import app.pulse.ui.rememberPressSource

// ─────────────────────────────────────────────────────────────
// Pulse onboarding — native mirror of the web OnboardingScreen:
// hero + wordmark, name/color step, @handle step with live
// availability, "log in instead" and "skip for now" affordances.
// ─────────────────────────────────────────────────────────────

// Tailwind palette slices the web screen uses (light/dark variants).
private val Zinc200 = Color(0xFFE4E4E7)
private val Zinc400 = Color(0xFFA1A1AA)
private val Zinc500 = Color(0xFF71717A)
private val Zinc700 = Color(0xFF3F3F46)
private val Zinc800 = Color(0xFF27272A)
private val Emerald400 = Color(0xFF34D399)
private val Emerald500 = Color(0xFF10B981)
private val Emerald600 = Color(0xFF059669)
private val Emerald700 = Color(0xFF047857)
private val Amber400 = Color(0xFFFBBF24)
private val Amber500 = Color(0xFFF59E0B)
private val Amber600 = Color(0xFFD97706)
private val Amber700 = Color(0xFFB45309)
private val White = Color(0xFFFFFFFF)

/** Web AVATAR_GRADIENTS — Tailwind 400→600 pairs, same order as PULSE_COLORS. */
private data class Swatch(val name: String, val from: Color, val to: Color)

private val SWATCHES = listOf(
    Swatch("emerald", Color(0xFF34D399), Color(0xFF059669)),
    Swatch("rose", Color(0xFFFB7185), Color(0xFFE11D48)),
    Swatch("amber", Color(0xFFFBBF24), Color(0xFFD97706)),
    Swatch("violet", Color(0xFFA78BFA), Color(0xFF7C3AED)),
    Swatch("teal", Color(0xFF2DD4BF), Color(0xFF0D9488)),
    Swatch("orange", Color(0xFFFB923C), Color(0xFFEA580C)),
    Swatch("pink", Color(0xFFF472B6), Color(0xFFDB2777)),
    Swatch("cyan", Color(0xFF22D3EE), Color(0xFF0E7490)),
)

private enum class NoticeTone { EMERALD, AMBER, MUTED }

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        OnboardingHero()
        Wordmark()

        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                (fadeIn(PulseMotion.snappy()) + slideInHorizontally(PulseMotion.snappy()) { full -> full / 4 }) togetherWith
                    (fadeOut(PulseMotion.snappy()) + slideOutHorizontally(PulseMotion.snappy()) { full -> -full / 4 })
            },
            label = "onboardingStep",
        ) { step ->
            when (step) {
                OnboardingStep.NAME -> NameStep(state = state, viewModel = viewModel, haptics = haptics)
                OnboardingStep.HANDLE -> HandleStep(state = state, viewModel = viewModel, haptics = haptics)
            }
        }

        TipCard()
    }
}

// ── hero + wordmark ──────────────────────────────────────────

@Composable
private fun OnboardingHero() {
    // webgl-glow stand-in: slow emerald breathing behind the illustration
    val transition = rememberInfiniteTransition(label = "heroGlow")
    val glowAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            tween(3600, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "heroGlowAlpha",
    )

    Box(
        modifier = Modifier
            .size(196.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Emerald500.copy(alpha = glowAlpha), Color.Transparent),
                    ),
                ),
        )
        Image(
            painter = painterResource(R.drawable.onboarding_hero),
            contentDescription = "Pulse messenger illustration",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp)),
        )
    }
}

@Composable
private fun Wordmark() {
    val dark = isSystemInDarkTheme()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Pulse",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = if (dark) Color(0xFFFAFAFA) else Color(0xFF18181B),
            )
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Emerald400, Emerald600))),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Your conversations, instantly alive.",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (dark) Zinc400 else Zinc500,
        )
    }
}

// ── step 1: display name + avatar color ──────────────────────

@Composable
private fun NameStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    haptics: HapticFeedback,
) {
    val validName = state.name.trim().isNotEmpty() && state.name.trim().length <= NAME_MAX
    val busy = state.pending || state.signingIn
    val notice = state.notice

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Display name")
            PulseTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                placeholder = "What should people call you?",
                error = state.nameTaken,
                autoFocus = true,
                capitalization = KeyboardCapitalization.Words,
                onGo = { if (validName && !busy) viewModel.startHandleStep() },
            )
            if (state.nameTaken) {
                NoticeLine(
                    text = "Already on Pulse as “${state.name.trim()}”?",
                    tone = NoticeTone.AMBER,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("Avatar color")
            SwatchRow(selected = state.color, onSelect = { viewModel.setColor(it) })
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PulsePrimaryButton(
                text = "Continue",
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                enabled = validName && !busy,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.startHandleStep()
                },
            )
            if (state.nameTaken) {
                PulseLoginButton(
                    text = if (state.signingIn) "Signing you in…" else "That's me — log in instead",
                    icon = Icons.AutoMirrored.Filled.Login,
                    loading = state.signingIn,
                    enabled = validName && !state.signingIn,
                    onClick = { viewModel.loginInstead() },
                )
            }
        }

        if (notice != null && state.step == OnboardingStep.NAME) {
            NoticeLine(text = notice, tone = NoticeTone.AMBER)
        }
    }
}

// ── step 2: pick a @handle ───────────────────────────────────

@Composable
private fun HandleStep(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    haptics: HapticFeedback,
) {
    val trimmed = state.handle.trim()
    val validHandle = isValidHandle(trimmed)
    // availability only counts when it belongs to the handle currently on screen
    val stale = state.checkedHandle != trimmed
    val checking = validHandle && (stale || state.checking)
    val checkAvailable = validHandle && !stale && state.checkAvailable == true
    val checkTaken = validHandle && !stale && state.checkAvailable == false && !checking
    val blocked = state.serverTakenMessage != null
    val canSubmit = validHandle && !checking && !checkTaken && !state.pending && !blocked

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val backInteraction = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pulsePress(backInteraction)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = backInteraction,
                        indication = null,
                    ) { viewModel.backToName() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to name step",
                    tint = Zinc500,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column {
                Text(
                    "Pick your handle",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Creating account for “${state.name.trim()}” — optional, but it makes you findable.",
                    fontSize = 11.sp,
                    color = Zinc500,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldLabel("@handle")
                Text(
                    "OPTIONAL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.8.sp,
                    color = Zinc400,
                )
            }
            PulseTextField(
                value = state.handle,
                onValueChange = viewModel::setHandle,
                placeholder = "e.g. alice_chen",
                leadingAt = true,
                error = checkTaken || blocked,
                keyboardType = KeyboardType.Ascii,
                onGo = { if (canSubmit) viewModel.submit() },
            )

            // live availability line (web aria-live region)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                when {
                    trimmed.isEmpty() ->
                        NoticeLine("Skip it if you prefer — you can add one later in Profile.", NoticeTone.MUTED)
                    !validHandle ->
                        NoticeLine("3–20 characters: lowercase letters, digits, underscore.", NoticeTone.MUTED)
                    checking -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Zinc500,
                        )
                        Text(
                            "Checking @$trimmed…",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Zinc500,
                        )
                    }
                    checkAvailable ->
                        NoticeLine("@$trimmed is free!", NoticeTone.EMERALD)
                    checkTaken ->
                        TakenLine(
                            text = "@$trimmed is taken",
                            suggestion = state.checkSuggestion,
                            onUse = viewModel::useSuggestion,
                        )
                    blocked ->
                        TakenLine(
                            text = state.serverTakenMessage ?: "",
                            suggestion = state.serverTakenSuggestion,
                            onUse = viewModel::useSuggestion,
                        )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PulsePrimaryButton(
                text = if (state.pending) "Creating your account…" else "Start chatting",
                icon = if (state.pending) null else Icons.AutoMirrored.Filled.ArrowForward,
                enabled = canSubmit,
                loading = state.pending,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.submit()
                },
            )
            PulseGhostButton(
                text = "Skip for now",
                enabled = !state.pending,
                onClick = { viewModel.skip() },
            )
        }
    }
}

// ── shared atoms ─────────────────────────────────────────────

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NoticeLine(text: String, tone: NoticeTone) {
    val dark = isSystemInDarkTheme()
    val color = when (tone) {
        NoticeTone.EMERALD -> if (dark) Emerald400 else Emerald600
        NoticeTone.AMBER -> if (dark) Amber400 else Amber600
        NoticeTone.MUTED -> if (dark) Zinc400 else Zinc500
    }
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        lineHeight = 16.sp,
    )
}

/** amber "taken" line with the optional Use-@suggestion pill (web parity). */
@Composable
private fun TakenLine(text: String, suggestion: String?, onUse: (String) -> Unit) {
    val dark = isSystemInDarkTheme()
    val amberText = if (dark) Amber400 else Amber600
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = amberText,
        )
        if (!suggestion.isNullOrBlank()) {
            val interaction = rememberPressSource()
            Box(
                modifier = Modifier
                    .pulsePress(interaction)
                    .clip(CircleShape)
                    .background(Amber500.copy(alpha = 0.15f))
                    .clickable(interactionSource = interaction, indication = null) { onUse(suggestion) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    "Use @$suggestion",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (dark) Amber400 else Amber700,
                )
            }
        }
    }
}

@Composable
private fun SwatchRow(selected: String, onSelect: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SWATCHES.forEach { sw ->
            val isSelected = sw.name == selected
            val interaction = rememberPressSource()
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .drawBehind {
                        if (isSelected) {
                            // web ring-2 ring-emerald-600 ring-offset-2
                            drawCircle(
                                color = Emerald600,
                                radius = size.minDimension / 2f + 2.dp.toPx(),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }
                    .pulsePress(interaction)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(sw.from, sw.to)))
                    .clickable(interactionSource = interaction, indication = null) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(sw.name)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "${sw.name} avatar selected",
                        tint = White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * The web's shadcn Input: 44dp rounded field on zinc-50/zinc-800,
 * emerald focus ring, amber error border — BasicTextField, exact metrics.
 */
@Composable
private fun PulseTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingAt: Boolean = false,
    error: Boolean = false,
    autoFocus: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    onGo: () -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (autoFocus) {
            kotlinx.coroutines.delay(350) // let the step transition settle
            focusRequester.requestFocus()
        }
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground,
        ),
        cursorBrush = SolidColor(Emerald600),
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = keyboardType,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (dark) Zinc800 else Color(0xFFFAFAFA))
            .border(
                width = 1.dp,
                color = when {
                    error -> Amber400
                    focused -> Emerald600.copy(alpha = 0.6f)
                    else -> if (dark) Zinc700 else Zinc200
                },
                shape = RoundedCornerShape(12.dp),
            ),
        decorationBox = { inner ->
            Row(
                modifier = Modifier.padding(horizontal = if (leadingAt) 14.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (leadingAt) 4.dp else 0.dp),
            ) {
                if (leadingAt) {
                    Text(
                        "@",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Zinc400,
                    )
                }
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            fontSize = 15.sp,
                            color = Zinc400,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

/** emerald-600 primary action — web Button classes verbatim (h-12 rounded-xl). */
@Composable
private fun PulsePrimaryButton(
    text: String,
    icon: ImageVector?,
    enabled: Boolean,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = rememberPressSource()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Emerald600,
            contentColor = White,
            disabledContainerColor = Emerald600.copy(alpha = 0.5f),
            disabledContentColor = White,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(6.dp, RoundedCornerShape(12.dp))
            .pulsePress(interaction),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = White,
            )
        }
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
        )
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

/** "That's me — log in instead" — emerald-tinted outline button (web parity). */
@Composable
private fun PulseLoginButton(
    text: String,
    icon: ImageVector,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val interaction = rememberPressSource()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Emerald500.copy(alpha = 0.10f),
            contentColor = if (dark) Emerald400 else Emerald700,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(1.dp, Emerald500.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .pulsePress(interaction),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = if (dark) Emerald400 else Emerald700,
            )
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** quiet "Skip for now" ghost action. */
@Composable
private fun PulseGhostButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = rememberPressSource()
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Zinc500,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Zinc500,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .pulsePress(interaction),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** dashed zinc tip card — the web footer, copy adapted for the native app. */
@Composable
private fun TipCard() {
    val dark = isSystemInDarkTheme()
    Row(
        Modifier
            .fillMaxWidth()
            .dashedBorder(1.dp, if (dark) Zinc700 else Zinc200, RoundedCornerShape(16.dp))
            .background(if (dark) Zinc800.copy(alpha = 0.6f) else Color(0xFFFAFAFA))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Emerald500,
            modifier = Modifier.size(14.dp),
        )
        Text(
            "Tip: your @handle is optional — add or change it anytime from your Profile.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = if (dark) Zinc400 else Zinc500,
        )
    }
}

private fun Modifier.dashedBorder(width: Dp, color: Color, shape: RoundedCornerShape): Modifier =
    this.then(
        Modifier.drawBehind {
            val stroke = width.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline = outline,
                color = color,
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
        },
    )
