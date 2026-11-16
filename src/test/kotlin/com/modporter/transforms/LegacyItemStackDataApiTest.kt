package com.modporter.transforms

import com.modporter.core.transforms.structural.PlayerInteractCancellation
import com.modporter.core.transforms.structural.ProjectValueFacts
import com.modporter.core.transforms.structural.SourceValueTypes
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateCustomDataComponentsSource
import com.modporter.core.transforms.structural.migrateHurtAndBreakEquipmentSlotSource
import com.modporter.core.transforms.structural.migrateLegacyItemDisplayNameWrites
import com.modporter.core.transforms.structural.migrateLegacyItemStackTagWrites
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class LegacyItemStackDataApiTest {
    private val pass = StructuralRefactorPass()

    private fun compact(source: String): String = source.replace(Regex("""\s+"""), " ").trim()

    @Test
    fun `base player interact events cancel through the cancellable subevents`() {
        val source = """
            package example;

            import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

            class Handler {
                static void onInteract(PlayerInteractEvent event) {
                    if (event.getEntity() == null)
                        return;
                    event.setCanceled(true);
                }
                static void onBlock(PlayerInteractEvent.RightClickBlock click) {
                    click.setCanceled(true);
                }
            }
        """.trimIndent()

        val migrated = PlayerInteractCancellation.migrate(source)

        assertTrue(
            compact(migrated).contains(
                "if (event instanceof net.neoforged.bus.api.ICancellableEvent modporterCancellable) modporterCancellable.setCanceled(true);"
            ),
            migrated
        )
        assertTrue(migrated.contains("click.setCanceled(true);"), migrated)
        assertEquals(migrated, PlayerInteractCancellation.migrate(migrated))
    }

    @Test
    fun `child tag reads on any proven stack expression read the custom data child`() {
        val source = """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;

            class Reader {
                static boolean copied(Player player) {
                    CompoundTag values = player.getMainHandItem().getTagElement("CopiedValues");
                    return values != null;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateCustomDataComponentsSource(source)

        assertTrue(migrated.contains("getCustomDataChild(player.getMainHandItem(), \"CopiedValues\")"), migrated)
        assertTrue(migrated.contains("private static CompoundTag getCustomDataChild(ItemStack stack, String key)"), migrated)
        assertFalse(migrated.contains(".getTagElement("), migrated)
        assertEquals(migrated, pass.migrateCustomDataComponentsSource(migrated))
    }

    @Test
    fun `setTag keeps the forge null meaning and reaches typed field receivers`() {
        val typedPass = StructuralRefactorPass().apply {
            projectValueFacts = ProjectValueFacts(
                fields = mapOf("example.Holder" to mapOf("container" to "net.minecraft.world.item.ItemStack")),
                types = setOf("example.Holder", "example.Writer")
            )
        }
        val source = """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class Writer {
                static void write(Holder holder, ItemStack stack, CompoundTag data) {
                    holder.container.setTag(data.isEmpty() ? null : data);
                    stack.setTag(null);
                    stack.setTag(new CompoundTag());
                }
            }
        """.trimIndent()

        val migrated = typedPass.migrateLegacyItemStackTagWrites(source)
        val text = compact(migrated)

        assertTrue(
            text.contains(
                "{ net.minecraft.nbt.CompoundTag modporterTag = data.isEmpty() ? null : data; " +
                    "if (modporterTag == null) holder.container.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA); " +
                    "else holder.container.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.of(modporterTag)); }"
            ),
            migrated
        )
        assertTrue(text.contains("stack.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);"), migrated)
        assertTrue(
            text.contains(
                "stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(new CompoundTag()));"
            ),
            migrated
        )
        assertFalse(migrated.contains(".setTag("), migrated)
    }

    @Test
    fun `setTag of a tag its enclosing null check requires writes the component directly`() {
        val source = """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;

            class Writer {
                static void write(ItemStack stack, CompoundTag tags, CompoundTag other, boolean keep) {
                    if (tags != null && keep) {
                        tags.remove("Damage");
                        stack.setTag(tags);
                    }
                    if (other != null) {
                        other = null;
                        stack.setTag(other);
                    }
                    if (tags != null || keep) {
                        stack.setTag(tags);
                    }
                }
            }
        """.trimIndent()

        val text = compact(StructuralRefactorPass().migrateLegacyItemStackTagWrites(source))

        assertTrue(
            text.contains(
                "tags.remove(\"Damage\"); stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.of(tags));"
            ),
            text
        )
        // reassigned after the check, or not required by it: the null meaning is kept
        assertTrue(text.contains("net.minecraft.nbt.CompoundTag modporterTag = other;"), text)
        assertTrue(text.contains("net.minecraft.nbt.CompoundTag modporterTag = tags;"), text)
    }

    @Test
    fun `a break callback slot read through its parameter reads the damaged entity`() {
        val source = """
            package example;

            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;

            class Polish {
                static void finish(ItemStack stack, LivingEntity entityLiving) {
                    stack.hurtAndBreak(1, entityLiving, p -> p.broadcastBreakEvent(p.getUsedItemHand()));
                }
            }
        """.trimIndent()

        val migrated = pass.migrateHurtAndBreakEquipmentSlotSource(source)

        assertTrue(
            compact(migrated).contains(
                "stack.hurtAndBreak(1, entityLiving, net.minecraft.world.entity.LivingEntity.getSlotForHand(entityLiving.getUsedItemHand()))"
            ),
            migrated
        )
        assertFalse(Regex("""\bp\.""").containsMatchIn(migrated), migrated)
    }

    @Test
    fun `display name writes of a serialized component become the custom name component`() {
        val source = """
            package example;

            import net.minecraft.network.chat.Component;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;

            class Books {
                static ItemStack named(Component name, String raw) {
                    ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
                    book.getOrCreateTagElement("display")
                        .putString("Name", net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, name).getOrThrow().toString());
                    book.getOrCreateTagElement("display").putString("Name", raw);
                    return book;
                }
            }
        """.trimIndent()

        val migrated = pass.migrateLegacyItemDisplayNameWrites(source)

        assertTrue(migrated.contains("book.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, name);"), migrated)
        assertTrue(migrated.contains("book.getOrCreateTagElement(\"display\").putString(\"Name\", raw);"), migrated)
        assertEquals(migrated, pass.migrateLegacyItemDisplayNameWrites(migrated))
    }

    @Test
    fun `a registry holder field reads its entry type through get`() {
        val typedPass = StructuralRefactorPass().apply {
            projectValueFacts = ProjectValueFacts(
                fields = mapOf(
                    "example.Items" to mapOf(
                        "FILTER" to "com.tterrag.registrate.util.entry.ItemEntry<example.ListFilter>",
                        "PLAIN" to "com.tterrag.registrate.util.entry.ItemEntry"
                    )
                ),
                types = setOf("example.Items", "example.ListFilter", "example.Menu")
            )
        }
        val source = """
            package example;

            class Menu {
                Object handler() {
                    return Items.FILTER.get();
                }
                Object plain() {
                    return Items.PLAIN.get();
                }
            }
        """.trimIndent()
        val typing = SourceValueTypes(typedPass, source)

        assertEquals("example.ListFilter", typing.typeOf("Items.FILTER.get()", source.indexOf("Items.FILTER")))
        assertEquals(null, typing.typeOf("Items.PLAIN.get()", source.indexOf("Items.PLAIN")))
    }
}
