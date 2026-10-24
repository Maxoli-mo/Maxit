package com.maximiliano.maxit.inference

/** Never expose an unfinished thinking block or half of its opening tag as answer text. */
internal class LocalStreamText {
    private val raw = StringBuilder()
    private var emitted = ""

    fun append(delta: String): String {
        require(raw.length + delta.length <= 8_388_608) { "Local response exceeded the per-response safety size" }
        raw.append(delta)
        var visible = raw.toString().replace(Regex("(?s)<think>.*?</think>"), "").substringBefore("<think>")
        for (length in 1 until "<think>".length) {
            if (visible.endsWith("<think>".take(length))) {
                visible = visible.dropLast(length)
                break
            }
        }
        check(visible.startsWith(emitted)) { "Local stream changed already emitted text" }
        val next = visible.substring(emitted.length)
        emitted = visible
        return next
    }

    fun answer(): String = emitted
}
