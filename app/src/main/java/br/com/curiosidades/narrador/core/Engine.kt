package br.com.curiosidades.narrador.core

import android.content.Context
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/** Instância única do Kokoro (Sherpa-ONNX). Inferência sempre serializada. */
object Engine {
    private val lock = Any()
    private var tts: OfflineTts? = null

    fun get(c: Context): OfflineTts = synchronized(lock) {
        tts?.let { return it }
        val d = Models.dir(c)
        val model = Models.modelFile(c) ?: error("Modelo não instalado")
        val kokoro = OfflineTtsKokoroModelConfig(
            model = model.path,
            voices = File(d, "voices.bin").path,
            tokens = File(d, "tokens.txt").path,
            dataDir = File(d, "espeak-ng-data").path,
            // Sem lexicon: todo o texto passa pelo espeak-ng em pt-BR (evita palavras PT lidas como inglês).
            lang = "pt-br"
        )
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(kokoro = kokoro, numThreads = Prefs.threads(c), debug = false, provider = "cpu"),
            maxNumSentences = 1
        )
        OfflineTts(config = cfg).also { tts = it }
    }

    fun synth(t: OfflineTts, text: String, sid: Int, speed: Float): GeneratedAudio =
        synchronized(lock) { t.generate(text = text, sid = sid, speed = speed) }

    fun release() = synchronized(lock) { tts?.release(); tts = null }
}
