package br.com.curiosidades.narrador.core

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import br.com.curiosidades.narrador.MainActivity
import br.com.curiosidades.narrador.R
import br.com.curiosidades.narrador.data.Db
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class GenState(
    val projectId: Long = -1,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val remainingSec: Long = -1
)

object Generator {
    val state = MutableStateFlow(GenState())

    fun start(c: Context, id: Long, force: Boolean = false) {
        ContextCompat.startForegroundService(
            c, Intent(c, GenerationService::class.java).putExtra("id", id).putExtra("force", force)
        )
    }

    fun stop(c: Context) {
        c.startService(Intent(c, GenerationService::class.java).setAction("STOP"))
    }

    fun projectDir(c: Context, id: Long) = File(c.filesDir, "projects/$id")
    fun chunkDir(c: Context, id: Long) = File(projectDir(c, id), "chunks")
    fun wavFile(c: Context, id: Long) = File(projectDir(c, id), "narracao.wav")
}

/** Serviço em primeiro plano: gera bloco a bloco, salva cada bloco em disco e permite retomar. */
class GenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            if (job?.isActive == true) job?.cancel() else stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getLongExtra("id", -1L) ?: -1L
        val force = intent?.getBooleanExtra("force", false) ?: false
        ensureChannel()
        ServiceCompat.startForeground(this, NOTIF_ID, notif("Preparando…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive == true) return START_NOT_STICKY
        if (id < 0) { stopSelf(); return START_NOT_STICKY }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "narrador:gen").apply { acquire(6 * 60 * 60 * 1000L) }
        job = scope.launch {
            try { run(id, force) } finally {
                wake?.let { if (it.isHeld) it.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun name(i: Int) = "%05d.pcm".format(i)

    private suspend fun run(id: Long, force: Boolean) {
        val dao = Db.get(this).dao()
        val p = dao.get(id) ?: return
        val key = chunkKey(p.text, p.voice, p.speed)
        val dir = Generator.chunkDir(this, id)
        val wav = Generator.wavFile(this, id)
        var done = 0
        var total = 0
        try {
            if (force || p.chunkKey != key) { dir.deleteRecursively(); wav.delete() }
            dir.mkdirs()
            dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
            val chunks = TextChunker.split(p.text)
            total = chunks.size
            if (chunks.isEmpty()) throw IllegalStateException("O texto está vazio.")
            val voice = Voice.fromId(p.voice)
            done = chunks.indices.count { i -> File(dir, name(i)).let { it.exists() && it.length() > 0 } }
            dao.progress(id, "GERANDO", done, total, key, "")
            publish(id, done, total, "Carregando modelo…", -1)
            val tts = Engine.get(this)
            val sr = tts.sampleRate()
            val t0 = System.currentTimeMillis()
            var generated = 0
            for ((i, c) in chunks.withIndex()) {
                currentCoroutineContext().ensureActive()
                val f = File(dir, name(i))
                if (f.exists() && f.length() > 0) continue
                waitCool(id, done, total)
                val audio = Engine.synth(tts, c.text, voice.sid, p.speed)
                val tmp = File(dir, name(i) + ".tmp")
                FileOutputStream(tmp).use {
                    it.write(Wav.floatToPcm(audio.samples))
                    it.write(ByteArray((sr * c.pauseMs / 1000) * 2))
                }
                if (!tmp.renameTo(f)) throw IOException("Falha ao salvar o bloco $i")
                done++; generated++
                val rem = (System.currentTimeMillis() - t0) / generated * (total - done) / 1000
                dao.progress(id, "GERANDO", done, total, key, "")
                publish(id, done, total, "Gerando bloco $done de $total", rem)
            }
            publish(id, done, total, "Unindo blocos…", 0)
            Wav.write(wav, chunks.indices.map { File(dir, name(it)) }, sr)
            dir.deleteRecursively()
            dao.finish(id, wav.path)
            publish(id, total, total, "Concluído", 0, running = false)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.status(id, "PAUSADO", "") }
            publish(id, done, total, "Pausado", -1, running = false)
            throw e
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            withContext(NonCancellable) { dao.status(id, "ERRO", msg) }
            publish(id, done, total, "Erro: $msg", -1, running = false)
        }
    }

    private suspend fun waitCool(id: Long, done: Int, total: Int) {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        while (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            publish(id, done, total, "Aparelho quente — aguardando esfriar…", -1)
            delay(15_000)
        }
    }

    private fun publish(id: Long, done: Int, total: Int, msg: String, rem: Long, running: Boolean = true) {
        Generator.state.value = GenState(id, running, done, total, msg, rem)
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif(msg, done, total))
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Geração de narração", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notif(text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GenerationService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Gerando narração")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Pausar", stop)
            .apply { if (total > 0) setProgress(total, done, false) else setProgress(0, 0, true) }
            .build()
    }

    companion object {
        private const val CHANNEL = "gen"
        private const val NOTIF_ID = 1
    }
}
