package me.parham1995.notes.ui.mermaid

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
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
            val key = digest(code + theme.key + PAGE_VERSION)
            val cached = File(cacheDir, "$key.svg")
            if (cached.isFile && cached.length() > 0) return Result.Svg(cached)

            // Everything below is bounded, including the wait for the lock.
            //
            // There is one WebView and one mutex around it, so a render that
            // wedges does not fail alone -- every other diagram in the note
            // waits behind it, on a placeholder, for ever. Both ways that
            // could happen were unbounded: acquiring the lock, and starting
            // the page, which sits outside the per-render timeout and is the
            // part that talks to a WebView that might not answer.
            return withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                mutex.withLock {
                    // Re-check: another caller may have rendered the same
                    // diagram while this one waited for the lock.
                    if (cached.isFile && cached.length() > 0) return@withLock Result.Svg(cached)

                    val view =
                        withTimeoutOrNull(LOAD_TIMEOUT_MS) { ensureWebView(theme) }
                            ?: run {
                                // Drop it rather than queue behind it again:
                                // a page that did not answer once will not
                                // answer the next diagram either.
                                resetWebView()
                                return@withLock Result.Failed("the diagram engine did not start")
                            }
                    val json =
                        withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                            evaluate(view, "render(${key.take(8).quoted()}, ${code.quoted()})")
                            awaitResult(view)
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
            } ?: Result.Failed("gave up waiting to draw")
        }

        /** Forgets the page, so the next diagram builds a fresh one. */
        private suspend fun resetWebView() =
            withContext(Dispatchers.Main) {
                runCatching { webView?.destroy() }
                webView = null
                themeKey = null
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
                view.webViewClient = AssetClient(loader) { dead -> discard(dead) }

                if (existing == null || themeKey == null) {
                    view.loadUrl("https://appassets.androidplatform.net/assets/mermaid/index.html")
                    awaitLoad(view)
                }
                evaluate(view, "setup(${theme.toJson().quoted()})")
                themeKey = theme.key
                view
            }

        /** Forgets a WebView whose render process died, so the next call rebuilds. */
        private fun discard(dead: WebView) {
            if (webView === dead) {
                webView = null
                themeKey = null
            }
        }

        /**
         * Serves the bundled assets and survives the render process dying.
         *
         * The suppression is narrow and deliberate: `onRenderProcessGone` is
         * implemented directly below with the framework signature, on a
         * WebViewClientCompat, as a named class rather than an object
         * expression. androidx.webkit's check still does not see it.
         * Suppressing here rather than disabling the rule keeps it live for any
         * other WebViewClient.
         */
        @SuppressLint("MissingOnRenderProcessGone")
        private class AssetClient(
            private val loader: WebViewAssetLoader,
            private val onGone: (WebView) -> Unit,
        ) : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

            /**
             * The render process is a separate process and can be killed under
             * memory pressure -- likely here, since mermaid is several megabytes of
             * script. Unhandled, it takes the whole app down. Returning true keeps
             * the app alive and drops the dead view.
             */
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                view.destroy()
                onGone(view)
                return true
            }
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

        /**
         * Waits for the diagram mermaid is drawing.
         *
         * `mermaid.render` is asynchronous and `evaluateJavascript` will not
         * wait for a promise, so the page leaves its answer in a global and
         * this reads it once it appears.
         *
         * Every `POLL_MS`, not every frame. Each ask is a hop to the main
         * thread and a call into the renderer, and asking sixty times a second
         * per diagram -- serialised across every diagram in a note, twelve of
         * them in the worst one here -- spends the main thread on nothing but
         * asking. A diagram takes long enough that the extra wait is not
         * visible.
         */
        private suspend fun awaitResult(view: WebView): String {
            while (true) {
                val value = evaluate(view, "result()")
                if (value.isNotEmpty() && value != "null") return value
                delay(POLL_MS)
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
            /**
             * Bumped whenever the page changes what it draws.
             *
             * SVGs are cached on disk by their source and theme, so a diagram
             * rendered by an older page is served forever -- including the
             * ones drawn with no labels in them at all, which is how this came
             * to be needed.
             *
             * 2: labels as SVG text rather than HTML in a foreignObject.
             * 3: a line's words joined, so the spaces between them survive.
             */
            const val PAGE_VERSION = "3"

            /**
             * The whole attempt, queueing included.
             *
             * Generous because a note can hold a dozen diagrams and they are
             * drawn one at a time; short enough that a wedged page becomes a
             * message rather than a placeholder that never resolves.
             */
            const val TOTAL_TIMEOUT_MS = 25_000L

            /** Building the page and parsing five megabytes of mermaid. */
            const val LOAD_TIMEOUT_MS = 10_000L

            const val RENDER_TIMEOUT_MS = 4_000L
            const val POLL_MS = 40L
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
