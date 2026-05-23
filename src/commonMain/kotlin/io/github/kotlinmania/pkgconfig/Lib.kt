// port-lint: source src/lib.rs
package io.github.kotlinmania.pkgconfig

// A build dependency for Cargo libraries to find system artifacts through the
// `pkg-config` utility.
//
// This library will shell out to `pkg-config` as part of build scripts and
// probe the system to determine how to link to a specified library. The
// [Config] class serves as a method of configuring how `pkg-config` is
// invoked in a builder style.
//
// After running `pkg-config` all appropriate Cargo metadata will be printed on
// stdout if the search was successful.
//
// # Environment variables
//
// A number of environment variables are available to globally configure how
// this crate will invoke `pkg-config`:
//
// * `FOO_NO_PKG_CONFIG` - if set, this will disable running `pkg-config` when
//   probing for the library named `foo`.
//
// ### Linking
//
// There are also a number of environment variables which can configure how a
// library is linked to (dynamically vs statically). These variables control
// whether the `--static` flag is passed. Note that this behavior can be
// overridden by configuring explicitly on [Config]. The variables are checked
// in the following order:
//
// * `FOO_STATIC` - pass `--static` for the library `foo`
// * `FOO_DYNAMIC` - do not pass `--static` for the library `foo`
// * `PKG_CONFIG_ALL_STATIC` - pass `--static` for all libraries
// * `PKG_CONFIG_ALL_DYNAMIC` - do not pass `--static` for all libraries
//
// ### Cross-compilation
//
// In cross-compilation context, it is useful to manage separately
// `PKG_CONFIG_PATH` and a few other variables for the `host` and the `target`
// platform.
//
// The supported variables are: `PKG_CONFIG_PATH`, `PKG_CONFIG_LIBDIR`, and
// `PKG_CONFIG_SYSROOT_DIR`.
//
// Each of these variables can also be supplied with certain prefixes and
// suffixes, in the following prioritized order:
//
// 1. `<var>_<target>` - for example, `PKG_CONFIG_PATH_x86_64-unknown-linux-gnu`
// 2. `<var>_<target_with_underscores>` - for example,
//    `PKG_CONFIG_PATH_x86_64_unknown_linux_gnu`
// 3. `<build-kind>_<var>` - for example, `HOST_PKG_CONFIG_PATH` or
//    `TARGET_PKG_CONFIG_PATH`
// 4. `<var>` - a plain `PKG_CONFIG_PATH`
//
// This crate will allow `pkg-config` to be used in cross-compilation
// if `PKG_CONFIG_SYSROOT_DIR` or `PKG_CONFIG` is set. You can set
// `PKG_CONFIG_ALLOW_CROSS=1` to bypass the compatibility check, but please
// note that enabling use of `pkg-config` in cross-compilation without
// appropriate sysroot and search paths set is likely to break builds.
//
// # Example
//
// Find the system library named `foo`, with minimum version 1.2.3:
//
//     fun main() {
//         Config().atleastVersion("1.2.3").probe("foo").getOrThrow()
//     }
//
// Find the system library named `foo`, with no version requirement (not
// recommended):
//
//     fun main() {
//         probeLibrary("foo").getOrThrow()
//     }
//
// Configure how library `foo` is linked to.
//
//     fun main() {
//         Config().atleastVersion("1.2.3").statik(true).probe("foo").getOrThrow()
//     }

/**
 * Mirrors Rust's [`std::ops::Bound`] used to parametrise the bounded version
 * ranges accepted by [Config.rangeVersion]. The variant order follows
 * upstream. Non-generic because every consumer specialises to `String`, and
 * a generic sealed class triggers the Kotlin → Swift Export bridge to emit
 * `VersionBound.Included<Any?>` casts that fail under `allWarningsAsErrors`.
 */
public sealed class VersionBound {
    public data class Included(val value: String) : VersionBound()
    public data class Excluded(val value: String) : VersionBound()
    public object Unbounded : VersionBound()
}

/**
 * Wrapper holder to polyfill methods introduced in 1.57 (`get_envs`,
 * `get_args` etc). This is needed to reconstruct the pkg-config command for
 * output in a copy-paste friendly format via [toString].
 */
internal class WrappedCommand internal constructor(programArg: String) {
    val program: String = programArg
    private val envVars: MutableList<Pair<String, String>> = mutableListOf()
    private val collectedArgs: MutableList<String> = mutableListOf()

    fun args(args: Iterable<String>): WrappedCommand {
        for (arg in args) {
            collectedArgs.add(arg)
        }
        return this
    }

    fun arg(arg: String): WrappedCommand {
        collectedArgs.add(arg)
        return this
    }

    fun env(key: String, value: String): WrappedCommand {
        envVars.add(key to value)
        return this
    }

    fun output(): ProcessOutput {
        val envMap = envVars.associate { it }
        return spawnProcess(program, collectedArgs.toList(), envMap)
    }

    // Output a command invocation that can be copy-pasted into the terminal.
    // [WrappedCommand]'s existing debug implementation is not used for that reason,
    // as it can sometimes lead to output such as:
    // `PKG_CONFIG_ALLOW_SYSTEM_CFLAGS="1" PKG_CONFIG_ALLOW_SYSTEM_LIBS="1" "pkg-config" "--libs" "--cflags" "mylibrary"`
    // Which cannot be copy-pasted into terminals such as nushell, and is a bit noisy.
    // This will look something like:
    // `PKG_CONFIG_ALLOW_SYSTEM_CFLAGS=1 PKG_CONFIG_ALLOW_SYSTEM_LIBS=1 pkg-config --libs --cflags mylibrary`
    override fun toString(): String {
        // Format all explicitly defined environment variables
        val envs = envVars
            .joinToString(separator = " ") { (env, arg) -> "$env=$arg" }

        // Format all pkg-config arguments
        val argsRendered = collectedArgs
            .joinToString(separator = " ") { arg -> quoteIfNeeded(arg) }

        return "$envs $program $argsRendered"
    }
}

/**
 * Builder configuration for invoking `pkg-config`.
 */
public class Config {
    internal var statik: Boolean? = null
    internal var minVersion: VersionBound = VersionBound.Unbounded
    internal var maxVersion: VersionBound = VersionBound.Unbounded
    internal val extraArgs: MutableList<String> = mutableListOf()
    internal var cargoMetadata: Boolean = true
    internal var envMetadata: Boolean = true
    internal var printSystemLibs: Boolean = true
    internal var printSystemCflags: Boolean = true

    public companion object {
        /**
         * Creates a new set of configuration options which are all initially
         * set to "blank".
         */
        public operator fun invoke(): Config = Config()

        /**
         * Deprecated in favor of the top level [getVariable] function.
         */
        @Deprecated(
            "use the top level getVariable function instead",
            level = DeprecationLevel.HIDDEN,
        )
        public fun getVariable(packageName: String, variable: String): VariableOutcome =
            io.github.kotlinmania.pkgconfig.getVariable(packageName, variable)
    }

    /**
     * Indicate whether the `--static` flag should be passed.
     *
     * This will override the inference from environment variables described in
     * the crate documentation.
     */
    public fun statik(statik: Boolean): Config {
        this.statik = statik
        return this
    }

    /** Indicate that the library must be at least version `vers`. */
    public fun atleastVersion(vers: String): Config {
        this.minVersion = VersionBound.Included(vers)
        this.maxVersion = VersionBound.Unbounded
        return this
    }

    /** Indicate that the library must be equal to version `vers`. */
    public fun exactlyVersion(vers: String): Config {
        this.minVersion = VersionBound.Included(vers)
        this.maxVersion = VersionBound.Included(vers)
        return this
    }

    /** Indicate that the library's version must be in `range`. */
    public fun rangeVersion(start: VersionBound, end: VersionBound): Config {
        this.minVersion = when (start) {
            is VersionBound.Included -> VersionBound.Included(start.value)
            is VersionBound.Excluded -> VersionBound.Excluded(start.value)
            VersionBound.Unbounded -> VersionBound.Unbounded
        }
        this.maxVersion = when (end) {
            is VersionBound.Included -> VersionBound.Included(end.value)
            is VersionBound.Excluded -> VersionBound.Excluded(end.value)
            VersionBound.Unbounded -> VersionBound.Unbounded
        }
        return this
    }

    /**
     * Add an argument to pass to pkg-config.
     *
     * It's placed after all of the arguments generated by this library.
     */
    public fun arg(arg: String): Config {
        extraArgs.add(arg)
        return this
    }

    /**
     * Define whether metadata should be emitted for cargo allowing it to
     * automatically link the binary. Defaults to `true`.
     */
    public fun cargoMetadata(cargoMetadata: Boolean): Config {
        this.cargoMetadata = cargoMetadata
        return this
    }

    /**
     * Define whether metadata should be emitted for cargo allowing to
     * automatically rebuild when environment variables change. Defaults to
     * `true`.
     */
    public fun envMetadata(envMetadata: Boolean): Config {
        this.envMetadata = envMetadata
        return this
    }

    /**
     * Enable or disable the `PKG_CONFIG_ALLOW_SYSTEM_LIBS` environment
     * variable.
     *
     * This env var is enabled by default.
     */
    public fun printSystemLibs(print: Boolean): Config {
        this.printSystemLibs = print
        return this
    }

    /**
     * Enable or disable the `PKG_CONFIG_ALLOW_SYSTEM_CFLAGS` environment
     * variable.
     *
     * This env var is enabled by default.
     */
    public fun printSystemCflags(print: Boolean): Config {
        this.printSystemCflags = print
        return this
    }

    /** Deprecated in favor of the [probe] function. */
    @Deprecated(
        "use probe instead",
        ReplaceWith("probe(name)"),
        level = DeprecationLevel.HIDDEN,
    )
    public fun find(name: String): ProbeOutcome = probe(name)

    /**
     * Run `pkg-config` to find the library `name`.
     *
     * This will use all configuration previously set to specify how
     * `pkg-config` is run.
     */
    public fun probe(name: String): ProbeOutcome {
        val abortVarName = "${envify(name)}_NO_PKG_CONFIG"
        if (envVarOs(abortVarName) != null) {
            return ProbeOutcome.Failure(Error.EnvNoPkgConfig(abortVarName))
        } else if (!targetSupported()) {
            return ProbeOutcome.Failure(Error.CrossCompilation)
        }

        val library = Library.new()

        val firstOutcome = runProbe(name, listOf("--libs", "--cflags"))
        val output = when (firstOutcome) {
            is RunOutcome.Ok -> firstOutcome.stdout
            is RunOutcome.Err -> {
                val mapped = if (firstOutcome.error is Error.Failure) {
                    Error.ProbeFailure(
                        name = name,
                        command = firstOutcome.error.command,
                        output = firstOutcome.error.output,
                    )
                } else {
                    firstOutcome.error
                }
                return ProbeOutcome.Failure(mapped)
            }
        }
        library.parseLibsCflags(name, output, this)

        val modOutcome = runProbe(name, listOf("--modversion"))
        val modversion = when (modOutcome) {
            is RunOutcome.Ok -> modOutcome.stdout
            is RunOutcome.Err -> return ProbeOutcome.Failure(modOutcome.error)
        }
        library.parseModversion(modversion.decodeToString())

        return ProbeOutcome.Success(library)
    }

    /**
     * True if pkg-config is used for the host system, or configured for
     * cross-compilation.
     */
    public fun targetSupported(): Boolean {
        val target = envVar("TARGET").orEmpty()
        val host = envVar("HOST").orEmpty()

        // Only use pkg-config in host == target situations by default (allowing
        // an override).
        if (host == target) {
            return true
        }

        // pkg-config may not be aware of cross-compilation, and require
        // a wrapper script that sets up platform-specific prefixes.
        return when (val v = targetedEnvVar("PKG_CONFIG_ALLOW_CROSS")) {
            // don't use pkg-config if explicitly disabled
            "0" -> false
            null -> {
                // if not disabled, and pkg-config is customized,
                // then assume it's prepared for cross-compilation
                targetedEnvVar("PKG_CONFIG") != null ||
                    targetedEnvVar("PKG_CONFIG_SYSROOT_DIR") != null
            }
            else -> {
                // Silence the unused-variable lint while still consulting `v`.
                v.isNotEmpty() || true
            }
        }
    }

    internal fun targetedEnvVar(varBase: String): String? {
        val target = envVar("TARGET")
        val host = envVar("HOST")
        return if (target != null && host != null) {
            val kind = if (host == target) "HOST" else "TARGET"
            val targetU = target.replace('-', '_')

            envVarOs("${varBase}_${target}")
                ?: envVarOs("${varBase}_${targetU}")
                ?: envVarOs("${kind}_${varBase}")
                ?: envVarOs(varBase)
        } else {
            envVarOs(varBase)
        }
    }

    internal fun envVarOs(name: String): String? {
        if (envMetadata) {
            printStdoutLine("cargo:rerun-if-env-changed=$name")
        }
        return envVar(name)
    }

    internal fun isStatic(name: String): Boolean =
        statik ?: inferStatic(name)

    internal fun runProbe(name: String, args: List<String>): RunOutcome {
        val pkgConfigExe = targetedEnvVar("PKG_CONFIG")
        val fallbackExe = if (pkgConfigExe == null) "pkgconf" else null
        val exe = pkgConfigExe ?: "pkg-config"

        val cmd = command(exe, name, args)

        val outcome: ProcessOutput = try {
            cmd.output()
        } catch (e: IoSpawnException) {
            if (fallbackExe != null) {
                try {
                    command(fallbackExe, name, args).output()
                } catch (_: IoSpawnException) {
                    return RunOutcome.Err(Error.Command(command = cmd.toString(), ioCause = e.ioError))
                }
            } else {
                return RunOutcome.Err(Error.Command(command = cmd.toString(), ioCause = e.ioError))
            }
        }

        return if (outcome.status.success()) {
            RunOutcome.Ok(outcome.stdout)
        } else {
            RunOutcome.Err(Error.Failure(command = cmd.toString(), output = outcome))
        }
    }

    internal fun command(exe: String, name: String, args: List<String>): WrappedCommand {
        val cmd = WrappedCommand(exe)
        if (isStatic(name)) {
            cmd.arg("--static")
        }
        cmd.args(args).args(extraArgs)

        targetedEnvVar("PKG_CONFIG_PATH")?.let { cmd.env("PKG_CONFIG_PATH", it) }
        targetedEnvVar("PKG_CONFIG_LIBDIR")?.let { cmd.env("PKG_CONFIG_LIBDIR", it) }
        targetedEnvVar("PKG_CONFIG_SYSROOT_DIR")?.let { cmd.env("PKG_CONFIG_SYSROOT_DIR", it) }
        if (printSystemLibs) {
            cmd.env("PKG_CONFIG_ALLOW_SYSTEM_LIBS", "1")
        }
        if (printSystemCflags) {
            cmd.env("PKG_CONFIG_ALLOW_SYSTEM_CFLAGS", "1")
        }
        cmd.arg(name)
        when (val v = minVersion) {
            is VersionBound.Included -> cmd.arg("$name >= ${v.value}")
            is VersionBound.Excluded -> cmd.arg("$name > ${v.value}")
            VersionBound.Unbounded -> { /* no version arg */ }
        }
        when (val v = maxVersion) {
            is VersionBound.Included -> cmd.arg("$name <= ${v.value}")
            is VersionBound.Excluded -> cmd.arg("$name < ${v.value}")
            VersionBound.Unbounded -> { /* no version arg */ }
        }
        return cmd
    }

    internal fun printMetadata(s: String) {
        if (cargoMetadata) {
            printStdoutLine("cargo:$s")
        }
    }

    internal fun inferStatic(name: String): Boolean {
        val nameEnv = envify(name)
        return when {
            envVarOs("${nameEnv}_STATIC") != null -> true
            envVarOs("${nameEnv}_DYNAMIC") != null -> false
            envVarOs("PKG_CONFIG_ALL_STATIC") != null -> true
            envVarOs("PKG_CONFIG_ALL_DYNAMIC") != null -> false
            else -> false
        }
    }
}

/**
 * Result of a successful [Config.probe] call: the parsed link search paths,
 * libraries, include paths and other metadata yielded by `pkg-config`.
 */
public class Library internal constructor() {
    internal val libsMut: MutableList<String> = mutableListOf()
    internal val linkPathsMut: MutableList<String> = mutableListOf()
    internal val linkFilesMut: MutableList<String> = mutableListOf()
    internal val frameworksMut: MutableList<String> = mutableListOf()
    internal val frameworkPathsMut: MutableList<String> = mutableListOf()
    internal val includePathsMut: MutableList<String> = mutableListOf()
    internal val ldArgsMut: MutableList<List<String>> = mutableListOf()

    /** Libraries specified by `-l`. */
    public val libs: List<String> get() = libsMut.toList()

    /** Library search paths specified by `-L`. */
    public val linkPaths: List<String> get() = linkPathsMut.toList()

    /** Library file paths specified without `-l`. */
    public val linkFiles: List<String> get() = linkFilesMut.toList()

    /** Darwin frameworks specified by `-framework`. */
    public val frameworks: List<String> get() = frameworksMut.toList()

    /** Darwin framework search paths specified by `-F`. */
    public val frameworkPaths: List<String> get() = frameworkPathsMut.toList()

    /** C/C++ header include paths specified by `-I`. */
    public val includePaths: List<String> get() = includePathsMut.toList()

    /** Linker options specified by `-Wl`. */
    public val ldArgs: List<List<String>> get() = ldArgsMut.toList()

    /** C/C++ definitions specified by `-D`. */
    public val defines: Defines = Defines()

    /** Version specified by .pc file's Version field. */
    public var version: String = ""

    internal companion object {
        fun new(): Library = Library()

        /**
         * Extract the substring to pass to `cargo:rustc-link-lib` from a
         * filename (just the file name, not including directories) using
         * target-specific logic.
         */
        fun extractLibFromFilename(target: String, filename: String): String? {
            fun testSuffixes(name: String, suffixes: Array<String>): String? {
                for (suffix in suffixes) {
                    if (name.endsWith(suffix)) {
                        return name.substring(0, name.length - suffix.length)
                    }
                }
                return null
            }

            val prefix = "lib"
            return when {
                target.contains("windows") -> {
                    if (target.contains("gnu") && filename.startsWith(prefix)) {
                        // GNU targets for Windows, including gnullvm, use `LinkerFlavor::Gcc`
                        // internally in rustc, which tells rustc to use the GNU linker. rustc
                        // does not prepend/append to the string it receives via the -l command
                        // line argument before passing it to the linker.
                        // GNU ld can work with more types of files than just the .lib files that
                        // MSVC's link.exe needs. GNU ld will prepend the `lib` prefix to the
                        // filename if necessary, so it is okay to remove the `lib` prefix from
                        // the filename. The `.a` suffix *requires* the `lib` prefix.
                        val rest = filename.substring(prefix.length)
                        testSuffixes(rest, arrayOf(".dll.a", ".dll", ".lib", ".a"))
                    } else {
                        // According to link.exe documentation:
                        //
                        //   LINK doesn't use file extensions to make assumptions about the
                        //   contents of a file. Instead, LINK examines each input file to
                        //   determine what kind of file it is.
                        //
                        // However, rustc appends `.lib` to the string it receives from the -l
                        // command line argument, which it receives from Cargo via
                        // cargo:rustc-link-lib. So the only file extension that works for MSVC
                        // targets is `.lib`. However, for externally created libraries, there's
                        // no guarantee that the extension is ".lib" so we need to consider all
                        // options.
                        testSuffixes(filename, arrayOf(".dll.a", ".dll", ".lib", ".a"))
                    }
                }
                target.contains("apple") -> {
                    if (filename.startsWith(prefix)) {
                        val rest = filename.substring(prefix.length)
                        testSuffixes(rest, arrayOf(".a", ".so", ".dylib"))
                    } else {
                        null
                    }
                }
                else -> {
                    if (filename.startsWith(prefix)) {
                        val rest = filename.substring(prefix.length)
                        testSuffixes(rest, arrayOf(".a", ".so"))
                    } else {
                        null
                    }
                }
            }
        }
    }

    internal fun parseLibsCflags(name: String, output: ByteArray, config: Config) {
        val target = envVar("TARGET")
        val isMsvc = target?.contains("msvc") ?: false

        val systemRoots: List<String> = if (currentTargetOs() == TargetOs.Macos) {
            listOf("/Library", "/System")
        } else {
            val sysroot = config.envVarOs("PKG_CONFIG_SYSROOT_DIR")
                ?: config.envVarOs("SYSROOT")

            if (currentTargetOs() == TargetOs.Windows) {
                if (sysroot != null) listOf(sysroot) else emptyList()
            } else {
                listOf(sysroot ?: "/usr")
            }
        }

        val dirs: MutableList<String> = mutableListOf()
        val statik = config.isStatic(name)

        val words = splitFlags(output)

        // Handle single-character arguments like `-I/usr/include`
        val parts = words.asSequence()
            .filter { it.length > 2 }
            .map { Pair(it.substring(0, 2), it.substring(2)) }
        for ((flag, value) in parts) {
            when (flag) {
                "-L" -> {
                    val meta = "rustc-link-search=native=$value"
                    config.printMetadata(meta)
                    dirs.add(value)
                    linkPathsMut.add(value)
                }
                "-F" -> {
                    val meta = "rustc-link-search=framework=$value"
                    config.printMetadata(meta)
                    frameworkPathsMut.add(value)
                }
                "-I" -> {
                    includePathsMut.add(value)
                }
                "-l" -> {
                    // These are provided by the CRT with MSVC
                    if (isMsvc && (value == "m" || value == "c" || value == "pthread")) {
                        continue
                    }

                    if (value.startsWith(':')) {
                        // Pass this flag to linker directly.
                        val meta = "rustc-link-arg=${flag}${value}"
                        config.printMetadata(meta)
                    } else if (statik && isStaticAvailable(value, systemRoots, dirs)) {
                        val meta = "rustc-link-lib=static=$value"
                        config.printMetadata(meta)
                    } else {
                        val meta = "rustc-link-lib=$value"
                        config.printMetadata(meta)
                    }

                    libsMut.add(value)
                }
                "-D" -> {
                    val iter = value.splitToSequence('=').iterator()
                    val key = iter.next()
                    val maybeValue: String? = if (iter.hasNext()) iter.next() else null
                    defines.put(key, maybeValue)
                }
                "-u" -> {
                    val meta = "rustc-link-arg=-Wl,-u,$value"
                    config.printMetadata(meta)
                }
                else -> { /* ignore */ }
            }
        }

        // Handle multi-character arguments with space-separated value like `-framework foo`
        val iter: Iterator<String> = sequence {
            for (arg in words) {
                if (arg.startsWith("-Wl,")) {
                    for (sub in arg.substring(4).split(',')) {
                        yield(sub)
                    }
                } else {
                    yield(arg)
                }
            }
        }.iterator()
        while (iter.hasNext()) {
            when (val part = iter.next()) {
                "-framework" -> {
                    if (iter.hasNext()) {
                        val lib = iter.next()
                        val meta = "rustc-link-lib=framework=$lib"
                        config.printMetadata(meta)
                        frameworksMut.add(lib)
                    }
                }
                "-isystem", "-iquote", "-idirafter" -> {
                    if (iter.hasNext()) {
                        val inc = iter.next()
                        includePathsMut.add(inc)
                    }
                }
                "-undefined", "--undefined" -> {
                    if (iter.hasNext()) {
                        val symbol = iter.next()
                        val meta = "rustc-link-arg=-Wl,${part},${symbol}"
                        config.printMetadata(meta)
                    }
                }
                else -> {
                    if (pathIsFile(part)) {
                        // Cargo doesn't have a means to directly specify a file path to link,
                        // so split up the path into the parent directory and library name.
                        val parentEnd = lastPathSeparator(part)
                        if (parentEnd >= 0 && target != null) {
                            val dir = part.substring(0, parentEnd)
                            val fileName = part.substring(parentEnd + 1)
                            when (val libBasename = extractLibFromFilename(target, fileName)) {
                                null -> {
                                    printStdoutLine(
                                        "cargo:warning=File path $part found in pkg-config file for $name, but could not extract library base name to pass to linker command line",
                                    )
                                }
                                else -> {
                                    val linkSearch = "rustc-link-search=$dir"
                                    config.printMetadata(linkSearch)

                                    val linkLib = "rustc-link-lib=$libBasename"
                                    config.printMetadata(linkLib)
                                    linkFilesMut.add(part)
                                }
                            }
                        }
                    }
                }
            }
        }

        val linkerOptions = words.asSequence().filter { it.startsWith("-Wl,") }
        for (option in linkerOptions) {
            var pop = false
            val ldOption: MutableList<String> = mutableListOf()
            for (subopt in option.substring(4).split(',')) {
                if (pop) {
                    pop = false
                    continue
                }

                if (subopt == "-framework") {
                    pop = true
                    continue
                }

                ldOption.add(subopt)
            }

            val meta = "rustc-link-arg=-Wl,${ldOption.joinToString(",")}"
            config.printMetadata(meta)

            ldArgsMut.add(ldOption.toList())
        }
    }

    internal fun parseModversion(output: String) {
        val firstLine = output.lineSequence().first()
        version += firstLine.trim()
    }
}

/** A single `-D` define parsed out of pkg-config output. */
public data class Define(val name: String, val value: String?)

/**
 * Ordered, deduplicating collection of `-D` defines parsed from pkg-config
 * output. Behaves like Rust's `HashMap<String, Option<String>>`: the same
 * name inserted twice keeps only the latest value.
 */
public class Defines {
    private val entries: MutableMap<String, String?> = mutableMapOf()

    /** Insert or overwrite the value associated with [name]. */
    public fun put(name: String, value: String?) {
        entries[name] = value
    }

    /** Look up the value for [name], or `null` if not present. */
    public operator fun get(name: String): String? = entries[name]

    /** True when [name] has been inserted (regardless of its value). */
    public operator fun contains(name: String): Boolean = entries.containsKey(name)

    /** Snapshot of the inserted defines, in insertion order. */
    public fun toList(): List<Define> = entries.map { (k, v) -> Define(k, v) }

    /** Number of defines currently held. */
    public val size: Int get() = entries.size
}

/**
 * Outcome of [Config.probe] / [probeLibrary] / [findLibrary]: either a
 * successfully parsed [Library], or an [Error] describing why the probe
 * could not complete.
 */
public sealed class ProbeOutcome {
    public data class Success(val library: Library) : ProbeOutcome()
    public data class Failure(val error: Error) : ProbeOutcome()
}

/**
 * Outcome of [getVariable] / [Config.getVariable]: either the trimmed
 * variable value, or an [Error] describing why pkg-config could not
 * supply it.
 */
public sealed class VariableOutcome {
    public data class Success(val value: String) : VariableOutcome()
    public data class Failure(val error: Error) : VariableOutcome()
}

/**
 * Represents all reasons `pkg-config` might not succeed or be run at all.
 * Not a [Throwable] subtype: the Swift Export bridge generates
 * `Array<Throwable>` shims for any exception-typed public surface, and
 * those casts to `Array<Any?>` fail under `allWarningsAsErrors`.
 */
public sealed class Error {
    /**
     * Aborted because of `*_NO_PKG_CONFIG` environment variable.
     *
     * Contains the name of the responsible environment variable.
     */
    public data class EnvNoPkgConfig(val name: String) : Error()

    /**
     * Detected cross compilation without a custom sysroot.
     *
     * Ignore the error with `PKG_CONFIG_ALLOW_CROSS=1`, which may let
     * `pkg-config` select libraries for the host's architecture instead of the
     * target's.
     */
    public object CrossCompilation : Error()

    /**
     * Failed to run `pkg-config`.
     *
     * Contains the command and the cause.
     */
    public data class Command(val command: String, val ioCause: IoError) : Error()

    /**
     * `pkg-config` did not exit successfully after probing a library.
     *
     * Contains the command and output.
     */
    public data class Failure(val command: String, val output: ProcessOutput) : Error()

    /**
     * `pkg-config` did not exit successfully on the first attempt to probe a
     * library.
     *
     * Contains the command and output.
     */
    public data class ProbeFailure(
        val name: String,
        val command: String,
        val output: ProcessOutput,
    ) : Error()

    /**
     * Reserved for future error variants; kept so exhaustive matching does
     * not assume the full set is known.
     */
    public object Nonexhaustive : Error()

    /**
     * Human-readable explanation for this error, matching the upstream
     * `Display`/`Debug` text exactly. Both the auto-`Debug` and `Display`
     * impls in the Rust crate route here.
     */
    public val message: String
        get() = renderDisplay()

    private fun renderDisplay(): String = when (this) {
        is EnvNoPkgConfig -> "Aborted because $name is set"
        CrossCompilation -> (
            "pkg-config has not been configured to support cross-compilation.\n" +
                "\n" +
                "Install a sysroot for the target platform and configure it via\n" +
                "PKG_CONFIG_SYSROOT_DIR and PKG_CONFIG_PATH, or install a\n" +
                "cross-compiling wrapper for pkg-config and set it via\n" +
                "PKG_CONFIG environment variable."
            )
        is Command -> when (ioCause.kind) {
            IoErrorKind.NotFound -> {
                val crateName = envVar("CARGO_PKG_NAME") ?: "sys"
                val targetOs = currentTargetOs()
                val instructions = when {
                    targetOs == TargetOs.Macos ->
                        "Try `brew install pkgconf` if you have Homebrew.\n"
                    targetOs == TargetOs.Ios ->
                        "" // iOS cross-compilation requires a custom setup, no easy fix
                    targetOs.isUnix() ->
                        "Try `apt install pkg-config`, or `yum install pkg-config`, or `brew install pkgconf`\n" +
                            "or `pkg install pkg-config`, or `apk add pkgconfig` " +
                            "depending on your distribution.\n"
                    else ->
                        "" // There's no easy fix for Windows users
                }
                "Could not run `$command`\n" +
                    "The pkg-config command could not be found.\n" +
                    "\n" +
                    "Most likely, you need to install a pkg-config package for your OS.\n" +
                    instructions +
                    "\n" +
                    "If you've already installed it, ensure the pkg-config command is one of the\n" +
                    "directories in the PATH environment variable.\n" +
                    "\n" +
                    "If you did not expect this build to link to a pre-installed system library,\n" +
                    "then check documentation of the $crateName crate for an option to\n" +
                    "build the library from source, or disable features or dependencies\n" +
                    "that require pkg-config."
            }
            else -> "Failed to run command `$command`, because: ${ioCause.message}"
        }
        is ProbeFailure -> {
            val crateName = envVar("CARGO_PKG_NAME") ?: "<NO CRATE NAME>"
            val sb = StringBuilder()
            sb.append('\n')

            // Give a short explanation of what the error is
            val statusLine = when (val code = output.status.code) {
                null -> "was terminated by signal"
                else -> "exited with status code $code"
            }
            sb.append("pkg-config $statusLine").append('\n')

            // Give the command run so users can reproduce the error
            sb.append("> ").append(command).append("\n\n")

            // Explain how it was caused
            sb.append("The system library `").append(name).append("` required by crate `")
                .append(crateName).append("` was not found.").append('\n')
            sb.append("The file `").append(name)
                .append(".pc` needs to be installed and the PKG_CONFIG_PATH environment variable must contain its parent directory.")
                .append('\n')

            // There will be no status code if terminated by signal
            if (output.status.code != null) {
                // Nix uses a wrapper script for pkg-config that sets the custom
                // environment variable PKG_CONFIG_PATH_FOR_TARGET
                val searchLocations = arrayOf("PKG_CONFIG_PATH_FOR_TARGET", "PKG_CONFIG_PATH")

                // Find a search path to use
                var searchData: Pair<String, String>? = null
                for (location in searchLocations) {
                    val searchPath = envVar(location)
                    if (searchPath != null) {
                        searchData = location to searchPath
                        break
                    }
                }

                // Guess the most reasonable course of action
                val hint = if (searchData != null) {
                    val (searchLocation, searchPath) = searchData
                    sb.append(searchLocation).append(" contains the following:\n")
                        .append(
                            searchPath
                                .split(':')
                                .joinToString(separator = "\n") { path -> "    - $path" },
                        )
                        .append('\n')

                    "you may need to install a package such as $name, $name-dev or $name-devel."
                } else {
                    // Even on Nix, setting PKG_CONFIG_PATH seems to be a viable option
                    sb.append("The PKG_CONFIG_PATH environment variable is not set.").append('\n')

                    "if you have installed the library, try setting PKG_CONFIG_PATH to the directory containing `$name.pc`."
                }

                // Try and nudge the user in the right direction so they don't get stuck
                sb.append("\nHINT: ").append(hint).append('\n')
            }

            sb.toString()
        }
        is Failure -> {
            val head = "`$command` did not exit successfully: ${output.status}"
            head + formatOutput(output)
        }
        Nonexhaustive -> kotlin.error("matched on reserved variant")
    }

    override fun toString(): String = message
}

/**
 * Internal companion of [ProbeOutcome] used by [Config.runProbe] to return
 * raw stdout bytes or a structured [Error]. Kept internal so the Swift
 * Export bridge does not have to expose a `RunOutcome<ByteArray>`
 * generic surface.
 */
internal sealed class RunOutcome {
    internal data class Ok(val stdout: ByteArray) : RunOutcome()
    internal data class Err(val error: Error) : RunOutcome()
}

internal fun formatOutput(output: ProcessOutput): String {
    val sb = StringBuilder()
    val stdout = output.stdout.decodeToString()
    if (stdout.isNotEmpty()) {
        sb.append("\n--- stdout\n").append(stdout)
    }
    val stderr = output.stderr.decodeToString()
    if (stderr.isNotEmpty()) {
        sb.append("\n--- stderr\n").append(stderr)
    }
    return sb.toString()
}

/** Deprecated in favor of the [probeLibrary] function. */
@Deprecated(
    "use probeLibrary instead",
    ReplaceWith("probeLibrary(name)"),
    level = DeprecationLevel.HIDDEN,
)
public fun findLibrary(name: String): ProbeOutcome = probeLibrary(name)

/** Simple shortcut for using all default options for finding a library. */
public fun probeLibrary(name: String): ProbeOutcome =
    Config().probe(name)

@Deprecated(
    "use config.targetSupported() instance method instead",
    ReplaceWith("Config().targetSupported()"),
    level = DeprecationLevel.HIDDEN,
)
public fun targetSupported(): Boolean =
    Config().targetSupported()

/**
 * Run `pkg-config` to get the value of a variable from a package using
 * `--variable`.
 *
 * The content of `PKG_CONFIG_SYSROOT_DIR` is not injected in paths that are
 * returned by `pkg-config --variable`, which makes them unsuitable to use
 * during cross-compilation unless specifically designed to be used at that
 * time.
 */
public fun getVariable(packageName: String, variable: String): VariableOutcome {
    val arg = "--variable=$variable"
    val cfg = Config()
    return when (val out = cfg.runProbe(packageName, listOf(arg))) {
        is RunOutcome.Ok -> VariableOutcome.Success(out.stdout.decodeToString().trimEnd())
        is RunOutcome.Err -> VariableOutcome.Failure(out.error)
    }
}

/**
 * Quote an argument that has spaces in it. When the [WrappedCommand] is
 * printed to the terminal, arguments that contain spaces need to be quoted.
 * Otherwise, we will have output such as:
 *   `pkg-config --libs --cflags foo foo < 3.11`
 * which cannot be used in a terminal — it will attempt to read a file named
 * 3.11 and provide it as stdin for pkg-config. Using this function, we
 * instead get the correct output:
 *   `pkg-config --libs --cflags foo 'foo < 3.11'`
 */
internal fun quoteIfNeeded(arg: String): String =
    if (arg.contains(' ')) "'$arg'" else arg

internal fun envify(name: String): String {
    val sb = StringBuilder(name.length)
    for (raw in name) {
        val upper = raw.uppercaseChar()
        sb.append(if (upper == '-') '_' else upper)
    }
    return sb.toString()
}

/** System libraries should only be linked dynamically. */
internal fun isStaticAvailable(
    name: String,
    systemRoots: List<String>,
    dirs: List<String>,
): Boolean {
    val libnames: List<String> = buildList {
        add("lib$name.a")
        if (currentTargetOs() == TargetOs.Windows) {
            add("$name.lib")
        }
    }

    return dirs.any { dir ->
        val libraryExists = libnames.any { libname ->
            pathExists(joinPath(dir, libname))
        }
        libraryExists && !systemRoots.any { sys -> startsWithPath(dir, sys) }
    }
}

/**
 * Split output produced by pkg-config --cflags and / or --libs into separate flags.
 *
 * Backslash in output is used to preserve literal meaning of following byte.
 * Different words are separated by unescaped space. Other whitespace
 * characters generally should not occur unescaped at all, apart from the
 * newline at the end of output. For compatibility with what other consumers
 * of pkg-config output would do in this scenario, they are used here for
 * splitting as well.
 */
internal fun splitFlags(output: ByteArray): List<String> {
    var word: MutableList<Byte> = mutableListOf()
    val words: MutableList<String> = mutableListOf()
    var escaped = false

    for (b in output) {
        when {
            escaped -> {
                escaped = false
                word.add(b)
            }
            b == '\\'.code.toByte() -> escaped = true
            b == '\t'.code.toByte() || b == '\n'.code.toByte() ||
                b == '\r'.code.toByte() || b == ' '.code.toByte() -> {
                if (word.isNotEmpty()) {
                    words.add(word.toByteArray().decodeToString())
                    word = mutableListOf()
                }
            }
            else -> word.add(b)
        }
    }

    if (word.isNotEmpty()) {
        words.add(word.toByteArray().decodeToString())
    }

    return words
}

// --- Path helpers that stand in for Rust's `PathBuf` / `Path` operations. ---

private fun lastPathSeparator(path: String): Int {
    var idx = -1
    for (i in path.indices) {
        val c = path[i]
        if (c == '/' || c == '\\') idx = i
    }
    return idx
}

private fun joinPath(base: String, child: String): String {
    if (base.isEmpty()) return child
    val last = base[base.length - 1]
    val sep = if (last == '/' || last == '\\') "" else "/"
    return base + sep + child
}

private fun startsWithPath(path: String, prefix: String): Boolean {
    // Match the prefix on directory boundaries to mirror `Path::starts_with`.
    if (prefix.isEmpty()) return true
    if (!path.startsWith(prefix)) return false
    if (path.length == prefix.length) return true
    val next = path[prefix.length]
    return next == '/' || next == '\\'
}
