// port-lint: ignore — wasm-wasi actuals. WASI does not expose a process API
// and the WASI preview1 environment surface is limited; we return empty data
// and report `NotFound` for spawn attempts.
package io.github.kotlinmania.pkgconfig

internal actual fun currentTargetOs(): TargetOs = TargetOs.Wasi

internal actual fun envVar(name: String): String? = null

internal actual fun pathExists(path: String): Boolean = false

internal actual fun pathIsFile(path: String): Boolean = false

internal actual fun spawnProcess(
    exe: String,
    args: List<String>,
    env: Map<String, String>,
): ProcessOutput = throw IoSpawnException(
    IoError(
        IoErrorKind.NotFound,
        "process invocation is not available on wasm-wasi; pkg-config cannot be probed here",
    ),
)

internal actual fun printStdoutLine(line: String) {
    println(line)
}
