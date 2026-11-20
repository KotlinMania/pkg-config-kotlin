@file:OptIn(ExperimentalForeignApi::class)

package io.github.kotlinmania.pkgconfig

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.EOF
import platform.posix._getcwd
import platform.posix._pclose
import platform.posix._popen
import platform.posix._putenv
import platform.posix.fgetc
import platform.posix.getenv

private const val CWD_BUFFER_SIZE = 4096

internal actual fun currentTargetOs(): TargetOs = TargetOs.Windows

private val trackedMingwEnvKeys =
    arrayOf(
        "TARGET",
        "HOST",
        "PKG_CONFIG",
        "PKG_CONFIG_PATH",
        "PKG_CONFIG_LIBDIR",
        "PKG_CONFIG_SYSROOT_DIR",
        "PKG_CONFIG_ALLOW_CROSS",
        "PKG_CONFIG_ALL_STATIC",
        "PKG_CONFIG_ALL_DYNAMIC",
        "FOO_NO_PKG_CONFIG",
        "FOO_STATIC",
        "FOO_DYNAMIC",
        "PATH",
        "CD",
        "PWD",
        "SYSROOT",
        "CARGO_PKG_NAME",
    )

internal actual fun platformEnvVar(name: String): String? = getenv(name)?.toKString()

internal actual fun platformEnvVars(): Map<String, String> =
    buildMap {
        for (key in trackedMingwEnvKeys) {
            val v = getenv(key)?.toKString()
            if (v != null) {
                put(key, v)
            }
        }
    }

internal actual fun platformCurrentDir(): String =
    getenv("CD")?.toKString()
        ?: getenv("PWD")?.toKString()
        ?: memScoped {
            val buf = allocArray<ByteVar>(CWD_BUFFER_SIZE)
            _getcwd(buf, CWD_BUFFER_SIZE)?.toKString() ?: "."
        }

internal actual fun spawnProcess(
    exe: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessOutput {
    val resolvedExe = resolveExecutablePath(exe)
    val overrides = currentEnvOverrides()
    val previousEnv: MutableMap<String, String?> = mutableMapOf()
    for ((key, _) in overrides) {
        previousEnv[key] = getenv(key)?.toKString()
    }
    for ((key, _) in env) {
        if (!previousEnv.containsKey(key)) {
            previousEnv[key] = getenv(key)?.toKString()
        }
    }
    for ((key, value) in overrides) {
        if (value == null) {
            _putenv("$key=")
        } else {
            _putenv("$key=$value")
        }
    }
    for ((key, value) in env) {
        _putenv("$key=$value")
    }
    try {
        val command =
            buildString {
                append(quoteWin(resolvedExe))
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
