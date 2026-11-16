package com.modporter.transforms

import com.google.gson.JsonParseException
import com.modporter.core.pipeline.Change
import com.modporter.core.transforms.structural.LegacyEffectComponentSurfaces
import com.modporter.core.transforms.structural.StructuralRefactorPass
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyEffectComponentSurfacesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `legacy effect and component calls port by their proven receiver API`() {
        write(
            "example/fluid/PotionTank.java",
            """
            package example.fluid;

            import net.neoforged.neoforge.fluids.FluidStack;

            class PotionTank {
                static final String KEY = "Potion";

                static FluidStack clear(FluidStack fluid, boolean empty) {
                    if (empty) {
                        fluid.removeChildTag("Potion");
                        return fluid;
                    }
                    if (fluid.isEmpty())
                        fluid.removeChildTag(KEY);
                    return fluid;
                }

                static String taken() {
                    String modporterChildData = "taken";
                    return modporterChildData;
                }
            }
            """
        )
        write(
            "example/effect/MilkCloud.java",
            """
            package example.effect;

            import java.util.List;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;

            class MilkCloud {
                boolean apply(List<LivingEntity> entities, Player player) {
                    ItemStack cure = new ItemStack(Items.MILK_BUCKET);
                    for (LivingEntity entity : entities) {
                        entity.curePotionEffects(cure);
                    }
                    return player.curePotionEffects(new ItemStack(Items.MILK_BUCKET));
                }
            }
            """
        )
        write(
            "example/effect/BottleEffects.java",
            """
            package example.effect;

            import java.util.Collection;
            import java.util.List;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.alchemy.PotionContents;

            class BottleEffects {
                static List<MobEffectInstance> effects(ItemStack bottle) {
                    return bottle.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).getAllEffects();
                }

                static Collection<MobEffectInstance> stored(PotionContents contents) {
                    Collection<MobEffectInstance> result = contents.getAllEffects();
                    return result;
                }

                static int count(PotionContents contents) {
                    int count = 0;
                    for (MobEffectInstance effect : contents.getAllEffects())
                        count++;
                    return count;
                }
            }
            """
        )
        write(
            "example/item/Display.java",
            """
            package example.item;

            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.ItemStack.TooltipPart;

            class Display {
                static void hide(ItemStack tool, ItemStack book, ItemStack banner) {
                    tool.hideTooltipPart(TooltipPart.MODIFIERS);
                    book.hideTooltipPart(ItemStack.TooltipPart.ENCHANTMENTS);
                    banner.hideTooltipPart(TooltipPart.ADDITIONAL);
                    banner.hideTooltipPart(TooltipPart.DYE);
                }
            }
            """
        )
        write(
            "example/feed/Feeder.java",
            """
            package example.feed;

            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.food.FoodProperties;
            import net.minecraft.world.item.ItemStack;

            class Feeder {
                static boolean feed(ItemStack stack, Player player) {
                    if (stack.isEdible()) {
                        FoodProperties food = stack.getItem().getFoodProperties(stack, player);
                        return player.canEat(food.canAlwaysEat());
                    }
                    return false;
                }
            }
            """
        )
        write(
            "example/audit/PageAudit.java",
            """
            package example.audit;

            import java.util.List;
            import net.createmod.catnip.nbt.NBTProcessors;
            import net.minecraft.nbt.Tag;
            import net.minecraft.network.chat.Component;

            public class PageAudit {
                public static boolean page(Tag page) {
                    return NBTProcessors.textComponentHasClickEvent(page.getAsString());
                }

                public static boolean entries(List<String> entries) {
                    for (String entry : entries)
                        if (NBTProcessors.textComponentHasClickEvent(entry))
                            return true;
                    return false;
                }

                public static boolean component(Component component) {
                    return NBTProcessors.textComponentHasClickEvent(component);
                }
            }
            """
        )
        val before = snapshot()

        assertEquals(6, migrate(dryRun = true).size)
        assertEquals(before, snapshot())
        val changes = migrate(dryRun = false)
        assertEquals(6, changes.size)
        assertTrue(changes.all { it.ruleId == LegacyEffectComponentSurfaces.RULE_ID })

        val fluid = read("example/fluid/PotionTank.java")
        assertFalse(fluid.contains("removeChildTag"), fluid)
        assertEquals(2, Regex("""modporterChildData2\.copyTag\(\)""").findAll(fluid).count(), fluid)
        assertTrue(
            compact(fluid).contains(
                "net.minecraft.world.item.component.CustomData modporterChildData2 = " +
                    "fluid.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);"
            ),
            fluid
        )
        assertTrue(compact(fluid).contains("if (modporterChildData2 != null) {"), fluid)
        assertTrue(compact(fluid).contains("modporterChildTag.remove(\"Potion\");"), fluid)
        assertTrue(compact(fluid).contains("modporterChildTag.remove(KEY);"), fluid)
        assertTrue(
            compact(fluid).contains(
                "fluid.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, " +
                    "net.minecraft.world.item.component.CustomData.of(modporterChildTag));"
            ),
            fluid
        )
        assertTrue(compact(fluid).contains("if (fluid.isEmpty()) {"), fluid)
        assertFalse(fluid.contains("new net.minecraft.nbt.CompoundTag"), fluid)

        val milk = read("example/effect/MilkCloud.java")
        assertFalse(milk.contains("curePotionEffects"), milk)
        assertFalse(milk.contains("new ItemStack"), milk)
        assertFalse(milk.contains("ItemStack cure"), milk)
        assertFalse(milk.contains("import net.minecraft.world.item.ItemStack;"), milk)
        assertFalse(milk.contains("import net.minecraft.world.item.Items;"), milk)
        assertTrue(milk.contains("import net.neoforged.neoforge.common.EffectCures;"), milk)
        assertTrue(milk.contains("entity.removeEffectsCuredBy(EffectCures.MILK);"), milk)
        assertTrue(milk.contains("return player.removeEffectsCuredBy(EffectCures.MILK);"), milk)
        assertTrue(
            milk.contains("    boolean apply(List<LivingEntity> entities, Player player) {\n        for"),
            milk
        )

        val effects = read("example/effect/BottleEffects.java")
        val materialized = "java.util.stream.StreamSupport.stream(%s.spliterator(), false)" +
            ".collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new))"
        assertTrue(
            effects.contains(
                "return " + materialized.format(
                    "bottle.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).getAllEffects()"
                ) + ";"
            ),
            effects
        )
        assertTrue(
            effects.contains(
                "Collection<MobEffectInstance> result = " +
                    materialized.format("contents.getAllEffects()") + ";"
            ),
            effects
        )
        assertTrue(effects.contains("for (MobEffectInstance effect : contents.getAllEffects())"), effects)

        val display = read("example/item/Display.java")
        assertTrue(
            display.contains(
                "tool.set(DataComponents.ATTRIBUTE_MODIFIERS, tool.getOrDefault(" +
                    "DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).withTooltip(false));"
            ),
            display
        )
        assertTrue(
            display.contains(
                "book.set(DataComponents.ENCHANTMENTS, book.getOrDefault(" +
                    "DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).withTooltip(false));"
            ),
            display
        )
        assertTrue(
            display.contains("banner.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);"),
            display
        )
        assertTrue(display.contains("banner.hideTooltipPart(TooltipPart.DYE);"), display)
        assertTrue(display.contains("import net.minecraft.world.item.ItemStack.TooltipPart;"), display)
        listOf(
            "net.minecraft.core.component.DataComponents",
            "net.minecraft.world.item.component.ItemAttributeModifiers",
            "net.minecraft.world.item.enchantment.ItemEnchantments",
            "net.minecraft.util.Unit"
        ).forEach { assertTrue(display.contains("import $it;"), display) }

        val feeder = read("example/feed/Feeder.java")
        assertTrue(feeder.contains("if (stack.has(DataComponents.FOOD)) {"), feeder)
        assertTrue(feeder.contains("import net.minecraft.core.component.DataComponents;"), feeder)
        // 1.20.1 dereferenced the looked-up food properties without a guard; so does the port.
        assertTrue(feeder.contains("return player.canEat(food.canAlwaysEat());"), feeder)

        val audit = read("example/audit/PageAudit.java")
        assertTrue(
            audit.contains("return modporter\$legacyTextComponentHasClickEvent(page.getAsString());"),
            audit
        )
        assertTrue(audit.contains("if (modporter\$legacyTextComponentHasClickEvent(entry))"), audit)
        assertTrue(audit.contains("return NBTProcessors.textComponentHasClickEvent(component);"), audit)
        assertTrue(audit.contains("import net.createmod.catnip.nbt.NBTProcessors;"), audit)
        assertEquals(
            2,
            Regex("""private static boolean\s+modporter\${'$'}legacyTextComponentHasClickEvent\(""")
                .findAll(audit).count(),
            audit
        )
        assertTrue(audit.contains("    private static final class modporter\$LegacyTextComponent {"), audit)
        assertTrue(audit.contains("reader.setLenient(false);"), audit)
        assertFalse(audit.contains("RegistryAccess.EMPTY"), audit)

        val migrated = snapshot()
        assertTrue(migrate(dryRun = false).isEmpty())
        assertEquals(migrated, snapshot())
    }

    @Test
    fun `same method names on non platform receivers stay unchanged`() {
        val source =
            """
            package example.lookalike;

            import java.util.List;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;

            class Tank {
                void removeChildTag(String key) {}
            }

            class Creature {
                boolean curePotionEffects(ItemStack stack) { return false; }
            }

            class Contents {
                List<String> getAllEffects() { return null; }
            }

            class Card {
                void hideTooltipPart(Object part) {}
                boolean isEdible() { return true; }
            }

            class NBTProcessors {
                static boolean textComponentHasClickEvent(String json) { return false; }
            }

            class Uses {
                List<String> run(Tank tank, Creature creature, Contents contents, Card card) {
                    tank.removeChildTag("Potion");
                    creature.curePotionEffects(new ItemStack(Items.MILK_BUCKET));
                    card.hideTooltipPart(null);
                    boolean edible = card.isEdible();
                    boolean click = NBTProcessors.textComponentHasClickEvent("{}");
                    return contents.getAllEffects();
                }
            }
            """.trimIndent() + "\n"
        write("example/lookalike/Uses.java", source)

        assertTrue(migrate(dryRun = false).isEmpty())
        assertEquals(source, read("example/lookalike/Uses.java"))
    }

    @Test
    fun `platform receivers without an exact replacement stay unchanged`() {
        val source =
            """
            package example.partial;

            import java.util.List;
            import java.util.function.Supplier;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.PotionContents;
            import net.neoforged.neoforge.fluids.FluidStack;

            class Partial {
                ItemStack held;

                void run(LivingEntity entity, ItemStack dynamic, FluidStack fluid, PotionContents contents) {
                    entity.curePotionEffects(new ItemStack(Items.HONEY_BOTTLE));
                    entity.curePotionEffects(dynamic);
                    ItemStack reassigned = new ItemStack(Items.MILK_BUCKET);
                    reassigned = dynamic;
                    entity.curePotionEffects(reassigned);
                    ItemStack shared = new ItemStack(Items.MILK_BUCKET);
                    shared.setCount(0);
                    entity.curePotionEffects(shared);
                    entity.curePotionEffects(held);
                    fluid.removeChildTag(key());
                    Supplier<List<MobEffectInstance>> lazy = () -> contents.getAllEffects();
                    held.hideTooltipPart(ItemStack.TooltipPart.UNBREAKABLE);
                    copy().hideTooltipPart(ItemStack.TooltipPart.MODIFIERS);
                }

                String key() {
                    return "Potion";
                }

                ItemStack copy() {
                    return held;
                }
            }
            """.trimIndent() + "\n"
        write("example/partial/Partial.java", source)

        assertTrue(migrate(dryRun = false).isEmpty())
        assertEquals(source, read("example/partial/Partial.java"))
    }

    @Test
    fun `project overrides keep the dispatching legacy calls`() {
        val source =
            """
            package example.gate;

            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.level.Level;

            abstract class Ghost extends LivingEntity {
                protected Ghost(EntityType<? extends LivingEntity> type, Level level) {
                    super(type, level);
                }

                public boolean curePotionEffects(ItemStack stack) {
                    return false;
                }
            }

            class Snack extends Item {
                Snack(Item.Properties properties) {
                    super(properties);
                }

                public boolean isEdible() {
                    return true;
                }
            }

            class Uses {
                boolean run(LivingEntity entity, ItemStack stack) {
                    entity.curePotionEffects(new ItemStack(Items.MILK_BUCKET));
                    return stack.isEdible();
                }
            }
            """.trimIndent() + "\n"
        write("example/gate/Uses.java", source)

        assertTrue(migrate(dryRun = false).isEmpty())
        assertEquals(source, read("example/gate/Uses.java"))
    }

    @Test
    fun `static imported audit and tab indented CRLF sources keep their layout`() {
        val source = listOf(
            "package example.crlf;",
            "",
            "import static net.createmod.catnip.nbt.NBTProcessors.textComponentHasClickEvent;",
            "",
            "import net.minecraft.world.item.ItemStack;",
            "import net.neoforged.neoforge.fluids.FluidStack;",
            "",
            "enum Pages {",
            "\tFIRST;",
            "",
            "\tboolean audit(String json, FluidStack fluid, ItemStack stack) {",
            "\t\tfluid.removeChildTag(\"Pages\");",
            "\t\treturn stack.isEdible() && textComponentHasClickEvent(json);",
            "\t}",
            "}",
            ""
        ).joinToString("\r\n")
        val file = directory.resolve("src/main/java/example/crlf/Pages.java")
        file.parent.createDirectories()
        file.writeText(source)

        assertEquals(1, migrate(dryRun = false).size)
        val migrated = file.readText()
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(migrated), migrated)
        assertTrue(
            migrated.contains(
                "\t\treturn stack.has(DataComponents.FOOD) && " +
                    "modporter\$legacyTextComponentHasClickEvent(json);\r\n"
            ),
            migrated
        )
        assertTrue(migrated.contains("import net.minecraft.core.component.DataComponents;\r\n"), migrated)
        assertTrue(
            migrated.contains(
                "\t\t{\r\n\t\t\tnet.minecraft.world.item.component.CustomData modporterChildData = " +
                    "fluid.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);\r\n"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "\r\n\tprivate static boolean modporter\$legacyTextComponentHasClickEvent(String json) {\r\n" +
                    "\t\tString normalized"
            ),
            migrated
        )
        assertTrue(migrated.endsWith("\t}\r\n}\r\n"), migrated)

        val again = file.readText()
        assertTrue(migrate(dryRun = false).isEmpty())
        assertEquals(again, file.readText())
    }

    @Test
    fun `stored text audit keeps the strict legacy JSON acceptance`() {
        write(
            "example/audit/StoredText.java",
            """
            package example.audit;

            import net.createmod.catnip.nbt.NBTProcessors;
            import net.minecraft.nbt.Tag;

            public class StoredText {
                public static boolean audit(String entry) {
                    return NBTProcessors.textComponentHasClickEvent(entry);
                }

                public static boolean audit(Tag entry) {
                    return NBTProcessors.textComponentHasClickEvent(
                        entry.getAsString());
                }
            }
            """
        )
        write(
            "net/createmod/catnip/nbt/NBTProcessors.java",
            """
            package net.createmod.catnip.nbt;
            public final class NBTProcessors {}
            """
        )
        write(
            "net/minecraft/nbt/Tag.java",
            """
            package net.minecraft.nbt;
            public interface Tag {
                String getAsString();
            }
            """
        )

        assertEquals(1, migrate(dryRun = false).size)
        assertFalse(read("example/audit/StoredText.java").contains("NBTProcessors"))
        val classes = compileJavaSources()
        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = loader.loadClass("example.audit.StoredText")
            val audit = owner.getDeclaredMethod("audit", String::class.java)

            fun hasClick(json: String): Boolean = audit.invoke(null, json) as Boolean

            fun failure(json: String): Throwable =
                assertFailsWith<InvocationTargetException> { hasClick(json) }.cause
                    ?: error("reflection failure had no cause")

            fun assertJsonFailure(json: String) {
                val cause = failure(json)
                assertTrue(cause is JsonParseException, cause.toString())
            }

            assertFalse(hasClick(""))
            assertTrue(
                hasClick(
                    """{"text":"root","clickEvent":{"action":"run_command","value":"/say root"}}"""
                )
            )
            assertTrue(
                hasClick(
                    """[{"text":"safe"},{"text":"array","clickEvent":""" +
                        """{"action":"open_url","value":"https://example.invalid"}}]"""
                )
            )
            assertTrue(
                hasClick(
                    """{"text":"safe","extra":[{"text":"nested","extra":[""" +
                        """{"text":"click","clickEvent":{"action":"suggest_command","value":"/help"}}]}]}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"text":"hover","hoverEvent":{"action":"show_text","contents":""" +
                        """{"text":"hidden","clickEvent":{"action":"run_command","value":"/say hidden"}}}}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"translate":"key","with":[{"text":"argument","clickEvent":""" +
                        """{"action":"run_command","value":"/say argument"}}]}"""
                )
            )
            assertFalse(
                hasClick(
                    """{"text":"metadata","custom":{"clickEvent":""" +
                        """{"action":"run_command","value":"/say metadata"}}}"""
                )
            )
            assertFalse(
                hasClick("""{"text":"file","clickEvent":{"action":"open_file","value":"secret.txt"}}""")
            )
            assertFalse(
                hasClick(
                    """{"text":"first"} {"text":"ignored","clickEvent":""" +
                        """{"action":"run_command","value":"/ignored"}}"""
                )
            )
            assertFalse(hasClick("""{"text":"color","color":"not_a_legacy_color"}"""))
            assertFalse(hasClick("""{"text":"flag","bold":[true]}"""))
            assertFalse(hasClick("""{"translate":"key","with":[[]]}"""))
            assertFalse(
                hasClick("""{"text":"unknown-hover","hoverEvent":{"action":"unknown","contents":{"missing":"text"}}}""")
            )
            assertFalse(
                hasClick(
                    """{"text":"item-hover","hoverEvent":{"action":"show_item","contents":""" +
                        """{"id":"minecraft:stone","count":1,"tag":"definitely not snbt"}}}"""
                )
            )
            assertFalse(
                hasClick("""{"text":"entity-hover","hoverEvent":{"action":"show_entity","contents":"ignored"}}""")
            )
            assertTrue(
                hasClick(
                    """{"text":"root","extra":[{"text":"click","clickEvent":""" +
                        """{"action":"run_command","value":"/first"}},[]]}"""
                )
            )

            assertJsonFailure("""{"text":""")
            assertJsonFailure("""/* comment */{"text":"strict"}""")
            assertJsonFailure("""{text:"strict"}""")
            assertJsonFailure("""{'text':'strict'}""")
            assertJsonFailure("""{"extra":[{"text":"child"}]}""")
            assertJsonFailure("""{"text":"root","extra":[]}""")
            assertJsonFailure("""{"translate":"key","with":{}}""")
            assertJsonFailure("""{"score":{"name":"player"}}""")
            assertJsonFailure("""{"selector":"@a","separator":null}""")
            assertJsonFailure(
                """{"text":"root","extra":[{"text":"click","clickEvent":""" +
                    """{"action":"run_command","value":"/first"}},{"extra":[]}]}"""
            )
            assertJsonFailure("""{"nbt":"path","storage":"Bad Namespace:Path"}""")
            assertJsonFailure("""{"text":"font","font":"Bad:Font"}""")
            assertJsonFailure("""{"text":"click","clickEvent":"wrong"}""")
            assertJsonFailure("""{"text":"click","clickEvent":{"action":null,"value":"x"}}""")
            assertJsonFailure(
                """{"text":"item","hoverEvent":{"action":"show_item","contents":""" +
                    """{"id":"minecraft:stone","count":"1"}}}"""
            )

            assertTrue(failure("null") is NullPointerException)
            assertTrue(failure("[]") is NullPointerException)
            assertTrue(failure("""{"text":"root","extra":[[]]}""") is NullPointerException)
            assertTrue(failure("""{"selector":"@a","separator":[]}""") is NullPointerException)
            assertTrue(
                failure(
                    """{"text":"item","hoverEvent":{"action":"show_item",""" +
                        """"value":{"text":"legacy","extra":[[]]}}}"""
                ) is NullPointerException
            )
            assertTrue(
                failure(
                    """{"text":"entity","hoverEvent":{"action":"show_entity",""" +
                        """"contents":{"type":"minecraft:pig","id":"bad-uuid"}}}"""
                ) is IllegalArgumentException
            )
        }
    }

    @Test
    fun `provider-less component JSON writes use the provider the method declares`() {
        val source =
            """
            package example.text;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.network.chat.Component;

            class Label {
                Component component;

                CompoundTag write(HolderLookup.Provider registries) {
                    CompoundTag tag = new CompoundTag();
                    if (component != null)
                        tag.putString("Text", net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, component).getOrThrow().toString());
                    return tag;
                }

                String plain() {
                    return net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, component).getOrThrow().toString();
                }

                com.google.gson.JsonElement tree(HolderLookup.Provider registries) {
                    return net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, component).getOrThrow();
                }

                String other(HolderLookup.Provider registries, Codecs codecs) {
                    return codecs.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, component).getOrThrow().toString();
                }
            }

            class Codecs {
                Object CODEC;
            }
            """.trimIndent() + "\n"
        write("example/text/Label.java", source)

        assertEquals(1, migrate(dryRun = false).size)
        val migrated = read("example/text/Label.java")
        assertTrue(
            migrated.contains("tag.putString(\"Text\", Component.Serializer.toJson(component, registries));"),
            migrated
        )
        assertTrue(
            migrated.contains(
                "return net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(" +
                    "com.mojang.serialization.JsonOps.INSTANCE, component).getOrThrow().toString();"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "return net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(" +
                    "com.mojang.serialization.JsonOps.INSTANCE, component).getOrThrow();"
            ),
            migrated
        )
        assertTrue(migrated.contains("return codecs.CODEC.encodeStart("), migrated)
        assertTrue(migrate(dryRun = false).isEmpty())
        assertEquals(migrated, read("example/text/Label.java"))
    }

    @Test
    fun `component JSON reads keep the legacy blank and null contract`() {
        write(
            "example/text/StoredName.java",
            """
            package example.text;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.network.chat.Component;
            import net.minecraft.network.chat.MutableComponent;

            public class StoredName {
                public static MutableComponent read(String json, HolderLookup.Provider registries) {
                    return Component.Serializer.fromJson(json, registries);
                }

                public static MutableComponent lenient(String json, HolderLookup.Provider registries) {
                    return Component.Serializer.fromJsonLenient(json, registries);
                }

                public static MutableComponent tree(com.google.gson.JsonElement json, HolderLookup.Provider registries) {
                    return Component.Serializer.fromJson(json, registries);
                }
            }
            """
        )
        write(
            "net/minecraft/core/HolderLookup.java",
            """
            package net.minecraft.core;
            public interface HolderLookup {
                interface Provider {}
            }
            """
        )
        write(
            "net/minecraft/network/chat/MutableComponent.java",
            """
            package net.minecraft.network.chat;
            public class MutableComponent {
                public final com.google.gson.JsonElement json;
                public MutableComponent(com.google.gson.JsonElement json) { this.json = json; }
            }
            """
        )
        write(
            "net/minecraft/network/chat/Component.java",
            """
            package net.minecraft.network.chat;
            public interface Component {
                final class Serializer {
                    public static MutableComponent fromJson(
                            com.google.gson.JsonElement json, net.minecraft.core.HolderLookup.Provider registries) {
                        if (json == null) return null;
                        if (json.isJsonNull()) throw new com.google.gson.JsonParseException("not a component");
                        return new MutableComponent(json);
                    }
                    public static MutableComponent fromJson(
                            String json, net.minecraft.core.HolderLookup.Provider registries) {
                        throw new AssertionError("the 1.21.1 string parser is not the 1.20.1 contract");
                    }
                    public static MutableComponent fromJsonLenient(
                            String json, net.minecraft.core.HolderLookup.Provider registries) {
                        throw new AssertionError("the 1.21.1 lenient parser is not the 1.20.1 contract");
                    }
                }
            }
            """
        )

        assertEquals(1, migrate(dryRun = false).size)
        val migrated = read("example/text/StoredName.java")
        assertEquals(
            2,
            migrated.split("return Component.Serializer.fromJson(modporter\$legacyComponentJson(json, false), registries);").size - 1,
            migrated
        )
        assertTrue(
            migrated.contains("return Component.Serializer.fromJson(modporter\$legacyComponentJson(json, true), registries);"),
            migrated
        )
        assertFalse(migrated.contains("fromJsonLenient"), migrated)
        assertTrue(migrate(dryRun = false).isEmpty())
        assertEquals(migrated, read("example/text/StoredName.java"))

        val classes = compileJavaSources()
        URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader).use { loader ->
            val owner = loader.loadClass("example.text.StoredName")
            val provider = loader.loadClass("net.minecraft.core.HolderLookup\$Provider")
            val read = owner.getDeclaredMethod("read", String::class.java, provider)
            val lenient = owner.getDeclaredMethod("lenient", String::class.java, provider)
            val tree = owner.getDeclaredMethod("tree", com.google.gson.JsonElement::class.java, provider)

            fun json(result: Any?): String? =
                result?.let { it.javaClass.getField("json").get(it).toString() }

            fun failure(method: java.lang.reflect.Method, argument: Any?): Throwable =
                assertFailsWith<InvocationTargetException> { method.invoke(null, argument, null) }.cause
                    ?: error("reflection failure had no cause")

            listOf("", "   ", " \n\t", "null", "  null  ", "null }").forEach {
                assertEquals(null, read.invoke(null, it, null), "'$it'")
            }
            assertEquals("""{"text":"a"}""", json(read.invoke(null, """{"text":"a"}""", null)))
            assertEquals("\"plain\"", json(read.invoke(null, "\"plain\"", null)))
            // one value is read; 1.20.1 never looked past it
            assertEquals("""{"text":"a"}""", json(read.invoke(null, """{"text":"a"} trailing""", null)))
            listOf("""{text:"a"}""", "nul", """{"text":""", "/* note */ {}").forEach {
                val cause = failure(read, it)
                assertTrue(cause is com.google.gson.JsonSyntaxException, "'$it' -> $cause")
            }
            assertTrue(failure(read, null) is NullPointerException)

            assertEquals(null, lenient.invoke(null, "", null))
            assertEquals(null, lenient.invoke(null, "null", null))
            assertEquals("""{"text":"a"}""", json(lenient.invoke(null, """{text:"a"}""", null)))

            assertEquals(null, tree.invoke(null, null, null))
            assertEquals(null, tree.invoke(null, com.google.gson.JsonNull.INSTANCE, null))
            assertEquals(
                """{"text":"b"}""",
                json(tree.invoke(null, com.google.gson.JsonParser.parseString("""{"text":"b"}"""), null))
            )
        }
    }

    private fun migrate(dryRun: Boolean): List<Change> =
        LegacyEffectComponentSurfaces.migrate(StructuralRefactorPass(), directory, dryRun)

    private fun compileJavaSources(): Path {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) {
            "Tests require a JDK Java compiler"
        }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val classes = directory.resolve("compiled")
        classes.createDirectories()
        compiler.getStandardFileManager(diagnostics, null, null).use { manager ->
            val success = compiler.getTask(
                null,
                manager,
                diagnostics,
                listOf("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                null,
                manager.getJavaFileObjectsFromPaths(javaFiles())
            ).call()
            assertTrue(success, diagnostics.diagnostics.joinToString("\n") { it.toString() })
        }
        return classes
    }

    private fun javaFiles(): List<Path> {
        val root = directory.resolve("src/main/java")
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }.sorted().toList()
        }
    }

    private fun snapshot(): Map<Path, String> = javaFiles().associateWith { it.readText() }

    private fun write(relative: String, source: String) {
        val file = directory.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent() + "\n")
    }

    private fun read(relative: String): String = directory.resolve("src/main/java").resolve(relative).readText()

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .trim()
}
