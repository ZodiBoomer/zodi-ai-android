package fr.zodi.ai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Android entry point for Zodi AI.
 *
 * The authenticated web application is the shared source of truth for
 * conversations and memory. Opening it in a Custom Tab keeps Google OAuth
 * in the system browser instead of an embedded WebView.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = Uri.parse("https://zodi-ai-core.vercel.app")
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .build()
            .launchUrl(this, uri)
        finish()
    }
}
