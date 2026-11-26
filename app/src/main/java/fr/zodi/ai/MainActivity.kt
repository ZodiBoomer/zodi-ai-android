package fr.zodi.ai

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val APP_URL = "https://zodi-ai-core.vercel.app/"
private const val APP_HOST = "zodi-ai-core.vercel.app"
private const val CONVERSATIONS_KEY = "zodi-conversations-v2"

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var filePicker: ActivityResultLauncher<Intent>
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val chatPreferences by lazy { getSharedPreferences("zodi_chat", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 18, 22)
        window.navigationBarColor = Color.rgb(18, 18, 22)
        window.decorView.systemUiVisibility = 0
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            callback?.onReceiveValue(
                if (result.resultCode == RESULT_OK) WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                else null
            )
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(33, 33, 33))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.userAgentString = settings.userAgentString + " ZodiAI-Android"
            addJavascriptInterface(NativeBridge(), "ZodiNative")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    routeUrl(request.url)
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    routeUrl(Uri.parse(url))
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {\n                    if (newProgress == 100) installWebCompatibilityAndMigrateHistory()\n                }
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = callback
                    return try {
                        filePicker.launch(Intent.createChooser(params.createIntent(), "Choisir un fichier"))
                        true
                    } catch (_: Exception) {
                        fileChooserCallback = null
                        callback.onReceiveValue(null)
                        false
                    }
                }
            }
        }

        setContentView(webView)
        webView.loadUrl(APP_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        lifecycleScope.launch {
            delay(1800)
            try {
                val update = AppUpdater.downloadIfAvailable(this@MainActivity)
                if (update != null) AppUpdater.install(this@MainActivity, update)
            } catch (_: Exception) {
                // Keep the shared web interface available if release checks are offline.
            }
        }
    }

    private fun routeUrl(uri: Uri): Boolean {
        if (uri.scheme == "https" && uri.host == APP_HOST) return false
        if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto" || uri.scheme == "tel") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
        return true
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                runCatching {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(intent, "Partager avec"))
                }.onFailure {
                    Toast.makeText(this@MainActivity, "Partage indisponible", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Zodi AI", text))
                Toast.makeText(this@MainActivity, "Copié", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("JavascriptInterface")
    private fun installWebCompatibilityAndMigrateHistory() {
        val saved = chatPreferences.getString("conversations", null)
            ?: chatPreferences.getString("messages", null)
        val migration = if (saved.isNullOrBlank()) "" else {
            val rawLiteral = JSONObject.quote(saved)
            """
            (() => {
              const key = "$CONVERSATIONS_KEY";
              if (localStorage.getItem(key)) return;
              try {
                let old = JSON.parse($rawLiteral);
                if (Array.isArray(old) && old.length && !old[0].messages && !old[0].Messages) {
                  old = [{ id: "android-legacy", title: "Conversation importée", updatedAt: Date.now(), messages: old }];
                }
                const normalized = (Array.isArray(old) ? old : []).map((c, i) => ({
                  id: c.id || c.Id || ("android-" + i),
                  title: c.title || c.Title || "Conversation importée",
                  updatedAt: c.updatedAt || c.UpdatedAt || Date.now(),
                  messages: (c.messages || c.Messages || []).map(m => ({
                    role: m.role || m.Role || "assistant",
                    content: m.content || m.Content || "",
                    images: (m.images || m.Images || []).map(img => ({
                      dataUrl: img.dataUrl || img.DataUrl || "",
                      mimeType: img.mimeType || img.MimeType || "image/jpeg",
                      name: img.name || img.Name || "Photo"
                    }))
                  }))
                }));
                if (normalized.length) {
                  localStorage.setItem(key, JSON.stringify(normalized));
                  location.reload();
                }
              } catch (_) {}
            })();
            """.trimIndent()
        }
        val bridge = """
            (() => {
              if (window.__zodiNativeBridgeInstalled) return;
              window.__zodiNativeBridgeInstalled = true;
              const native = window.ZodiNative;
              if (!native) return;
              if (!navigator.share) {
                Object.defineProperty(navigator, "share", {
                  configurable: true,
                  value: async (data = {}) => native.shareText([data.title, data.text, data.url].filter(Boolean).join("\n"))
                });
              }
              try {
                Object.defineProperty(navigator, "clipboard", {
                  configurable: true,
                  value: { writeText: async (text) => native.copyText(String(text)) }
                });
              } catch (_) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(bridge, null)
        if (migration.isNotBlank()) webView.evaluateJavascript(migration, null)
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        webView.stopLoading()
        webView.removeJavascriptInterface("ZodiNative")
        webView.destroy()
        super.onDestroy()
    }
}
