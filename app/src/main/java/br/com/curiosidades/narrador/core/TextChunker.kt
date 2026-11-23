package br.com.curiosidades.narrador.core

data class Chunk(val text: String, val pauseMs: Int)

/** Divide o roteiro em blocos por parágrafo/frase, sem cortar palavras (exceto palavras gigantes). */
object TextChunker {
    const val MAX = 300
    private val sentenceSplit = Regex("(?<=[.!?…])\\s+")
    private val paraSplit = Regex("\\n+")
    private val spaces = Regex("[ \\t]+")

    fun split(raw: String, max: Int = MAX): List<Chunk> {
        val out = mutableListOf<Chunk>()
        for (para in raw.replace("\r", "").split(paraSplit)) {
            val p = para.trim().replace(spaces, " ")
            if (p.isEmpty()) continue
            val pieces = mutableListOf<String>()
            for (s in p.split(sentenceSplit)) pieces += splitLong(s, max)
            val paraChunks = mutableListOf<String>()
            val buf = StringBuilder()
            for (s in pieces) {
                if (buf.isNotEmpty() && buf.length + 1 + s.length > max) {
                    paraChunks += buf.toString(); buf.clear()
                }
                if (buf.isNotEmpty()) buf.append(' ')
                buf.append(s)
            }
            if (buf.isNotEmpty()) paraChunks += buf.toString()
            val valid = paraChunks.filter { t -> t.any { it.isLetterOrDigit() } }
            valid.forEachIndexed { i, t -> out += Chunk(t, if (i == valid.lastIndex) 600 else 150) }
        }
        return out
    }

    private fun splitLong(s: String, max: Int): List<String> {
        var rest = s.trim()
        if (rest.length <= max) return listOf(rest)
        val out = mutableListOf<String>()
        while (rest.length > max) {
            var cut = rest.lastIndexOfAny(charArrayOf(',', ';', ':'), max - 1)
            if (cut >= max / 3) {
                cut += 1
            } else {
                cut = rest.lastIndexOf(' ', max)
                if (cut <= 0) cut = max
            }
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
