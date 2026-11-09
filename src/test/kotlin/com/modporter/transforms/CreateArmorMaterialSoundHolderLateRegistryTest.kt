package com.modporter.core.transforms.build

import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateArmorMaterialSoundHolderLateRegistryTest {
    @TempDir
    lateinit var directory: Path

    private val fixtureRoot = Path.of(
        "src",
        "test",
        "resources",
        "fixtures",
        "create-armor-sound-holder"
    )

    @Test
    fun `Java 17 late registry preserves exact sound holders values and armor metadata`() {
        val enumSource = fixtureRoot.resolve("create")
            .resolve("AllArmorMaterials.pre-render.java")
            .readText()
        val producerPath =
            Path.of("src/main/java/com/simibubi/create/AllSoundEvents.java")
        val producerSource = fixtureRoot.resolve("runtime")
            .resolve("AllSoundEvents.java")
            .readText()
        val plan = LegacyArmorMaterialSoundHolderGraph.resolve(
            enumSource,
            mapOf(producerPath to producerSource)
        )
        val migratedProducer = plan.rewrittenSources.getValue(producerPath)
        val migratedArmor = requireNotNull(
            LegacyArmorMaterialEnums.render(
                enumSource,
                "com.simibubi.create.Create.ID",
                setOf("CARDBOARD"),
                plan.holderSuppliers
            )
        ).source

        val sources = listOf(
            writeJava("demo/Timeline.java", timelineSource()),
            writeJava("com/google/common/base/Suppliers.java", suppliersSource()),
            writeJava("net/minecraft/resources/ResourceKey.java", resourceKeySource()),
            writeJava("net/minecraft/resources/ResourceLocation.java", resourceLocationSource()),
            writeJava("net/minecraft/core/Holder.java", holderSource()),
            writeJava(
                "net/minecraft/core/registries/Registries.java",
                registriesSource()
            ),
            writeJava(
                "net/minecraft/core/registries/BuiltInRegistries.java",
                builtInRegistriesSource()
            ),
            writeJava(
                "net/neoforged/neoforge/registries/DeferredHolder.java",
                deferredHolderSource()
            ),
            writeJava(
                "net/neoforged/neoforge/registries/DeferredRegister.java",
                deferredRegisterSource()
            ),
            writeJava("net/minecraft/sounds/SoundEvent.java", soundEventSource()),
            writeJava("net/minecraft/sounds/SoundEvents.java", soundEventsSource()),
            writeJava("net/minecraft/world/item/ArmorItem.java", armorItemSource()),
            writeJava(
                "net/minecraft/world/item/ArmorMaterial.java",
                armorMaterialSource()
            ),
            writeJava("net/minecraft/world/item/Item.java", itemSource()),
            writeJava("net/minecraft/world/item/Items.java", itemsSource()),
            writeJava(
                "net/minecraft/world/item/crafting/Ingredient.java",
                ingredientSource()
            ),
            writeJava("com/simibubi/create/Create.java", createSource()),
            writeJava("com/simibubi/create/AllItems.java", allItemsSource()),
            writeJava("com/simibubi/create/AllSoundEvents.java", migratedProducer),
            writeJava(
                "com/simibubi/create/content/equipment/armor/AllArmorMaterials.java",
                migratedArmor
            ),
            writeJava(
                "com/simibubi/create/content/equipment/armor/Probe.java",
                probeSource()
            )
        )

        val classes = directory.resolve("classes").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for late registry semantics")
        val result = compiler.run(
            null,
            diagnostics,
            diagnostics,
            "-proc:none",
            "--release",
            "17",
            "-d",
            classes.toString(),
            *sources.map(Path::toString).toTypedArray()
        )
        assertEquals(0, result, diagnostics.toString(Charsets.UTF_8))
        URLClassLoader(
            arrayOf(classes.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        ).use { loader ->
            assertEquals(
                "passed",
                loader.loadClass(
                    "com.simibubi.create.content.equipment.armor.Probe"
                ).getMethod("verify").invoke(null)
            )
        }
    }

    private fun probeSource(): String = """
        package com.simibubi.create.content.equipment.armor;

        import com.simibubi.create.AllItems;
        import com.simibubi.create.AllSoundEvents;
        import demo.Timeline;
        import net.minecraft.core.Holder;
        import net.minecraft.sounds.SoundEvent;
        import net.minecraft.sounds.SoundEvents;
        import net.minecraft.world.item.ArmorItem;
        import net.minecraft.world.item.ArmorMaterial;
        import net.minecraft.world.item.Item;
        import net.minecraft.world.item.Items;
        import net.minecraft.world.item.crafting.Ingredient;
        import net.neoforged.neoforge.registries.DeferredHolder;

        public final class Probe {
            public static String verify() throws Exception {
                Class.forName(
                    "com.simibubi.create.content.equipment.armor.AllArmorMaterials"
                );
                if (Timeline.producerClassInitializations != 0)
                    throw new AssertionError("producer initialized while queueing");
                if (Timeline.prepareCalls != 0)
                    throw new AssertionError("producer prepared while queueing");
                if (AllArmorMaterials.ARMOR_MATERIALS.pendingCount() != 2)
                    throw new AssertionError("armor callbacks were not queued");
                if (DeferredHolder.soundGetCalls() != 0 ||
                        DeferredHolder.soundValueCalls() != 0)
                    throw new AssertionError("sound dereferenced while queueing");

                AllSoundEvents.prepare();
                if (Timeline.producerClassInitializations != 1 ||
                        Timeline.prepareCalls != 1)
                    throw new AssertionError("producer preparation order");
                Holder<SoundEvent> exact =
                    AllSoundEvents.COPPER_ARMOR_EQUIP.getMainEventHolder();
                if (!"create:copper_armor_equip".equals(exact.key().location()))
                    throw new AssertionError("wrong project sound key");
                if (DeferredHolder.soundGetCalls() != 0 ||
                        DeferredHolder.soundValueCalls() != 0)
                    throw new AssertionError("prepare dereferenced sound");

                AllArmorMaterials.ARMOR_MATERIALS.runCallbacks();
                ArmorMaterial copper = AllArmorMaterials.COPPER.value();
                ArmorMaterial cardboard = AllArmorMaterials.CARDBOARD.value();
                if (copper.equipSound() != exact)
                    throw new AssertionError("project holder identity changed");
                if (cardboard.equipSound() !=
                        SoundEvents.ARMOR_EQUIP_LEATHER)
                    throw new AssertionError("vanilla holder identity changed");
                if (DeferredHolder.soundGetCalls() != 0 ||
                        DeferredHolder.soundValueCalls() != 0)
                    throw new AssertionError(
                        "early armor callback dereferenced unbound sound");

                assertArmorMetadata(copper, cardboard);

                SoundEvent sameSound = new SoundEvent("copper_armor_equip");
                AllSoundEvents.bindCopper(sameSound);
                SoundEvent fromRecord = copper.equipSound().value();
                SoundEvent fromLegacy =
                    AllArmorMaterials.Legacy.COPPER.getEquipSound();
                if (fromRecord != sameSound || fromLegacy != sameSound)
                    throw new AssertionError("sound identity changed after bind");
                if (DeferredHolder.soundValueCalls() != 1)
                    throw new AssertionError("holder value count " +
                        DeferredHolder.soundValueCalls());
                if (DeferredHolder.soundGetCalls() != 1)
                    throw new AssertionError("legacy get count " +
                        DeferredHolder.soundGetCalls());
                return "passed";
            }

            private static void assertArmorMetadata(
                    ArmorMaterial copper,
                    ArmorMaterial cardboard) {
                if (copper.defense().get(ArmorItem.Type.HELMET) != 2 ||
                        copper.defense().get(ArmorItem.Type.CHESTPLATE) != 4 ||
                        copper.defense().get(ArmorItem.Type.LEGGINGS) != 3 ||
                        copper.defense().get(ArmorItem.Type.BOOTS) != 1)
                    throw new AssertionError("copper defense changed");
                if (cardboard.defense().values().stream()
                        .anyMatch(value -> value != 1))
                    throw new AssertionError("cardboard defense changed");
                if (copper.enchantmentValue() != 25 ||
                        cardboard.enchantmentValue() != 25)
                    throw new AssertionError("enchantment changed");
                if (copper.toughness() != 0.0F ||
                        copper.knockbackResistance() != 0.0F)
                    throw new AssertionError("physical stats changed");

                Ingredient copperRepair = copper.repairIngredient().get();
                Ingredient cardboardRepair = cardboard.repairIngredient().get();
                if (copperRepair.item() != Items.COPPER_INGOT ||
                        cardboardRepair.item() != AllItems.CARDBOARD)
                    throw new AssertionError("repair ingredients changed");
                if (!"create:copper".equals(
                        copper.layers().get(0).texture().toString()))
                    throw new AssertionError("material name changed");
                if (copper.layers().size() != 1)
                    throw new AssertionError("copper became dyeable");
                if (cardboard.layers().size() != 2 ||
                        !cardboard.layers().get(0).dyeable() ||
                        cardboard.layers().get(1).dyeable() ||
                        !"_overlay".equals(cardboard.layers().get(1).suffix()))
                    throw new AssertionError("dyeable layers changed");

                Item.Properties properties = AllArmorMaterials.withDurability(
                    new Item.Properties(),
                    AllArmorMaterials.COPPER,
                    ArmorItem.Type.HELMET
                );
                if (properties.durability() != 77)
                    throw new AssertionError("durability changed");
            }
        }
    """

    private fun timelineSource(): String = """
        package demo;
        public final class Timeline {
            public static int producerClassInitializations;
            public static int prepareCalls;
            private Timeline() {}
        }
    """

    private fun suppliersSource(): String = """
        package com.google.common.base;
        import java.util.function.Supplier;
        public final class Suppliers {
            public static <T> Supplier<T> memoize(Supplier<T> source) {
                return new Supplier<>() {
                    private boolean ready;
                    private T value;
                    @Override
                    public T get() {
                        if (!ready) {
                            value = source.get();
                            ready = true;
                        }
                        return value;
                    }
                };
            }
            private Suppliers() {}
        }
    """

    private fun resourceKeySource(): String = """
        package net.minecraft.resources;
        public record ResourceKey<T>(String registry, String location) {}
    """

    private fun resourceLocationSource(): String = """
        package net.minecraft.resources;
        public final class ResourceLocation {
            private final String namespace;
            private final String path;
            private ResourceLocation(String namespace, String path) {
                this.namespace = namespace;
                this.path = path;
            }
            public static ResourceLocation parse(String value) {
                int split = value.indexOf(':');
                return split < 0
                    ? new ResourceLocation("minecraft", value)
                    : new ResourceLocation(
                        value.substring(0, split),
                        value.substring(split + 1)
                    );
            }
            public static ResourceLocation fromNamespaceAndPath(
                    String namespace,
                    String path) {
                return new ResourceLocation(namespace, path);
            }
            public String getPath() {
                return path;
            }
            @Override
            public String toString() {
                return namespace + ":" + path;
            }
        }
    """

    private fun holderSource(): String = """
        package net.minecraft.core;
        import net.minecraft.resources.ResourceKey;
        public interface Holder<T> {
            T value();
            ResourceKey<T> key();
            default boolean is(ResourceKey<T> other) {
                return key().equals(other);
            }
        }
    """

    private fun registriesSource(): String = """
        package net.minecraft.core.registries;
        public final class Registries {
            public static final String ARMOR_MATERIAL = "armor_material";
            public static final String SOUND_EVENT = "sound_event";
            private Registries() {}
        }
    """

    private fun builtInRegistriesSource(): String = """
        package net.minecraft.core.registries;
        public final class BuiltInRegistries {
            private BuiltInRegistries() {}
        }
    """

    private fun deferredHolderSource(): String = """
        package net.neoforged.neoforge.registries;

        import net.minecraft.core.Holder;
        import net.minecraft.resources.ResourceKey;

        public final class DeferredHolder<R, T extends R>
                implements Holder<T> {
            private static int soundGetCalls;
            private static int soundValueCalls;
            private final ResourceKey<T> key;
            private T bound;

            private DeferredHolder(String registry, String location) {
                key = new ResourceKey<>(registry, location);
            }

            public static <R, T extends R> DeferredHolder<R, T> create(
                    String registry,
                    String location) {
                return new DeferredHolder<>(registry, location);
            }

            public void bind(T value) {
                if (bound != null)
                    throw new IllegalStateException("already bound " + key);
                bound = value;
            }

            public T get() {
                if ("sound_event".equals(key.registry()))
                    soundGetCalls++;
                return requireBound();
            }

            @Override
            public T value() {
                if ("sound_event".equals(key.registry()))
                    soundValueCalls++;
                return requireBound();
            }

            private T requireBound() {
                if (bound == null)
                    throw new IllegalStateException("unbound " + key);
                return bound;
            }

            @Override
            public ResourceKey<T> key() {
                return key;
            }

            public ResourceKey<T> getKey() {
                return key;
            }

            public static int soundGetCalls() {
                return soundGetCalls;
            }

            public static int soundValueCalls() {
                return soundValueCalls;
            }
        }
    """

    private fun deferredRegisterSource(): String = """
        package net.neoforged.neoforge.registries;

        import java.util.ArrayList;
        import java.util.List;
        import java.util.function.Supplier;

        public final class DeferredRegister<R> {
            private final String registry;
            private final String namespace;
            private final List<Runnable> callbacks = new ArrayList<>();

            private DeferredRegister(String registry, String namespace) {
                this.registry = registry;
                this.namespace = namespace;
            }

            public static <R> DeferredRegister<R> create(
                    String registry,
                    String namespace) {
                return new DeferredRegister<>(registry, namespace);
            }

            public DeferredHolder<R, R> register(
                    String name,
                    Supplier<? extends R> supplier) {
                DeferredHolder<R, R> holder =
                    DeferredHolder.create(registry, namespace + ":" + name);
                callbacks.add(() -> holder.bind(supplier.get()));
                return holder;
            }

            public int pendingCount() {
                return callbacks.size();
            }

            public void runCallbacks() {
                List<Runnable> pending = List.copyOf(callbacks);
                callbacks.clear();
                pending.forEach(Runnable::run);
            }
        }
    """

    private fun soundEventSource(): String = """
        package net.minecraft.sounds;
        public record SoundEvent(String name) {}
    """

    private fun soundEventsSource(): String = """
        package net.minecraft.sounds;
        import net.neoforged.neoforge.registries.DeferredHolder;
        public final class SoundEvents {
            public static final DeferredHolder<SoundEvent, SoundEvent>
                ARMOR_EQUIP_LEATHER =
                    DeferredHolder.create(
                        "sound_event",
                        "minecraft:armor_equip_leather"
                    );
            public static final DeferredHolder<SoundEvent, SoundEvent>
                ARMOR_EQUIP_GOLD =
                    DeferredHolder.create(
                        "sound_event",
                        "minecraft:armor_equip_gold"
                    );
            static {
                ARMOR_EQUIP_LEATHER.bind(
                    new SoundEvent("armor_equip_leather")
                );
                ARMOR_EQUIP_GOLD.bind(new SoundEvent("armor_equip_gold"));
            }
            private SoundEvents() {}
        }
    """

    private fun armorItemSource(): String = """
        package net.minecraft.world.item;
        public class ArmorItem {
            public enum Type {
                HELMET, CHESTPLATE, LEGGINGS, BOOTS, BODY
            }
        }
    """

    private fun armorMaterialSource(): String = """
        package net.minecraft.world.item;

        import java.util.List;
        import java.util.Map;
        import java.util.function.Supplier;
        import net.minecraft.core.Holder;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.sounds.SoundEvent;
        import net.minecraft.world.item.crafting.Ingredient;

        public record ArmorMaterial(
            Map<ArmorItem.Type, Integer> defense,
            int enchantmentValue,
            Holder<SoundEvent> equipSound,
            Supplier<Ingredient> repairIngredient,
            List<Layer> layers,
            float toughness,
            float knockbackResistance
        ) {
            public record Layer(
                ResourceLocation texture,
                String suffix,
                boolean dyeable
            ) {
                public Layer(ResourceLocation texture) {
                    this(texture, "", false);
                }
            }
        }
    """

    private fun itemSource(): String = """
        package net.minecraft.world.item;
        public class Item {
            public static final class Properties {
                private int durability;
                public Properties durability(int value) {
                    durability = value;
                    return this;
                }
                public int durability() {
                    return durability;
                }
            }
        }
    """

    private fun itemsSource(): String = """
        package net.minecraft.world.item;
        public final class Items {
            public static final Object COPPER_INGOT = new Object();
            private Items() {}
        }
    """

    private fun ingredientSource(): String = """
        package net.minecraft.world.item.crafting;
        public record Ingredient(Object item) {
            public static Ingredient of(Object item) {
                return new Ingredient(item);
            }
        }
    """

    private fun createSource(): String = """
        package com.simibubi.create;
        import net.minecraft.resources.ResourceLocation;
        public final class Create {
            public static final String ID = "create";
            public static ResourceLocation asResource(String path) {
                return ResourceLocation.fromNamespaceAndPath(ID, path);
            }
            private Create() {}
        }
    """

    private fun allItemsSource(): String = """
        package com.simibubi.create;
        public final class AllItems {
            public static final Object CARDBOARD = new Object();
            private AllItems() {}
        }
    """

    private fun writeJava(relative: String, source: String): Path {
        val file = directory.resolve("sources").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent() + "\n")
        return file
    }
}
