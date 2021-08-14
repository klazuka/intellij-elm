/*
The MIT License (MIT)

Derived from intellij-rust
Copyright (c) 2015 Aleksey Kladov, Evgeny Kurbatsky, Alexey Kudinkin and contributors
Copyright (c) 2016 JetBrains

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package org.elm.utils

import kotlin.system.measureTimeMillis

class Timings(
        private val valuesTotal: LinkedHashMap<String, Long> = LinkedHashMap(),
        private val invokes: MutableMap<String, Long> = mutableMapOf()
) {
    fun <T> measure(name: String, f: () -> T): T {
        check(name !in valuesTotal)
        return measureInternal(name, f)
    }

    fun <T> measureAverage(name: String, f: () -> T): T = measureInternal(name, f)

    fun merge(other: Timings): Timings {
        val values = values()
        val otherValues = other.values()
        check(values.isEmpty() || otherValues.isEmpty() || values.size == otherValues.size)
        val result = Timings()
        for (k in values.keys.union(otherValues.keys)) {
            result.valuesTotal[k] =
                    // https://www.youtube.com/watch?v=vrfYLlR8X8k&feature=youtu.be&t=25m17s
                    minOf(values.getOrDefault(k, Long.MAX_VALUE), otherValues.getOrDefault(k, Long.MAX_VALUE))
            result.invokes[k] = 1
        }
        return result
    }

    fun report() {
        val values = values()
        if (values.isEmpty()) {
            println("No metrics recorder")
            return
        }

        val width = values.keys.maxOf { it.length }
        for ((k, v) in values) {
            println("${k.padEnd(width)}: $v ms")
        }
        val total = values.values.sum()
        println("$total ms total.")
        println()
    }

    private fun <T> measureInternal(name: String, f: () -> T): T {
        var result: T? = null
        val time = measureTimeMillis { result = f() }
        valuesTotal.merge(name, time, Long::plus)
        invokes.merge(name, 1, Long::plus)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun values(): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        for ((k, sum) in valuesTotal) {
            result[k] = (sum.toDouble() / invokes[k]!!).toLong()
        }
        return result
    }
}
