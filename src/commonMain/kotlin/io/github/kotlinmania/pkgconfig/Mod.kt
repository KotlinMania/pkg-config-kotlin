// port-lint: source lib.rs
package io.github.kotlinmania.pkgconfig

/**
 * A build dependency for libraries to find system artifacts through the
 * `pkg-config` utility.
 *
 * This library shells out to `pkg-config` as part of build scripts and probes
 * the system to determine how to link to a specified library. The [Config]
 * structure serves as a method of configuring how `pkg-config` is invoked in a
 * builder style.
 *
 * After running `pkg-config` all appropriate Cargo metadata will be printed on
 * stdout if the search was successful.
 *
 * # Environment variables
 *
 * A number of environment variables are available to globally configure how
 * this library invokes `pkg-config`:
 *
 * * `FOO_NO_PKG_CONFIG` - if set, this disables running `pkg-config` when
 *   probing for the library named `foo`.
 *
 * ### Linking
 *
 * There are also a number of environment variables which can configure how a
 * library is linked to (dynamically vs statically). These variables control
 * whether the `--static` flag is passed. Note that this behavior can be
 * overridden by configuring explicitly on [Config]. The variables are checked
 * in the following order:
 *
 * * `FOO_STATIC` - pass `--static` for the library `foo`
 * * `FOO_DYNAMIC` - do not pass `--static` for the library `foo`
 * * `PKG_CONFIG_ALL_STATIC` - pass `--static` for all libraries
 * * `PKG_CONFIG_ALL_DYNAMIC` - do not pass `--static` for all libraries
 *
 * ### Cross-compilation
 *
 * In cross-compilation context, it is useful to manage separately
 * `PKG_CONFIG_PATH` and a few other variables for the `host` and the `target`
 * platform.
 *
 * The supported variables are: `PKG_CONFIG_PATH`, `PKG_CONFIG_LIBDIR`, and
 * `PKG_CONFIG_SYSROOT_DIR`.
 *
 * Each of these variables can also be supplied with certain prefixes and
 * suffixes, in the following prioritized order:
 *
 * 1. `<var>_<target>` - for example, `PKG_CONFIG_PATH_TARGET`
 * 2. `<var>_<target-with-underscores>`
 * 3. `<build-kind>_<var>` - for example, `HOST_PKG_CONFIG_PATH` or
 *    `TARGET_PKG_CONFIG_PATH`
 * 4. `<var>` - a plain `PKG_CONFIG_PATH`
 *
 * This library allows `pkg-config` to be used in cross-compilation
 * if `PKG_CONFIG_SYSROOT_DIR` or `PKG_CONFIG` is set. You can set
 * `PKG_CONFIG_ALLOW_CROSS=1` to bypass the compatibility check, but please
 * note that enabling use of `pkg-config` in cross-compilation without
 * appropriate sysroot and search paths set is likely to break builds.
 *
 * # Example
 *
 * Find the system library named `foo`, with minimum version 1.2.3:
 *
 * ```kotlin
 * fun main() {
 *     Config.new().atleastVersion("1.2.3").probe("foo")
 * }
 * ```
 *
 * Find the system library named `foo`, with no version requirement (not
 * recommended):
 *
 * ```kotlin
 * fun main() {
 *     probeLibrary("foo")
 * }
 * ```
 *
 * Configure how library `foo` is linked to:
 *
 * ```kotlin
 * fun main() {
 *     Config.new().atleastVersion("1.2.3").statik(true).probe("foo")
 * }
 * ```
 */

// Crate-root module ledger for upstream `pkg-config/src/lib.rs`:
// - `VersionBound` -> `VersionBound.kt`
// - `WrappedCommand`, `quoteIfNeeded` -> `WrappedCommand.kt`
// - `Config`, `RunOutcome`, `findLibrary`, `probeLibrary`, `targetSupported`,
//   `getVariable`, `envify`, `isStaticAvailable`, `splitFlags` -> `Config.kt`
// - `Library`, `Define`, `Defines` -> `Library.kt`
// - `ProbeOutcome`, `VariableOutcome`, `Error`, `formatOutput` -> `Error.kt`
//
// Callers migrated:
//   (none)

private const val CRATE_ROOT = "io.github.kotlinmania.pkgconfig"
