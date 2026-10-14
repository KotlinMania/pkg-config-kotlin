package io.github.kotlinmania.pkgconfig

/**
 * Mirrors `std::process::Output`: the captured stdout / stderr bytes together
 * with the exit status of a finished process.
 */
public data class ProcessOutput(
    val status: ProcessStatus,
    val stdout: ByteArray,
    val stderr: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessOutput) return false
        if (status != other.status) return false
        if (!stdout.contentEquals(other.stdout)) return false
        if (!stderr.contentEquals(other.stderr)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + stdout.contentHashCode()
        result = 31 * result + stderr.contentHashCode()
        return result
    }
}

/**
 * Mirrors `std::process::ExitStatus`. `code` is `null` when the process was
 * terminated by a signal and therefore has no portable integer status.
 */
public data class ProcessStatus(
    val code: Int?,
) {
    fun success(): Boolean = code == 0

    override fun toString(): String =
        when (code) {
            null -> "signal: terminated"
            else -> "exit status: $code"
        }
}

/**
 * Mirrors `std::io::Error`: a host-side error from a system call such as
 * spawning a process. `kind` reproduces the small set of `io::ErrorKind`
 * variants the upstream crate actually distinguishes. Not a [Throwable]
 * subtype: the Swift Export bridge expands `Throwable.suppressed:
 * Array<Throwable>` into `Array<Any?>` casts that fail under
 * `allWarningsAsErrors`.
 */
public data class IoError(
    val kind: IoErrorKind,
    val message: String,
)

public enum class IoErrorKind {
    NotFound,
    Other,
}

/**
 * Internal `Throwable`-shaped wrapper for [IoError] used so the per-target
 * [spawnProcess] actuals can keep throwing through a `try`/`catch`. Kept
 * `internal` so it never reaches the Swift Export bridge.
 */
internal class IoSpawnException(
    val ioError: IoError,
) : Throwable(ioError.message)

/**
 * Operating system family corresponding to Rust's `cfg!(target_os = "...")`
 * compile-time check. The active value is fixed per Kotlin target.
 */
internal enum class TargetOs {
    Macos,
    Ios,
    TvOs,
    WatchOs,
    Linux,
    Android,
    Windows,
    Wasi,
    Browser,
    Other,
}

/** True when the active target satisfies Rust's `cfg!(unix)`. */
internal fun TargetOs.isUnix(): Boolean =
    when (this) {
        TargetOs.Macos, TargetOs.Ios, TargetOs.TvOs, TargetOs.WatchOs,
        TargetOs.Linux, TargetOs.Android,
        -> true

        else -> false
    }

/**
 * True when the active target runtime executes directly on a host OS that can
 * spawn `pkg-config` child processes (as opposed to a Browser, WASI, or Apple
 * Simulator sandbox without `/bin/sh` or `pkg-config`).
 */
internal fun TargetOs.canSpawnHostProcess(): Boolean =
    when (this) {
        TargetOs.Macos, TargetOs.Linux, TargetOs.Windows, TargetOs.Android -> true
        TargetOs.Ios, TargetOs.TvOs, TargetOs.WatchOs, TargetOs.Wasi, TargetOs.Browser, TargetOs.Other -> false
    }

/** Operating system the current Kotlin binary is running on. */
internal expect fun currentTargetOs(): TargetOs

private val envOverrides: MutableMap<String, String?> = mutableMapOf()

/** Equivalent of `std::env::var_os` / `std::env::var`. */
internal fun envVar(name: String): String? =
    if (envOverrides.containsKey(name)) {
        envOverrides[name]
    } else {
        platformEnvVar(name)
    }

/** Equivalent of `std::env::set_var`. */
internal fun setEnvVar(name: String, value: String) {
    envOverrides[name] = value
}

/** Equivalent of `std::env::remove_var`. */
internal fun removeEnvVar(name: String) {
    envOverrides[name] = null
}

/** Equivalent of `std::env::vars`. */
internal fun envVars(): Map<String, String> {
    val merged = platformEnvVars().toMutableMap()
    for ((key, value) in envOverrides) {
        if (value == null) {
            merged.remove(key)
        } else {
            merged[key] = value
        }
    }
    return merged
}

internal fun currentEnvOverrides(): Map<String, String?> = envOverrides.toMap()

/** Equivalent of `std::env::current_dir`. */
internal fun currentDir(): String {
    val raw = platformCurrentDir()
    if (raw.isNotEmpty() && !pathExists(joinPath(raw, "tests"))) {
        val candidate = joinPath(raw, "../../../..")
        if (pathExists(joinPath(candidate, "tests"))) {
            return candidate
        }
    }
    return raw
}

internal fun resolveExecutablePath(exe: String): String {
    if (exe.contains('/') || exe.contains('\\')) return exe
    val pathSep = if (currentTargetOs() == TargetOs.Windows) ';' else ':'
    val pathEnv = envVar("PATH").orEmpty()
    val searchDirs =
        buildList {
            for (dir in pathEnv.split(pathSep)) {
                if (dir.isNotEmpty()) add(dir)
            }
            if (currentTargetOs().isUnix()) {
                if ("/opt/homebrew/bin" !in this) add("/opt/homebrew/bin")
                if ("/usr/local/bin" !in this) add("/usr/local/bin")
                if ("/usr/bin" !in this) add("/usr/bin")
            }
        }
    for (dir in searchDirs) {
        val candidate = joinPath(dir, exe)
        if (pathIsFile(candidate)) {
            return candidate
        }
    }
    return exe
}

/** Platform-native lookup for `std::env::var_os` / `std::env::var`. */
internal expect fun platformEnvVar(name: String): String?

/** Platform-native snapshot for `std::env::vars`. */
internal expect fun platformEnvVars(): Map<String, String>

/** Platform-native working directory for `std::env::current_dir`. */
internal expect fun platformCurrentDir(): String

/** Equivalent of `Path::new(path).exists()`. */
internal expect fun pathExists(path: String): Boolean

/** Equivalent of `Path::new(path).is_file()`. */
internal expect fun pathIsFile(path: String): Boolean

/**
 * Equivalent of `std::process::Command::new(exe).args(args).envs(env).output()`.
 * Returns the captured output on success, throws [IoSpawnException] when the
 * spawn itself fails. The caller is expected to translate that exception into
 * a public [Error.Command] with the wrapped [IoError].
 */
internal expect fun spawnProcess(
    exe: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessOutput

/**
 * Equivalent of `println!` for emitting `cargo:*` directives. Build scripts
 * receive these by reading the build script's stdout, so this routes through
 * the platform's standard-output sink.
 */
internal expect fun printStdoutLine(line: String)
