package com.modporter.transforms

import com.modporter.core.transforms.structural.CreateFlapDisplaySerializationMigration
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CreateFlapDisplaySerializationMigrationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `preserves the different provider names assigned to each source method`() {
        val project = newProject("provider-names")
        val source = writeSource(
            project,
            legacySource().replace("registries", "registries1")
                .replace("write(HolderLookup.Provider registries1)",
                    "write(HolderLookup.Provider writeRegistries)")
        )
        val result = CreateFlapDisplaySerializationMigration.migrate(project, false)
        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        assertTrue(source.readText().contains("toJson(component, writeRegistries)"))
        assertTrue(source.readText().contains("fromJson(text, registries1)"))
        assertTrue(CreateFlapDisplaySerializationMigration.migrate(project, false).changes.isEmpty())
    }

    @Test
    fun `migrates the exact converted flap display boundary to native 1_21 semantics`() {
        val project = newProject("create-flap-display")
        val source = writeSource(project, legacySource())

        val result = CreateFlapDisplaySerializationMigration.migrate(project, dryRun = false)

        assertEquals(1, result.changes.size, result.gates.joinToString())
        assertTrue(result.gates.isEmpty())
        val migrated = source.readText()
        assertTrue(
            migrated.contains(
                """tag.putString("Text", Component.Serializer.toJson(component, registries));"""
            ),
            migrated
        )
        assertTrue(migrated.contains("""String text = tag.getString("Text");"""), migrated)
        assertTrue(migrated.contains("String trimmedText = text.trim();"), migrated)
        assertTrue(
            migrated.contains(
                "component = trimmedText.isEmpty() || trimmedText.equals(\"null\") ? " +
                    "null : Component.Serializer.fromJson(text, registries);"
            ),
            migrated
        )
        assertFalse(migrated.contains("ComponentSerialization.CODEC"), migrated)
        assertTrue(migrated.contains("static Random r = new Random();"), migrated)
        assertTrue(migrated.contains("public int tick(boolean instant)"), migrated)

        val second = CreateFlapDisplaySerializationMigration.migrate(project, dryRun = false)
        assertTrue(second.changes.isEmpty(), second.gates.joinToString())
        assertTrue(second.gates.isEmpty())
        assertEquals(migrated, source.readText())
    }

    @Test
    fun `empty update clears a preexisting component while malformed JSON remains parser owned`() {
        val project = newProject("create-flap-display-null-contract")
        val source = writeSource(project, legacySource())

        val result = CreateFlapDisplaySerializationMigration.migrate(project, dryRun = false)

        assertTrue(result.gates.isEmpty(), result.gates.joinToString())
        val classes = compileMigratedBoundary(project, source)
        URLClassLoader(
            arrayOf(classes.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        ).use { loader ->
            val sectionClass = loader.loadClass(
                "com.simibubi.create.content.trains.display.FlapDisplaySection"
            )
            val componentClass = loader.loadClass("net.minecraft.network.chat.Component")
            val tagClass = loader.loadClass("net.minecraft.nbt.CompoundTag")
            val providerClass = loader.loadClass("net.minecraft.core.HolderLookup\$Provider")
            val section = sectionClass.getConstructor().newInstance()
            val componentField = sectionClass.getDeclaredField("component").apply {
                isAccessible = true
            }
            val componentConstructor = componentClass.getConstructor(String::class.java)
            val tagConstructor = tagClass.getConstructor()
            val putString = tagClass.getMethod(
                "putString",
                String::class.java,
                String::class.java
            )
            val update = sectionClass.getMethod("update", tagClass, providerClass)
            val write = sectionClass.getMethod("write", providerClass)
            val provider = Proxy.newProxyInstance(
                loader,
                arrayOf(providerClass)
            ) { _, _, _ -> null }

            fun assertClears(text: String?) {
                componentField.set(section, componentConstructor.newInstance("""{"text":"old"}"""))
                val tag = tagConstructor.newInstance()
                if (text != null) putString.invoke(tag, "Text", text)
                update.invoke(section, tag, provider)
                assertNull(componentField.get(section), "Text=$text must clear the old component")
            }

            assertClears(null)
            assertClears("")
            assertClears("   ")
            assertClears(" null ")

            val richJson = """{"text":"new","bold":true}"""
            val richTag = tagConstructor.newInstance()
            putString.invoke(richTag, "Text", richJson)
            update.invoke(section, richTag, provider)
            val decoded = componentField.get(section)
            assertEquals(
                richJson,
                componentClass.getField("json").get(decoded),
                "Valid rich text must pass through the provider-aware parser"
            )
            val written = write.invoke(section, provider)
            assertEquals(
                richJson,
                tagClass.getMethod("getString", String::class.java)
                    .invoke(written, "Text")
            )
            assertTrue(
                componentClass.getField("lastProvider").get(null) === provider,
                "Both read and write must use the supplied registry provider"
            )

            val malformed = tagConstructor.newInstance()
            putString.invoke(malformed, "Text", "{bad")
            val failure = assertFailsWith<InvocationTargetException> {
                update.invoke(section, malformed, provider)
            }
            assertTrue(failure.cause is IllegalArgumentException, failure.toString())
        }
    }

    @Test
    fun `dry run reports the migration without writing`() {
        val project = newProject("create-flap-display-dry")
        val source = writeSource(project, legacySource())
        val before = source.readText()

        val result = CreateFlapDisplaySerializationMigration.migrate(project, dryRun = true)

        assertEquals(1, result.changes.size, result.gates.joinToString())
        assertTrue(result.gates.isEmpty())
        assertEquals(before, source.readText())
    }

    @Test
    fun `refuses a partial target write and legacy update without writing`() {
        val project = newProject("create-flap-display-partial")
        val source = writeSource(
            project,
            legacySource().replace(
                legacyWriteExpression(),
                "Component.Serializer.toJson(component, registries)"
            )
        )
        val before = source.readText()

        val result = CreateFlapDisplaySerializationMigration.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.single().contains("partial or incompatible"))
        assertEquals(before, source.readText())
    }

    @Test
    fun `refuses an altered update graph without writing`() {
        val project = newProject("create-flap-display-altered")
        val source = writeSource(
            project,
            legacySource().replace(
                """refresh(tag.getBoolean("Transition"));""",
                """refresh(false);"""
            )
        )
        val before = source.readText()

        val result = CreateFlapDisplaySerializationMigration.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.single().contains("partial or incompatible"))
        assertEquals(before, source.readText())
    }

    @Test
    fun `refuses a load boundary that lost its missing Text guard`() {
        val project = newProject("create-flap-display-load")
        val source = writeSource(
            project,
            legacySource().replace(
                Regex(
                    """\s*if\s*\(\s*!tag\.contains\("Text"\)\s*\)\s*""" +
                        """return\s+section;\s*"""
                ),
                "\n"
            )
        )
        val before = source.readText()

        val result = CreateFlapDisplaySerializationMigration.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.single().contains("load is not the expected"))
        assertEquals(before, source.readText())
    }

    private fun newProject(name: String): Path = directory.resolve(name).also { it.createDirectories() }

    private fun writeSource(project: Path, content: String): Path {
        val source = project.resolve(
            "src/main/java/com/simibubi/create/content/trains/display/FlapDisplaySection.java"
        )
        source.parent.createDirectories()
        source.writeText(content)
        return source
    }

    private fun compileMigratedBoundary(project: Path, migrated: Path): Path {
        val holder = project.resolve("src/main/java/net/minecraft/core/HolderLookup.java")
        holder.parent.createDirectories()
        holder.writeText(
            """
                package net.minecraft.core;
                public final class HolderLookup {
                    public interface Provider {}
                }
            """.trimIndent()
        )
        val tag = project.resolve("src/main/java/net/minecraft/nbt/CompoundTag.java")
        tag.parent.createDirectories()
        tag.writeText(
            """
                package net.minecraft.nbt;
                import java.util.HashMap;
                import java.util.Map;
                public class CompoundTag {
                    private final Map<String, String> strings = new HashMap<>();
                    public void putString(String key, String value) { strings.put(key, value); }
                    public String getString(String key) { return strings.getOrDefault(key, ""); }
                    public boolean getBoolean(String key) { return false; }
                    public boolean contains(String key) { return strings.containsKey(key); }
                }
            """.trimIndent()
        )
        val component = project.resolve(
            "src/main/java/net/minecraft/network/chat/Component.java"
        )
        component.parent.createDirectories()
        component.writeText(
            """
                package net.minecraft.network.chat;
                import net.minecraft.core.HolderLookup;
                public class Component {
                    public static Object lastProvider;
                    public final String json;
                    public Component(String json) { this.json = json; }
                    public static final class Serializer {
                        public static String toJson(
                            Component component,
                            HolderLookup.Provider registries
                        ) {
                            lastProvider = registries;
                            return component.json;
                        }
                        public static Component fromJson(
                            String json,
                            HolderLookup.Provider registries
                        ) {
                            lastProvider = registries;
                            if ("{bad".equals(json))
                                throw new IllegalArgumentException("malformed");
                            return new Component(json);
                        }
                    }
                }
            """.trimIndent()
        )
        val classes = project.resolve("compiled")
        classes.createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val code = PrintStream(diagnostics).use { output ->
            compiler.run(
                null,
                output,
                output,
                "-d",
                classes.toString(),
                migrated.toString(),
                holder.toString(),
                tag.toString(),
                component.toString()
            )
        }
        assertEquals(0, code, diagnostics.toString(Charsets.UTF_8))
        return classes
    }

    private fun legacyWriteExpression(): String =
        "net.minecraft.network.chat.ComponentSerialization.CODEC" +
            ".encodeStart(com.mojang.serialization.JsonOps.INSTANCE, component)" +
            ".getOrThrow().toString()"

    private fun legacySource(): String = """
        package com.simibubi.create.content.trains.display;

        import java.util.Random;

        import net.minecraft.core.HolderLookup;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.network.chat.Component;

        public class FlapDisplaySection {
            static Random r = new Random();
            String[] cyclingOptions;
            String cycle;
            Component component;

            public int tick(boolean instant) {
                return r.nextInt();
            }

            public CompoundTag write(HolderLookup.Provider registries) {
                CompoundTag tag = new CompoundTag();
                if (component != null)
                    tag.putString("Text", ${legacyWriteExpression()});
                return tag;
            }

            public static FlapDisplaySection load(
                CompoundTag tag,
                HolderLookup.Provider registries
            ) {
                FlapDisplaySection section = new FlapDisplaySection();
                if (!tag.contains("Text"))
                    return section;

                section.component =
                    Component.Serializer.fromJson(tag.getString("Text"), registries);
                section.refresh(tag.getBoolean("Transition"));
                return section;
            }

            public void update(CompoundTag tag, HolderLookup.Provider registries) {
                component = Component.Serializer.fromJson(tag.getString("Text"), registries);
                if (cyclingOptions == null)
                    cyclingOptions = getFlapCycle(cycle);
                refresh(tag.getBoolean("Transition"));
            }

            private static String[] getFlapCycle(String cycle) {
                return new String[] { cycle };
            }

            private void refresh(boolean transition) {
            }
        }
    """.trimIndent()
}
