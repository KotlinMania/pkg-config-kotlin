// port-lint: tests lib.rs
package io.github.kotlinmania.pkgconfig

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibTest {
    private fun testLibraryFilename(target: String, filename: String) {
        assertEquals(
            "foo",
            Library.extractLibFromFilename(target, filename),
        )
    }

    @Test
    fun linkFilenameLinux() {
        val target = "x86_64-unknown-linux-gnu"
        testLibraryFilename(target, "libfoo.a")
        testLibraryFilename(target, "libfoo.so")
    }

    @Test
    fun linkFilenameApple() {
        val target = "x86_64-apple-darwin"
        testLibraryFilename(target, "libfoo.a")
        testLibraryFilename(target, "libfoo.so")
        testLibraryFilename(target, "libfoo.dylib")
    }

    @Test
    fun linkFilenameMsvc() {
        val target = "x86_64-pc-windows-msvc"
        // static and dynamic libraries have the same .lib suffix
        testLibraryFilename(target, "foo.lib")
    }

    @Test
    fun linkFilenameMingw() {
        val target = "x86_64-pc-windows-gnu"
        testLibraryFilename(target, "foo.lib")
        testLibraryFilename(target, "libfoo.a")
        testLibraryFilename(target, "foo.dll")
        testLibraryFilename(target, "foo.dll.a")
    }

    @Test
    fun extractLibReturnsNullWhenLinuxLacksPrefix() {
        assertNull(Library.extractLibFromFilename("x86_64-unknown-linux-gnu", "foo.so"))
    }

    @Test
    fun envifyUppercasesAndConvertsDashes() {
        assertEquals("MY_LIB_NAME", envify("my-lib-name"))
        assertEquals("FOO_BAR", envify("Foo-Bar"))
        assertEquals("ABC", envify("abc"))
    }

    @Test
    fun quoteIfNeededWrapsSpaces() {
        assertEquals("'foo bar'", quoteIfNeeded("foo bar"))
        assertEquals("foo", quoteIfNeeded("foo"))
    }

    @Test
    fun splitFlagsHandlesSpacesAndBackslashes() {
        val raw = "-I/usr/include -L/usr/lib -lfoo\\ bar".encodeToByteArray()
        val parts = splitFlags(raw)
        assertEquals(listOf("-I/usr/include", "-L/usr/lib", "-lfoo bar"), parts)
    }

    @Test
    fun splitFlagsHandlesTrailingNewline() {
        val raw = "-lfoo -lbar\n".encodeToByteArray()
        val parts = splitFlags(raw)
        assertEquals(listOf("-lfoo", "-lbar"), parts)
    }
}
