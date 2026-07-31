// port-lint: ignore — JS actuals. Reads env from `process.env` when running
// under Node; falls back to `null` in the browser. Process invocation is
// implemented for Node via `child_process.spawnSync`.
package io.github.kotlinmania.pkgconfig

internal actual fun currentTargetOs(): TargetOs {
    val raw = jsOsPlatform() ?: return TargetOs.Browser
    return when {
        raw == "darwin" -> TargetOs.Macos
        raw == "linux" -> TargetOs.Linux
        raw == "win32" -> TargetOs.Windows
        raw == "android" -> TargetOs.Android
        else -> TargetOs.Other
    }
}

internal actual fun envVar(name: String): String? {
    val raw: dynamic = jsGetEnv(name)
    return if (raw == null || raw == undefined()) null else raw.unsafeCast<String>()
}

internal actual fun pathExists(path: String): Boolean = jsPathExists(path) == true

internal actual fun pathIsFile(path: String): Boolean = jsPathIsFile(path) == true

internal actual fun spawnProcess(
    exe: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessOutput {
    val envObj: dynamic = js("{}")
    for ((key, value) in env) {
        envObj[key] = value
    }
    val jsArgs = args.toTypedArray()
    val result =
        jsSpawnSync(exe, jsArgs, envObj)
            ?: throw IoSpawnException(IoError(IoErrorKind.NotFound, "child_process unavailable in this JS host"))
    val err: dynamic = result.error
    if (err != null && err != undefined()) {
        val errCode: dynamic = err.code
        val kind = if (errCode == "ENOENT") IoErrorKind.NotFound else IoErrorKind.Other
        val msgRaw: dynamic = err.message
        val message: String = if (msgRaw == null || msgRaw == undefined()) "spawn failed" else msgRaw.toString()
        throw IoSpawnException(IoError(kind, message))
    }
    val statusDyn: dynamic = result.status
    val code: Int? = if (statusDyn == null || statusDyn == undefined()) null else (statusDyn as Number).toInt()
    val stdoutBytes = bufferToBytes(result.stdout)
    val stderrBytes = bufferToBytes(result.stderr)
    return ProcessOutput(
        status = ProcessStatus(code = code),
        stdout = stdoutBytes,
        stderr = stderrBytes,
    )
}

internal actual fun printStdoutLine(line: String) {
    println(line)
}

private fun jsGetEnv(name: String): dynamic =
    js(
        "(typeof process !== 'undefined' && process && process.env) ? process.env[name] : undefined",
    )

private fun jsOsPlatform(): String? =
    js(
        "(typeof process !== 'undefined' && process && typeof process.platform === 'string') ? process.platform : null",
    ).unsafeCast<String?>()

private fun jsPathExists(path: String): Boolean? =
    js(
        "(function(p){try{var fs=require('fs');return fs.existsSync(p);}catch(e){return null;}})(path)",
    ).unsafeCast<Boolean?>()

private fun jsPathIsFile(path: String): Boolean? =
    js(
        "(function(p){try{var fs=require('fs');var s=fs.statSync(p);return s.isFile();}catch(e){return null;}})(path)",
    ).unsafeCast<Boolean?>()

private fun jsSpawnSync(exe: String, args: Array<String>, env: dynamic): dynamic =
    js(
        "(function(e,a,en){try{var cp=require('child_process');return cp.spawnSync(e,a,{env:en});}catch(err){return null;}})(exe,args,env)",
    )

private fun bufferToBytes(buffer: dynamic): ByteArray {
    if (buffer == null || buffer == undefined()) return byteArrayOf()
    val length: Int = (buffer.length as Number).toInt()
    val out = ByteArray(length)
    for (i in 0 until length) {
        out[i] = ((buffer[i] as Number).toInt() and 0xff).toByte()
    }
    return out
}

private fun undefined(): dynamic = js("undefined")
