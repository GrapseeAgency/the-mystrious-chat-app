package app.pulse.android

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder

/* R56 artboard tokens - lifted verbatim from the web app's globals.css so the
   shell chrome and the connect state wear the EXACT same palette as the web
   surfaces the user previews. */
private val ArtBg = Color(0xFF0D0906)
private val ArtText = Color(0xFFF5EFE8)
private val ArtTextSoft = Color(0xFFCBC0B4)
private val ArtDim = Color(0xFF9B8C7B)
private val ArtFaint = Color(0xFF7A6D5D)
private val ArtHairline = Color(0x14FFFFFF)
private val ArtChip = Color(0x0FFFFFFF)
private val ArtPanel = Color(0xA818120D)
private val ArtEmber = Color(0xFFFF7A3D)
private val ArtEmberDeep = Color(0xFF8A481C)
private val ArtEmberSoft = Color(0xFFFFB86B)
private val ArtInk = Color(0xFF20150C)

/**
 * R56 - the WEB-FIRST shell, now total. The two reference artboards (home +
 * chat room) are WEB surfaces: the interface is built in the language that
 * supports every animation and interaction (the web), rendered 1:1 across
 * every device size. This composable renders the GATEWAY web app (the exact
 * same Next.js surface the Preview Panel shows) inside a WebView, deep-linking
 * the stored viewer identity through the web's `?login=` auto-login contract;
 * with no stored identity it boots the web onboarding itself, so the app is
 * the web from the very first frame.
 *
 * Honest failure, native-free: a main-frame load error (server unreachable,
 * origin dead) shows the ARTBOARD CONNECT PANEL - the web's own ember-on-
 * carbon language - with retry and a themed server-link editor. The shell
 * NEVER auto-flips to the native interface (a transient network blip used to
 * persist webUi=false and strand the user in the old icons); the native shell
 * stays reachable ONLY through the explicit "Open the classic interface"
 * opt-out at the bottom of the panel.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebShellScreen(
    serverBase: String,
    viewerName: String,
    onFallback: () -> Unit,
    onUpdateServerBase: (String) -> Unit,
    // R61 - the audit flavor hides the classic-interface opt-out entirely:
    // the old native shell is dead there (no path back to it).
    showClassicOptOut: Boolean = true,
) {
    val context = LocalContext.current

    // Status bar icons stay LIGHT (the web artboard is always dark).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    // File-chooser bridge: the web camera/attachment inputs (<input type=file>)
    // surface here - without it the artboard's camera icon would be a dead tap.
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pickHint by remember { mutableStateOf("*/*") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(uri?.let { arrayOf(it) })
    }

    // Connect state. `loadFailed` only swaps the WebView for the themed panel;
    // `reloadNonce` remounts the WebView for retry/reconnect.
    var loadFailed by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableStateOf(0) }
    var panelMode by remember { mutableStateOf(ConnectPanelMode.Status) }
    var linkDraft by remember { mutableStateOf(serverBase) }
    var pendingReconnect by remember { mutableStateOf(false) }

    // When the user saves a NEW server link the session round-trips it through
    // prefs + PulseEndpoints; the recomposed serverBase arrives here and the
    // WebView remounts against the fresh origin. Same-link saves reconnect
    // immediately (no recomposition to wait for).
    LaunchedEffect(serverBase) {
        if (pendingReconnect) {
            pendingReconnect = false
            loadFailed = false
            reloadNonce++
        }
    }

    // The exact web boot URL: the gateway origin + the web onboarding's
    // ?login= deep link, which signs the EXISTING native identity into the
    // web session (name-keyed identities - one identity across both shells).
    // No identity -> plain origin; the web onboarding takes over.
    val bootUrl = remember(serverBase, viewerName) {
        val base = serverBase.trim().trimEnd('/')
        if (viewerName.isBlank()) {
            base
        } else {
            val encoded = runCatching { URLEncoder.encode(viewerName, "UTF-8") }.getOrElse { viewerName }
            "$base/?login=$encoded"
        }
    }

    // Live handle for back-navigation (set in the factory, cleared on release).
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(ArtBg)
            .drawBehind {
                // R57 - Chrome-equivalent viewport. The web scene gradient runs
                // #2B1C10 (top) to art-bg (bottom), so the system-bar strips
                // painted behind the inset WebView blend seamlessly with the
                // page - the app now frames the web EXACTLY like the browser
                // does: header clear of the clock, dock clear of the gesture
                // pill, nothing cramped or clipped.
                drawRect(Brush.verticalGradient(listOf(Color(0xFF2B1C10), ArtBg)))
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!loadFailed) {
                    key(serverBase, reloadNonce) {
                        AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(ArtBg.toArgb())
                            isVerticalScrollBarEnabled = false
                            settings.javaScriptEnabled = true
                            // The web session (zustand persist) lives in localStorage +
                            // sessionStorage - without DOM storage the web cannot boot.
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.setSupportZoom(false)
                            settings.builtInZoomControls = false
                            // R57 - system font scaling must not inflate the
                            // artboard's exact typography inside the shell.
                            settings.textZoom = 100
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request == null || !request.isForMainFrame) return
                                    loadFailed = true
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?,
                                ) {
                                    // A 4xx/5xx on the DOCUMENT itself means the gateway
                                    // has no Pulse web app behind it - show the panel.
                                    // Asset subresource errors stay non-fatal.
                                    if (request == null || !request.isForMainFrame) return
                                    val status = errorResponse?.statusCode ?: 0
                                    if (status >= 400) loadFailed = true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    callback: ValueCallback<Array<Uri>>,
                                    params: WebChromeClient.FileChooserParams?,
                                ): Boolean {
                                    val accepts = params?.acceptTypes.orEmpty()
                                        .filter { it.isNotBlank() }
                                    pickHint = when {
                                        accepts.isEmpty() -> "*/*"
                                        accepts.any { it.contains("image") } -> "image/*"
                                        accepts.any { it.contains("video") } -> "video/*"
                                        accepts.any { it.contains("audio") } -> "audio/*"
                                        else -> accepts.first()
                                    }
                                    fileCallback = callback
                                    runCatching { filePicker.launch(pickHint) }
                                        .onFailure {
                                            fileCallback = null
                                            callback.onReceiveValue(null)
                                        }
                                    return true
                                }
                            }
                            webViewHolder.value = this
                            loadUrl(bootUrl)
                        }
                    },
                    onRelease = { webView ->
                        if (webViewHolder.value === webView) webViewHolder.value = null
                        webView.stopLoading()
                        webView.destroy()
                    },
                )
            }
        }

        if (loadFailed) {
            ConnectPanel(
                serverBase = serverBase,
                mode = panelMode,
                linkDraft = linkDraft,
                onLinkDraftChange = { linkDraft = it },
                onRetry = {
                    panelMode = ConnectPanelMode.Status
                    loadFailed = false
                    reloadNonce++
                },
                onEditLink = {
                    linkDraft = serverBase
                    panelMode = ConnectPanelMode.EditLink
                },
                onSaveLink = { cleaned ->
                    panelMode = ConnectPanelMode.Status
                    if (cleaned == serverBase) {
                        loadFailed = false
                        reloadNonce++
                    } else {
                        pendingReconnect = true
                        onUpdateServerBase(cleaned)
                    }
                },
                onCancelEdit = { panelMode = ConnectPanelMode.Status },
                onClassic = onFallback,
            )
            }
        }
    }
}

    // Native back: walk the web history first, then hand the event back to
    // the system (ordinary app exit). The panel lets back dismiss the editor.
    BackHandler(enabled = true) {
        if (loadFailed && panelMode == ConnectPanelMode.EditLink) {
            panelMode = ConnectPanelMode.Status
            return@BackHandler
        }
        val web = webViewHolder.value
        if (web != null && web.canGoBack()) {
            web.goBack()
        } else {
            (context as? Activity)?.finish()
        }
    }
}

private enum class ConnectPanelMode { Status, EditLink }

/** Strip + scheme-normalize the link the way the web onboarding does. */
private fun normalizeServerLink(raw: String): String {
    val t = raw.trim().trimEnd('/')
    if (t.isEmpty()) return t
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}

/**
 * The artboard connect panel: the web scene (warm carbon field + the amber
 * horizon band across the middle) behind a glass panel carrying the ember
 * Pulse mark, the failure status, and the recovery actions. Every color is
 * a web token - no Material-default chrome anywhere.
 */
@Composable
private fun ConnectPanel(
    serverBase: String,
    mode: ConnectPanelMode,
    linkDraft: String,
    onLinkDraftChange: (String) -> Unit,
    onRetry: () -> Unit,
    onEditLink: () -> Unit,
    onSaveLink: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onClassic: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFF2B1C10), Color(0xFF241609), Color(0xFF170E07), ArtBg),
                    ),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0x70BE6826), Color.Transparent),
                        radius = size.width * 1.35f,
                    ),
                    radius = size.width * 1.35f,
                    center = Offset(size.width / 2f, size.height * 0.63f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0x458A481C), Color.Transparent),
                        radius = size.width * 1.7f,
                    ),
                    radius = size.width * 1.7f,
                    center = Offset(size.width / 2f, size.height * 0.70f),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(22.dp))
                .background(ArtPanel)
                .border(1.dp, ArtHairline, RoundedCornerShape(22.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (mode == ConnectPanelMode.Status) {
                PulseMark(Modifier.size(76.dp))
                Spacer(Modifier.height(18.dp))
                Text("Pulse", color = ArtText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Can't reach your Pulse server",
                    color = ArtTextSoft,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    serverBase.trim().ifBlank { "No server link set yet" },
                    color = ArtDim,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                EmberPill(
                    label = "Try again",
                    onClick = onRetry,
                )
                Spacer(Modifier.height(10.dp))
                GlassChip(label = "Server link", onClick = onEditLink)
                if (showClassicOptOut) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open the classic interface",
                        color = ArtFaint,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onClassic)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            } else {
                Text("Server link", color = ArtText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Where your Pulse web app lives",
                    color = ArtTextSoft,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = linkDraft,
                    onValueChange = onLinkDraftChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://your-pulse-gateway", color = ArtFaint, fontSize = 14.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = ArtText,
                        unfocusedTextColor = ArtText,
                        cursorColor = ArtEmber,
                        focusedBorderColor = ArtEmber,
                        unfocusedBorderColor = ArtHairline,
                        focusedContainerColor = ArtChip,
                        unfocusedContainerColor = ArtChip,
                    ),
                )
                Spacer(Modifier.height(18.dp))
                EmberPill(
                    label = "Save and reconnect",
                    onClick = {
                        val cleaned = normalizeServerLink(linkDraft)
                        if (cleaned.isNotEmpty()) onSaveLink(cleaned)
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cancel",
                    color = ArtFaint,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onCancelEdit)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Full-width ember pill - the web's primary action button. */
@Composable
private fun EmberPill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ArtEmber)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ArtInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Full-width quiet glass chip - the web's secondary action. */
@Composable
private fun GlassChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ArtChip)
            .border(1.dp, ArtHairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ArtText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** The Pulse mark: ember ring + white-hot heartbeat, drawn, no raster. */
@Composable
private fun PulseMark(modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 8.dp.toPx()
        drawCircle(
            color = ArtEmberDeep.copy(alpha = 0.54f),
            radius = r + 3.dp.toPx(),
            center = c,
            style = Stroke(width = 9.dp.toPx()),
        )
        drawCircle(
            color = ArtEmber,
            radius = r,
            center = c,
            style = Stroke(width = 4.dp.toPx()),
        )
        val y = c.y
        val path = Path().apply {
            moveTo(c.x - r * 0.62f, y)
            lineTo(c.x - r * 0.32f, y)
            lineTo(c.x - r * 0.12f, y - r * 0.52f)
            lineTo(c.x + r * 0.18f, y + r * 0.52f)
            lineTo(c.x + r * 0.38f, y)
            lineTo(c.x + r * 0.62f, y)
        }
        drawPath(
            path,
            ArtText,
            style = Stroke(
                width = 3.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
