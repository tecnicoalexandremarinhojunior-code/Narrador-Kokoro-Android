package br.com.curiosidades.narrador.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Download, extração e verificação do modelo Kokoro (fonte oficial: k2-fsa/sherpa-onnx). */
object Models {
    const val URL_MODEL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
    private const val MIN_MODEL_BYTES = 80_000_000L

    sealed interface State {
        data object Missing : State
        data class Busy(val label: String, val progress: Float) : State // progress < 0 = indeterminado
        data object Ready : State
        data class Error(val msg: String) : State
    }

    val state = MutableStateFlow<State>(State.Missing)

    fun dir(c: Context) = File(c.filesDir, "kokoro")
    fun modelFile(c: Context): File? =
        dir(c).listFiles { f -> f.name.startsWith("model") && f.name.endsWith(".onnx") }?.firstOrNull()

    private fun marker(c: Context): Map<String, String> {
        val f = File(dir(c), ".ok")
        if (!f.exists()) return emptyMap()
        return f.readLines().mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }

    fun refresh(c: Context) {
        if (state.value is State.Busy) return
        val d = dir(c)
        val m = modelFile(c)
        val mk = marker(c)
        val ok = m != null && m.length() >= MIN_MODEL_BYTES && mk["model_size"] == m.length().toString() &&
            File(d, "voices.bin").exists() && File(d, "tokens.txt").exists() && File(d, "espeak-ng-data").isDirectory
        state.value = if (ok) State.Ready else State.Missing
    }

    suspend fun install(c: Context) = withContext(Dispatchers.IO) {
        if (state.value is State.Busy) return@withContext
        val part = File(c.filesDir, "kokoro.tar.bz2.part")
        val tmp = File(c.filesDir, "kokoro.tmp")
        try {
            if (c.filesDir.usableSpace < 450_000_000L) throw IOException("Espaço insuficiente: são necessários cerca de 450 MB livres.")
            download(part)
            state.value = State.Busy("Verificando arquivo baixado…", -1f)
            val archiveSha = sha256(part)
            state.value = State.Busy("Extraindo modelo…", -1f)
            tmp.deleteRecursively(); tmp.mkdirs()
            try { extract(part, tmp) } catch (e: Exception) { part.delete(); throw IOException("Arquivo corrompido: ${e.message}. Tente baixar novamente.") }
            val m = tmp.listFiles { f -> f.name.startsWith("model") && f.name.endsWith(".onnx") }?.firstOrNull()
                ?: throw IOException("Arquivo do modelo ausente no pacote.")
            if (m.length() < MIN_MODEL_BYTES) { part.delete(); throw IOException("Modelo menor que o esperado.") }
            for (n in listOf("voices.bin", "tokens.txt")) if (!File(tmp, n).exists()) throw IOException("Faltando: $n")
            if (!File(tmp, "espeak-ng-data").isDirectory) throw IOException("Faltando: espeak-ng-data")
            state.value = State.Busy("Calculando checksum…", -1f)
            File(tmp, ".ok").writeText("archive_sha256=$archiveSha\nmodel_size=${m.length()}\nmodel_sha256=${sha256(m)}\n")
            Engine.release()
            dir(c).deleteRecursively()
            if (!tmp.renameTo(dir(c))) throw IOException("Não foi possível mover o modelo para o destino.")
            part.delete()
            state.value = State.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            state.value = State.Missing; throw e
        } catch (e: Throwable) {
            tmp.deleteRecursively()
            state.value = State.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun download(part: File) {
        var have = if (part.exists()) part.length() else 0L
        val conn = (URL(URL_MODEL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 416) return // arquivo parcial já está completo
            if (code != 200 && code != 206) throw IOException("Servidor respondeu HTTP $code")
            if (code == 200) have = 0
            val len = conn.contentLengthLong
            val total = if (len > 0) have + len else -1L
            conn.inputStream.use { inp ->
                FileOutputStream(part, code == 206).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        coroutineContext.ensureActive()
                        out.write(buf, 0, n); have += n
                        val now = System.nanoTime()
                        if (now - last > 300_000_000L) {
                            last = now
                            state.value = State.Busy("Baixando modelo… ${have / 1_000_000} MB", if (total > 0) have.toFloat() / total else -1f)
                        }
                    }
                }
            }
        } finally { conn.disconnect() }
    }

    private fun extract(archive: File, dest: File) {
        val root = dest.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 16))).use { tar ->
            var e = tar.nextEntry
            while (e != null) {
                val clean = e.name.removePrefix("./")
                val rel = clean.substringAfter('/', "") // remove a pasta de topo
                if (rel.isNotEmpty()) {
                    val out = File(dest, rel)
                    if (!out.canonicalPath.startsWith(root)) throw IOException("Caminho inválido no pacote")
                    if (e.isDirectory) out.mkdirs()
                    else { out.parentFile?.mkdirs(); FileOutputStream(out).use { tar.copyTo(it, 1 shl 16) } }
                }
                e = tar.nextEntry
            }
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun verify(c: Context): String = withContext(Dispatchers.IO) {
        val m = modelFile(c) ?: return@withContext "Modelo não encontrado."
        val expected = marker(c)["model_sha256"] ?: return@withContext "Sem checksum registrado."
        if (sha256(m) == expected) "Integridade OK." else "ARQUIVO ALTERADO ou corrompido. Reinstale o modelo."
    }

    fun delete(c: Context) {
        Engine.release()
        dir(c).deleteRecursively()
        File(c.filesDir, "kokoro.tar.bz2.part").delete()
        state.value = State.Missing
    }
}
