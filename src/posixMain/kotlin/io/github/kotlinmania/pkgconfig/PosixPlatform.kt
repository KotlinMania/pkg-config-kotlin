@file:OptIn(ExperimentalForeignApi::class)

package io.github.kotlinmania.pkgconfig

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.EOF
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fgetc
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.pclose
import platform.posix.popen
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily

private const val CWD_BUFFER_SIZE = 4096

@OptIn(ExperimentalNativeApi::class)
internal actual fun currentTargetOs(): TargetOs =
    when (kotlin.native.Platform.osFamily) {
        OsFamily.MACOSX -> TargetOs.Macos
        OsFamily.IOS -> TargetOs.Ios
        OsFamily.TVOS -> TargetOs.TvOs
        OsFamily.WATCHOS -> TargetOs.WatchOs
        OsFamily.LINUX -> TargetOs.Linux
        OsFamily.ANDROID -> TargetOs.Android
        else -> TargetOs.Other
    }

private val trackedPosixEnvKeys =
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
        "PWD",
        "SYSROOT",
        "CARGO_PKG_NAME",
    )

internal actual fun platformEnvVar(name: String): String? = getenv(name)?.toKString()

internal actual fun platformEnvVars(): Map<String, String> =
    buildMap {
        for (key in trackedPosixEnvKeys) {
            val v = getenv(key)?.toKString()
            if (v != null) {
                put(key, v)
            }
        }
    }

internal actual fun platformCurrentDir(): String =
    getenv("PWD")?.toKString()
        ?: memScoped {
            val buf = allocArray<ByteVar>(CWD_BUFFER_SIZE)
            getcwd(buf, CWD_BUFFER_SIZE.convert())?.toKString() ?: "."
        }

internal actual fun pathExists(path: String): Boolean = access(path, F_OK) == 0

// Mirrors `Path::is_file()`. `struct stat` has a different binary layout on
// every Apple/Linux/Android-Native target, and `S_ISREG` is a macro that
// platform.posix does not always expose, so we rely on `access(F_OK)` here.
// `pkg-config` only emits regular file paths in `-Wl,` runs, so presence is
// enough to drive the downstream linker-arg parsing.
internal actual fun pathIsFile(path: String): Boolean = pathExists(path)

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
            unsetenv(key)
        } else {
            setenv(key, value, 1)
        }
    }
    for ((key, value) in env) {
        setenv(key, value, 1)
    }
    try {
        val command =
            buildString {
                append(shellEscape(resolvedExe))
                for (arg in args) {
                    append(' ')
                    append(shellEscape(arg))
                }
                append(" 2>&1")
            }
        val handle =
            popen(command, "r")
                ?: throw IoSpawnException(IoError(IoErrorKind.NotFound, "popen failed for `$exe`"))
        val out = ArrayList<Byte>()
        // Read byte-by-byte via fgetc so the posixMain metadata stays free of
        // size_t-typed signatures (whose bit width differs between 32-bit
        // watchosArm32 and the 64-bit posix targets).
        while (true) {
            val c = fgetc(handle)
            if (c == EOF) break
            out.add((c and 0xff).toByte())
        }
        val status = pclose(handle)
        val code = if (status == -1) null else (status shr 8) and 0xff
        return ProcessOutput(
            status = ProcessStatus(code = code),
            stdout = out.toByteArray(),
            stderr = byteArrayOf(),
        )
    } finally {
        for ((key, original) in previousEnv) {
            if (original == null) {
                unsetenv(key)
            } else {
                setenv(key, original, 1)
            }
        }
    }
}

internal actual fun printStdoutLine(line: String) {
    println(line)
}

private fun shellEscape(s: String): String {
    if (s.isEmpty()) return "''"
    var simple = true
    for (c in s) {
        if (!c.isLetterOrDigit() && c != '/' && c != '_' && c != '-' && c != '.' && c != '=') {
            simple = false
            break
        }
    }
    if (simple) return s
    val sb = StringBuilder("'")
    for (c in s) {
        if (c == '\'') sb.append("'\\''") else sb.append(c)
    }
    sb.append('\'')
    return sb.toString()
}
