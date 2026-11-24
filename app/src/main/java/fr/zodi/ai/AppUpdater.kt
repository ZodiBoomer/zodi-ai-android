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
 * Downloads only signed APKs published in the official public release repository.
 * Android itself remains responsible for the final package-install confirmation.
 */
object AppUpdater {
    private const val RELEASES_API =
        "https://api.github.com/repos/ZodiBoomer/zodi-ai-releases-publique/releases?per_page=30"

    suspend fun downloadIfAvailable(context: Context): File? = withContext(Dispatchers.IO) {
        val releases = getArray(RELEASES_API)
        val release = (0 until releases.length())
            .map { releases.getJSONObject(it) }
            .firstOrNull { it.optString("tag_name").startsWith("android-v") }
            ?: error("Aucune publication Android n'est encore disponible.")
        val assets = release.optJSONArray("assets") ?: error("La publication ne contient aucun fichier.")
        val manifestAsset = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == "latest-android.json" }
            ?: error("Le manifeste Android est absent de la dernière publication.")

        val manifest = getJson(manifestAsset.getString("browser_download_url"))
        val version = manifest.optString("version")
        val assetName = manifest.optString("asset")
        val expectedSha256 = manifest.optString("sha256").lowercase()
        require(version.isNotBlank() && assetName.isNotBlank() && expectedSha256.matches(Regex("[a-f0-9]{64}"))) {
            "Le manifeste de mise à jour est invalide."
        }

        @Suppress("DEPRECATION")
        val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        if (compareVersions(version, currentVersion) <= 0) return@withContext null

        val apkAsset = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == assetName }
            ?: error("Le fichier APK $assetName est absent de la publication.")

        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(directory, "zodi-ai-$version.apk")
        download(apkAsset.getString("browser_download_url"), apk)
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

    private fun getArray(address: String): org.json.JSONArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Zodi-AI-Updater")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("GitHub a répondu HTTP $code.")
            org.json.JSONArray(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(address: String): JSONObject {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Zodi-AI-Updater")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) error("GitHub a répondu HTTP $code.")
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
