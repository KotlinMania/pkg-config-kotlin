// port-lint: ignore — Android actuals for the Platform.kt expectations.
package io.github.kotlinmania.pkgconfig

internal actual fun currentTargetOs(): TargetOs = TargetOs.Android

internal actual fun envVar(name: String): String? = try {
    System.getenv(name)
} catch (_: SecurityException) {
    null
}

internal actual fun pathExists(path: String): Boolean = try {
    java.io.File(path).exists()
} catch (_: SecurityException) {
    false
}

internal actual fun pathIsFile(path: String): Boolean = try {
    java.io.File(path).isFile
} catch (_: SecurityException) {
    false
}

internal actual fun spawnProcess(
    exe: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessOutput {
    val cmd = ArrayList<String>(args.size + 1).apply {
        add(exe)
        addAll(args)
    }
    val builder = ProcessBuilder(cmd)
    for ((key, value) in env) {
        builder.environment()[key] = value
    }
    val process = try {
        builder.start()
    } catch (e: java.io.IOException) {
        val message = e.message ?: ""
        val kind = if (e is java.io.FileNotFoundException || message.contains("No such file")) {
            IoErrorKind.NotFound
        } else {
            IoErrorKind.Other
        }
        throw IoError(kind, e.message ?: "process failed to spawn")
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
