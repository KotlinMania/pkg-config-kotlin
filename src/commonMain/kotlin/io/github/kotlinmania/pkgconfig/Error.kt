// port-lint: source lib.rs
package io.github.kotlinmania.pkgconfig

/**
 * Outcome of [Config.probe] / [probeLibrary] / [findLibrary]: either a
 * successfully parsed [Library], or an [Error] describing why the probe
 * could not complete.
 */
public sealed class ProbeOutcome {
    public data class Success(
        val library: Library,
    ) : ProbeOutcome()

    public data class Failure(
        val error: Error,
    ) : ProbeOutcome()
}

/**
 * Outcome of [getVariable] / [Config.getVariable]: either the trimmed
 * variable value, or an [Error] describing why pkg-config could not
 * supply it.
 */
public sealed class VariableOutcome {
    public data class Success(
        val value: String,
    ) : VariableOutcome()

    public data class Failure(
        val error: Error,
    ) : VariableOutcome()
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
    public data class EnvNoPkgConfig(
        val name: String,
    ) : Error()

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
    public data class Command(
        val command: String,
        val ioCause: IoError,
    ) : Error()

    /**
     * `pkg-config` did not exit successfully after probing a library.
     *
     * Contains the command and output.
     */
    public data class Failure(
        val command: String,
        val output: ProcessOutput,
    ) : Error()

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
     * `Display`/`Debug` text. Both the debug and display formatting in the
     * upstream crate route here.
     */
    public val message: String
        get() = fmt()

    internal fun fmt(): String =
        when (this) {
            is EnvNoPkgConfig -> {
                "Aborted because $name is set"
            }

            CrossCompilation -> {
                "pkg-config has not been configured to support cross-compilation.\n" +
                    "\n" +
                    "Install a sysroot for the target platform and configure it via\n" +
                    "PKG_CONFIG_SYSROOT_DIR and PKG_CONFIG_PATH, or install a\n" +
                    "cross-compiling wrapper for pkg-config and set it via\n" +
                    "PKG_CONFIG environment variable."
            }

            is Command -> {
                when (ioCause.kind) {
                    IoErrorKind.NotFound -> {
                        val crateName = envVar("CARGO_PKG_NAME") ?: "sys"
                        val targetOs = currentTargetOs()
                        val instructions =
                            when {
                                targetOs == TargetOs.Macos -> {
                                    "Try `brew install pkgconf` if you have Homebrew.\n"
                                }

                                targetOs == TargetOs.Ios -> {
                                    "" // iOS cross-compilation requires a custom setup, no easy fix
                                }

                                targetOs.isUnix() -> {
                                    "Try `apt install pkg-config`, or `yum install pkg-config`, or `brew install pkgconf`\n" +
                                        "or `pkg install pkg-config`, or `apk add pkgconfig` " +
                                        "depending on your distribution.\n"
                                }

                                else -> {
                                    "" // There's no easy fix for Windows users
                                }
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

                    else -> {
                        "Failed to run command `$command`, because: ${ioCause.message}"
                    }
                }
            }

            is ProbeFailure -> {
                val crateName = envVar("CARGO_PKG_NAME") ?: "<NO CRATE NAME>"
                val sb = StringBuilder()
                sb.append('\n')

                // Give a short explanation of what the error is
                val statusLine =
                    when (val code = output.status.code) {
                        null -> "was terminated by signal"
                        else -> "exited with status code $code"
                    }
                sb.append("pkg-config $statusLine").append('\n')

                // Give the command run so users can reproduce the error
                sb.append("> ").append(command).append("\n\n")

                // Explain how it was caused
                sb
                    .append("The system library `")
                    .append(name)
                    .append("` required by crate `")
                    .append(crateName)
                    .append("` was not found.")
                    .append('\n')
                sb
                    .append("The file `")
                    .append(name)
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
                    val hint =
                        if (searchData != null) {
                            val (searchLocation, searchPath) = searchData
                            sb
                                .append(searchLocation)
                                .append(" contains the following:\n")
                                .append(
                                    searchPath
                                        .split(':')
                                        .joinToString(separator = "\n") { path -> "    - $path" },
                                ).append('\n')

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

            Nonexhaustive -> {
                kotlin.error("matched on reserved variant")
            }
        }

    internal fun fmt(f: StringBuilder): StringBuilder = f.append(fmt())

    override fun toString(): String = message
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
