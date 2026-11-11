package com.modporter.transforms

import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.modporter.core.transforms.structural.ExactReceiverProviderProvenance
import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun `actual minecart LazyOptional callback stays outside BlockEntity provenance`() {
        withActualCall(
            "com/simibubi/create/content/contraptions/minecart/capability/MinecartControllerUpdatePacket.java",
            "deserializeNBT"
        ) { index, call, callable ->
            val lambda = call.findAncestor(LambdaExpr::class.java).orElseThrow()
            val callback = lambda.parentNode.orElseThrow() as MethodCallExpr
            val factory = callback.scope.orElseThrow() as MethodCallExpr
            assertEquals(
                "com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController",
                index.methodCallReceiverType(factory),
                "Exact project static scope must resolve before callback typing"
            )
            assertEquals(
                "net.minecraft.world.entity.Entity",
                index.expressionType(factory.arguments.first(), factory),
                "Factory entity argument must retain its declared type"
            )
            assertEquals(
                "com.modporter.generated.create.compat.LazyOptional",
                index.expressionType(factory, factory),
                "Factory return container must resolve before its callback element"
            )
            assertEquals(
                "com.simibubi.create.content.contraptions.minecart.capability.MinecartController",
                index.methodCallReceiverType(call)
            )
            assertNull(ExactReceiverProviderProvenance.providerExpression(call, callable, index))
        }
    }

    @Test
    fun `actual Lectern inherited callback obtains its queried worlds registries`() {
        withActualCall(
            "com/simibubi/create/content/redstone/link/controller/LecternControllerBlock.java",
            "dropController"
        ) { index, call, callable ->
            assertEquals(
                "world.registryAccess()",
                ExactReceiverProviderProvenance.providerExpression(call, callable, index)
            )
        }
    }

    private fun withActualCall(
        relative: String,
        methodName: String,
        assertion: (JavaProjectTypeIndex, MethodCallExpr, MethodDeclaration) -> Unit
    ) {
        val configured = System.getenv("MODPORTER_CREATE_PROVIDER_REPLAY")
        assumeTrue(!configured.isNullOrBlank(), "Requires the local normalized Create source replay")
        val sourceRoot = Path.of(checkNotNull(configured)).toRealPath().resolve("src/main/java")
        val path = sourceRoot.resolve(relative)
        val original = Files.readAllBytes(path)
        try {
            val index = JavaProjectTypeIndex.build(sourceRoot)
            val call = index.unit(path).findAll(MethodCallExpr::class.java)
                .single { it.nameAsString == methodName }
            val callable = call.findAncestor(MethodDeclaration::class.java).orElseThrow()
            assertion(index, call, callable)
        } finally {
            assertContentEquals(original, Files.readAllBytes(path), "Replay modified $path")
        }
    }
}
