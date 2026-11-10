package com.modporter.transforms

import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.ExactReceiverProviderProvenance
import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateProviderProvenanceReplayTest {
    @Test
    fun `actual normalized Create callback uses the source world without modifying failure evidence`() {
        val configured = System.getenv("MODPORTER_CREATE_PROVIDER_REPLAY")
        assumeTrue(!configured.isNullOrBlank(), "Requires the local normalized Create source replay")
        val project = Path.of(checkNotNull(configured)).toRealPath()
        val sourceRoot = project.resolve("src/main/java")
        assertTrue(Files.isDirectory(sourceRoot))
        val relativeFiles = listOf(
            "com/simibubi/create/content/equipment/toolbox/ToolboxBlock.java",
            "com/simibubi/create/content/equipment/toolbox/ToolboxBlockEntity.java",
            "com/simibubi/create/foundation/block/IBE.java",
            "com/simibubi/create/compat/computercraft/implementation/ComputerUtil.java",
            "com/simibubi/create/compat/computercraft/implementation/luaObjects/LuaComparable.java",
            "com/simibubi/create/compat/computercraft/implementation/luaObjects/LuaItemStack.java"
        )
        val originals = relativeFiles.associate { relative ->
            val path = sourceRoot.resolve(relative)
            path to Files.readAllBytes(path)
        }
        try {
            val index = JavaProjectTypeIndex.build(sourceRoot)
            val unit = index.unit(sourceRoot.resolve(relativeFiles.first()))
            val call = unit.findAll(MethodCallExpr::class.java).single { it.nameAsString == "readInventory" }
            val callable = call.findAncestor(MethodDeclaration::class.java).orElseThrow()
            assertEquals("setPlacedBy", callable.nameAsString)
            assertEquals(
                "worldIn.registryAccess()",
                ExactReceiverProviderProvenance.providerExpression(call, callable, index)
            )
        } finally {
            originals.forEach { (path, bytes) ->
                assertContentEquals(bytes, Files.readAllBytes(path), "Replay modified $path")
            }
        }
    }
}
