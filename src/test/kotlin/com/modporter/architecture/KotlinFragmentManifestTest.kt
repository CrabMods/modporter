package com.modporter.architecture

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class KotlinFragmentManifestTest {
    private val manifest = Path("config/kotlin-fragments.tsv")

    @Test
    fun `configured Kotlin fragments assemble to generated sources`() {
        val targets = loadTargets()
        assertTrue(targets.isNotEmpty(), "Fragment manifest must contain at least one target")

        targets.forEach { target ->
            assertTrue(target.source.exists(), "Generated source is missing for ${target.id}: ${target.source}")
            assertTrue(target.orderFile.exists(), "Order file is missing for ${target.id}: ${target.orderFile}")

            val fragments = target.fragmentNames().map { name ->
                val fragment = target.fragmentDir.resolve(name)
                assertTrue(fragment.exists(), "Listed fragment is missing for ${target.id}: $fragment")
                fragment
            }
            val assembled = fragments
                .map { it.readBytes() }
                .fold(ByteArray(0)) { acc, bytes -> acc + bytes }

            assertContentEquals(
                target.source.readBytes(),
                assembled,
                "${target.id} must be assembled from ordered fragments; run python scripts/kotlin_fragments.py assemble"
            )
        }
    }

    @Test
    fun `configured Kotlin fragments remain reviewable chunks`() {
        val oversized = loadTargets().flatMap { target ->
            target.fragmentNames().map { name ->
                val lineCount = target.fragmentDir.resolve(name).readLines().size
                "${target.id}/$name" to (lineCount to target.maxLines)
            }
        }.filter { (_, counts) ->
            val (lineCount, maxLines) = counts
            lineCount > maxLines
        }

        assertTrue(
            oversized.isEmpty(),
            "Fragments should stay within configured line budgets: " +
                oversized.joinToString { (name, counts) ->
                    val (lineCount, maxLines) = counts
                    "$name=$lineCount>$maxLines"
                }
        )
    }

    @Test
    fun `fragment manifest uses repo relative paths only`() {
        loadTargets().forEach { target ->
            assertTrue(target.source.isRegularFile(), "Source path must be a file: ${target.source}")
            assertTrue(target.orderFile.name.isNotBlank(), "Order file must be named for ${target.id}")
        }
    }

    private fun loadTargets(): List<FragmentTarget> {
        assertTrue(manifest.exists(), "Missing fragment manifest: $manifest")
        return manifest.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filterNot { it.startsWith("id\t") }
            .map { line ->
                val columns = line.split('\t')
                assertTrue(columns.size == 5, "Malformed fragment manifest row: $line")
                val (id, source, fragmentDir, orderFile, maxLines) = columns
                listOf(source, fragmentDir, orderFile).forEach { path ->
                    val parsed = Path(path)
                    assertTrue(!parsed.isAbsolute, "Fragment manifest paths must be relative: $path")
                    assertTrue(
                        parsed.none { it.toString() == ".." },
                        "Fragment manifest paths must not escape the repo: $path"
                    )
                }
                FragmentTarget(
                    id = id,
                    source = Path(source),
                    fragmentDir = Path(fragmentDir),
                    orderFile = Path(fragmentDir).resolve(orderFile),
                    maxLines = maxLines.toInt()
                )
            }
    }

    private data class FragmentTarget(
        val id: String,
        val source: java.nio.file.Path,
        val fragmentDir: java.nio.file.Path,
        val orderFile: java.nio.file.Path,
        val maxLines: Int
    ) {
        fun fragmentNames(): List<String> =
            orderFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
