package com.adamcarr.hondashlauncher

/** Runs shell commands through Magisk's su. Every call blocks, so never call it on the main thread. */
object Root {
    class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    fun run(command: String): Result = try {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        Result(p.waitFor(), out)
    } catch (e: Exception) {
        Result(-1, e.message ?: "su not found")
    }

    /** True if su works and we really are uid 0. First call shows Magisk's grant prompt. */
    fun available(): Boolean = run("id -u").let { it.ok && it.out.lines().lastOrNull()?.trim() == "0" }
}
