package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue

class ConditionSerializationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `platform condition writers use the codec and unrelated helpers keep their serializer`() {
        val root = directory.resolve("src/main/java/example").createDirectories()
        val native = root.resolve("Conditions.java")
        native.writeText("""
            package example;
            import com.google.gson.JsonObject;
            import net.neoforged.neoforge.common.crafting.CraftingHelper;
            import net.neoforged.neoforge.common.conditions.ICondition;
            class Conditions { JsonObject write(ICondition condition) { return CraftingHelper.serialize(condition); } }
        """.trimIndent())
        val local = root.resolve("Local.java")
        local.writeText("""
            package example;
            class Local {
                static class CraftingHelper { static Object serialize(Object object) { return object; } }
                Object write(Object condition) { return CraftingHelper.serialize(condition); }
            }
        """.trimIndent())
        StructuralRefactorPass().apply(directory)
        assertTrue(native.readText().contains("ICondition.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, condition).getOrThrow().getAsJsonObject()"), native.readText())
        assertTrue(local.readText().contains("CraftingHelper.serialize(condition)"), local.readText())
    }
}
