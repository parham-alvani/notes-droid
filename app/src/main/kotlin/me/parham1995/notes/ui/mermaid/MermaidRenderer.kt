package me.parham1995.notes.ui.mermaid

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Renders mermaid diagrams to SVG.
 *
 * There is no JVM implementation of mermaid, so a WebView is the only real
 * option -- but there is exactly **one**, offscreen and shared, and it is never
 * placed in the scroll list. A WebView costs tens of megabytes; a note with
 * several diagrams would be fatal with one apiece. What the list shows is the
 * resulting SVG as an ordinary image.
 *
 * Results are cached on disk under a key that includes the theme, because the
 * same diagram is a different picture in dark mode.
 */
@Singleton
class MermaidRenderer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val mutex = Mutex()
        private var webView: WebView? = null
        private var themeKey: String? = null
        private val cacheDir = File(context.cacheDir, "mermaid").apply { mkdirs() }

        sealed interface Result {
            data class Svg(
                val file: File,
            ) : Result

            /** Rendering failed; the caller shows the diagram source instead. */
            data class Failed(
                val message: String,
            ) : Result
        }

        suspend fun render(
            code: String,
            theme: MermaidTheme,
        ): Result {
            val key = digest(code + theme.key)
            val cached = File(cacheDir, "$key.svg")
            if (cached.isFile && cached.length() > 0) return Result.Svg(cached)

            return mutex.withLock {
                // Re-check: another caller may have rendered the same diagram
                // while this one waited for the lock.
                if (cached.isFile && cached.length() > 0) return@withLock Result.Svg(cached)

                val view = ensureWebView(theme)
                val json =
                    withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                        evaluate(view, "render(${key.take(8).quoted()}, ${code.quoted()})")
                    } ?: return@withLock Result.Failed("timed out")

                val parsed =
                    runCatching { JSONObject(json) }.getOrNull()
                        ?: return@withLock Result.Failed("unreadable response")

                if (!parsed.optBoolean("ok")) {
                    return@withLock Result.Failed(parsed.optString("error", "invalid diagram"))
                }

                withContext(Dispatchers.IO) { cached.writeText(parsed.getString("svg")) }
                Result.Svg(cached)
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private suspend fun ensureWebView(theme: MermaidTheme): WebView =
            withContext(Dispatchers.Main) {
                val existing = webView
                if (existing != null && themeKey == theme.key) return@withContext existing

                val view = existing ?: WebView(context).also { webView = it }
                view.settings.javaScriptEnabled = true
                // The diagram source is untrusted and the renderer needs
                // nothing from the network or the filesystem.
                view.settings.allowFileAccess = false
                view.settings.allowContentAccess = false
                view.settings.blockNetworkLoads = true

                val loader =
                    WebViewAssetLoader
                        .Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                        .build()
                view.webViewClient =
                    object : android.webkit.WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ) = loader.shouldInterceptRequest(request.url)
                    }

                if (existing == null || themeKey == null) {
                    view.loadUrl("https://appassets.androidplatform.net/assets/mermaid/index.html")
                    awaitLoad(view)
                }
                evaluate(view, "setup(${theme.toJson().quoted()})")
                themeKey = theme.key
                view
            }

        private suspend fun awaitLoad(view: WebView) {
            // The asset page is local, so this settles almost immediately; the
            // frame wait simply yields until the document exists.
            repeat(LOAD_FRAMES) {
                awaitFrame()
                val ready = evaluate(view, "typeof mermaid !== 'undefined'")
                if (ready == "true") return
            }
        }

        private suspend fun evaluate(
            view: WebView,
            script: String,
        ): String =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    view.evaluateJavascript(script) { value ->
                        continuation.resume(value?.unquoteJs().orEmpty())
                    }
                }
            }

        private fun digest(input: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)

        suspend fun clearCache() =
            withContext(Dispatchers.IO) {
                cacheDir.listFiles()?.forEach { it.delete() }
                Unit
            }

        private companion object {
            const val RENDER_TIMEOUT_MS = 4_000L
            const val LOAD_FRAMES = 120
        }
    }

/** Mermaid's theme variables, derived from the app's own colour scheme. */
data class MermaidTheme(
    val background: String,
    val primary: String,
    val primaryText: String,
    val line: String,
    val text: String,
    val dark: Boolean,
) {
    val key: String get() = "$background|$primary|$line|$text|$dark"

    fun toJson(): String =
        JSONObject()
            .put("background", background)
            .put("primaryColor", primary)
            .put("primaryTextColor", primaryText)
            .put("primaryBorderColor", line)
            .put("lineColor", line)
            .put("textColor", text)
            .put("mainBkg", primary)
            .put("nodeBorder", line)
            .toString()
}

private fun String.quoted(): String = JSONObject.quote(this)

/** `evaluateJavascript` hands back a JSON-encoded value, even for strings. */
private fun String.unquoteJs(): String =
    when {
        this == "null" -> ""
        startsWith("\"") -> runCatching { JSONObject("{\"v\":$this}").getString("v") }.getOrDefault(this)
        else -> this
    }
