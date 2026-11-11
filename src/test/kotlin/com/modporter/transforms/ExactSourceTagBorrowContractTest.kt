package com.modporter.transforms

import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.modporter.core.transforms.structural.ExactExternalTagContracts
import com.modporter.core.transforms.structural.ExactProjectTagEffects
import com.modporter.core.transforms.structural.JavaProjectTypeIndex
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.io.TempDir

class ExactSourceTagBorrowContractTest {
    @TempDir lateinit var directory: Path

    private fun write(relative: String, text: String): Path = directory.resolve("src/main/java/$relative").also {
        it.parent.createDirectories()
        it.writeText(text.trimIndent())
    }

    private fun reader(body: String) = write("example/Reader.java", """
        package example;
        import java.util.List;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.resources.ResourceLocation;
        import net.createmod.catnip.nbt.NBTHelper;
        class Reader {
            private List<CompoundTag> retained;
            $body
        }
    """)

    private fun effect(file: Path, method: String): ExactExternalTagContracts.Effect? {
        val root = directory.resolve("src/main/java")
        val index = JavaProjectTypeIndex.build(root)
        val declaration = index.unit(file).findAll(MethodDeclaration::class.java).single { it.nameAsString == method }
        return ExactProjectTagEffects(root, index).sourceMethodParameterEffect(declaration, 0)
    }

    @Test
    fun `pinned Catnip resource decoder borrows only a tag and string and returns an immutable id`() {
        val file = reader("""
            ResourceLocation read(CompoundTag tag) { return NBTHelper.readResourceLocation(tag, "id"); }
            ResourceLocation wrong(CompoundTag tag) { return NBTHelper.readResourceLocation(tag); }
        """.trimIndent())
        assertEquals(ExactExternalTagContracts.Effect.READ, effect(file, "read"))
        assertNull(effect(file, "wrong"))
    }

    @Test
    fun `source definition cannot acquire the external resource decoder contract`() {
        val file = reader("ResourceLocation read(CompoundTag tag) { return NBTHelper.readResourceLocation(tag, \"id\"); }")
        write("net/createmod/catnip/nbt/NBTHelper.java", """
            package net.createmod.catnip.nbt;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.resources.ResourceLocation;
            public class NBTHelper {
                private static CompoundTag retained;
                public static ResourceLocation readResourceLocation(CompoundTag tag, String key) {
                    retained = tag; return null;
                }
            }
        """)
        assertNull(effect(file, "read"))
    }

    @Test
    fun `ordinary read does not prove a retained callback view is borrowed or contaminate its cache`() {
        val file = reader("""
            void caller(CompoundTag input) { decode(input); }
            void decode(CompoundTag input) {
                retained = NBTHelper.readCompoundList(input.getList("values", 10), entry -> entry);
            }
        """.trimIndent())
        val root = directory.resolve("src/main/java")
        val index = JavaProjectTypeIndex.build(root)
        val unit = index.unit(file)
        val effects = ExactProjectTagEffects(root, index)
        val call = unit.findAll(MethodCallExpr::class.java).single { it.nameAsString == "decode" }
        val declaration = unit.findAll(MethodDeclaration::class.java).single { it.nameAsString == "decode" }
        assertEquals(ExactExternalTagContracts.Effect.READ, effects.compoundTagArgumentEffect(call, 0))
        assertNull(effects.sourceMethodParameterEffect(declaration, 0))
        assertEquals(ExactExternalTagContracts.Effect.READ, effects.compoundTagArgumentEffect(call, 0))
    }

    @Test
    fun `borrowed callbacks may mutate and return copies while opaque argument escape still refuses`() {
        val file = reader("""
            void mutate(CompoundTag input) {
                retained = NBTHelper.readCompoundList(input.getList("values", 10), entry -> {
                    entry.putInt("count", 1); return entry.copy();
                });
            }
            void opaque(CompoundTag input) {
                retained = NBTHelper.readCompoundList(input.getList("values", 10), entry -> unknown(entry));
            }
        """.trimIndent())
        assertEquals(ExactExternalTagContracts.Effect.MUTATE, effect(file, "mutate"))
        assertNull(effect(file, "opaque"))
    }
}
