package fr.zodi.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads only signed APKs listed in the official unified stable update index.
 * Android remains responsible for the final package-install confirmation.
 */
object AppUpdater {
    private const val UPDATE_INDEX =
        "https://raw.githubusercontent.com/ZodiBoomer/zodi-ai-releases-publique/main/latest.json"
    private const val OFFICIAL_RELEASE_PREFIX =
        "https://github.com/ZodiBoomer/zodi-ai-releases-publique/releases/download/"

    suspend fun downloadIfAvailable(context: Context): File? = withContext(Dispatchers.IO) {
        val index = getJson(UPDATE_INDEX)
        val platform = index.getJSONObject("platforms").getJSONObject("android-universal")
        val version = platform.getString("version")
        val downloadUrl = platform.getString("url")
        val expectedSha256 = platform.getString("sha256").lowercase()

        require(version.isNotBlank() && expectedSha256.matches(Regex("[a-f0-9]{64}"))) {
            "Le manifeste de mise à jour Android est invalide."
        }
        require(downloadUrl.startsWith(OFFICIAL_RELEASE_PREFIX)) {
            "L'adresse de téléchargement Android n'est pas une publication officielle."
        }

        @Suppress("DEPRECATION")
        val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        if (compareVersions(version, currentVersion) <= 0) return@withContext null

        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(directory, "zodi-ai-$version.apk")
        download(downloadUrl, apk)
        val actualSha256 = sha256(apk)
        if (actualSha256 != expectedSha256) {
            apk.delete()
            error("La vérification SHA-256 de l'APK a échoué.")
        }
        apk
    }

    fun install(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            Toast.makeText(
                context,
                "Autorise Zodi AI à installer cette mise à jour, puis relance la vérification.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun getJson(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("Pragma", "no-cache")
        connection.setRequestProperty("User-Agent", "Zodi-AI-Updater")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("Le catalogue de mises à jour a répondu HTTP $code.")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun download(address: String, destination: File) {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        connection.setRequestProperty("User-Agent", "Zodi-AI-Updater")
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("Téléchargement impossible (HTTP $code).")
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
        val b = right.split(".", "-", "+").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val comparison = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }
}
