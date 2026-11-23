package fr.zodi.ai

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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val API_BASE = "https://zodi-ai-core.vercel.app"
private val Background = Color(0xFF090D18)
private val Surface = Color(0xFF121A2B)
private val SurfaceAlt = Color(0xFF202B40)
private val Purple = Color(0xFF7664F5)
private val TextMain = Color(0xFFF1F4FF)
private val TextMuted = Color(0xFF9AA8C5)
private val Green = Color(0xFF43D49A)

data class ChatMessage(val role: String, val content: String)

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
    val messages = remember {
        mutableStateListOf<ChatMessage>().apply {
            val raw = prefs.getString("messages", null)
            if (raw != null) {
                runCatching {
                    val array = JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        add(ChatMessage(item.getString("role"), item.getString("content")))
                    }
                }
            }
            if (isEmpty()) add(ChatMessage("assistant", "Salut ! Je suis Zodi. Que veux-tu faire aujourd'hui ?"))
        }
    }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf("Connexion au serveur…") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun persist() {
        val array = JSONArray()
        messages.forEach { item ->
            array.put(JSONObject().put("role", item.role).put("content", item.content))
        }
        prefs.edit().putString("messages", array.toString()).apply()
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

    LaunchedEffect(Unit) { checkHealth() }
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun sendMessage() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        val history = messages.toList().takeLast(10)
        messages.add(ChatMessage("user", text))
        input = ""
        busy = true
        persist()
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
                messages.add(ChatMessage("assistant", result))
                connected = true
                status = "En ligne · réponse reçue"
            } catch (error: Exception) {
                messages.add(ChatMessage("assistant", error.message ?: "Impossible de contacter Zodi. Réessaie."))
                connected = false
                status = "Échec de la requête"
            } finally {
                busy = false
                persist()
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
                    Text("Ton assistant intelligent", color = TextMuted, fontSize = 12.sp)
                }
                TextButton(onClick = {
                    messages.clear()
                    messages.add(ChatMessage("assistant", "Nouvelle conversation. Je t'écoute !"))
                    persist()
                }) { Text("Nouveau", color = TextMuted) }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().background(Surface, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).background(if (connected == true) Green else Color(0xFFF4B942), CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(status, color = TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
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
                if (busy) item {
                    Text("Zodi réfléchit…", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                }
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
                Button(
                    onClick = { sendMessage() },
                    enabled = input.isNotBlank() && !busy,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 12.dp)
                ) { Text(if (busy) "…" else "Envoyer", fontWeight = FontWeight.SemiBold) }
            }
            Text(
                "Tes conversations restent enregistrées sur cet appareil. Les clés API restent protégées sur le serveur.",
                color = TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}
