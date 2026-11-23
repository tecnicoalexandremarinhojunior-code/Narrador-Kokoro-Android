package br.com.curiosidades.narrador.core

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

enum class Voice(val id: String, val label: String, val sid: Int, val desc: String) {
    DORA("pf_dora", "Dora", 42, "Voz feminina"),
    ALEX("pm_alex", "Alex", 43, "Voz masculina"),
    SANTA("pm_santa", "Santa", 44, "Voz masculina");

    companion object {
        fun fromId(id: String): Voice = entries.firstOrNull { it.id == id } ?: DORA
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun threads(c: Context): Int = sp(c).getInt("threads", 4)
    fun setThreads(c: Context, n: Int) = sp(c).edit().putInt("threads", n).apply()
}

fun chunkKey(text: String, voice: String, speed: Float): String {
    val md = MessageDigest.getInstance("SHA-1")
    md.update("$voice|${String.format(Locale.US, "%.2f", speed)}|${TextChunker.MAX}|$text".toByteArray())
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun fmtSecs(s: Long): String {
    val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return when {
        h > 0 -> "%d h %02d min".format(h, m)
        m > 0 -> "%d min %02d s".format(m, x)
        else -> "$x s"
    }
}
