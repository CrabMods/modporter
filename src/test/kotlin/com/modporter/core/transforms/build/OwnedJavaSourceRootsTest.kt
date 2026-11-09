package com.modporter.core.transforms.build

import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OwnedJavaSourceRootsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `owned module source roots ignore installed sidecar Java across dry run and rerun`() {
        val module = directory.resolve("loader")
        module.createDirectories()
        module.resolve("build.gradle").writeText("plugins { id 'net.neoforged.moddev' }\n")

        val main = writeOwnedSource(module.resolve("src/main/java/example/MainSource.java"), "MainSource")
        val gameTest = writeOwnedSource(module.resolve("src/gameTest/java/example/GameTestSource.java"), "GameTestSource")
        val auxiliary = writeOwnedSource(
            directory.resolve(".modporter/legacy-plant-compat/src/main/java/sidecar/AuxiliarySource.java"),
            "AuxiliarySource",
            "sidecar"
        )
        val generatedRuntime = writeOwnedSource(
            directory.resolve(".modporter/legacy-plant-compat/runtime/generated/src/main/java/sidecar/GeneratedRuntime.java"),
            "GeneratedRuntime",
            "sidecar"
        )
        val embedded = writeOwnedSource(
            directory.resolve(".modporter/legacy-plant-compat/src/main/resources/embedded/EmbeddedSource.java"),
            "EmbeddedSource",
            "embedded"
        )
        val outside = listOf(auxiliary, generatedRuntime, embedded)
        val originals = (listOf(main, gameTest) + outside).associateWith { it.readText() }

        val firstFindings = ResidualRemovedApiGate.scan(directory)
        val secondFindings = ResidualRemovedApiGate.scan(directory)

        assertEquals(firstFindings, secondFindings)
        assertEquals(2, firstFindings.size, firstFindings.joinToString("\n"))
        assertTrue(firstFindings.any { it.endsWith("src/main/java/example/MainSource.java") }, firstFindings.joinToString("\n"))
        assertTrue(firstFindings.any { it.endsWith("src/gameTest/java/example/GameTestSource.java") }, firstFindings.joinToString("\n"))
        outside.forEach { file ->
            assertFalse(firstFindings.any { file.fileName.toString() in it }, firstFindings.joinToString("\n"))
        }

        val analysis = StructuralRefactorPass().analyze(directory)
        originals.forEach { (file, source) -> assertEquals(source, file.readText(), file.toString()) }
        assertTrue(analysis.changes.any { it.file == main && it.ruleId == "struct-dist-executor-dist-guard" })
        assertTrue(analysis.changes.any { it.file == gameTest && it.ruleId == "struct-dist-executor-dist-guard" })
        assertFalse(analysis.changes.any { it.file in outside }, analysis.changes.joinToString("\n") { it.file.toString() })
        assertFalse(analysis.errors.any { error -> outside.any { it.fileName.toString() in error } }, analysis.errors.joinToString("\n"))

        val firstApply = StructuralRefactorPass().apply(directory)
        assertTrue(firstApply.changes.any { it.file == main && it.ruleId == "struct-dist-executor-dist-guard" })
        assertTrue(firstApply.changes.any { it.file == gameTest && it.ruleId == "struct-dist-executor-dist-guard" })
        assertFalse(main.readText().contains("DistExecutor.unsafeRunWhenOn"), main.readText())
        assertFalse(gameTest.readText().contains("DistExecutor.unsafeRunWhenOn"), gameTest.readText())
        outside.forEach { file -> assertEquals(originals.getValue(file), file.readText(), file.toString()) }

        val secondApply = StructuralRefactorPass().apply(directory)
        assertFalse(secondApply.changes.any { it.file == main || it.file == gameTest }, secondApply.changes.joinToString("\n") { it.file.toString() })
        assertFalse(secondApply.changes.any { it.file in outside }, secondApply.changes.joinToString("\n") { it.file.toString() })
        outside.forEach { file -> assertEquals(originals.getValue(file), file.readText(), file.toString()) }
    }

    private fun writeOwnedSource(file: Path, typeName: String, packageName: String = "example"): Path {
        file.parent.createDirectories()
        file.writeText(
            """
            package $packageName;

            import net.minecraftforge.api.distmarker.Dist;
            import net.minecraftforge.fml.DistExecutor;
            import net.minecraft.world.item.ItemStack;

            class $typeName {
                void initialize(ItemStack stack) {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> System.out.println("client"));
                    stack.setTag(null);
                }
            }
            """.trimIndent() + "\n"
        )
        return file
    }
}
