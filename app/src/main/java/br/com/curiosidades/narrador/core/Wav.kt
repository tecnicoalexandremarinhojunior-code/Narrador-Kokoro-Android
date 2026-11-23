package br.com.curiosidades.narrador.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
    fun floatToPcm(s: FloatArray): ByteArray {
        val b = ByteArray(s.size * 2)
        for (i in s.indices) {
            val v = (s[i].coerceIn(-1f, 1f) * 32767f).toInt()
            b[2 * i] = (v and 0xFF).toByte()
            b[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    private fun header(dataLen: Long, sr: Int): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(dataLen.toInt())
        return bb.array()
    }

    /** Concatena arquivos PCM 16-bit mono em um WAV, em streaming (sem carregar tudo na RAM). */
    fun write(out: File, pcmFiles: List<File>, sr: Int) {
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        var total = 0L
        FileOutputStream(tmp).use { o ->
            o.write(header(0, sr))
            val buf = ByteArray(1 shl 16)
            for (f in pcmFiles) FileInputStream(f).use { i ->
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n); total += n
                }
            }
        }
        RandomAccessFile(tmp, "rw").use { it.seek(0); it.write(header(total, sr)) }
        out.delete()
        if (!tmp.renameTo(out)) throw java.io.IOException("Não foi possível criar o WAV final")
    }
}
