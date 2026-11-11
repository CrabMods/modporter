package com.modporter.core.transforms.structural

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class FlapDisplayProviderDemandTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `both original and fallback JSON writes thread the real caller provider through the callback`() {
        for ((name, value) in listOf(
            "original" to "Component.Serializer.toJson(component)",
            "fallback" to "net.minecraft.network.chat.ComponentSerialization.CODEC" +
                ".encodeStart(com.mojang.serialization.JsonOps.INSTANCE, component).getOrThrow().toString()"
        )) {
            val files = fixture(name, value)
            val pass = StructuralRefactorPass()
            val inheritance = pass.collectJavaInheritanceIndex(files)
            val methods = pass.collectItemStackNbtMethodsNeedingHolderLookup(files, inheritance)
            assertTrue(methods.any { it.owner == "FlapDisplaySection" && it.methodName == "write" }, methods.toString())
            assertTrue(methods.any { it.owner == "FlapDisplayLayout" && it.methodName == "write" }, methods.toString())
            for (file in files) {
                var source = file.readText()
                repeat(2) { source = pass.migrateProjectItemStackNbtMethods(source, methods, inheritance) }
                file.writeText(source)
            }
            assertTrue(files[0].readText().contains("write(HolderLookup.Provider"), files[0].readText())
            assertTrue(files[1].readText().contains("write(HolderLookup.Provider"), files[1].readText())
            assertFalse(files[1].readText().contains("FlapDisplaySection::write"), files[1].readText())
            assertTrue(files[2].readText().contains("layout.write(registries)"), files[2].readText())
            for (file in files) {
                val source = file.readText()
                assertEquals(source, pass.migrateProjectItemStackNbtMethods(source, methods, inheritance))
            }
        }
    }

    @Test
    fun `a local component value or source SDK impostor does not seed provider demand`() {
        for (kind in listOf("local", "sdk")) {
            val files = fixture(kind,
                "net.minecraft.network.chat.ComponentSerialization.CODEC" +
                    ".encodeStart(com.mojang.serialization.JsonOps.INSTANCE, component).getOrThrow().toString()",
                withCaller = false
            ).toMutableList()
            if (kind == "local") {
                files[0].writeText(files[0].readText().replace(
                    "CompoundTag tag = new CompoundTag();",
                    "Component component = null; CompoundTag tag = new CompoundTag();"
                ))
            } else {
                val shadow = directory.resolve("$kind/src/main/java/net/minecraft/network/chat/Component.java")
                shadow.parent.createDirectories()
                shadow.writeText("package net.minecraft.network.chat; public class Component {}")
                files.add(shadow)
            }
            val originals = files.associateWith { it.readText() }
            val pass = StructuralRefactorPass()
            val index = JavaProjectTypeIndex.build(commonJavaSourceRoot(files))
            val method = index.unit(files[0]).findAll(com.github.javaparser.ast.body.MethodDeclaration::class.java)
                .single { it.nameAsString == "write" }
            assertEquals(null, CreateFlapDisplaySerializationMigration.providerDemandCall(method, index), kind)
            val methods = pass.collectItemStackNbtMethodsNeedingHolderLookup(files, pass.collectJavaInheritanceIndex(files))
            assertFalse(methods.any { it.owner == "FlapDisplaySection" && it.methodName == "write" }, "$kind: $methods")
            assertEquals(originals, files.associateWith { it.readText() })
        }
    }

    private fun fixture(name: String, value: String, withCaller: Boolean = true): List<Path> {
        val root = directory.resolve("$name/src/main/java/com/simibubi/create/content/trains/display")
        root.createDirectories()
        return listOf(
            "FlapDisplaySection" to """
                import net.minecraft.nbt.CompoundTag;
                import net.minecraft.network.chat.Component;
                public class FlapDisplaySection {
                    Component component;
                    public CompoundTag write() {
                        CompoundTag tag = new CompoundTag();
                        tag.putString("Text", $value);
                        return tag;
                    }
                }
            """,
            "FlapDisplayLayout" to """
                import java.util.List;
                import net.minecraft.nbt.CompoundTag;
                import net.createmod.catnip.nbt.NBTHelper;
                public class FlapDisplayLayout {
                    List<FlapDisplaySection> sections;
                    public CompoundTag write() {
                        CompoundTag tag = new CompoundTag();
                        tag.put("Sections", NBTHelper.writeCompoundList(sections, FlapDisplaySection::write));
                        return tag;
                    }
                }
            """,
            "FlapDisplayBlockEntity" to """
                import net.minecraft.core.HolderLookup;
                import net.minecraft.nbt.CompoundTag;
                public class FlapDisplayBlockEntity {
                    FlapDisplayLayout layout;
                    protected void write(CompoundTag tag, boolean clientPacket, HolderLookup.Provider registries) {
                        tag.put("Display", layout.write());
                    }
                }
            """
        ).let { if (withCaller) it else it.take(2) }.map { (type, source) ->
            root.resolve("$type.java").also {
                it.writeText("package com.simibubi.create.content.trains.display;\n" + source.trimIndent())
            }
        }
    }
}
