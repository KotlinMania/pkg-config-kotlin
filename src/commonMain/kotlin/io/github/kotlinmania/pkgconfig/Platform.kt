package io.github.kotlinmania.pkgconfig

/**
 * Captured stdout and stderr bytes together with the exit status of a finished process.
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
 * Process exit status. [code] is `null` when the process was terminated by a
 * signal and therefore has no portable integer status.
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
 * A host-side error from a system call such as spawning a process.
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
 * Internal wrapper for [IoError] used by per-target [spawnProcess] actuals.
 */
internal class IoSpawnException(
    val ioError: IoError,
) : Throwable(ioError.message)

/**
 * Operating system family for the active compilation target.
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

/** True when the active target belongs to the Unix family. */
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
        TargetOs.Macos, TargetOs.Linux, TargetOs.Windows, TargetOs.Android -> pathIsFile(resolveExecutablePath("pkg-config"))
        TargetOs.Ios, TargetOs.TvOs, TargetOs.WatchOs, TargetOs.Wasi, TargetOs.Browser, TargetOs.Other -> false
    }

/** Operating system the current Kotlin binary is running on. */
internal expect fun currentTargetOs(): TargetOs

private val envOverrides: MutableMap<String, String?> = mutableMapOf()

/** Reads an environment variable by name, consulting process-local overrides first. */
internal fun envVar(name: String): String? =
    if (envOverrides.containsKey(name)) {
        envOverrides[name]
    } else {
        platformEnvVar(name)
    }

/** Sets a process-local environment variable override. */
internal fun setEnvVar(name: String, value: String) {
    envOverrides[name] = value
}

/** Removes an environment variable from the process-local view. */
internal fun removeEnvVar(name: String) {
    envOverrides[name] = null
}

/** Returns all active environment variables merged with process-local overrides. */
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

/** Returns the current working directory. */
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
    val isWin = currentTargetOs() == TargetOs.Windows
    val pathSep = if (isWin) ';' else ':'
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
            } else if (isWin) {
                for (winDir in listOf("C:\\msys64\\mingw64\\bin", "C:\\msys64\\ucrt64\\bin", "C:\\msys64\\usr\\bin", "C:\\Strawberry\\c\\bin")) {
                    if (winDir !in this) add(winDir)
                }
            }
        }
    for (dir in searchDirs) {
        val candidate = joinPath(dir, exe)
        if (pathIsFile(candidate)) {
            return candidate
        }
        if (isWin && !exe.endsWith(".exe")) {
            val winCandidate = joinPath(dir, "$exe.exe")
            if (pathIsFile(winCandidate)) {
                return winCandidate
            }
        }
    }
    return exe
}

/** Platform-native environment variable lookup. */
internal expect fun platformEnvVar(name: String): String?

/** Platform-native environment variable snapshot. */
internal expect fun platformEnvVars(): Map<String, String>

/** Platform-native current working directory. */
internal expect fun platformCurrentDir(): String

/** Returns true if the given filesystem path exists. */
internal expect fun pathExists(path: String): Boolean

/** Returns true if the given filesystem path exists and is a regular file. */
internal expect fun pathIsFile(path: String): Boolean

/**
 * Spawns an external process and returns its captured output, or throws
 * [IoSpawnException] when the spawn itself fails.
 */
internal expect fun spawnProcess(
    exe: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessOutput

/**
 * Emits a line to standard output.
 */
internal expect fun printStdoutLine(line: String)
