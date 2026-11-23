package br.com.curiosidades.narrador

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import br.com.curiosidades.narrador.core.*
import br.com.curiosidades.narrador.data.Db
import br.com.curiosidades.narrador.data.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Editor(val id: Long) : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val dao = Db.get(ctx).dao()

    val projects: StateFlow<List<Project>> = dao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val currentId = MutableStateFlow(-1L)
    val current: StateFlow<Project?> = combine(projects, currentId) { l, id -> l.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val gen = Generator.state
    val modelState = Models.state

    var screen by mutableStateOf<Screen>(Screen.Home); private set
    var name by mutableStateOf(""); private set
    var text by mutableStateOf(""); private set
    var voice by mutableStateOf(Voice.DORA); private set
    var speed by mutableFloatStateOf(1.0f); private set
    var threads by mutableIntStateOf(Prefs.threads(ctx)); private set
    var previewing by mutableStateOf(false); private set
    var snack by mutableStateOf<String?>(null); private set
    var nowPlaying by mutableStateOf(""); private set

    val player: ExoPlayer = ExoPlayer.Builder(ctx).build()

    init {
        Models.refresh(ctx)
        if (!Generator.state.value.running) viewModelScope.launch { dao.markInterrupted() }
    }

    override fun onCleared() { player.release(); super.onCleared() }

    fun clearSnack() { snack = null }
    private fun busyGen() = Generator.state.value.running

    // ---------- navegação ----------
    fun back() { flush(); screen = Screen.Home; currentId.value = -1L }
    fun openSettings() { screen = Screen.Settings }

    fun newProject() {
        viewModelScope.launch {
            val stamp = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date())
            val id = dao.insert(Project(name = "Novo projeto $stamp"))
            openProject(id)
        }
    }

    fun openProject(id: Long) {
        viewModelScope.launch {
            val p = dao.get(id) ?: return@launch
            player.stop(); nowPlaying = ""
            name = p.name; text = p.text; voice = Voice.fromId(p.voice); speed = p.speed
            currentId.value = id
            screen = Screen.Editor(id)
        }
    }

    fun rename(id: Long, newName: String) {
        viewModelScope.launch { dao.rename(id, newName.ifBlank { "Sem título" }, System.currentTimeMillis()) }
    }

    fun delete(id: Long) {
        if (busyGen() && Generator.state.value.projectId == id) { snack = "Pare a geração antes de excluir."; return }
        viewModelScope.launch(Dispatchers.IO) {
            dao.delete(id)
            Generator.projectDir(ctx, id).deleteRecursively()
        }
    }

    // ---------- editor (salvamento automático) ----------
    private var saveJob: Job? = null
    private fun scheduleSave() { saveJob?.cancel(); saveJob = viewModelScope.launch { delay(600); saveNow() } }
    private suspend fun saveNow() {
        val id = currentId.value
        if (id > 0) dao.saveEditor(id, name.ifBlank { "Sem título" }, text, voice.id, speed, System.currentTimeMillis())
    }
    fun flush() { saveJob?.cancel(); viewModelScope.launch { saveNow() } }

    fun onName(v: String) { name = v; scheduleSave() }
    fun onText(v: String) { text = v; scheduleSave() }
    fun pickVoice(v: Voice) { voice = v; scheduleSave() }
    fun onSpeed(v: Float) { speed = (v * 20).roundToInt() / 20f; scheduleSave() }

    // ---------- geração ----------
    fun generate(force: Boolean) {
        viewModelScope.launch {
            saveJob?.cancel(); saveNow()
            if (modelState.value !is Models.State.Ready) { snack = "Instale o modelo de voz primeiro."; return@launch }
            if (busyGen()) { snack = "Já existe uma geração em andamento."; return@launch }
            if (text.isBlank()) { snack = "Escreva ou cole um texto."; return@launch }
            player.stop(); nowPlaying = ""
            Generator.start(ctx, currentId.value, force)
        }
    }

    fun stopGeneration() = Generator.stop(ctx)

    fun preview() {
        if (modelState.value !is Models.State.Ready) { snack = "Instale o modelo de voz primeiro."; return }
        if (busyGen() || previewing) return
        val txt = text; val v = voice; val sp = speed
        previewing = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val first = TextChunker.split(txt, 220).firstOrNull() ?: run { snack = "Escreva um texto para a prévia."; return@launch }
                val tts = Engine.get(ctx)
                val a = Engine.synth(tts, first.text, v.sid, sp)
                ctx.cacheDir.listFiles { f -> f.name.startsWith("previa") }?.forEach { it.delete() }
                val pcm = File(ctx.cacheDir, "previa.pcm")
                pcm.writeBytes(Wav.floatToPcm(a.samples))
                val wav = File(ctx.cacheDir, "previa-${System.currentTimeMillis()}.wav")
                Wav.write(wav, listOf(pcm), a.sampleRate)
                pcm.delete()
                withContext(Dispatchers.Main) { play(wav, "Prévia") }
            } catch (e: Throwable) {
                snack = "Erro na prévia: ${e.message ?: e.javaClass.simpleName}"
            } finally { previewing = false }
        }
    }

    // ---------- player / exportação ----------
    private fun play(f: File, label: String) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
        player.prepare(); player.play()
        nowPlaying = label
    }

    fun playNarration() {
        val p = current.value ?: return
        val f = File(p.audioPath)
        if (!f.exists()) { snack = "Arquivo de áudio não encontrado."; return }
        play(f, "Narração completa")
    }

    fun exportWav(uri: Uri) {
        val p = current.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val src = File(p.audioPath)
                ctx.contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o, 1 shl 16) } }
                snack = "WAV salvo."
            } catch (e: Exception) { snack = "Falha ao salvar: ${e.message}" }
        }
    }

    // ---------- configurações / modelo ----------
    fun installModel() { viewModelScope.launch { Models.install(ctx) } }

    fun deleteModel() {
        if (busyGen()) { snack = "Pare a geração antes."; return }
        Models.delete(ctx)
    }

    fun verifyModel() { viewModelScope.launch { snack = Models.verify(ctx) } }

    fun setThreads(n: Int) {
        if (busyGen()) { snack = "Pare a geração antes."; return }
        Prefs.setThreads(ctx, n); threads = n; Engine.release()
    }

    fun clearTemp() {
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        snack = "Arquivos temporários removidos."
    }
}
