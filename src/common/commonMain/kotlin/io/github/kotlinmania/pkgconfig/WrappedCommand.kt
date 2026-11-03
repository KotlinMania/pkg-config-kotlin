// port-lint: source lib.rs
package io.github.kotlinmania.pkgconfig

/**
 * Wrapper holder to track environment variables and arguments. This is needed
 * to reconstruct the pkg-config command for output in a copy-paste friendly
 * format via [toString].
 */
internal class WrappedCommand internal constructor(
    programArg: String,
) {
    val program: String = programArg
    private val envVars: MutableList<Pair<String, String>> = mutableListOf()
    private val collectedArgs: MutableList<String> = mutableListOf()

    internal companion object {
        fun new(program: String): WrappedCommand = WrappedCommand(program)
    }

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
    fun fmt(): String {
        // Format all explicitly defined environment variables
        val envs =
            envVars
                .joinToString(separator = " ") { (env, arg) -> "$env=$arg" }

        // Format all pkg-config arguments
        val argsRendered =
            collectedArgs
                .joinToString(separator = " ") { arg -> quoteIfNeeded(arg) }

        return "$envs $program $argsRendered"
    }

    override fun toString(): String = fmt()
}

/**
 * Quote an argument that has spaces in it. When the [WrappedCommand] is
 * printed to the terminal, arguments that contain spaces need to be quoted.
 * Otherwise, we will have output such as:
 *   `pkg-config --libs --cflags foo foo < 3.11`
 * which cannot be used in a terminal — it will attempt to read a file named
 * 3.11 and provide it as stdin for pkg-config. Using this function, we
 * instead get the correct output:
 *   `pkg-config --libs --cflags foo "foo < 3.11"`
 */
internal fun quoteIfNeeded(arg: String): String =
    if (arg.contains(' ')) "'$arg'" else arg
