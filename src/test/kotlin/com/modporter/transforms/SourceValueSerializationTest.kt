package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.SourceValueTypes
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SourceValueSerializationTest {
    @TempDir lateinit var tempDir: Path
    private fun java(name: String, text: String): Path = tempDir.resolve("src/main/java/com/example/$name.java").also {
        it.parent.createDirectories()
        it.writeText(text.trimIndent())
    }

    @Test
    fun `a getter inherited from a project parent retains its proven stack type`() {
        java("Parent", """
            package com.example;
            import net.minecraft.world.item.ItemStack;
            public abstract class Parent {
                public ItemStack getFilter() { return ItemStack.EMPTY; }
            }
        """)
        val file = java("Child", """
            package com.example;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            public class Child extends Parent {
                public void write(HolderLookup.Provider registries, CompoundTag tag) {
                    tag.put("Filter", getFilter().serializeNBT());
                }
            }
        """)
        StructuralRefactorPass().apply(tempDir)
        val result = file.readText()
        assertTrue(result.contains("getFilter().saveOptional(registries)"), result)
        assertFalse(result.contains("getFilter().serializeNBT"), result)
    }

    @Test
    fun `a child getter with an unrelated return type blocks a private parent getter`() {
        java("Parent", """
            package com.example;
            import net.minecraft.world.item.ItemStack;
            public abstract class Parent {
                private ItemStack getFilter() { return ItemStack.EMPTY; }
            }
        """)
        java("Other", """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class Other { public CompoundTag serializeNBT() { return new CompoundTag(); } }
        """)
        val file = java("Child", """
            package com.example;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            public class Child extends Parent {
                public Other getFilter() { return new Other(); }
                public void write(HolderLookup.Provider registries, CompoundTag tag) {
                    tag.put("Filter", getFilter().serializeNBT());
                }
            }
        """)
        StructuralRefactorPass().apply(tempDir)
        val result = file.readText()
        assertTrue(result.contains("getFilter().serializeNBT()"), result)
        assertFalse(result.contains("getFilter().saveOptional"), result)
    }

    @Test
    fun `declared arrays getters and library callback values retain their compound serialization type`() {
        java("Frequency", """
            package com.example;
            import net.minecraft.world.item.ItemStack;
            public class Frequency {
                private ItemStack stack;
                public ItemStack getStack() { return stack; }
            }
        """)
        val file = java("Writer", """
            package com.example;
            import java.util.List;
            import java.util.Map;
            import com.google.common.collect.ImmutableList;
            import net.createmod.catnip.data.IntAttached;
            import net.createmod.catnip.data.Couple;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.items.ItemStackHandler;
            public class Writer {
                private ItemStack icon;
                public ItemStack getFilter() { return icon; }
                public void write(HolderLookup.Provider registries, CompoundTag tag,
                                  List<IntAttached<ItemStack>> items, Couple<Frequency> frequencies,
                                  Map<String, ItemStackHandler> handlers, ItemStack[] stacks) {
                    tag.put("Rows", NBTHelper.writeCompoundList(items, ia -> ia.getValue().serializeNBT()));
                    tag.put("Inline", NBTHelper.writeCompoundList(ImmutableList.of(IntAttached.with(1, icon)), ia -> ia.getValue().serializeNBT()));
                    tag.put("Frequency", frequencies.serializeEach(f -> f.getStack().serializeNBT()));
                    items.forEach(ia -> ia.getValue().serializeNBT());
                    handlers.forEach((key, h) -> h.deserializeNBT(tag.getCompound(key)));
                    tag.put("Array", stacks[0].serializeNBT());
                    tag.put("Getter", getFilter().serializeNBT());
                    stacks[1].serializeNBT();
                }
            }
        """)
        val pass = StructuralRefactorPass()
        pass.apply(tempDir)
        val result = file.readText()
        assertEquals("net.minecraft.world.item.ItemStack", pass.projectValueFacts.getters["com.example.Writer"]?.get("getFilter"), pass.projectValueFacts.toString())
        val offset = result.indexOf("getFilter().") + "getFilter()".length
        assertEquals("net.minecraft.world.item.ItemStack", SourceValueTypes(pass, result).typeOf("getFilter()", offset), result)
        assertTrue(result.contains("ia.getValue().saveOptional(registries)"), result)
        assertTrue(result.contains("f.getStack().saveOptional(registries)"), result)
        assertTrue(result.contains("h.deserializeNBT(registries, tag.getCompound(key))"), result)
        assertTrue(result.contains("stacks[0].saveOptional(registries)"), result)
        assertTrue(result.contains("getFilter().saveOptional(registries)"), result)
        assertTrue(result.contains("CompoundTag.class.cast(stacks[1].saveOptional(registries));"), result)
        assertTrue(result.contains("CompoundTag.class.cast(ia.getValue().saveOptional(registries))"), result)
        assertTrue(result.contains("items.forEach(ia -> net.minecraft.nbt.CompoundTag.class.cast("), result)
        assertFalse(result.contains(".serializeNBT()"), result)
    }

    @Test
    fun `an unrelated callback value shadows a same named stack field`() {
        java("Other", """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            public class Other {
                public CompoundTag serializeNBT() { return new CompoundTag(); }
            }
        """)
        val file = java("Writer", """
            package com.example;
            import net.createmod.catnip.data.Couple;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            public class Writer {
                ItemStack value;
                public Object write(HolderLookup.Provider registries, Couple<Other> pair) {
                    return pair.serializeEach(value -> value.serializeNBT());
                }
            }
        """)
        StructuralRefactorPass().apply(tempDir)
        val result = file.readText()
        assertTrue(result.contains("value -> value.serializeNBT()"), result)
        assertFalse(result.contains("value.saveOptional"), result)
    }

    @Test
    fun `fluid stack reader references receive the provider in scope`() {
        val file = java("Reader", """
            package com.example;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.ListTag;
            import net.neoforged.neoforge.fluids.FluidStack;
            public class Reader {
                public Object read(HolderLookup.Provider registries, ListTag tag) {
                    return NBTHelper.readCompoundList(tag, FluidStack::loadFluidStackFromNBT);
                }
            }
        """)
        StructuralRefactorPass().apply(tempDir)
        val result = file.readText()
        assertTrue(result.contains("FluidStack.parseOptional(registries, modporterFluidTag)"), result)
        assertFalse(result.contains("FluidStack::loadFluidStackFromNBT"), result)
    }

    @Test
    fun `a project map return types both values of its forEach callback`() {
        java("MovementContext", """
            package com.example;
            import net.minecraft.world.level.Level;
            public class MovementContext {
                public Level world;
            }
        """)
        val file = java("BasinMovementBehaviour", """
            package com.example;

            import java.util.HashMap;
            import java.util.Map;
            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.items.ItemStackHandler;

            public class BasinMovementBehaviour {
                public Map<String, ItemStackHandler> getOrReadInventory(MovementContext context) {
                    return new HashMap<>();
                }

                private void dump(MovementContext context, CompoundTag blockEntityData) {
                    getOrReadInventory(context).forEach((key, itemStackHandler) -> {
                        blockEntityData.put(key, itemStackHandler.serializeNBT());
                    });
                }
            }
        """)

        val pass = StructuralRefactorPass()
        pass.apply(tempDir)

        val result = file.readText()
        assertEquals(
            "java.util.Map<java.lang.String,net.neoforged.neoforge.items.ItemStackHandler>",
            pass.projectValueFacts.methods["com.example.BasinMovementBehaviour"]
                ?.get("getOrReadInventory/1")?.singleOrNull()?.returnType,
            pass.projectValueFacts.toString()
        )
        val callbackOffset = result.indexOf("itemStackHandler.serializeNBT")
        assertEquals(
            "net.neoforged.neoforge.items.ItemStackHandler",
            SourceValueTypes(pass, result).typeOf("itemStackHandler", callbackOffset),
            result
        )
        assertTrue(
            result.contains("itemStackHandler.serializeNBT(context.world.registryAccess())"),
            result
        )
        assertFalse(result.contains("itemStackHandler.serializeNBT()"), result)
    }

    @Test
    fun `an unqualified project consumer types its callback value for provider propagation`() {
        val file = java("SmartFluidTankBehaviour", """
            package com.example;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.function.Consumer;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.fluids.capability.templates.SmartFluidTank;

            public class SmartFluidTankBehaviour {
                private final List<TankSegment> tanks = new ArrayList<>();

                public void forEach(Consumer<TankSegment> action) {
                    tanks.forEach(action);
                }

                public void write(CompoundTag nbt, boolean clientPacket, HolderLookup.Provider registries) {
                    forEach(ts -> nbt.put("Tank", ts.writeNBT()));
                }

                public class TankSegment {
                    protected SmartFluidTank tank;

                    public CompoundTag writeNBT() {
                        return tank.writeToNBT(new CompoundTag());
                    }
                }
            }
        """)

        val pass = StructuralRefactorPass()
        pass.apply(tempDir)

        val result = file.readText()
        val callbackOffset = result.indexOf("ts.writeNBT")
        assertEquals(
            "com.example.SmartFluidTankBehaviour.TankSegment",
            SourceValueTypes(pass, result).typeOf("ts", callbackOffset),
            result
        )
        assertTrue(result.contains("CompoundTag writeNBT(HolderLookup.Provider registries)"), result)
        assertTrue(result.contains("ts.writeNBT(registries)"), result)
        assertTrue(result.contains("tank.writeToNBT(registries, new CompoundTag())"), result)
        assertFalse(result.contains("getLevel()"), result)
        assertFalse(result.contains("BuiltInRegistries"), result)
    }

    @Test
    fun `wildcard receivers retain raw type and functional lower bound`() {
        val file = java("RecipeValues", """
            package com.example;

            import java.util.function.Predicate;
            import net.minecraft.world.item.crafting.Recipe;

            public class RecipeValues {
                public void use(Predicate<? super Recipe<?>> predicate) {
                }

                public void inspect(Recipe<?> rawRecipe) {
                    use(recipe -> recipe != null);
                    rawRecipe.toString();
                }
            }
        """)

        val pass = StructuralRefactorPass()
        pass.apply(tempDir)

        val result = file.readText()
        val values = SourceValueTypes(pass, result)
        assertEquals(
            "net.minecraft.world.item.crafting.Recipe",
            values.typeOf("rawRecipe", result.indexOf("rawRecipe.toString")),
            result
        )
        assertEquals(
            "net.minecraft.world.item.crafting.Recipe",
            values.typeOf("recipe", result.indexOf("recipe != null")),
            result
        )
    }

    @Test
    fun `catnip compound readers give untyped callbacks their exact tag type`() {
        val file = java("CompoundReaders", """
            package com.example;

            import net.createmod.catnip.data.Couple;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.nbt.ListTag;

            public class CompoundReaders {
                public void inspect(ListTag tags) {
                    NBTHelper.iterateCompoundList(tags, iterated -> iterated.toString());
                    NBTHelper.readCompoundList(tags, read -> read.toString());
                    Couple.deserializeEach(tags, coupled -> coupled.toString());
                }
            }
        """)

        val pass = StructuralRefactorPass()
        pass.apply(tempDir)

        val result = file.readText()
        val values = SourceValueTypes(pass, result)
        for (name in listOf("iterated", "read", "coupled")) {
            assertEquals(
                "net.minecraft.nbt.CompoundTag",
                values.typeOf(name, result.indexOf("$name.toString")),
                result
            )
        }
    }
}
