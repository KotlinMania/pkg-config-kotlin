@file:OptIn(kotlin.wasm.ExperimentalWasmInterop::class, kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package io.github.kotlinmania.pkgconfig

import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.withScopedMemoryAllocator

internal actual fun currentTargetOs(): TargetOs = TargetOs.Wasi

internal actual fun platformEnvVar(name: String): String? = platformEnvVars()[name]

internal actual fun platformEnvVars(): Map<String, String> =
    withScopedMemoryAllocator { allocator ->
        val countPtr = allocator.allocate(Int.SIZE_BYTES)
        val sizePtr = allocator.allocate(Int.SIZE_BYTES)
        val sizesErrno = environSizesGet(countPtr.address.toInt(), sizePtr.address.toInt())
        if (sizesErrno != 0) return@withScopedMemoryAllocator emptyMap()

        val count = countPtr.loadInt()
        val bufferSize = sizePtr.loadInt()
        if (count <= 0 || bufferSize <= 0) return@withScopedMemoryAllocator emptyMap()

        val environPtr = allocator.allocate(count * Int.SIZE_BYTES)
        val bufferPtr = allocator.allocate(bufferSize)
        val getErrno = environGet(environPtr.address.toInt(), bufferPtr.address.toInt())
        if (getErrno != 0) return@withScopedMemoryAllocator emptyMap()

        buildMap(count) {
            for (index in 0 until count) {
                val entryPtr = Pointer((environPtr + index * Int.SIZE_BYTES).loadInt().toUInt())
                val entry = entryPtr.readNullTerminatedString()
                val eq = entry.indexOf('=')
                if (eq > 0) {
                    put(entry.substring(0, eq), entry.substring(eq + 1))
                }
            }
        }
    }

internal actual fun platformCurrentDir(): String = platformEnvVar("PWD") ?: "."

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

private fun Pointer.readNullTerminatedString(): String {
    var length = 0
    while ((this + length).loadByte().toInt() != 0) {
        length += 1
    }
    val bytes = ByteArray(length)
    for (index in 0 until length) {
        bytes[index] = (this + index).loadByte()
    }
    return bytes.decodeToString()
}

@WasmImport("wasi_snapshot_preview1", "environ_sizes_get")
private external fun environSizesGet(environCount: Int, environBufferSize: Int): Int

@WasmImport("wasi_snapshot_preview1", "environ_get")
private external fun environGet(environ: Int, environBuffer: Int): Int
