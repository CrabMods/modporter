package com.modporter.core.transforms.structural

import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class PotionFluidEffectDataMigrationTest {
    @TempDir lateinit var directory: Path

    private val owner = "com/simibubi/create/content/fluids/potion/PotionFluid.java"

    private fun source() = """
        package com.simibubi.create.content.fluids.potion;
        import java.util.Collection;
        import net.neoforged.neoforge.fluids.FluidStack;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.nbt.ListTag;
        import net.minecraft.world.effect.MobEffectInstance;
        public class PotionFluid {
            public static FluidStack appendEffects(FluidStack fs, Collection<MobEffectInstance> customEffects) {
                if (customEffects.isEmpty())
                    return fs;
                CompoundTag compoundnbt = fs.getOrCreateTag();
                ListTag listnbt = compoundnbt.getList("CustomPotionEffects", 9);
                for (MobEffectInstance effectinstance : customEffects)
                    listnbt.add(effectinstance.save());
                compoundnbt.put("CustomPotionEffects", listnbt);
                return fs;
            }
        }
    """.trimIndent()

    private fun write(path: String, text: String): Path =
        directory.resolve("src/main/java/$path").also {
            it.parent.createDirectories()
            it.writeText(text.trimIndent())
        }

    private fun migrate(dryRun: Boolean) =
        CreateEffectComponentSurfaces.migrate(StructuralRefactorPass(), directory, dryRun)

    @Test
    fun `effect list migration preserves live versus detached child writes and throwing iteration`() {
        val original = source()
        val file = write(owner, original)
        assertEquals(1, migrate(true).size)
        assertEquals(original, file.readText())
        assertEquals(1, migrate(false).size)
        assertFalse(file.readText().contains("getOrCreateTag"))
        assertTrue(migrate(false).isEmpty())

        // Install test ABI stubs only after admission; source declarations shadowing the SDK
        // deliberately cannot acquire the production migration's external-type contracts.
        write("reference/LegacyPotionFluid.java", original
            .replace("package com.simibubi.create.content.fluids.potion;", "package reference;")
            .replace("net.neoforged.neoforge.fluids.FluidStack", "legacy.FluidStack")
            .replace("public class PotionFluid", "public class LegacyPotionFluid"))
        writeStubs()
        write("Scenario.java", """
            import java.util.*;
            import java.util.function.Supplier;
            import net.minecraft.nbt.*;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.world.item.component.CustomData;
            import com.simibubi.create.content.fluids.potion.PotionFluid;
            import reference.LegacyPotionFluid;
            public class Scenario {
                static void require(boolean value, String label) {
                    if (!value) throw new AssertionError(label);
                }
                static CompoundTag snapshot(net.neoforged.neoforge.fluids.FluidStack stack) {
                    return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
                }
                static Collection<MobEffectInstance> effects(Supplier<CompoundTag> read,
                        List<String> observed, boolean throwing) {
                    return new AbstractCollection<MobEffectInstance>() {
                        public int size() { return 2; }
                        public Iterator<MobEffectInstance> iterator() {
                            return new Iterator<MobEffectInstance>() {
                                int next;
                                public boolean hasNext() { observed.add(read.get().toString()); return next < 2; }
                                public MobEffectInstance next() {
                                    return new MobEffectInstance("effect" + (++next), throwing && next == 2);
                                }
                            };
                        }
                    };
                }
                public static void run() {
                    for (String mode : List.of("missing", "empty", "typed-empty", "compound-list", "list-list")) {
                        for (boolean throwing : List.of(false, true)) {
                            CompoundTag initial = new CompoundTag();
                            initial.putString("Sibling", "kept");
                            if (!mode.equals("missing")) {
                                ListTag list = mode.equals("typed-empty") ? ListTag.typedEmpty(9) : new ListTag();
                                if (mode.equals("compound-list")) list.add(new CompoundTag());
                                if (mode.equals("list-list")) list.add(new ListTag());
                                initial.put("CustomPotionEffects", list);
                            }
                            legacy.FluidStack oldStack = new legacy.FluidStack(initial.copy());
                            net.neoforged.neoforge.fluids.FluidStack newStack =
                                new net.neoforged.neoforge.fluids.FluidStack();
                            newStack.set(DataComponents.CUSTOM_DATA, CustomData.of(initial));
                            List<String> oldObserved = new ArrayList<>(), newObserved = new ArrayList<>();
                            Class<?> oldThrew = null, newThrew = null;
                            try { require(LegacyPotionFluid.appendEffects(oldStack,
                                effects(oldStack::getOrCreateTag, oldObserved, throwing)) == oldStack, "old identity"); }
                            catch (RuntimeException expected) { oldThrew = expected.getClass(); }
                            try { require(PotionFluid.appendEffects(newStack,
                                effects(() -> snapshot(newStack), newObserved, throwing)) == newStack, "new identity"); }
                            catch (RuntimeException expected) { newThrew = expected.getClass(); }
                            require(oldThrew == newThrew, mode + " throw parity");
                            require(oldStack.getOrCreateTag().toString().equals(snapshot(newStack).toString()),
                                mode + " final data parity");
                            require(oldObserved.equals(newObserved), mode + " iteration observations");
                            require(snapshot(newStack).getString("Sibling").equals("kept"), "sibling");
                        }
                    }
                    legacy.FluidStack oldEmpty = new legacy.FluidStack(null);
                    net.neoforged.neoforge.fluids.FluidStack newEmpty = new net.neoforged.neoforge.fluids.FluidStack();
                    LegacyPotionFluid.appendEffects(oldEmpty, List.of());
                    PotionFluid.appendEffects(newEmpty, List.of());
                    require(oldEmpty.data == null && !newEmpty.has(DataComponents.CUSTOM_DATA), "empty no-op");
                    boolean failed = false;
                    try { PotionFluid.appendEffects(newEmpty, List.of(new MobEffectInstance("bad", true))); }
                    catch (IllegalArgumentException expected) { failed = true; }
                    require(failed && newEmpty.has(DataComponents.CUSTOM_DATA), "legacy empty tag materialization");
                }
            }
        """)
        val classes = compile()
        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            try { loader.loadClass("Scenario").getMethod("run").invoke(null) }
            catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
        }
    }

    @Test
    fun `extra child escape changed key early exit or shadow type refuses before source writes`() {
        for (variant in listOf("escape", "key", "exit", "shadow")) {
            val file = write(owner, when (variant) {
                "escape" -> source().replace("compoundnbt.put(\"CustomPotionEffects\", listnbt);", "observe(listnbt); compoundnbt.put(\"CustomPotionEffects\", listnbt);")
                "key" -> source().replace("getList(\"CustomPotionEffects\", 9)", "getList(\"Other\", 9)")
                "exit" -> source().replace("listnbt.add(effectinstance.save());", "if (customEffects.size() == 1) return fs; else listnbt.add(effectinstance.save());")
                else -> source()
            })
            if (variant == "shadow") write("net/minecraft/nbt/CompoundTag.java", "package net.minecraft.nbt; public class CompoundTag {}")
            val before = file.readBytes()
            assertFailsWith<IllegalStateException> { migrate(false) }
            assertTrue(before.contentEquals(file.readBytes()), variant)
        }
    }

    @Test
    fun `captured potion fluid source admits an isolated dry run without editing its input`() {
        val configured = System.getenv("MODPORTER_CREATE_ITEM_DATA_REPLAY")
        assumeTrue(!configured.isNullOrBlank(), "Optional captured-source replay")
        val original = Path.of(configured!!).resolve(owner)
        val bytes = original.readBytes()
        write(owner, original.readText())
        assertEquals(1, migrate(true).size)
        assertTrue(bytes.contentEquals(original.readBytes()))
    }

    private fun compile(): Path {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val classes = directory.resolve("compiled").also { it.createDirectories() }
        val sources = Files.walk(directory.resolve("src/main/java")).use { paths ->
            paths.filter { it.toString().endsWith(".java") }.toList()
        }
        compiler.getStandardFileManager(diagnostics, null, null).use { manager ->
            val units = manager.getJavaFileObjectsFromPaths(sources)
            val result = compiler.getTask(null, manager, diagnostics,
                listOf("-proc:none", "-d", classes.toString()), null, units).call()
            assertTrue(result, diagnostics.diagnostics.joinToString("\n"))
        }
        return classes
    }

    private fun writeStubs() {
        write("net/minecraft/nbt/Tag.java", "package net.minecraft.nbt; public interface Tag { int getId(); Tag copy(); }")
        write("net/minecraft/nbt/CompoundTag.java", """
            package net.minecraft.nbt;
            import java.util.*;
            public class CompoundTag implements Tag {
                private final Map<String,Object> values = new TreeMap<>();
                public int getId() { return 10; }
                public void put(String key, Tag value) { values.put(key, value); }
                public void putString(String key, String value) { values.put(key, value); }
                public String getString(String key) { return (String)values.get(key); }
                public Tag get(String key) { return values.get(key) instanceof Tag tag ? tag : null; }
                public ListTag getList(String key, int kind) {
                    if (values.get(key) instanceof ListTag list && (list.isEmpty() || list.kind == kind)) return list;
                    return new ListTag();
                }
                public CompoundTag copy() {
                    CompoundTag result = new CompoundTag();
                    values.forEach((key,value) -> result.values.put(key, value instanceof Tag tag ? tag.copy() : value));
                    return result;
                }
                public String toString() { return values.toString(); }
            }
        """)
        write("net/minecraft/nbt/ListTag.java", """
            package net.minecraft.nbt;
            import java.util.*;
            public class ListTag implements Tag {
                final List<Tag> values = new ArrayList<>(); int kind;
                public static ListTag typedEmpty(int kind) { ListTag result = new ListTag(); result.kind=kind; return result; }
                public int getId() { return 9; }
                public boolean isEmpty() { return values.isEmpty(); }
                public boolean add(Tag tag) {
                    if (kind == 0) kind = tag.getId();
                    if (kind != tag.getId()) throw new UnsupportedOperationException("mixed tag kinds");
                    return values.add(tag);
                }
                public ListTag copy() { ListTag result = new ListTag(); values.forEach(value -> result.add(value.copy())); result.kind=kind; return result; }
                public String toString() { return values.toString(); }
            }
        """)
        write("net/minecraft/world/effect/MobEffectInstance.java", """
            package net.minecraft.world.effect;
            import net.minecraft.nbt.*;
            public class MobEffectInstance {
                private final String id; private final boolean explode;
                public MobEffectInstance(String id, boolean explode) { this.id=id; this.explode=explode; }
                public CompoundTag save() { if (explode) throw new IllegalArgumentException(id); CompoundTag tag=new CompoundTag(); tag.putString("id",id); return tag; }
            }
        """)
        write("legacy/FluidStack.java", "package legacy; import net.minecraft.nbt.*; public class FluidStack { public CompoundTag data; public FluidStack(CompoundTag data) {this.data=data;} public CompoundTag getOrCreateTag(){if(data==null)data=new CompoundTag();return data;} }")
        write("net/minecraft/core/component/DataComponentType.java", "package net.minecraft.core.component; public class DataComponentType<T> {}")
        write("net/minecraft/core/component/DataComponents.java", "package net.minecraft.core.component; import net.minecraft.world.item.component.CustomData; public class DataComponents { public static final DataComponentType<CustomData> CUSTOM_DATA=new DataComponentType<>(); }")
        write("net/minecraft/world/item/component/CustomData.java", "package net.minecraft.world.item.component; import net.minecraft.nbt.*; public class CustomData { private final CompoundTag data; public static final CustomData EMPTY=new CustomData(new CompoundTag()); private CustomData(CompoundTag data){this.data=data.copy();} public static CustomData of(CompoundTag data){return new CustomData(data);} public CompoundTag copyTag(){return data.copy();} }")
        write("net/neoforged/neoforge/fluids/FluidStack.java", """
            package net.neoforged.neoforge.fluids;
            import net.minecraft.core.component.*;
            public class FluidStack {
                private Object value;
                public boolean has(DataComponentType<?> key){return value!=null;}
                @SuppressWarnings("unchecked") public <T>T getOrDefault(DataComponentType<T> key,T fallback){return value==null?fallback:(T)value;}
                @SuppressWarnings("unchecked") public <T>T set(DataComponentType<T> key,T next){T old=(T)value;value=next;return old;}
            }
        """)
    }
}
