package org.elm.workspace

import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class ElmToolchainTest {
    @Test
    fun `looksLikeValidToolchain allows an executable file`() {
        val rawElmPath = Paths.get(ClassLoader.getSystemResource("org/elm/workspace/fixtures/elm-executable").toURI())
        val toolchain = ElmToolchain(rawElmPath, null, null, false)
        assertTrue(toolchain.looksLikeValidToolchain())
    }

    @Test
    fun `looksLikeValidToolchain does not allow a non-executable file`() {
        val rawElmPath = Paths.get(ClassLoader.getSystemResource("org/elm/workspace/fixtures/non-executable").toURI())
        val toolchain = ElmToolchain(rawElmPath, null, null, false)
        assertFalse(toolchain.looksLikeValidToolchain())
    }

    @Test
    fun `looksLikeValidToolchain allows bare elm if it is on the path`() {
        val pathSearchLocation = Paths.get(ClassLoader.getSystemResource("org/elm/workspace/fixtures/PATH").toURI())
        val toolchain = ElmToolchain(Paths.get("elm"), null, null, false)
        assertTrue(toolchain.looksLikeValidToolchain(sequenceOf(pathSearchLocation)))
    }

    @Test
    fun `looksLikeValidToolchain does not allow bare elm if it is not on the path`() {
        val pathSearchLocation = Paths.get(ClassLoader.getSystemResource("org/elm/workspace/fixtures/NOT_PATH").toURI())
        val toolchain = ElmToolchain(Paths.get("elm"), null, null, false)
        assertFalse(toolchain.looksLikeValidToolchain(sequenceOf(pathSearchLocation)))
    }

    @Test
    fun `looksLikeValidToolchain does not allow an empty path`() {
        val toolchain = ElmToolchain(null, null, null, false)
        assertFalse(toolchain.looksLikeValidToolchain())
    }
}
