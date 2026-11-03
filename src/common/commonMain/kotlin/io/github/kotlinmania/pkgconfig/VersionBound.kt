// port-lint: source lib.rs
package io.github.kotlinmania.pkgconfig

/**
 * Parametrises the bounded version ranges accepted by [Config.rangeVersion].
 * The variant order follows upstream. Non-generic because every consumer specialises
 * to `String`, and a generic sealed class triggers the Kotlin to Swift Export bridge
 * to emit `VersionBound.Included<Any?>` casts that fail under `allWarningsAsErrors`.
 */
public sealed class VersionBound {
    public data class Included(
        val value: String,
    ) : VersionBound()

    public data class Excluded(
        val value: String,
    ) : VersionBound()

    public object Unbounded : VersionBound()
}
