@file:OptIn(ExperimentalForeignApi::class)

package io.github.kotlinmania.pkgconfig

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.EOF
import platform.posix._access
import platform.posix._pclose
import platform.posix._popen
import platform.posix._putenv
import platform.posix.fgetc
import platform.posix.getenv

internal actual fun currentTargetOs(): TargetOs = TargetOs.Windows

internal actual fun envVar(name: String): String? = getenv(name)?.toKString()

internal actual fun pathExists(path: String): Boolean = _access(path, 0) == 0

internal actual fun pathIsFile(path: String): Boolean = pathExists(path)

internal actual fun spawnProcess(
    exe: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessOutput {
    val previousEnv: MutableMap<String, String?> = mutableMapOf()
    for ((key, _) in env) {
        previousEnv[key] = getenv(key)?.toKString()
    }
    for ((key, value) in env) {
        _putenv("$key=$value")
    }
    try {
        val command =
            buildString {
                append(quoteWin(exe))
                for (arg in args) {
                    append(' ')
                    append(quoteWin(arg))
                }
                append(" 2>&1")
            }
        val handle =
            _popen(command, "rb")
                ?: throw IoSpawnException(IoError(IoErrorKind.NotFound, "_popen failed for `$exe`"))
        val out = ArrayList<Byte>()
        while (true) {
            val c = fgetc(handle)
            if (c == EOF) break
            out.add((c and 0xff).toByte())
        }
        val status = _pclose(handle)
        return ProcessOutput(
            status = ProcessStatus(code = if (status < 0) null else status),
            stdout = out.toByteArray(),
            stderr = byteArrayOf(),
        )
    } finally {
        for ((key, original) in previousEnv) {
            if (original == null) {
                _putenv("$key=")
            } else {
                _putenv("$key=$original")
            }
        }
    }
}

internal actual fun printStdoutLine(line: String) {
    println(line)
}

private fun quoteWin(s: String): String {
    if (s.isEmpty()) return "\"\""
    var simple = true
    for (c in s) {
        if (!c.isLetterOrDigit() && c != '/' && c != '\\' && c != '_' && c != '-' && c != '.' && c != ':' && c != '=') {
            simple = false
            break
        }
    }
    if (simple) return s
    val sb = StringBuilder("\"")
    for (c in s) {
        if (c == '"') sb.append("\\\"") else sb.append(c)
    }
    sb.append('"')
    return sb.toString()
}
