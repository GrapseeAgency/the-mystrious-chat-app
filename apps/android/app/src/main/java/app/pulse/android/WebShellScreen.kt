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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder

/**
 * R54 - the WEB-FIRST shell. The two reference artboards (home + chat room)
 * are WEB surfaces: the user directed that the interface be built in the
 * language that supports every animation and interaction (the web), rendered
 * 1:1 across every device size. This composable renders the GATEWAY web app
 * (the exact same Next.js surface the Preview Panel shows) inside a WebView,
 * deep-linking the stored viewer identity through the web's `?login=`
 * auto-login contract.
 *
 * Honest failure: a main-frame load error (server unreachable, origin dead)
 * calls [onFallback] once, which flips the renderer gate to the native
 * Compose shell - the app never strands the user on a dead WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebShellScreen(
    serverBase: String,
    viewerName: String,
    onFallback: () -> Unit,
) {
    val context = LocalContext.current
    // The artboard base color - the WebView paints this while the page boots
    // (no white flash over a dark design).
    val artBackground = Color(0xFF0D0906)

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

    // One-shot fallback: only a MAIN-frame error flips the renderer.
    var fallbackFired by remember { mutableStateOf(false) }
    var showFallbackNotice by remember { mutableStateOf(false) }

    // The exact web boot URL: the gateway origin + the web onboarding's
    // ?login= deep link, which signs the EXISTING native identity into the
    // web session (name-keyed identities - one identity across both shells).
    val bootUrl = remember(serverBase, viewerName) {
        val base = serverBase.trim().trimEnd('/')
        val encoded = runCatching { URLEncoder.encode(viewerName, "UTF-8") }.getOrElse { viewerName }
        "$base/?login=$encoded"
    }

    // Live handle for back-navigation (set in the factory, cleared on release).
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }

    if (showFallbackNotice) {
        AlertDialog(
            onDismissRequest = { showFallbackNotice = false },
            title = { Text("Web UI unreachable") },
            text = {
                Text(
                    "The web interface could not be loaded from this gateway. " +
                        "The native interface is active - you can switch back in Profile.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showFallbackNotice = false }) { Text("OK") }
            },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(artBackground),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(artBackground.toArgb())
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
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request == null || !request.isForMainFrame) return
                            if (fallbackFired) return
                            fallbackFired = true
                            showFallbackNotice = true
                            onFallback()
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?,
                        ) {
                            // A 4xx/5xx on the DOCUMENT itself means the gateway
                            // has no Pulse web app behind it - fall back. Asset
                            // subresource errors stay non-fatal.
                            if (request == null || !request.isForMainFrame) return
                            val status = errorResponse?.statusCode ?: 0
                            if (status >= 400 && !fallbackFired) {
                                fallbackFired = true
                                showFallbackNotice = true
                                onFallback()
                            }
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

    // Native back: walk the web history first, then hand the event back to
    // the system (ordinary app exit).
    BackHandler(enabled = true) {
        val web = webViewHolder.value
        if (web != null && web.canGoBack()) {
            web.goBack()
        } else {
            (context as? Activity)?.finish()
        }
    }
}
