package org.elm.ide.actions

import org.elm.TestProject
import org.elm.workspace.ElmWorkspaceTestBase


class ElmMoveFileTest : ElmWorkspaceTestBase() {

    override fun setUp() {
        super.setUp()
        makeTestProjectFixture()
    }

    fun `test change module declaration`() {
        myFixture.moveFile("src/Foo/Baz.elm", "src/Bar")
        myFixture.checkResult("src/Bar/Baz.elm",
            """
            module Bar.Baz exposing (..)
            
            placeholderValue = 0
            """.trimIndent(),
            true)
    }

    fun `test change import statements`() {
        myFixture.moveFile("src/Foo/Baz.elm", "src/Bar")
        myFixture.checkResult("src/Main.elm",
            """
            module Main exposing (..)
                    
            import Bar.Baz (placeholderValue)
                    
            init = placeholderValue
            """.trimIndent(),
            true)
    }

    fun `test move to base directory`() {
        myFixture.moveFile("src/Foo/Baz.elm", "src")
        myFixture.checkResult("src/Baz.elm",
            """
            module Baz exposing (..)
            
            placeholderValue = 0
            """.trimIndent(),
            true)
    }

    private fun makeTestProjectFixture(): TestProject =
        buildProject {
            project(
                "elm.json", """
                {
                    "type": "application",
                    "source-directories": [
                        "src",
                        "vendor/elm-foo",
                        "./foo1"
                    ],
                    "elm-version": "0.19.1",
                    "dependencies": {
                        "direct": {
                            "elm/core": "1.0.0",
                            "elm/json": "1.0.0"                        
                        }, 
                        "indirect": {}
                    },
                    "test-dependencies": { "direct": {}, "indirect": {} }
                }
                """.trimIndent()
            )
            dir("src") {
                elm("Main.elm", """
                    module Main exposing (..)
                    
                    import Foo.Baz (placeholderValue)
                    
                    init = placeholderValue
                """.trimIndent())
                dir("Foo") {
                    elm("Baz.elm", """
                        module Foo.Baz exposing (..)
                        
                        placeholderValue = 0
                    """.trimIndent())
                }
                dir("Bar") {}
            }
            dir("vendor") {
                dir("elm-foo") {
                    dir("Internals") {}
                }
            }
            dir("foo1") {
                dir("Foo1") {}
            }
            dir("tests") {
                dir("Legacy") {}
            }
            dir("outside") {}
        }
}
