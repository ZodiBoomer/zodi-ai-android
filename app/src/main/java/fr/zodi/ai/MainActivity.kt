package fr.zodi.ai

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val API_BASE = "https://zodi-ai-core.vercel.app"
private val Background = Color(0xFF212121)
private val Surface = Color(0xFF171717)
private val SurfaceAlt = Color(0xFF303030)
private val Purple = Color(0xFF3B82F6)
private val TextMain = Color(0xFFECECEC)
private val TextMuted = Color(0xFF999999)
private val Green = Color(0xFF10A37F)

data class ChatImage(val dataUrl: String, val mimeType: String = "image/jpeg", val name: String = "Photo")
data class ChatMessage(val role: String, val content: String, val images: List<ChatImage> = emptyList())
data class ChatConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>
)

private fun loadConversations(prefs: android.content.SharedPreferences): List<ChatConversation> {
    val saved = prefs.getString("conversations", null)
    if (!saved.isNullOrBlank()) {
        runCatching {
            val array = JSONArray(saved)
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val messageArray = item.optJSONArray("messages") ?: JSONArray()
                val messages = (0 until messageArray.length()).map { messageIndex ->
                    val message = messageArray.getJSONObject(messageIndex)
                    ChatMessage(message.getString("role"), message.getString("content"), (0 until (message.optJSONArray("images")?.length() ?: 0)).map { imageIndex ->
                    val image = message.getJSONArray("images").getJSONObject(imageIndex)
                    ChatImage(image.getString("dataUrl"), image.optString("mimeType", "image/jpeg"), image.optString("name", "Photo"))
                })
                }
                ChatConversation(
                    id = item.optString("id", index.toString()),
                    title = item.optString("title", "Conversation"),
                    updatedAt = item.optLong("updatedAt", 0L),
                    messages = messages
                )
            }.sortedByDescending { it.updatedAt }
        }
    }

    // Migration of the previous single-conversation format.
    val legacy = prefs.getString("messages", null)
    val migratedMessages = runCatching {
        val array = JSONArray(legacy ?: "[]")
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ChatMessage(item.getString("role"), item.getString("content"))
        }
    }.getOrDefault(emptyList())
    val initial = migratedMessages.ifEmpty {
        listOf(ChatMessage("assistant", "Salut ! Je suis Zodi. Que veux-tu faire aujourd'hui ?"))
    }
    return listOf(ChatConversation("conversation-${System.currentTimeMillis()}", "Nouvelle conversation", System.currentTimeMillis(), initial))
}

data class RemoteAppConfig(
    val configVersion: String = "default",
    val subtitle: String = "Ton assistant intelligent",
    val announcement: String = "",
    val announcementVersion: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZodiApp() }
    }
}

@Composable
private fun ZodiApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("zodi_chat", android.content.Context.MODE_PRIVATE) }
    var conversations by remember { mutableStateOf(loadConversations(prefs)) }
    var activeConversationId by remember { mutableStateOf(conversations.first().id) }
    var showConversationHistory by remember { mutableStateOf(false) }
    var historyQuery by remember { mutableStateOf("") }
    var pendingDeleteConversation by remember { mutableStateOf<ChatConversation?>(null) }
    val messages = conversations.firstOrNull { it.id == activeConversationId }?.messages.orEmpty()

    fun persist() {
        val array = JSONArray()
        conversations.forEach { conversation ->
            val messageArray = JSONArray()
            conversation.messages.forEach { message ->
                val imageArray = JSONArray()
                message.images.forEach { image -> imageArray.put(JSONObject().put("dataUrl", image.dataUrl).put("mimeType", image.mimeType).put("name", image.name)) }
                messageArray.put(JSONObject().put("role", message.role).put("content", message.content).put("images", imageArray))
            }
            array.put(JSONObject()
                .put("id", conversation.id)
                .put("title", conversation.title)
                .put("updatedAt", conversation.updatedAt)
                .put("messages", messageArray))
        }
        prefs.edit().putString("conversations", array.toString()).remove("messages").apply()
    }

    fun updateActiveConversation(transform: (ChatConversation) -> ChatConversation) {
        conversations = conversations.map { conversation ->
            if (conversation.id == activeConversationId) transform(conversation) else conversation
        }.sortedByDescending { it.updatedAt }
        persist()
    }

    fun startNewConversation() {
        val id = "conversation-${System.currentTimeMillis()}"
        val conversation = ChatConversation(
            id = id,
            title = "Nouvelle conversation",
            updatedAt = System.currentTimeMillis(),
            messages = listOf(ChatMessage("assistant", "Nouvelle conversation. Je t'écoute !"))
        )
        conversations = listOf(conversation) + conversations
        activeConversationId = id
        persist()
    }

    fun deleteConversation(conversation: ChatConversation) {
        val remaining = conversations.filterNot { it.id == conversation.id }
        if (remaining.isEmpty()) {
            val replacement = ChatConversation(
                id = "conversation-${System.currentTimeMillis()}",
                title = "Nouvelle conversation",
                updatedAt = System.currentTimeMillis(),
                messages = listOf(ChatMessage("assistant", "Nouvelle conversation. Je t'écoute !"))
            )
            conversations = listOf(replacement)
            activeConversationId = replacement.id
        } else {
            conversations = remaining
            if (activeConversationId == conversation.id) activeConversationId = remaining.first().id
        }
        pendingDeleteConversation = null
        persist()
    }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Connexion au serveur…") }
    var selectedImages by remember { mutableStateOf<List<ChatImage>>(emptyList()) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val added = mutableListOf<ChatImage>()
        var totalBytes = selectedImages.sumOf { image ->
            runCatching { Base64.decode(image.dataUrl.substringAfter("base64,"), Base64.DEFAULT).size }.getOrDefault(0)
        }
        for (uri in uris.take((4 - selectedImages.size).coerceAtLeast(0))) {
            val image = runCatching {
                val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
                val scale = minOf(1f, 1600f / maxOf(source.width, source.height))
                val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true) else source
                val output = java.io.ByteArrayOutputStream()
                var quality = 82
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                while (output.size() > 680 * 1024 && quality > 45) { output.reset(); quality -= 8; bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) }
                if (output.size() > 700 * 1024) return@runCatching null
                if (totalBytes + output.size() > 2 * 1024 * 1024) {
                    status = "Les photos jointes ne doivent pas dépasser 2 Mo au total."
                    return@runCatching null
                }
                ChatImage("data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP), "image/jpeg", uri.lastPathSegment ?: "Photo")
            }.getOrNull()
            if (image != null) {
                totalBytes += Base64.decode(image.dataUrl.substringAfter("base64,"), Base64.DEFAULT).size
                added.add(image)
            }
        }
        selectedImages = selectedImages + added
        if (uris.size > added.size && status != "Les photos jointes ne doivent pas dépasser 2 Mo au total.") {
            status = "Certaines photos dépassent la limite de taille ou le maximum de 4."
        }
    }
    var busy by remember { mutableStateOf(false) }
    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }
    val clipboard = remember { context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager }
    var connected by remember { mutableStateOf<Boolean?>(null) }
    var remoteConfig by remember { mutableStateOf(RemoteAppConfig()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    suspend fun refreshRemoteConfig() {
        try {
            val config = withContext(Dispatchers.IO) {
                val connection = (URL("$API_BASE/api/mobile/config").openConnection() as HttpURLConnection)
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Cache-Control", "no-cache")
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw Exception("Configuration distante indisponible ($code)")
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally { connection.disconnect() }
            }
            remoteConfig = RemoteAppConfig(
                configVersion = config.optString("configVersion", "default"),
                subtitle = config.optString("subtitle", "Ton assistant intelligent"),
                announcement = config.optString("announcement", ""),
                announcementVersion = config.optString("announcementVersion", "")
            )
        } catch (_: Exception) {
            // Keep the last known/default configuration if the server is temporarily unavailable.
        }
    }

    suspend fun checkHealth() {
        try {
            val result = withContext(Dispatchers.IO) {
                val connection = (URL("$API_BASE/api/health").openConnection() as HttpURLConnection)
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                try {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(body)
                } finally { connection.disconnect() }
            }
            connected = result.optBoolean("ok") && result.optBoolean("aiConfigured")
            status = if (connected == true) "En ligne · ${result.optString("provider").uppercase()} · ${result.optString("model")}" else "Serveur accessible, IA non configurée"
        } catch (_: Exception) {
            connected = false
            status = "Serveur momentanément inaccessible"
        }
    }

    LaunchedEffect(Unit) {
        // Check updates before optional health/config requests so a slow API
        // cannot delay the release check or make startup updates appear stuck.
        try {
            val updateApk = withContext(Dispatchers.IO) { AppUpdater.downloadIfAvailable(context) }
            if (updateApk != null) {
                status = "Mise à jour disponible · confirmation Android requise"
                AppUpdater.install(context, updateApk)
            }
        } catch (_: Exception) {
            // Keep startup usable when offline or when no release is published.
        }
        checkHealth()
        refreshRemoteConfig()
        while (true) {
            delay(15 * 60 * 1000L)
            refreshRemoteConfig()
        }
    }
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun copyResponse(content: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Réponse de Zodi AI", content))
        status = "Réponse copiée"
    }

    fun shareResponse(content: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content)
            }
            context.startActivity(Intent.createChooser(intent, "Partager le message"))
        }.onFailure { status = "Partage indisponible" }
    }

    fun editUserMessage(messageIndex: Int) {
        val message = messages.getOrNull(messageIndex) ?: return
        if (message.role != "user" || busy) return
        updateActiveConversation { conversation ->
            conversation.copy(
                updatedAt = System.currentTimeMillis(),
                messages = conversation.messages.take(messageIndex)
            )
        }
        input = message.content
        selectedImages = message.images
        contextMenuIndex = null
        status = "Message prêt à modifier"
    }

    fun regenerateResponse(messageIndex: Int) {
        if (busy || messageIndex !in messages.indices) return
        val userIndex = messages.subList(0, messageIndex).indexOfLast { it.role == "user" }
        if (userIndex < 0) return
        val prompt = messages[userIndex]
        val history = messages.take(userIndex).takeLast(10)
        busy = true
        status = "Régénération de la réponse…"
        updateActiveConversation { conversation ->
            conversation.copy(messages = conversation.messages.take(userIndex + 1), updatedAt = System.currentTimeMillis())
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val connection = (URL("$API_BASE/api/chat").openConnection() as HttpURLConnection)
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 70000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val historyJson = JSONArray()
                    history.forEach { item ->
                        val priorImages = JSONArray()
                        item.images.forEach { image -> priorImages.put(JSONObject().put("dataUrl", image.dataUrl).put("mimeType", image.mimeType)) }
                        historyJson.put(JSONObject().put("role", item.role).put("content", item.content).put("images", priorImages))
                    }
                    val imageJson = JSONArray()
                    prompt.images.forEach { image -> imageJson.put(JSONObject().put("dataUrl", image.dataUrl).put("mimeType", image.mimeType)) }
                    val payload = JSONObject().put("message", prompt.content).put("history", historyJson).put("images", imageJson)
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    val json = JSONObject(body)
                    if (code !in 200..299) throw Exception(json.optString("error", "Erreur serveur ($code)"))
                    json.optString("reply").ifBlank { throw Exception("Zodi n'a pas renvoyé de réponse.") }
                }
                updateActiveConversation { it.copy(updatedAt = System.currentTimeMillis(), messages = it.messages + ChatMessage("assistant", result)) }
                status = "Réponse régénérée"
                connected = true
            } catch (error: Exception) {
                updateActiveConversation { it.copy(updatedAt = System.currentTimeMillis(), messages = it.messages + ChatMessage("assistant", error.message ?: "Impossible de régénérer la réponse.")) }
                status = "Échec de la régénération"
                connected = false
            } finally {
                busy = false
            }
        }
    }

    fun sendMessage() {
        val text = input.trim()
        if ((text.isEmpty() && selectedImages.isEmpty()) || busy) return
        val attachments = selectedImages
        val history = messages.takeLast(10)
        val latestImageMessageIndex = if (attachments.isEmpty()) history.indexOfLast { it.images.isNotEmpty() } else -1
        val requestHistory = history.mapIndexed { index, message -> if (index == latestImageMessageIndex) message else message.copy(images = emptyList()) }
        updateActiveConversation { conversation ->
            val updatedMessages = conversation.messages + ChatMessage("user", text.ifBlank { "Analyse les photos jointes." }, attachments)
            val firstUserMessage = updatedMessages.firstOrNull { it.role == "user" }?.content.orEmpty()
            conversation.copy(
                title = firstUserMessage.take(34).ifBlank { "Nouvelle conversation" },
                updatedAt = System.currentTimeMillis(),
                messages = updatedMessages
            )
        }
        input = ""
        selectedImages = emptyList()
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val connection = (URL("$API_BASE/api/chat").openConnection() as HttpURLConnection)
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 70000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val historyJson = JSONArray()
                    requestHistory.filter { it.role == "user" || it.role == "assistant" }.forEach { message ->
                        val priorImages = JSONArray()
                        message.images.forEach { image ->
                            priorImages.put(JSONObject().put("dataUrl", image.dataUrl).put("mimeType", image.mimeType))
                        }
                        historyJson.put(JSONObject()
                            .put("role", message.role)
                            .put("content", message.content)
                            .put("images", priorImages))
                    }
                    val imageJson = JSONArray()
                    attachments.forEach { image -> imageJson.put(JSONObject().put("dataUrl", image.dataUrl).put("mimeType", image.mimeType)) }
                    val payload = JSONObject().put("message", text).put("history", historyJson).put("images", imageJson)
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    val json = JSONObject(body)
                    if (code !in 200..299) throw Exception(json.optString("error", "Erreur serveur ($code)"))
                    json.optString("reply").ifBlank { throw Exception("Zodi n'a pas renvoyé de réponse.") }
                }
                updateActiveConversation { it.copy(updatedAt = System.currentTimeMillis(), messages = it.messages + ChatMessage("assistant", result)) }
                connected = true
                status = "En ligne · réponse reçue"
            } catch (error: Exception) {
                updateActiveConversation { it.copy(updatedAt = System.currentTimeMillis(), messages = it.messages + ChatMessage("assistant", error.message ?: "Impossible de contacter Zodi. Réessaie.")) }
                connected = false
                status = "Échec de la requête"
            } finally {
                busy = false
            }
        }
    }

    val statusColor = when {
        Regex("échec|erreur|impossible|indisponible", RegexOption.IGNORE_CASE).containsMatchIn(status) -> Color(0xFFEF5350)
        Regex("attention|confirmation|mise à jour disponible", RegexOption.IGNORE_CASE).containsMatchIn(status) -> Color(0xFFFFB74D)
        connected == true -> Color(0xFF4CAF50)
        else -> Color(0xFF64B5F6)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Background,
            surface = Surface,
            primary = Purple,
            onBackground = TextMain,
            onSurface = TextMain
        )
    ) {
        Column(
            Modifier.fillMaxSize().background(Background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { historyQuery = ""; showConversationHistory = true },
                    modifier = Modifier.background(SurfaceAlt, RoundedCornerShape(50)),
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp)
                ) { Text("☰", color = TextMain, fontSize = 18.sp) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { startNewConversation() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("＋ Nouveau", color = TextMain, fontSize = 13.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        // Show immediate feedback and do not wait for unrelated remote config.
                        status = "Recherche d'une mise à jour…"
                        try {
                            val apk = withContext(Dispatchers.IO) { AppUpdater.downloadIfAvailable(context) }
                            if (apk != null) {
                                status = "Mise à jour téléchargée · confirmation Android requise"
                                AppUpdater.install(context, apk)
                            } else {
                                status = "Zodi AI est déjà à jour"
                            }
                        } catch (error: Exception) {
                            status = "Mise à jour impossible · ${error.message ?: "erreur inconnue"}"
                        }
                    }
                }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)) { Text("Mettre à jour", color = TextMuted, fontSize = 12.sp, maxLines = 1) }
                TextButton(onClick = { startNewConversation() }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)) {
                    Text("Nouveau", color = TextMuted, fontSize = 12.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(status, color = TextMuted, fontSize = 12.sp, maxLines = 2, lineHeight = 17.sp)
            }
            if (remoteConfig.announcement.isNotBlank() && prefs.getString("dismissed_announcement", "") != remoteConfig.announcementVersion) {
                Row(
                    Modifier.fillMaxWidth().background(SurfaceAlt, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Nouveauté", color = Color(0xFF10A37F), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(remoteConfig.announcement, color = TextMain, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    TextButton(onClick = {
                        prefs.edit().putString("dismissed_announcement", remoteConfig.announcementVersion).apply()
                        remoteConfig = remoteConfig.copy(announcement = "")
                    }) { Text("OK", color = TextMuted) }
                }
                Spacer(Modifier.height(12.dp))
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(messages) { messageIndex, message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start) {
                        Column(Modifier.fillMaxWidth(0.9f).widthIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (message.role == "user") Color(0xFF263B63) else Color.Transparent, RoundedCornerShape(22.dp))
                                    .border(if (message.role == "user") 1.dp else 0.dp, if (message.role == "user") Color(0xFF3B82F6) else Color.Transparent, RoundedCornerShape(22.dp))
                                    .then(if (message.role == "user") Modifier.combinedClickable(
                                        onClick = {},
                                        onLongClick = { contextMenuIndex = messageIndex }
                                    ) else Modifier)
                                    .padding(horizontal = if (message.role == "user") 15.dp else 0.dp, vertical = if (message.role == "user") 12.dp else 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                message.images.forEach { image ->
                                    runCatching { Base64.decode(image.dataUrl.substringAfter("base64,"), Base64.DEFAULT) }.getOrNull()?.let { bytes ->
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                                            Image(bitmap.asImageBitmap(), contentDescription = image.name, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp))
                                        }
                                    }
                                }
                                Text(message.content, color = TextMain, fontSize = 15.sp, lineHeight = 22.sp)
                            }
                            if (message.role == "user") {
                                DropdownMenu(
                                    expanded = contextMenuIndex == messageIndex,
                                    onDismissRequest = { contextMenuIndex = null }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Copier") },
                                        onClick = { copyResponse(message.content); contextMenuIndex = null }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Modifier le message") },
                                        onClick = { editUserMessage(messageIndex) },
                                        enabled = !busy
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Partager le prompt") },
                                        onClick = { shareResponse(message.content); contextMenuIndex = null }
                                    )
                                }
                            }
                            if (message.role == "assistant" && messageIndex > 0) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { copyResponse(message.content) }, contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp)) {
                                        Text("▢ Copier", color = TextMuted, fontSize = 10.sp)
                                    }
                                    TextButton(onClick = { regenerateResponse(messageIndex) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)) {
                                        Text("↻", color = TextMuted, fontSize = 17.sp)
                                    }
                                    TextButton(onClick = { shareResponse(message.content) }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)) {
                                        Text("↗", color = TextMuted, fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                if (busy) item {
                    Text("Zodi réfléchit…", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                }
            }
            if (selectedImages.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    selectedImages.forEachIndexed { index, image ->
                        TextButton(onClick = { selectedImages = selectedImages.filterIndexed { i, _ -> i != index } }) {
                            Text("📷 ${index + 1} ×", color = TextMain, fontSize = 12.sp)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp).background(Color(0xFF292D35), RoundedCornerShape(28.dp)).border(1.dp, Color(0xFF46536A), RoundedCornerShape(28.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextMain, fontSize = 15.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Purple),
                    decorationBox = { inner ->
                        if (input.isEmpty()) Text("Écris à Zodi…", color = TextMuted, fontSize = 15.sp)
                        inner()
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                    enabled = !busy
                )
                TextButton(onClick = { imagePicker.launch("image/*") }, enabled = !busy && selectedImages.size < 4) {
                    Text("＋", color = Color(0xFFDBEAFE), fontSize = 22.sp)
                }
                Button(
                    onClick = { sendMessage() },
                    enabled = (input.isNotBlank() || selectedImages.isNotEmpty()) && !busy,
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB), contentColor = Color.White, disabledContainerColor = Color(0xFF46536A)),
                    contentPadding = PaddingValues(0.dp)
                ) { Text(if (busy) "…" else "↑", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            }

            pendingDeleteConversation?.let { conversation ->
                AlertDialog(
                    onDismissRequest = { pendingDeleteConversation = null },
                    containerColor = Surface,
                    title = { Text("Supprimer cette conversation ?", color = TextMain) },
                    text = {
                        Text(
                            "« ${conversation.title} » sera supprimée de cet appareil. Cette action est définitive.",
                            color = TextMuted
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { deleteConversation(conversation) }) {
                            Text("Supprimer", color = Color(0xFFEF5350))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteConversation = null }) {
                            Text("Annuler", color = TextMuted)
                        }
                    }
                )
            }

            if (showConversationHistory) {
                AlertDialog(
                    onDismissRequest = { showConversationHistory = false },
                    containerColor = Surface,
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Tes conversations", color = TextMain)
                            OutlinedTextField(
                                value = historyQuery,
                                onValueChange = { historyQuery = it },
                                placeholder = { Text("Rechercher…") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    text = {
                        val filteredConversations = conversations.filter { conversation ->
                            conversation.title.contains(historyQuery, ignoreCase = true) ||
                                conversation.messages.any { it.content.contains(historyQuery, ignoreCase = true) }
                        }
                        if (filteredConversations.isEmpty()) {
                            Text(if (conversations.isEmpty()) "Aucune conversation enregistrée." else "Aucun résultat.", color = TextMuted)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(filteredConversations, key = { it.id }) { conversation ->
                                    val preview = conversation.messages.lastOrNull()?.content.orEmpty()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = {
                                                activeConversationId = conversation.id
                                                showConversationHistory = false
                                                input = ""
                                            },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Column(Modifier.fillMaxWidth()) {
                                                Text(
                                                    conversation.title,
                                                    color = if (conversation.id == activeConversationId) Color(0xFFB8AEFF) else TextMain,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1
                                                )
                                                Text(preview, color = TextMuted, fontSize = 11.sp, maxLines = 2)
                                            }
                                        }
                                        TextButton(
                                            onClick = { pendingDeleteConversation = conversation },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                                        ) {
                                            Text("🗑", color = Color(0xFFEF5350), fontSize = 16.sp)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showConversationHistory = false
                            startNewConversation()
                        }) { Text("Nouvelle conversation") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConversationHistory = false }) { Text("Fermer") }
                    }
                )
            }

        }
    }
}
