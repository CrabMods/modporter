package com.modporter.transforms

import com.modporter.core.transforms.structural.ExactLegacyCustomDataMigration
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExactLegacyCustomDataMigrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `structural pass wires the exact custom data graph before common rewrites`() {
        val source = writeJava(
            "WiredCustomData.java",
            """
            package com.example;

            import net.minecraft.world.item.ItemStack;

            class WiredCustomData {
                void write(ItemStack stack) {
                    stack.getOrCreateTag().putInt("Count", 1);
                }
            }
            """.trimIndent()
        )

        val result = StructuralRefactorPass().apply(tempDir)
        val migrated = source.readText()

        assertTrue(
            result.changes.any { it.ruleId == "struct-exact-legacy-custom-data-graph" },
            result.changes.joinToString()
        )
        assertTrue(migrated.contains("CustomData.update"), migrated)
        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
    }

    @Test
    fun `direct item and fluid custom data operations preserve writes explicitly`() {
        val source = writeJava(
            "ExactCustomData.java",
            """
            package com.example;

            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.fluids.FluidStack;

            class ExactCustomData {
                void mutateItem(ItemStack stack) {
                    stack.getOrCreateTag().putInt("Count", 3);
                }

                int readItem(ItemStack stack) {
                    return stack.getOrCreateTag().getInt("Count");
                }

                void mutateFluid(FluidStack fluid, Mode mode) {
                    NBTHelper.writeEnum(fluid.getOrCreateTag(), "Mode", mode);
                }

                Mode readFluid(FluidStack fluid) {
                    return NBTHelper.readEnum(fluid.getOrCreateTag(), "Mode", Mode.class);
                }

                enum Mode {
                    FIRST, SECOND
                }
            }
            """.trimIndent()
        )

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertEquals(1, changes.size)
        assertTrue(
            Regex(
                """net\.minecraft\.world\.item\.component\.CustomData\.update\(""" +
                    """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*stack,\s*""" +
                    """modPorterCustomDataTag\s*->\s*modPorterCustomDataTag\.putInt\("Count",\s*3\)\);"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            migrated.contains(
                "(stack).getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getInt(\"Count\")"
            ),
            migrated
        )
        assertTrue(
            Regex(
                """net\.minecraft\.Util\.make\(fluid,\s*modPorterCustomDataOwner\s*->\s*""" +
                    """net\.minecraft\.Util\.make\(\(modPorterCustomDataOwner\)\.getOrDefault\(""" +
                    """net\.minecraft\.core\.component\.DataComponents\.CUSTOM_DATA,\s*""" +
                    """net\.minecraft\.world\.item\.component\.CustomData\.EMPTY\)\.copyTag\(\),\s*""" +
                    """modPorterCustomDataTag\s*->"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(migrated.contains("NBTHelper.writeEnum(modPorterCustomDataTag, \"Mode\", mode);"), migrated)
        assertTrue(
            migrated.contains(
                "(modPorterCustomDataOwner).set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.of(modPorterCustomDataTag))"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "NBTHelper.readEnum((fluid).getOrDefault(" +
                    "net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.EMPTY).copyTag(), \"Mode\", Mode.class)"
            ),
            migrated
        )
        assertTrue(!migrated.contains("getOrCreateTag()"), migrated)
    }

    @Test
    fun `local live tag aliases write back after every proven mutation`() {
        val source = writeJava(
            "AliasCustomData.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.fluids.FluidStack;

            class AliasCustomData {
                int update(ItemStack stack) {
                    CompoundTag tag = stack.getOrCreateTag();
                    tag.putString("Name", "value");
                    int result = tag.getInt("Count");
                    tag.remove("Name");
                    return result;
                }

                void explicitWriteBack(ItemStack stack) {
                    CompoundTag tag = stack.getOrCreateTag();
                    tag.putInt("Count", 1);
                    stack.set(
                        net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(tag)
                    );
                }

                void lambdaMutation(ItemStack stack) {
                    CompoundTag tag = stack.getOrCreateTag();
                    java.util.Optional.of(1).ifPresent(value -> tag.putInt("Count", value));
                }
            }
            """.trimIndent()
        )

        ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(
            migrated.contains(
                "CompoundTag tag = (stack).getOrDefault(" +
                    "net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.EMPTY).copyTag();"
            ),
            migrated
        )
        assertEquals(
            4,
            Regex("""if \(\(tag\)\.isEmpty\(\)\)""").findAll(migrated).count(),
            migrated
        )
        assertEquals(
            5,
            Regex("""CustomData\.of\(tag\)""").findAll(migrated).count(),
            migrated
        )
        assertTrue(migrated.indexOf("tag.putString") < migrated.indexOf("CustomData.of(tag)"), migrated)
        assertTrue(migrated.indexOf("tag.remove") < migrated.lastIndexOf("CustomData.of(tag)"), migrated)
        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
        assertTrue(
            Regex(
                """ifPresent\(value\s*->\s*\{[\s\S]*?tag\.putInt""" +
                    """\("Count",\s*value\);[\s\S]*?CustomData\.of\(tag\)"""
            ).containsMatchIn(migrated),
            migrated
        )
    }

    @Test
    fun `member aliases and fluid call receivers are evaluated once through owner snapshots`() {
        val source = writeJava(
            "OwnerSnapshots.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.fluids.FluidStack;

            class OwnerSnapshots {
                ItemStack item;

                void fieldAlias() {
                    CompoundTag tag = item.getOrCreateTag();
                    tag.putInt("Count", 1);
                }

                void returnedFluid() {
                    nextFluid().getOrCreateTag().putInt("Count", 1);
                }

                FluidStack nextFluid() {
                    return null;
                }
            }
            """.trimIndent()
        )

        ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(
            Regex(
                """net\.minecraft\.world\.item\.ItemStack\s+modPorterCustomDataOwner\s*=\s*item;"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            migrated.contains(
                "(modPorterCustomDataOwner).set(" +
                    "net.minecraft.core.component.DataComponents.CUSTOM_DATA"
            ),
            migrated
        )
        assertTrue(
            Regex(
                """net\.minecraft\.Util\.make\(nextFluid\(\),\s*""" +
                    """modPorterCustomDataOwner\s*->\s*net\.minecraft\.Util\.make\("""
            ).containsMatchIn(migrated),
            migrated
        )
        assertEquals(
            1,
            Regex("""net\.minecraft\.Util\.make\(nextFluid\(\)""").findAll(migrated).count(),
            migrated
        )
    }

    @Test
    fun `single statement if mutations keep write back inside each branch`() {
        val source = writeJava(
            "ConditionalTagMutation.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class ConditionalTagMutation {
                void update(ItemStack stack, boolean enabled) {
                    CompoundTag tag = stack.getOrCreateTag();
                    if (enabled)
                        tag.putInt("Count", 1);
                    else
                        tag.remove("Count");
                }
            }
            """.trimIndent()
        )

        ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
        assertEquals(
            2,
            Regex("""if \(\(tag\)\.isEmpty\(\)\)""").findAll(migrated).count(),
            migrated
        )
        assertTrue(
            Regex("""if \(enabled\)\s*\{\s*tag\.putInt""")
                .containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            Regex("""else\s*\{\s*tag\.remove""").containsMatchIn(migrated),
            migrated
        )
    }

    @Test
    fun `legacy set tag preserves receiver argument and null evaluation order`() {
        val source = writeJava(
            "SetTagCalls.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.fluids.FluidStack;

            class SetTagCalls {
                void clear(ItemStack stack) {
                    stack.setTag(null);
                }

                void replace(ItemStack stack, CompoundTag value) {
                    stack.setTag(value);
                }

                void fluid(FluidStack stack, CompoundTag value) {
                    stack.setTag(value);
                }

                void live(ItemStack stack) {
                    CompoundTag tag = stack.getOrCreateTag();
                    tag.putInt("Count", 1);
                    stack.setTag(tag);
                }
            }
            """.trimIndent()
        )

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertEquals(1, changes.size, migrated)
        assertTrue(!migrated.contains("setTag("), migrated)
        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
        assertEquals(
            4,
            Regex("""net\.minecraft\.Util\.make\(stack""").findAll(migrated).count(),
            migrated
        )
        assertTrue(migrated.contains("modPorterCustomDataValue == null"), migrated)
        assertTrue(
            migrated.contains(
                "net.minecraft.world.item.component.CustomData.of(modPorterCustomDataValue)"
            ),
            migrated
        )
    }

    @Test
    fun `tag elements preserve attachment mutation and read lift semantics`() {
        val local = writeJava(
            "LocalTagElement.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class LocalTagElement {
                void local(ItemStack stack, String key) {
                    CompoundTag child = stack.getOrCreateTagElement(key);
                    child.putInt("Count", 1);
                    net.minecraft.world.item.component.CustomData.update(
                        net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        stack,
                        root -> root.put(key, child)
                    );
                }
            }
            """.trimIndent()
        )
        val direct = writeJava(
            "DirectTagElement.java",
            """
            package com.example;

            import net.minecraft.world.item.ItemStack;

            class DirectTagElement {
                void direct(ItemStack stack) {
                    stack.getOrCreateTagElement("Direct").putInt("Count", 1);
                }
            }
            """.trimIndent()
        )
        val lifted = writeJava(
            "LiftedTagElement.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class LiftedTagElement {
                int lifted(ItemStack stack) {
                    return read(stack.getOrCreateTagElement("Read"));
                }

                static int read(
                    CompoundTag tag,
                    net.minecraft.core.HolderLookup.Provider registries
                ) {
                    return tag.getInt("Count");
                }
            }
            """.trimIndent()
        )
        val handler = writeJava(
            "HandlerTagElement.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.neoforged.neoforge.items.ItemStackHandler;

            class HandlerTagElement {
                ItemStackHandler read(ItemStack stack) {
                    ItemStackHandler handler = new ItemStackHandler(9);
                    CompoundTag child = stack.getOrCreateTagElement("Items");
                    handler.deserializeNBT(child);
                    return handler;
                }
            }
            """.trimIndent()
        )

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val localMigrated = local.readText()
        val directMigrated = direct.readText()
        val liftedMigrated = lifted.readText()
        val handlerMigrated = handler.readText()
        val migrated = listOf(
            localMigrated,
            directMigrated,
            liftedMigrated,
            handlerMigrated
        ).joinToString("\n")

        assertEquals(4, changes.size, migrated)
        assertTrue(!migrated.contains("getOrCreateTagElement"), migrated)
        assertTrue(
            Regex(
                """String\s+modPorterCustomDataKey\s*=\s*key;[\s\S]*?""" +
                    """CompoundTag\s+child\s*=\s*modPorterCustomDataRoot\.getCompound""" +
                    """\(modPorterCustomDataKey\);"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            Regex(
                """CustomData\.update\([\s\S]*?String\s+modPorterCustomDataKey\d*\s*=\s*"Direct";""" +
                    """[\s\S]*?modPorterCustomDataChild\d*\.putInt\("Count",\s*1\);""" +
                    """[\s\S]*?modPorterCustomDataRoot\d*\.put\("""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(
            Regex(
                """CompoundTag\s+modPorterCustomDataChild\d*\s*=\s*""" +
                    """modPorterCustomDataRoot\d*\.getCompound\(modPorterCustomDataKey\d*\);""" +
                    """[\s\S]*?return read\(modPorterCustomDataChild\d*\);"""
            ).containsMatchIn(migrated),
            migrated
        )
    }

    @Test
    fun `unsupported tag escape keeps every exact call in the file untouched`() {
        val source = writeJava(
            "UnsupportedCustomData.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class UnsupportedCustomData {
                CompoundTag escaped;

                void mixed(ItemStack first, ItemStack second) {
                    first.getOrCreateTag().putInt("Safe", 1);
                    consume(second.getOrCreateTag());
                }

                void consume(CompoundTag tag) {
                    escaped = tag;
                }
            }
            """.trimIndent()
        )
        val original = source.readText()

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertEquals(original, source.readText())
    }

    @Test
    fun `project method tag effects are derived from exact parameter uses`() {
        val source = writeJava(
            "ProjectTagEffects.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class ProjectTagEffects {
                void apply(ItemStack written, ItemStack readOnly) {
                    CompoundTag first = written.getOrCreateTag();
                    write(first);
                    CompoundTag second = readOnly.getOrCreateTag();
                    int value = read(second);
                }

                static void write(CompoundTag tag) {
                    tag.putInt("Count", 1);
                }

                static int read(CompoundTag tag) {
                    return tag.getInt("Count");
                }
            }
            """.trimIndent()
        )

        ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
        assertTrue(
            Regex("""write\(first\);\s*if \(\(first\)\.isEmpty\(\)\)""")
                .containsMatchIn(migrated),
            migrated
        )
        assertEquals(
            0,
            Regex("""read\(second\);\s*if \(\(second\)\.isEmpty\(\)\)""")
                .findAll(migrated)
                .count(),
            migrated
        )
    }

    @Test
    fun `project nested tag views trace aliases casts and lambda effects`() {
        val source = writeJava(
            "ProjectNestedTagEffects.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.Tag;
            import net.minecraft.world.item.ItemStack;

            class ProjectNestedTagEffects {
                void apply(ItemStack readOnly, ItemStack written) {
                    CompoundTag first = readOnly.getOrCreateTag();
                    read(first);
                    CompoundTag second = written.getOrCreateTag();
                    mutate(second);
                }

                static void read(CompoundTag root) {
                    Tag payload = root.get("Payload");
                    if (payload != null && payload.getId() == Tag.TAG_COMPOUND) {
                        CompoundTag nested = (CompoundTag) payload;
                        nested.getList("Entries", Tag.TAG_COMPOUND).forEach(entry -> {
                            CompoundTag row = (CompoundTag) entry;
                            row.getInt("Count");
                        });
                    }
                }

                static void mutate(CompoundTag root) {
                    root.getList("Entries", Tag.TAG_COMPOUND).forEach(entry ->
                        ((CompoundTag) entry).putInt("Count", 1)
                    );
                }
            }
            """.trimIndent()
        )

        ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
        assertEquals(
            0,
            Regex("""read\(first\);\s*if \(\(first\)\.isEmpty\(\)\)""")
                .findAll(migrated)
                .count(),
            migrated
        )
        assertTrue(
            Regex("""mutate\(second\);\s*if \(\(second\)\.isEmpty\(\)\)""")
                .containsMatchIn(migrated),
            migrated
        )
    }

    @Test
    fun `project nested tag view escape keeps the source graph untouched`() {
        val source = writeJava(
            "ProjectNestedTagEscape.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.Tag;
            import net.minecraft.world.item.ItemStack;

            class ProjectNestedTagEscape {
                static CompoundTag escaped;

                void apply(ItemStack stack) {
                    CompoundTag root = stack.getOrCreateTag();
                    inspect(root);
                }

                static void inspect(CompoundTag root) {
                    root.getList("Entries", Tag.TAG_COMPOUND).forEach(entry ->
                        escaped = (CompoundTag) entry
                    );
                }
            }
            """.trimIndent()
        )
        val original = source.readText()

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertEquals(original, source.readText())
    }

    @Test
    fun `project mutable primitive array tag views keep the source graph untouched`() {
        val source = writeJava(
            "ProjectMutableArrayTagView.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class ProjectMutableArrayTagView {
                void apply(ItemStack stack) {
                    CompoundTag root = stack.getOrCreateTag();
                    mutate(root);
                }

                static void mutate(CompoundTag root) {
                    byte[] bytes = root.getByteArray("Bytes");
                    bytes[0] = 1;
                }
            }
            """.trimIndent()
        )
        val original = source.readText()

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertEquals(original, source.readText())
    }

    @Test
    fun `project method tag escape keeps the source graph untouched`() {
        val source = writeJava(
            "ProjectTagEscape.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class ProjectTagEscape {
                CompoundTag expose(ItemStack stack) {
                    CompoundTag tag = stack.getOrCreateTag();
                    return identity(tag);
                }

                static CompoundTag identity(CompoundTag tag) {
                    return tag;
                }
            }
            """.trimIndent()
        )
        val original = source.readText()

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)

        assertTrue(changes.isEmpty())
        assertEquals(original, source.readText())
    }

    @Test
    fun `nested compound and list values migrate only when every use is read only`() {
        val source = writeJava(
            "NestedTagReads.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.ListTag;
            import net.minecraft.nbt.Tag;
            import net.minecraft.world.item.ItemStack;
            import net.createmod.catnip.nbt.NBTHelper;
            import java.util.Objects;

            class NestedTagReads {
                int child(ItemStack stack) {
                    CompoundTag child = stack.getOrCreateTag().getCompound("Child");
                    return child.getInt("Count");
                }

                int list(ItemStack stack) {
                    ListTag values = stack.getOrCreateTag().getList("Values", Tag.TAG_COMPOUND);
                    int count = 0;
                    for (Tag value : values) {
                        count++;
                    }
                    return count;
                }

                ItemStack parsed(ItemStack stack) {
                    CompoundTag root = stack.getOrCreateTag();
                    return ItemStack.of(root.getCompound("Stored"));
                }

                boolean compared(ItemStack stack) {
                    CompoundTag root = stack.getOrCreateTag();
                    return Objects.equals(new CompoundTag(), root.get("Stored"));
                }

                int casted(ItemStack stack) {
                    ListTag values =
                        stack.getOrCreateTag().getList("Values", Tag.TAG_COMPOUND);
                    return read((CompoundTag) values.get(0));
                }

                static int read(CompoundTag tag) {
                    return tag.getInt("Count");
                }

                Object catnip(ItemStack stack) {
                    CompoundTag root = stack.getOrCreateTag();
                    return NBTHelper.readCompoundList(
                        root.getList("Entries", Tag.TAG_COMPOUND),
                        entry -> entry
                    );
                }

                void catnipIteration(ItemStack stack) {
                    CompoundTag root = stack.getOrCreateTag();
                    NBTHelper.iterateCompoundList(
                        root.getList("Entries", Tag.TAG_COMPOUND),
                        entry -> entry.getInt("Count")
                    );
                }
            }
            """.trimIndent()
        )

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertEquals(1, changes.size)
        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
        assertTrue(migrated.contains(".copyTag().getCompound(\"Child\")"), migrated)
        assertTrue(migrated.contains(".copyTag().getList(\"Values\""), migrated)
        assertTrue(migrated.contains("ItemStack.of(root.getCompound(\"Stored\"))"), migrated)
    }

    @Test
    fun `project generic inherited fields preserve their exact item stack type`() {
        writeJava(
            "GenericHolder.java",
            """
            package com.example;

            class GenericHolder<T> {
                public T contentHolder;
            }
            """.trimIndent()
        )
        writeJava(
            "ItemMenu.java",
            """
            package com.example;

            import net.minecraft.world.item.ItemStack;

            class ItemMenu extends GenericHolder<ItemStack> {
            }
            """.trimIndent()
        )
        val source = writeJava(
            "GenericHolderScreen.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;

            class GenericHolderScreen {
                GenericHolderScreen(ItemMenu menu) {
                    CompoundTag tag =
                        menu.contentHolder.getOrCreateTag().getCompound("Stored");
                    if (!tag.isEmpty()) {
                        tag.getInt("Count");
                    }
                }
            }
            """.trimIndent()
        )

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertEquals(1, changes.size, migrated)
        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
        assertTrue(migrated.contains("menu.contentHolder).getOrDefault"), migrated)
    }

    @Test
    fun `closed vanilla item stack accessor contracts prove method receivers`() {
        val source = writeJava(
            "VanillaItemStackAccessors.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.item.ItemEntity;
            import net.minecraft.world.item.context.UseOnContext;

            class VanillaItemStackAccessors {
                void context(UseOnContext context) {
                    CompoundTag tag = context.getItemInHand().getOrCreateTag();
                    tag.putInt("Count", 1);
                }

                int entity(ItemEntity entity) {
                    CompoundTag tag = entity.getItem().getOrCreateTag();
                    return tag.getInt("Count");
                }
            }
            """.trimIndent()
        )

        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertEquals(1, changes.size, migrated)
        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
        assertTrue(
            migrated.contains(
                "net.minecraft.world.item.ItemStack modPorterCustomDataOwner = " +
                    "context.getItemInHand();"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "net.minecraft.world.item.ItemStack modPorterCustomDataOwner = " +
                    "entity.getItem();"
            ),
            migrated
        )
    }

    @Test
    fun `receiver reassignment snapshots the original owner while lookalikes remain untouched`() {
        val source = writeJava(
            "AmbiguousCustomData.java",
            """
            package com.example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class AmbiguousCustomData {
                void reassigned(ItemStack stack, ItemStack replacement) {
                    CompoundTag tag = stack.getOrCreateTag();
                    stack = replacement;
                    tag.putInt("Count", 1);
                }

                int lookalike(Box box) {
                    return box.getOrCreateTag().getInt("Count");
                }

                static class Box {
                    CompoundTag getOrCreateTag() {
                        return new CompoundTag();
                    }
                }
            }
            """.trimIndent()
        )
        val changes = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        val migrated = source.readText()

        assertEquals(1, changes.size)
        assertTrue(
            Regex(
                """net\.minecraft\.world\.item\.ItemStack\s+modPorterCustomDataOwner\s*=\s*stack;"""
            ).containsMatchIn(migrated),
            migrated
        )
        assertTrue(migrated.contains("stack = replacement;"), migrated)
        assertTrue(
            migrated.contains(
                "(modPorterCustomDataOwner).set(" +
                    "net.minecraft.core.component.DataComponents.CUSTOM_DATA"
            ),
            migrated
        )
        assertTrue(migrated.contains("return box.getOrCreateTag().getInt(\"Count\");"), migrated)
    }

    @Test
    fun `compound tag equality reads its argument after a detached copy and merge`() {
        val source = writeJava(
            "TagEquality.java",
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            class TagEquality {
                boolean matches(ItemStack stack, CompoundTag expected) {
                    CompoundTag tag = stack.getOrCreateTag();
                    return tag.copy().merge(expected).equals(tag);
                }
            }
            """.trimIndent()
        )
        val result = ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false)
        assertEquals(1, result.size)
        val migrated = source.readText()
        assertTrue(!migrated.contains("getOrCreateTag"), migrated)
        assertTrue(migrated.contains("return tag.copy().merge(expected).equals(tag);"), migrated)
        assertTrue(ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false).isEmpty())
    }

    @Test
    fun `equality on an unknown receiver never acquires the compound tag read contract`() {
        val source = writeJava(
            "UnknownEquality.java",
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            class UnknownEquality {
                boolean matches(ItemStack stack, Object observer) {
                    CompoundTag tag = stack.getOrCreateTag();
                    return observer.equals(tag);
                }
            }
            """.trimIndent()
        )
        val before = source.readText()
        assertTrue(ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false).isEmpty())
        assertEquals(before, source.readText())
    }

    @Test
    fun `project declared compound tag cannot acquire target copy merge or equality contracts`() {
        val shadow = tempDir.resolve("src/main/java/net/minecraft/nbt/CompoundTag.java")
        shadow.parent.createDirectories()
        shadow.writeText(
            """
            package net.minecraft.nbt;
            public class CompoundTag {
                public CompoundTag copy() { return this; }
                public CompoundTag merge(CompoundTag value) { return this; }
                public boolean equals(Object value) { return false; }
            }
            """.trimIndent()
        )
        val source = writeJava(
            "ShadowEquality.java",
            """
            package com.example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            class ShadowEquality {
                boolean matches(ItemStack stack, CompoundTag expected) {
                    CompoundTag tag = stack.getOrCreateTag();
                    return tag.copy().merge(expected).equals(tag);
                }
            }
            """.trimIndent()
        )
        val before = source.readText()
        assertTrue(ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false).isEmpty())
        assertEquals(before, source.readText())
    }

    @Test
    fun `wrong copy or merge signatures do not prove a compound equality receiver`() {
        for ((index, expression) in listOf(
            "tag.copy(expected).merge(expected)",
            "tag.copy().merge(expected, expected)",
            "tag.copy().merge(1)"
        ).withIndex()) {
            val source = writeJava(
                "WrongEquality$index.java",
                """
                package com.example;
                import net.minecraft.nbt.CompoundTag;
                import net.minecraft.world.item.ItemStack;
                class WrongEquality$index {
                    boolean matches(ItemStack stack, CompoundTag expected) {
                        CompoundTag tag = stack.getOrCreateTag();
                        return $expression.equals(tag);
                    }
                }
                """.trimIndent()
            )
            val before = source.readText()
            assertTrue(ExactLegacyCustomDataMigration().migrate(tempDir, dryRun = false).isEmpty())
            assertEquals(before, source.readText())
        }
    }

    private fun writeJava(name: String, source: String): Path {
        val directory = tempDir.resolve("src/main/java/com/example")
        directory.createDirectories()
        return directory.resolve(name).also { it.writeText(source) }
    }
}
