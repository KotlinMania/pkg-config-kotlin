// port-lint: ignore — wasm-js actuals. Reads env from `process.env` when
// running under Node; pkg-config invocation is not available from Wasm/JS, so
// `spawnProcess` always reports the process could not be found.
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kotlinmania.pkgconfig

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

internal actual fun envVar(name: String): String? = jsGetEnv(name)

internal actual fun pathExists(path: String): Boolean = jsPathExists(path)

internal actual fun pathIsFile(path: String): Boolean = jsPathIsFile(path)

internal actual fun spawnProcess(
    exe: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessOutput = throw IoSpawnException(
    IoError(
        IoErrorKind.NotFound,
        "process invocation is not available on wasm-js; pkg-config cannot be probed here",
    ),
)

internal actual fun printStdoutLine(line: String) {
    println(line)
}

private fun jsGetEnv(name: String): String? =
    js(
        "(typeof process !== 'undefined' && process && process.env && typeof process.env[name] === 'string') ? process.env[name] : null",
    )

private fun jsOsPlatform(): String? =
    js(
        "(typeof process !== 'undefined' && process && typeof process.platform === 'string') ? process.platform : null",
    )

private fun jsPathExists(path: String): Boolean =
    js(
        "(function(p){try{var fs=require('fs');return !!fs.existsSync(p);}catch(e){return false;}})(path)",
    )

private fun jsPathIsFile(path: String): Boolean =
    js(
        "(function(p){try{var fs=require('fs');return !!fs.statSync(p).isFile();}catch(e){return false;}})(path)",
    )
