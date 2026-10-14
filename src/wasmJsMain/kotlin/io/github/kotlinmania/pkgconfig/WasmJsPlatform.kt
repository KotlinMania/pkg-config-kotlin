@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.github.kotlinmania.pkgconfig

import kotlin.io.encoding.Base64

internal actual fun currentTargetOs(): TargetOs {
    val raw = jsOsPlatform() ?: return TargetOs.Browser
    return when (raw) {
        "darwin" -> TargetOs.Macos
        "linux" -> TargetOs.Linux
        "win32" -> TargetOs.Windows
        "android" -> TargetOs.Android
        else -> TargetOs.Other
    }
}

internal actual fun platformEnvVar(name: String): String? = jsGetEnv(name)

internal actual fun platformEnvVars(): Map<String, String> {
    val joined = jsGetEnvKeysJoined()
    if (joined.isEmpty()) return emptyMap()
    return buildMap {
        for (key in joined.split('\u0000')) {
            if (key.isNotEmpty()) {
                val v = jsGetEnv(key)
                if (v != null) {
                    put(key, v)
                }
            }
        }
    }
}

internal actual fun platformCurrentDir(): String = jsCurrentDir() ?: ""

internal actual fun pathExists(path: String): Boolean = jsPathExists(path)

internal actual fun pathIsFile(path: String): Boolean = jsPathIsFile(path)

internal actual fun spawnProcess(
    exe: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessOutput {
    val resolvedExe = resolveExecutablePath(exe)
    val overrides = currentEnvOverrides()
    val unsetKeys =
        buildList {
            for ((key, value) in overrides) {
                if (value == null) add(key)
            }
        }
    val setPairs =
        buildList {
            for ((key, value) in overrides) {
                if (value != null) add("$key=$value")
            }
            for ((key, value) in env) {
                add("$key=$value")
            }
        }
    val raw =
        jsSpawnSyncEncoded(
            resolvedExe,
            args.joinToString("\u0000"),
            args.size,
            setPairs.joinToString("\u0000"),
            unsetKeys.joinToString("\u0000"),
        )
    if (raw.startsWith("ERR:")) {
        val rest = raw.substring(4)
        val sep = rest.indexOf(':')
        val kindStr = if (sep >= 0) rest.substring(0, sep) else "Other"
        val message = if (sep >= 0) rest.substring(sep + 1) else rest
        val kind = if (kindStr == "NotFound") IoErrorKind.NotFound else IoErrorKind.Other
        throw IoSpawnException(IoError(kind, message))
    }
    val payload = raw.removePrefix("OK:")
    val firstSep = payload.indexOf(':')
    val secondSep = payload.indexOf(':', firstSep + 1)
    val statusStr = payload.substring(0, firstSep)
    val stdoutB64 = payload.substring(firstSep + 1, secondSep)
    val stderrB64 = payload.substring(secondSep + 1)
    val code = if (statusStr.isEmpty()) null else statusStr.toIntOrNull()
    val stdoutBytes = if (stdoutB64.isEmpty()) byteArrayOf() else Base64.decode(stdoutB64)
    val stderrBytes = if (stderrB64.isEmpty()) byteArrayOf() else Base64.decode(stderrB64)
    return ProcessOutput(
        status = ProcessStatus(code = code),
        stdout = stdoutBytes,
        stderr = stderrBytes,
    )
}

internal actual fun printStdoutLine(line: String) {
    println(line)
}

private fun jsGetEnv(name: String): String? =
    js(
        "(typeof process !== 'undefined' && process && process.env && typeof process.env[name] === 'string') ? process.env[name] : null",
    )

private fun jsGetEnvKeysJoined(): String =
    js(
        "(typeof process !== 'undefined' && process && process.env) ? Object.keys(process.env).join('\\u0000') : ''",
    )

private fun jsCurrentDir(): String? =
    js(
        "(typeof process !== 'undefined' && process && typeof process.cwd === 'function') ? process.cwd() : null",
    )

private fun jsOsPlatform(): String? =
    js(
        "(typeof process !== 'undefined' && process && typeof process.platform === 'string') ? process.platform : null",
    )

private fun jsPathExists(path: String): Boolean =
    js(
        "(function(p){try{var fs=(typeof process!=='undefined'&&process&&typeof process.getBuiltinModule==='function')?process.getBuiltinModule('fs'):(eval('typeof require===\"function\"?require:null')?eval('require')('fs'):null);return fs?!!fs.existsSync(p):false;}catch(e){return false;}})(path)",
    )

private fun jsPathIsFile(path: String): Boolean =
    js(
        "(function(p){try{var fs=(typeof process!=='undefined'&&process&&typeof process.getBuiltinModule==='function')?process.getBuiltinModule('fs'):(eval('typeof require===\"function\"?require:null')?eval('require')('fs'):null);return fs?!!fs.statSync(p).isFile():false;}catch(e){return false;}})(path)",
    )

private fun jsSpawnSyncEncoded(
    exe: String,
    argsJoined: String,
    argsCount: Int,
    envSetJoined: String,
    envUnsetJoined: String,
): String =
    js(
        "(function(e,aj,ac,es,eu){try{var cp=(typeof process!=='undefined'&&process&&typeof process.getBuiltinModule==='function')?process.getBuiltinModule('child_process'):(eval('typeof require===\"function\"?require:null')?eval('require')('child_process'):null);if(!cp)return 'ERR:NotFound:child_process unavailable in this Wasm-JS host';var a=ac===0?[]:aj.split('\\u0000');var en=Object.assign({},(typeof process!=='undefined'&&process&&process.env)?process.env:{});if(eu.length>0){var uk=eu.split('\\u0000');for(var i=0;i<uk.length;i++)delete en[uk[i]];}if(es.length>0){var ps=es.split('\\u0000');for(var j=0;j<ps.length;j++){var eq=ps[j].indexOf('=');if(eq>0)en[ps[j].substring(0,eq)]=ps[j].substring(eq+1);}}var r=cp.spawnSync(e,a,{env:en});if(r.error){var k=r.error.code==='ENOENT'?'NotFound':'Other';var m=r.error.message?String(r.error.message):'spawn failed';return 'ERR:'+k+':'+m;}var st=(r.status===null||r.status===undefined)?'':String(r.status);var so=r.stdout?r.stdout.toString('base64'):'';var se=r.stderr?r.stderr.toString('base64'):'';return 'OK:'+st+':'+so+':'+se;}catch(err){return 'ERR:Other:'+(err&&err.message?String(err.message):String(err));}})(exe,argsJoined,argsCount,envSetJoined,envUnsetJoined)",
    )
