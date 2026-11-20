package io.github.kotlinmania.pkgconfig

internal actual fun currentTargetOs(): TargetOs {
    val name = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        name.contains("mac") || name.contains("darwin") -> TargetOs.Macos

        name.contains("win") -> TargetOs.Windows

        name.contains("linux") -> TargetOs.Linux

        name.contains("freebsd") ||
            name.contains("openbsd") ||
            name.contains("netbsd") ||
            name.contains("sunos") -> TargetOs.Linux

        else -> TargetOs.Other
    }
}

internal actual fun platformEnvVar(name: String): String? =
    try {
        System.getenv(name)
    } catch (_: SecurityException) {
        null
    }

internal actual fun platformEnvVars(): Map<String, String> =
    try {
        System.getenv()
    } catch (_: SecurityException) {
        emptyMap()
    }

internal actual fun platformCurrentDir(): String =
    try {
        System.getProperty("user.dir").orEmpty()
    } catch (_: SecurityException) {
        ""
    }

internal actual fun spawnProcess(
    exe: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessOutput {
    val resolvedExe = resolveExecutablePath(exe)
    val cmd =
        ArrayList<String>(args.size + 1).apply {
            add(resolvedExe)
            addAll(args)
        }
    val builder = ProcessBuilder(cmd)
    val builderEnv = builder.environment()
    for ((key, value) in currentEnvOverrides()) {
        if (value == null) {
            builderEnv.remove(key)
        } else {
            builderEnv[key] = value
        }
    }
    for ((key, value) in env) {
        builderEnv[key] = value
    }
    val process =
        try {
            builder.start()
        } catch (e: java.io.IOException) {
            val message = e.message.orEmpty()
            val kind =
                if (e is java.io.FileNotFoundException || message.contains("No such file")) {
                    IoErrorKind.NotFound
                } else {
                    IoErrorKind.Other
                }
            throw IoSpawnException(IoError(kind, e.message ?: "process failed to spawn"))
        }

    process.outputStream.close()
    val stdout = process.inputStream.readBytes()
    val stderr = process.errorStream.readBytes()
    val code = process.waitFor()
    return ProcessOutput(
        status = ProcessStatus(code = code),
        stdout = stdout,
        stderr = stderr,
    )
}

internal actual fun printStdoutLine(line: String) {
    println(line)
}
