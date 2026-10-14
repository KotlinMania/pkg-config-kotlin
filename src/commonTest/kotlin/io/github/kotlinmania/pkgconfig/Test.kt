// port-lint: tests tests/test.rs
package io.github.kotlinmania.pkgconfig

import io.github.kotlinmania.lazystatic.LazyStaticRef
import io.github.kotlinmania.lazystatic.initialize
import io.github.kotlinmania.lazystatic.lazyStatic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.test.fail

private val LOCK: LazyStaticRef<Unit> = lazyStatic {}

class PkgConfigTest {
    private fun reset() {
        initialize(LOCK)
        for ((k, _) in envVars()) {
            if (k.contains("DYNAMIC") ||
                k.contains("STATIC") ||
                k.contains("PKG_CONFIG_ALLOW_CROSS") ||
                k.contains("PKG_CONFIG_SYSROOT_DIR") ||
                k.contains("FOO_NO_PKG_CONFIG")
            ) {
                removeEnvVar(k)
            }
        }
        removeEnvVar("TARGET")
        removeEnvVar("HOST")
        setEnvVar("PKG_CONFIG_PATH", joinPath(currentDir(), "tests"))
    }

    private fun find(name: String): ProbeOutcome =
        probeLibrary(name)

    @Test
    fun crossDisabled() {
        reset()
        setEnvVar("TARGET", "foo")
        setEnvVar("HOST", "bar")
        when (val x = find("foo")) {
            is ProbeOutcome.Failure -> {
                if (x.error !is Error.CrossCompilation) {
                    fail("Error::CrossCompilation expected, found `$x`")
                }
            }

            is ProbeOutcome.Success -> {
                fail("Error::CrossCompilation expected, found `$x`")
            }
        }
    }

    @Test
    fun crossEnabled() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        setEnvVar("TARGET", "foo")
        setEnvVar("HOST", "bar")
        setEnvVar("PKG_CONFIG_ALLOW_CROSS", "1")
        find("foo").unwrap()
    }

    @Test
    fun crossEnabledIfCustomized() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        setEnvVar("TARGET", "foo")
        setEnvVar("HOST", "bar")
        setEnvVar("PKG_CONFIG_SYSROOT_DIR", "/tmp/cross-test")
        find("foo").unwrap()
    }

    @Test
    fun crossDisabledIfCustomized() {
        reset()
        setEnvVar("TARGET", "foo")
        setEnvVar("HOST", "bar")
        setEnvVar("PKG_CONFIG_ALLOW_CROSS", "0")
        setEnvVar("PKG_CONFIG_SYSROOT_DIR", "/tmp/cross-test")
        when (val x = find("foo")) {
            is ProbeOutcome.Failure -> {
                if (x.error !is Error.CrossCompilation) {
                    fail("expected CrossCompilation failure")
                }
            }

            is ProbeOutcome.Success -> {
                fail("expected CrossCompilation failure")
            }
        }
    }

    @Test
    fun packageDisabled() {
        reset()
        setEnvVar("FOO_NO_PKG_CONFIG", "1")
        when (val x = find("foo")) {
            is ProbeOutcome.Failure -> {
                when (val err = x.error) {
                    is Error.EnvNoPkgConfig -> assertEquals("FOO_NO_PKG_CONFIG", err.name)
                    else -> fail("Error::EnvNoPkgConfig expected, found `$x`")
                }
            }

            is ProbeOutcome.Success -> {
                fail("Error::EnvNoPkgConfig expected, found `$x`")
            }
        }
    }

    @Test
    fun outputOk() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        val lib = find("foo").unwrap()
        assertTrue(lib.libs.contains("gcc"))
        assertTrue(lib.libs.contains("coregrind-amd64-linux"))
        assertTrue(lib.linkPaths.contains("/usr/lib/valgrind"))
        assertTrue(lib.includePaths.contains("/usr/include/valgrind"))
        assertTrue(lib.includePaths.contains("/usr/foo"))
    }

    @Test
    fun escapes() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        val lib = find("escape").unwrap()
        assertTrue(lib.includePaths.contains("include path with spaces"))
        assertTrue(lib.linkPaths.contains("link path with spaces"))
        assertEquals("\"escaped string' literal\"", lib.defines.get("A"))
        assertEquals("ESCAPED IDENTIFIER", lib.defines.get("B"))
        assertEquals("🦊", lib.defines.get("FOX"))
    }

    @Test
    fun framework() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        val lib = find("framework").unwrap()
        assertTrue(lib.frameworks.contains("foo"))
        assertTrue(lib.frameworks.contains("bar"))
        assertTrue(lib.frameworks.contains("baz"))
        assertTrue(lib.frameworks.contains("foobar"))
        assertTrue(lib.frameworks.contains("foobaz"))
        assertTrue(lib.frameworkPaths.contains("/usr/lib"))
    }

    @Test
    fun getVariable() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        val prefix = getVariable("foo", "prefix").unwrap()
        assertEquals("/usr", prefix)
    }

    @Test
    fun version() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        assertEquals("3.10.0.SVN", find("foo").unwrap().version)
    }

    @Test
    fun atleastVersionOk() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        Config
            .new()
            .atleastVersion("3.10")
            .probe("foo")
            .unwrap()
    }

    @Test
    fun atleastVersionNg() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        assertFails {
            Config
                .new()
                .atleastVersion("3.11")
                .probe("foo")
                .unwrap()
        }
    }

    @Test
    fun exactlyVersionOk() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        Config
            .new()
            .exactlyVersion("3.10.0.SVN")
            .probe("foo")
            .unwrap()
    }

    @Test
    fun exactlyVersionNg() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        assertFails {
            Config
                .new()
                .exactlyVersion("3.10.0")
                .probe("foo")
                .unwrap()
        }
    }

    @Test
    fun rangeVersionRangeOk() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        Config
            .new()
            .rangeVersion(VersionBound.Included("4.2.0"), VersionBound.Excluded("4.4.0"))
            .probe("escape")
            .unwrap()
    }

    @Test
    fun rangeVersionRangeNg() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        assertFails {
            Config
                .new()
                .rangeVersion(VersionBound.Included("4.0.0"), VersionBound.Excluded("4.2.0"))
                .probe("escape")
                .unwrap()
        }
    }

    @Test
    fun rangeVersionRangeInclusiveOk() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        Config
            .new()
            .rangeVersion(VersionBound.Included("4.0.0"), VersionBound.Included("4.2.0"))
            .probe("escape")
            .unwrap()
    }

    @Test
    fun rangeVersionRangeInclusiveNg() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        assertFails {
            Config
                .new()
                .rangeVersion(VersionBound.Included("3.8.0"), VersionBound.Included("4.0.0"))
                .probe("escape")
                .unwrap()
        }
    }

    @Test
    fun rangeVersionRangeFromOk() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        Config
            .new()
            .rangeVersion(VersionBound.Included("4.0.0"), VersionBound.Unbounded)
            .probe("escape")
            .unwrap()
    }

    @Test
    fun rangeVersionRangeFromNg() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        assertFails {
            Config
                .new()
                .rangeVersion(VersionBound.Included("4.4.0"), VersionBound.Unbounded)
                .probe("escape")
                .unwrap()
        }
    }

    @Test
    fun rangeVersionRangeToOk() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        Config
            .new()
            .rangeVersion(VersionBound.Unbounded, VersionBound.Excluded("4.4.0"))
            .probe("escape")
            .unwrap()
    }

    @Test
    fun rangeVersionRangeToNg() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        assertFails {
            Config
                .new()
                .rangeVersion(VersionBound.Unbounded, VersionBound.Excluded("4.2.0"))
                .probe("escape")
                .unwrap()
        }
    }

    @Test
    fun rangeVersionRangeToInclusiveOk() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        Config
            .new()
            .rangeVersion(VersionBound.Unbounded, VersionBound.Included("4.2.0"))
            .probe("escape")
            .unwrap()
    }

    @Test
    fun rangeVersionRangeToInclusiveNg() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        assertFails {
            Config
                .new()
                .rangeVersion(VersionBound.Unbounded, VersionBound.Included("4.0.0"))
                .probe("escape")
                .unwrap()
        }
    }

    @Test
    fun rangeVersionFull() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        Config
            .new()
            .rangeVersion(VersionBound.Unbounded, VersionBound.Unbounded)
            .probe("escape")
            .unwrap()
    }

    @Test
    fun rpath() {
        if (!currentTargetOs().canSpawnHostProcess()) return
        reset()
        val lib = find("rpath").unwrap()
        assertTrue(lib.ldArgs.contains(listOf("-rpath", "/usr/local/lib")))
    }
}
