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

internal actual fun platformEnvVar(name: String): String? {
    val raw: dynamic = jsGetEnv(name)
    return if (raw == null || raw == undefined()) null else raw.unsafeCast<String>()
}

internal actual fun platformEnvVars(): Map<String, String> {
    val keys = jsGetEnvKeys()
    return buildMap {
        for (key in keys) {
            val v = platformEnvVar(key)
            if (v != null) {
                put(key, v)
            }
        }
    }
}

internal actual fun platformCurrentDir(): String = jsCurrentDir() ?: ""

internal actual fun spawnProcess(
    exe: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessOutput {
    val resolvedExe = resolveExecutablePath(exe)
    val envObj: dynamic = jsCloneProcessEnv()
    for ((key, value) in currentEnvOverrides()) {
        if (value == null) {
            jsDeleteEnvKey(envObj, key)
        } else {
            envObj[key] = value
        }
    }
    for ((key, value) in env) {
        envObj[key] = value
    }
    val jsArgs = args.toTypedArray()
    val result =
        jsSpawnSync(resolvedExe, jsArgs, envObj)
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

private fun jsGetEnvKeys(): Array<String> =
    js(
        "(typeof process !== 'undefined' && process && process.env) ? Object.keys(process.env) : []",
    ).unsafeCast<Array<String>>()

private fun jsCloneProcessEnv(): dynamic =
    js(
        "(typeof process !== 'undefined' && process && process.env) ? Object.assign({}, process.env) : {}",
    )

private fun jsDeleteEnvKey(envObj: dynamic, key: String) {
    js("delete envObj[key]")
}

private fun jsCurrentDir(): String? =
    js(
        "(typeof process !== 'undefined' && process && typeof process.cwd === 'function') ? process.cwd() : null",
    ).unsafeCast<String?>()

private fun jsOsPlatform(): String? =
    js(
        "(typeof process !== 'undefined' && process && typeof process.platform === 'string') ? process.platform : null",
    ).unsafeCast<String?>()

private fun jsSpawnSync(exe: String, args: Array<String>, env: dynamic): dynamic =
    js(
        "(function(e,a,en){try{var cp=(typeof process!=='undefined'&&process&&typeof process.getBuiltinModule==='function')?process.getBuiltinModule('child_process'):(eval('typeof require===\"function\"?require:null')?eval('require')('child_process'):null);return cp?cp.spawnSync(e,a,{env:en}):null;}catch(err){return null;}})(exe,args,env)",
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
