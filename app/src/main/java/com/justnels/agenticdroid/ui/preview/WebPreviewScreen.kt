package com.justnels.agenticdroid.ui.preview

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

private const val MAX_LOCAL_STARTUP_RETRIES = 180
private const val LOCAL_STARTUP_RETRY_DELAY_MS = 1_000L

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewScreen(
    currentUrl: String,
    onUrlChange: (String) -> Unit,
    onNavigateToTerminal: () -> Unit,
    serverStatus: String? = null,
    serverActive: Boolean = false,
    serverReady: Boolean = false,
    serverLog: String = "",
    onStopServer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var urlInput by remember(currentUrl) { mutableStateOf(currentUrl) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var startupRetries by remember(currentUrl) { mutableIntStateOf(0) }
    var showLogDialog by remember { mutableStateOf(false) }

    LaunchedEffect(currentUrl) {
        if (currentUrl.isNotBlank() && currentUrl != urlInput) {
            urlInput = currentUrl
            loadError = null
            startupRetries = 0
            webViewInstance?.loadUrl(formatUrl(currentUrl))
        }
    }

    val retryingLocalServer = loadError != null &&
        isLocalDevUrl(urlInput) && startupRetries < MAX_LOCAL_STARTUP_RETRIES
    LaunchedEffect(loadError, urlInput, startupRetries) {
        if (retryingLocalServer) {
            delay(LOCAL_STARTUP_RETRY_DELAY_MS)
            startupRetries++
            loadError = null
            webViewInstance?.loadUrl(formatUrl(urlInput))
        }
    }

    val quickPorts = listOf(
        "5173" to "Vite",
        "5174" to "Vite (Alt)",
        "3000" to "React/Next",
        "3001" to "Next/Node (Alt)",
        "8000" to "Python",
        "8080" to "Webpack/HTTP",
        "5000" to "Flask",
        "4173" to "Vite Preview"
    )

    Column(modifier = modifier.fillMaxSize()) {
        // Address & Control Bar
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { webViewInstance?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(
                        onClick = { webViewInstance?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(
                        onClick = {
                            startupRetries = 0
                            loadError = null
                            webViewInstance?.reload()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        trailingIcon = {
                            if (urlInput.isNotBlank()) {
                                IconButton(onClick = {
                                    val formatted = formatUrl(urlInput)
                                    onUrlChange(formatted)
                                    loadError = null
                                    webViewInstance?.loadUrl(formatted)
                                }) {
                                    Icon(Icons.Default.ArrowForward, contentDescription = "Go")
                                }
                            }
                        }
                    )

                    IconButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formatUrl(urlInput)))
                                context.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(context, "Could not open browser: ${it.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in external browser")
                    }

                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("URL", urlInput))
                            Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL")
                    }
                }

                // Quick Port Presets
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ports:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    quickPorts.forEach { (port, label) ->
                        val targetUrl = "http://localhost:$port"
                        val isSelected = currentUrl.contains(":$port")
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                urlInput = targetUrl
                                onUrlChange(targetUrl)
                                loadError = null
                                webViewInstance?.loadUrl(targetUrl)
                            },
                            label = { Text("$port ($label)") }
                        )
                    }
                }
            }
        }

        if (serverStatus != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (serverActive && !serverReady) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (serverReady) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(serverStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (serverLog.isNotBlank()) {
                        TextButton(onClick = { showLogDialog = true }) { Text("View Logs") }
                    }
                    if (serverActive) {
                        TextButton(onClick = onStopServer) { Text("Stop") }
                    }
                }
            }
        }

        // Progress Bar
        if (isLoading) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Content Area
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                loadError = null
                                url?.let {
                                    urlInput = it
                                    onUrlChange(it)
                                }
                                canGoBack = view?.canGoBack() ?: false
                                canGoForward = view?.canGoForward() ?: false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                if (loadError == null) startupRetries = 0
                                canGoBack = view?.canGoBack() ?: false
                                canGoForward = view?.canGoForward() ?: false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    val desc = error?.description?.toString() ?: "Connection failed"
                                    loadError = desc
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                        }

                        webViewInstance = this
                        loadUrl(formatUrl(currentUrl))
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                }
            )

            // Error Overlay when server is not running
            if (loadError != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .fillMaxWidth(0.9f),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (retryingLocalServer) "Starting Local Server" else "Server Not Responding",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (retryingLocalServer) {
                                "Waiting for $urlInput (attempt ${startupRetries + 1} of $MAX_LOCAL_STARTUP_RETRIES)"
                            } else {
                                "Could not connect to $urlInput (${loadError})"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Make sure your dev server is running (e.g. npm run dev or python -m http.server).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (serverLog.isNotBlank()) {
                                OutlinedButton(onClick = { showLogDialog = true }) {
                                    Icon(Icons.Default.Terminal, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("View Logs")
                                }
                            }
                            Button(
                                onClick = onNavigateToTerminal
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Go to Terminal")
                            }
                            OutlinedButton(
                                onClick = {
                                    startupRetries = 0
                                    loadError = null
                                    webViewInstance?.loadUrl(formatUrl(urlInput))
                                }
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Server Output") },
            text = {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .padding(4.dp)
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = serverLog.ifBlank { "No log output recorded yet." },
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.verticalScroll(scrollState)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) {
                    Text("Close")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Server Log", serverLog))
                    Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy")
                }
            }
        )
    }
}

private fun isLocalDevUrl(url: String): Boolean {
    val host = runCatching { Uri.parse(formatUrl(url)).host }.getOrNull()
    return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
}

private fun formatUrl(url: String): String {
    val trimmed = url.trim()
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://") -> trimmed
        trimmed.startsWith("localhost") || trimmed.startsWith("127.0.0.1") -> "http://$trimmed"
        trimmed.all { it.isDigit() } -> "http://localhost:$trimmed"
        else -> "http://$trimmed"
    }
}
