// port-lint: source lib.rs
package io.github.kotlinmania.pkgconfig

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
        fun new(): Library =
            Library().apply {
                libsMut.clear()
                linkPathsMut.clear()
                linkFilesMut.clear()
                includePathsMut.clear()
                ldArgsMut.clear()
                frameworksMut.clear()
                frameworkPathsMut.clear()
                version = ""
            }

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

        val systemRoots: List<String> =
            if (currentTargetOs() == TargetOs.Macos) {
                listOf("/Library", "/System")
            } else {
                val sysroot =
                    config.envVarOs("PKG_CONFIG_SYSROOT_DIR")
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
        val parts =
            words
                .asSequence()
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
                        val meta = "rustc-link-arg=${flag}$value"
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
        val iter: Iterator<String> =
            sequence {
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
                        val meta = "rustc-link-arg=-Wl,$part,$symbol"
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
public data class Define(
    val name: String,
    val value: String?,
)

/**
 * Ordered, deduplicating collection of `-D` defines parsed from pkg-config
 * output. The same name inserted twice keeps only the latest value.
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

// --- Path helpers for file path operations. ---

internal fun lastPathSeparator(path: String): Int {
    var idx = -1
    for (i in path.indices) {
        val c = path[i]
        if (c == '/' || c == '\\') idx = i
    }
    return idx
}

internal fun joinPath(base: String, child: String): String {
    if (base.isEmpty()) return child
    val last = base[base.length - 1]
    val sep = if (last == '/' || last == '\\') "" else "/"
    return base + sep + child
}

internal fun startsWithPath(path: String, prefix: String): Boolean {
    // Match the prefix on directory boundaries.
    if (prefix.isEmpty()) return true
    if (!path.startsWith(prefix)) return false
    if (path.length == prefix.length) return true
    val next = path[prefix.length]
    return next == '/' || next == '\\'
}
