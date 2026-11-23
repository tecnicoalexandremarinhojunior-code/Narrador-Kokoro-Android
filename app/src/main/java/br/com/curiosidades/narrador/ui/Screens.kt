package br.com.curiosidades.narrador.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import br.com.curiosidades.narrador.AppViewModel
import br.com.curiosidades.narrador.Screen
import br.com.curiosidades.narrador.core.*
import br.com.curiosidades.narrador.data.Project
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private fun clock(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
}

private fun statusLabel(s: String) = when (s) {
    "GERANDO" -> "Gerando…"; "PAUSADO" -> "Pausado"; "PRONTO" -> "Pronto"; "ERRO" -> "Erro"; else -> "Rascunho"
}

@Composable
fun Root(vm: AppViewModel) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(vm.snack) { vm.snack?.let { host.showSnackbar(it); vm.clearSnack() } }
    BackHandler(vm.screen != Screen.Home) { vm.back() }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding()
    ) {
        when (vm.screen) {
            Screen.Home -> HomeScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
            is Screen.Editor -> EditorScreen(vm)
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ModelCard(vm: AppViewModel, alwaysShow: Boolean = false) {
    val st by vm.modelState.collectAsStateWithLifecycle()
    if (st is Models.State.Ready && !alwaysShow) return
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = st) {
                Models.State.Ready -> Text("✔ Modelo Kokoro instalado — geração 100% offline.")
                Models.State.Missing -> {
                    Text("Modelo de voz não instalado", fontWeight = FontWeight.SemiBold)
                    Text("Baixe uma única vez (~132 MB; use Wi-Fi). Depois disso tudo funciona sem internet.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { vm.installModel() }) { Text("Baixar modelo") }
                }
                is Models.State.Busy -> {
                    Text(s.label)
                    if (s.progress >= 0) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is Models.State.Error -> {
                    Text("Erro: ${s.msg}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.installModel() }) { Text("Tentar novamente") }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    val list by vm.projects.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Narrador Kokoro", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Narração por IA, direto no celular", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { vm.openSettings() }) { Icon(Icons.Default.Settings, "Configurações") }
        }
        ModelCard(vm)
        Button(onClick = { vm.newProject() }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Novo projeto")
        }
        if (list.isEmpty()) Text("Nenhum projeto ainda. Toque em “Novo projeto” para começar.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.clickable { vm.openProject(p.id) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${Voice.fromId(p.voice).label} • ${"%.2f".format(p.speed)}× • ${p.text.length} caracteres",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("${statusLabel(p.status)} • ${fmt.format(Date(p.updatedAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p.totalChunks > 0 && p.status != "PRONTO")
                            LinearProgressIndicator(progress = { p.doneChunks.toFloat() / p.totalChunks }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        if (p.status == "ERRO" && p.error.isNotBlank()) Text(p.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { vm.openProject(p.id) }) { Text(if (p.status == "PAUSADO") "Continuar" else "Abrir") }
                            TextButton(onClick = { renaming = p }) { Text("Renomear") }
                            TextButton(onClick = { deleting = p }) { Text("Excluir") }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { p ->
        var v by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renomear projeto") },
            text = { OutlinedTextField(v, { v = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(p.id, v); renaming = null }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } }
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir projeto?") },
            text = { Text("“${p.name}” e o áudio gerado serão apagados. Isso não pode ser desfeito.") },
            confirmButton = { TextButton(onClick = { vm.delete(p.id); deleting = null }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
fun EditorScreen(vm: AppViewModel) {
    val p by vm.current.collectAsStateWithLifecycle()
    val g by vm.gen.collectAsStateWithLifecycle()
    val model by vm.modelState.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri -> if (uri != null) vm.exportWav(uri) }
    val proj = p

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("Editor") { vm.back() }
        OutlinedTextField(vm.name, { vm.onName(it) }, label = { Text("Nome do projeto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            vm.text, { vm.onText(it) }, label = { Text("Roteiro") },
            placeholder = { Text("Cole ou escreva o texto da narração…") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 360.dp)
        )
        val blocks = remember(vm.text) { TextChunker.split(vm.text).size }
        val secs = (vm.text.length / (15.0 * vm.speed)).toLong()
        Text(
            "${vm.text.length} caracteres • $blocks blocos • duração estimada ~${fmtSecs(secs)}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("Voz", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Voice.entries.forEach { v ->
                FilterChip(selected = vm.voice == v, onClick = { vm.pickVoice(v) }, label = { Text(v.label) }, modifier = Modifier.weight(1f))
            }
        }
        Text("${vm.voice.desc} • ${vm.voice.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Velocidade: ${"%.2f".format(vm.speed)}×", fontWeight = FontWeight.SemiBold)
        Slider(value = vm.speed, onValueChange = { vm.onSpeed(it) }, valueRange = 0.7f..1.3f, steps = 11)
        Text("Controle nativo do Kokoro. Valores muito distantes de 1,00 podem reduzir a naturalidade.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ModelCard(vm)

        if (proj != null) {
            val running = g.running && g.projectId == proj.id
            val same = proj.chunkKey == chunkKey(vm.text, vm.voice.id, vm.speed)
            val force = proj.status == "PRONTO" && same
            val label = when {
                proj.status == "PRONTO" && same -> "Gerar novamente"
                proj.status == "PRONTO" -> "Gerar de novo (texto alterado)"
                same && proj.doneChunks > 0 && proj.doneChunks < proj.totalChunks -> "Continuar de onde parou"
                else -> "Gerar narração"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { vm.preview() },
                    enabled = model is Models.State.Ready && !g.running && !vm.previewing,
                    modifier = Modifier.weight(1f)
                ) { Text(if (vm.previewing) "Gerando…" else "Prévia") }
                if (running) {
                    Button(onClick = { vm.stopGeneration() }, modifier = Modifier.weight(2f)) { Text("Pausar geração") }
                } else {
                    Button(onClick = { vm.generate(force) }, enabled = !g.running, modifier = Modifier.weight(2f)) { Text(label) }
                }
            }
            if (running) {
                val frac = if (g.total > 0) g.done.toFloat() / g.total else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Text(
                    g.message + if (g.remainingSec > 0) " • restam ~${fmtSecs(g.remainingSec)}" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                if (proj.status == "ERRO") Text("Erro: ${proj.error}", color = MaterialTheme.colorScheme.error)
                if (proj.totalChunks > 0 && proj.status != "PRONTO")
                    Text("Progresso salvo: ${proj.doneChunks} de ${proj.totalChunks} blocos.", style = MaterialTheme.typography.bodyMedium)
            }

            val audioOk = proj.status == "PRONTO" && proj.audioPath.isNotBlank() && File(proj.audioPath).exists()
            if (audioOk) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Narração pronta", fontWeight = FontWeight.SemiBold)
                        Text("WAV 24 kHz • ${"%.0f".format(File(proj.audioPath).length() / 1_000_000.0)} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.playNarration() }) { Text("Ouvir") }
                            OutlinedButton(onClick = { export.launch(vm.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").ifBlank { "narracao" } + ".wav") }) { Text("Salvar WAV") }
                            OutlinedButton(onClick = {}, enabled = false) { Text("MP3 (em breve)") }
                        }
                    }
                }
            }
        }
        if (vm.nowPlaying.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Player — ${vm.nowPlaying}", fontWeight = FontWeight.SemiBold)
                    PlayerControls(vm)
                }
            }
        }
    }
}

@Composable
fun PlayerControls(vm: AppViewModel) {
    val p = vm.player
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var vol by remember { mutableFloatStateOf(p.volume) }
    LaunchedEffect(Unit) {
        while (true) {
            if (!seeking) pos = p.currentPosition
            dur = p.duration.coerceAtLeast(0L)
            playing = p.isPlaying
            delay(400)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Slider(
            value = pos.toFloat().coerceAtMost(max(dur, 1L).toFloat()),
            onValueChange = { seeking = true; pos = it.toLong() },
            onValueChangeFinished = { p.seekTo(pos); seeking = false },
            valueRange = 0f..max(dur, 1L).toFloat()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(pos), style = MaterialTheme.typography.bodySmall); Text(clock(dur), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { p.seekTo((p.currentPosition - 15_000).coerceAtLeast(0)) }) { Text("−15 s") }
            Button(onClick = {
                if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
            }) { Text(if (playing) "⏸ Pausar" else "▶ Tocar") }
            OutlinedButton(onClick = { p.seekTo(p.currentPosition + 15_000) }) { Text("+15 s") }
        }
        Text("Volume", style = MaterialTheme.typography.bodySmall)
        Slider(value = vol, onValueChange = { vol = it; p.volume = it }, valueRange = 0f..1f)
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val model by vm.modelState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header("Configurações") { vm.back() }
        Text("Modelo de voz", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ModelCard(vm, alwaysShow = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.verifyModel() }, enabled = model is Models.State.Ready) { Text("Verificar integridade") }
            OutlinedButton(onClick = { vm.deleteModel() }, enabled = model is Models.State.Ready) { Text("Excluir modelo") }
        }
        Text("Desempenho", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text("Threads de CPU: ${vm.threads}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2, 3, 4, 6).forEach { n -> FilterChip(selected = vm.threads == n, onClick = { vm.setThreads(n) }, label = { Text("$n") }) }
        }
        Text("Mais threads aceleram, mas aquecem mais e gastam mais bateria. O melhor valor para o Galaxy A55 será definido em teste.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Armazenamento", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        OutlinedButton(onClick = { vm.clearTemp() }) { Text("Limpar arquivos temporários") }
        Text("Sobre", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Kokoro-82M via Sherpa-ONNX (Apache-2.0). Vozes: pf_dora, pm_alex, pm_santa. " +
                "A fonemização usa espeak-ng (GPL-3.0): verifique as obrigações de licença antes de distribuir o APK a terceiros.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
