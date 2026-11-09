package com.modporter.core.pipeline

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ProjectScopedPassTest {
    private val project = Path.of("owned-project").toAbsolutePath()
    private class Scoped(
        override val name: String,
        override val order: Int,
        private val events: MutableList<String>,
        private val resultErrors: List<String> = emptyList(),
        private val failure: RuntimeException? = null,
        private val cleanupFailure: RuntimeException? = null
    ) : ProjectScopedPass {
        override fun analyze(projectDir: Path) = apply(projectDir)
        override fun apply(projectDir: Path): PassResult {
            events.add("run:$name")
            failure?.let { throw it }
            return PassResult(name, emptyList(), resultErrors)
        }
        override fun finishProject(projectDir: Path) {
            events.add("finish:$name:$projectDir")
            cleanupFailure?.let { throw it }
        }
    }

    @Test
    fun `resources live through later passes and finish in reverse order`() {
        val events = mutableListOf<String>()
        Pipeline(listOf(Scoped("inventory", 0, events), Scoped("consumer", 1, events))).run(project)
        assertEquals(listOf("run:inventory", "run:consumer", "finish:consumer:$project", "finish:inventory:$project"), events)
    }

    @Test
    fun `failed inventory proof prevents source writers and still releases resources`() {
        val events = mutableListOf<String>()
        val result = Pipeline(listOf(Scoped("inventory", 0, events, listOf("missing proof")), Scoped("writer", 1, events))).run(project)
        assertEquals(1, result.totalErrors)
        assertEquals(listOf("run:inventory", "finish:writer:$project", "finish:inventory:$project"), events)
    }

    @Test
    fun `cleanup preserves the original exception and attempts every scope`() {
        val events = mutableListOf<String>()
        val original = IllegalStateException("source failure")
        val firstCleanup = IllegalArgumentException("consumer cleanup")
        val secondCleanup = IllegalArgumentException("inventory cleanup")
        val failure = assertFailsWith<IllegalStateException> {
            Pipeline(listOf(Scoped("inventory", 0, events, cleanupFailure = secondCleanup),
                Scoped("consumer", 1, events, failure = original, cleanupFailure = firstCleanup))).run(project)
        }
        assertSame(original, failure)
        assertSame(firstCleanup, failure.suppressed.single())
        assertSame(secondCleanup, firstCleanup.suppressed.single())
        assertEquals(listOf("run:inventory", "run:consumer", "finish:consumer:$project", "finish:inventory:$project"), events)
    }

    @Test
    fun `failed cleanup cannot be reported as a successful project`() {
        val events = mutableListOf<String>()
        val expected = IllegalStateException("cleanup")
        assertSame(expected, assertFailsWith<IllegalStateException> {
            Pipeline(listOf(Scoped("inventory", 0, events, cleanupFailure = expected))).run(project)
        })
    }
}
