package fr.zodi.ai

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.util.UUID

private const val API_BASE = "https://zodi-ai-core.vercel.app"
private val Background = Color(0xFF090D18)
private val Surface = Color(0xFF121A2B)
private val SurfaceAlt = Color(0xFF202B40)
private val Purple = Color(0xFF7664F5)
private val TextMain = Color(0xFFF1F4FF)
private val TextMuted = Color(0xFF9AA8C5)
private val Green = Color(0xFF43D49A)

data class ChatMessage(val role: String, val content: String)
data class ChatConversation(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val updatedAt: Long
)

data class RemoteAppConfig(
    val configVersion: String = "default",
    val subtitle: String = "Ton assistant intelligent",
    val announcement: String = "",
    val announcementVersion: String = ""
)

private fun makeConversationTitle(value: String): String {
    val title = value.replace(Regex("\\s+"), " ").trim()
    return if (title.length > 42) title.take(39) + "…" else title.ifBlank { "Nouvelle conversation" }
}

private fun createChatConversation(): ChatConversation {
    val now = System.currentTimeMillis()
    return ChatConversation(
        id = UUID.randomUUID().toString(),
        title = "Nouvelle conversation",
        messages = listOf(ChatMessage("assistant", "Salut ! Je suis Zodi. Que veux-tu faire aujourd'hui ?")),
        updatedAt = now
    )
}

private fun loadChatConversations(prefs: SharedPreferences): List<ChatConversation> {
    val saved = prefs.getString("conversations", null)
    if (!saved.isNullOrBlank()) {
        runCatching {
            val array = JSONArray(saved)
            val result = mutableListOf<ChatConversation>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val messagesJson = item.optJSONArray("messages") ?: JSONArray()
                val messages = buildList {
                    for (j in 0 until messagesJson.length()) {
                        val message = messagesJson.optJSONObject(j) ?: continue
                        val role = message.optString("role")
                        val text = message.optString("content")
                        if ((role == "user" || role == "assistant") && text.isNotBlank()) add(ChatMessage(role, text))
                    }
                }
                result.add(ChatConversation(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = item.optString("title").ifBlank { "Nouvelle conversation" },
                    messages = messages.ifEmpty { createChatConversation().messages },
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis())
                ))
            }
            if (result.isNotEmpty()) return result.sortedByDescending { it.updatedAt }
        }
    }

    // Migration: keep the single conversation saved by earlier Android versions.
    val legacy = prefs.getString("messages", null)
    if (!legacy.isNullOrBlank()) {
        runCatching {
            val array = JSONArray(legacy)
            val messages = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    val text = item.optString("content")
                    if ((role == "user" || role == "assistant") && text.isNotBlank()) add(ChatMessage(role, text))
                }
            }
            if (messages.isNotEmpty()) return listOf(ChatConversation(
                UUID.randomUUID().toString(),
                "Conversation précédente",
                messages,
                System.currentTimeMillis()
            ))
        }
    }
    return listOf(createChatConversation())
}

private fun saveChatConversations(
    prefs: SharedPreferences,
    conversations: List<ChatConversation>,
    activeId: String
) {
    val array = JSONArray()
    conversations.forEach { conversation ->
        val messages = JSONArray()
        conversation.messages.forEach { message ->
            messages.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        array.put(JSONObject()
            .put("id", conversation.id)
            .put("title", conversation.title)
            .put("updatedAt", conversation.updatedAt)
            .put("messages", messages))
    }
    prefs.edit()
        .putString("conversations", array.toString())
        .putString("active_conversation_id", activeId)
        .apply()
}

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
    val initialConversations = remember { loadChatConversations(prefs) }
    var conversations by remember { mutableStateOf(initialConversations) }
    var activeId by remember {
        mutableStateOf(
            prefs.getString("active_conversation_id", null)
                ?.takeIf { id -> initialConversations.any { it.id == id } }
                ?: initialConversations.first().id
        )
    }
    val activeConversation = conversations.firstOrNull { it.id == activeId } ?: conversations.first()
    val messages = activeConversation.messages
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf("Connexion au serveur…") }
    var remoteConfig by remember { mutableStateOf(RemoteAppConfig()) }
    var showHistory by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ChatConversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatConversation?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun commitConversations(updated: List<ChatConversation>, selectedId: String = activeId) {
        conversations = updated
        activeId = selectedId
        saveChatConversations(prefs, updated, selectedId)
    }

    fun updateConversation(id: String, transform: (ChatConversation) -> ChatConversation) {
        val updated = conversations.map { item -> if (item.id == id) transform(item) else item }
        commitConversations(updated)
    }

    fun startNewConversation() {
        val fresh = createChatConversation()
        commitConversations(listOf(fresh) + conversations, fresh.id)
        input = ""
        showHistory = false
    }

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
        checkHealth()
        refreshRemoteConfig()
        while (true) {
            delay(15 * 60 * 1000L)
            refreshRemoteConfig()
        }
    }
    LaunchedEffect(activeId, messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun sendMessage() {
        val text = input.trim()
        val conversation = activeConversation
        if (text.isEmpty() || busy) return
        val history = conversation.messages.takeLast(10)
        val outgoing = conversation.messages + ChatMessage("user", text)
        val title = if (conversation.title == "Nouvelle conversation") makeConversationTitle(text) else conversation.title
        updateConversation(conversation.id) { it.copy(title = title, messages = outgoing, updatedAt = System.currentTimeMillis()) }
        input = ""
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
                    history.filter { it.role == "user" || it.role == "assistant" }.forEach {
                        historyJson.put(JSONObject().put("role", it.role).put("content", it.content))
                    }
                    val payload = JSONObject().put("message", text).put("history", historyJson)
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream.bufferedReader().use { it.readText() }
                    connection.disconnect()
                    val json = JSONObject(body)
                    if (code !in 200..299) throw Exception(json.optString("error", "Erreur serveur ($code)"))
                    json.optString("reply").ifBlank { throw Exception("Zodi n'a pas renvoyé de réponse.") }
                }
                updateConversation(conversation.id) { it.copy(messages = it.messages + ChatMessage("assistant", result), updatedAt = System.currentTimeMillis()) }
                connected = true
                status = "En ligne · réponse reçue"
            } catch (error: Exception) {
                updateConversation(conversation.id) { it.copy(messages = it.messages + ChatMessage("assistant", error.message ?: "Impossible de contacter Zodi. Réessaie."), updatedAt = System.currentTimeMillis()) }
                connected = false
                status = "Échec de la requête"
            } finally {
                busy = false
            }
        }
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
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111A32), Background)))
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier.size(48.dp).background(Brush.linearGradient(listOf(Purple, Color(0xFF20C5C8))), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) { Text("✦", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Zodi AI", color = TextMain, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text(remoteConfig.subtitle, color = TextMuted, fontSize = 12.sp)
                }
                TextButton(onClick = { scope.launch { refreshRemoteConfig() } }) { Text("Mettre à jour", color = TextMuted) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showHistory = true }, modifier = Modifier.weight(1f)) {
                    Text("Historique · ${conversations.size}", color = TextMain)
                }
                Button(onClick = { startNewConversation() }, shape = RoundedCornerShape(12.dp)) {
                    Text("＋ Nouveau")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).background(if (connected == true) Green else Color(0xFFF4B942), CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(status, color = TextMuted, fontSize = 12.sp)
            }
            if (remoteConfig.announcement.isNotBlank() && prefs.getString("dismissed_announcement", "") != remoteConfig.announcementVersion) {
                Row(
                    Modifier.fillMaxWidth().background(SurfaceAlt, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Nouveauté", color = Color(0xFFB8AEFF), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(remoteConfig.announcement, color = TextMain, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    TextButton(onClick = {
                        prefs.edit().putString("dismissed_announcement", remoteConfig.announcementVersion).apply()
                        remoteConfig = remoteConfig.copy(announcement = "")
                    }) { Text("OK", color = TextMuted) }
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(activeConversation.title, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start) {
                        Text(
                            message.content,
                            color = TextMain,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.widthIn(max = 320.dp)
                                .background(if (message.role == "user") Color(0xFF5548B8) else SurfaceAlt, RoundedCornerShape(18.dp))
                                .padding(horizontal = 15.dp, vertical = 12.dp)
                        )
                    }
                }
                if (busy) item { Text("Zodi réfléchit…", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp).background(Surface, RoundedCornerShape(18.dp)).padding(8.dp),
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
                Button(onClick = { sendMessage() }, enabled = input.isNotBlank() && !busy, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 17.dp, vertical = 12.dp)) {
                    Text(if (busy) "…" else "Envoyer", fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                "Configuration distante v${remoteConfig.configVersion} · Historique conservé sur cet appareil. Les clés API restent sur le serveur.",
                color = TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        if (showHistory) {
            AlertDialog(
                onDismissRequest = { showHistory = false },
                title = { Text("Tes conversations") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(conversations.sortedByDescending { it.updatedAt }, key = { it.id }) { item ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                                Text(item.title, color = TextMain, fontWeight = FontWeight.SemiBold)
                                Text(item.messages.lastOrNull()?.content?.take(72) ?: "", color = TextMuted, fontSize = 11.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { activeId = item.id; saveChatConversations(prefs, conversations, item.id); showHistory = false }) { Text("Ouvrir") }
                                    TextButton(onClick = { renameTarget = item; renameText = item.title; showHistory = false }) { Text("Renommer") }
                                    TextButton(onClick = { deleteTarget = item; showHistory = false }) { Text("Supprimer", color = Color(0xFFFF9B9B)) }
                                }
                            }
                            HorizontalDivider(color = SurfaceAlt)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Fermer") } }
            )
        }

        renameTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("Renommer la conversation") },
                text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text("Nom") }) },
                confirmButton = {
                    TextButton(onClick = {
                        val title = makeConversationTitle(renameText)
                        val updated = conversations.map { if (it.id == target.id) it.copy(title = title, updatedAt = System.currentTimeMillis()) else it }
                        commitConversations(updated)
                        renameTarget = null
                    }) { Text("Enregistrer") }
                },
                dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Annuler") } }
            )
        }

        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("Supprimer cette conversation ?") },
                text = { Text("« ${target.title} » sera supprimée de cet appareil.") },
                confirmButton = {
                    TextButton(onClick = {
                        val remaining = conversations.filterNot { it.id == target.id }
                        if (remaining.isEmpty()) {
                            val fresh = createChatConversation()
                            commitConversations(listOf(fresh), fresh.id)
                        } else {
                            val selected = if (activeId == target.id) remaining.maxBy { it.updatedAt }.id else activeId
                            commitConversations(remaining, selected)
                        }
                        deleteTarget = null
                    }) { Text("Supprimer", color = Color(0xFFFF9B9B)) }
                },
                dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Annuler") } }
            )
        }
    }
}
