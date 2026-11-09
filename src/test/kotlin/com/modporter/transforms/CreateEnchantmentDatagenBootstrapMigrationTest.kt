package com.modporter.transforms

import com.modporter.core.transforms.structural.CreateEnchantmentDatagenBootstrapMigration
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreateEnchantmentDatagenBootstrapMigrationTest {
    @Test
    fun `restores source preserving Create enchantment data and is idempotent`() {
        val project = project(legacyEnchantments(), legacyProvider())

        val result =
            CreateEnchantmentDatagenBootstrapMigration.migrate(project, dryRun = false)

        assertEquals(4, result.changes.size, result.gates.joinToString())
        assertTrue(result.gates.isEmpty())
        val enchantments = enchantments(project).readText()
        val provider = provider(project).readText()
        val datagen = datagen(project).readText()
        val tagsProvider = tagsProvider(project).readText()
        val compactEnchantments = enchantments.replace(Regex("""\s+"""), "")
        assertTrue(enchantments.contains("public static void bootstrap(BootstrapContext<Enchantment> context)"))
        assertFalse(enchantments.contains("EnchantmentEffectComponents"))
        assertTrue(enchantments.contains("HolderSet.direct(AllItems.POTATO_CANNON)"))
        assertTrue(
            compactEnchantments.contains(
                "HolderSet.direct(AllItems.COPPER_BACKTANK,AllItems.NETHERITE_BACKTANK)"
            )
        )
        assertTrue(enchantments.contains("Enchantment.dynamicCost(11, 10)"))
        assertTrue(enchantments.contains("Enchantment.dynamicCost(16, 10)"))
        assertTrue(enchantments.contains("EquipmentSlotGroup.HAND"))
        assertTrue(enchantments.contains("EquipmentSlotGroup.CHEST"))
        assertTrue(enchantments.contains("java.util.List.of(POTATO_RECOVERY, CAPACITY)"))
        assertTrue(enchantments.contains("context.register(key, builder.build(key.location()))"))
        assertTrue(enchantments.contains("public static void register()"))
        assertTrue(provider.contains("import com.simibubi.create.AllEnchantments;"))
        assertTrue(
            provider.contains(".add(Registries.ENCHANTMENT, AllEnchantments::bootstrap)")
        )
        assertTrue(
            datagen.contains(
                "new CreateEnchantmentTagsProvider(output, lookupProvider, existingFileHelper)"
            )
        )
        assertTrue(tagsProvider.contains("tag(EnchantmentTags.NON_TREASURE)"))
        assertTrue(tagsProvider.contains("tag(EnchantmentTags.IN_ENCHANTING_TABLE)"))
        assertFalse(tagsProvider.contains("AMMO_USE"))

        val second =
            CreateEnchantmentDatagenBootstrapMigration.migrate(project, dryRun = false)
        assertTrue(second.changes.isEmpty())
        assertTrue(second.gates.isEmpty())
        assertEquals(enchantments, enchantments(project).readText())
        assertEquals(provider, provider(project).readText())
        assertEquals(datagen, datagen(project).readText())
        assertEquals(tagsProvider, tagsProvider(project).readText())
    }

    @Test
    fun `dry run reports both owners without writing`() {
        val project = project(legacyEnchantments(), legacyProvider())
        val enchantmentsBefore = enchantments(project).readText()
        val providerBefore = provider(project).readText()
        val datagenBefore = datagen(project).readText()

        val result =
            CreateEnchantmentDatagenBootstrapMigration.migrate(project, dryRun = true)

        assertEquals(4, result.changes.size, result.gates.joinToString())
        assertTrue(result.gates.isEmpty())
        assertEquals(enchantmentsBefore, enchantments(project).readText())
        assertEquals(providerBefore, provider(project).readText())
        assertEquals(datagenBefore, datagen(project).readText())
        assertFalse(Files.exists(tagsProvider(project)))
    }

    @Test
    fun `partial bootstrap family is refused before either write`() {
        val project = project(
            legacyEnchantments(),
            legacyProvider().replace(
                "private static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()",
                "private static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()\n" +
                    "\t\t.add(Registries.ENCHANTMENT, AllEnchantments::bootstrap)"
            ).replace(
                "import com.simibubi.create.AllDamageTypes;",
                "import com.simibubi.create.AllDamageTypes;\n" +
                    "import com.simibubi.create.AllEnchantments;"
            )
        )
        val enchantmentsBefore = enchantments(project).readText()
        val providerBefore = provider(project).readText()
        val datagenBefore = datagen(project).readText()

        val result =
            CreateEnchantmentDatagenBootstrapMigration.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.single().contains("partial or incompatible"))
        assertEquals(enchantmentsBefore, enchantments(project).readText())
        assertEquals(providerBefore, provider(project).readText())
        assertEquals(datagenBefore, datagen(project).readText())
        assertFalse(Files.exists(tagsProvider(project)))
    }

    @Test
    fun `altered legacy enchantment key is refused before either write`() {
        val project = project(
            legacyEnchantments().replace("\"capacity\"", "\"custom_capacity\""),
            legacyProvider()
        )
        val enchantmentsBefore = enchantments(project).readText()
        val providerBefore = provider(project).readText()
        val datagenBefore = datagen(project).readText()

        val result =
            CreateEnchantmentDatagenBootstrapMigration.migrate(project, dryRun = false)

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.single().contains("partial or incompatible"))
        assertEquals(enchantmentsBefore, enchantments(project).readText())
        assertEquals(providerBefore, provider(project).readText())
        assertEquals(datagenBefore, datagen(project).readText())
        assertFalse(Files.exists(tagsProvider(project)))
    }

    @Test
    fun `second publish failure rolls the first owner back`() {
        val project = project(legacyEnchantments(), legacyProvider())
        val enchantmentsBefore = enchantments(project).readText()
        val providerBefore = provider(project).readText()
        val datagenBefore = datagen(project).readText()
        var publishes = 0

        val result = CreateEnchantmentDatagenBootstrapMigration.migrateForTest(
            project,
            dryRun = false
        ) { source, target ->
            val isPublish = source.fileName.toString().contains("-stage-")
            if (isPublish && ++publishes == 4) {
                throw IOException("injected fourth publish failure")
            }
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        }

        assertTrue(result.changes.isEmpty())
        assertTrue(result.gates.single().contains("injected fourth publish failure"))
        assertEquals(enchantmentsBefore, enchantments(project).readText())
        assertEquals(providerBefore, provider(project).readText())
        assertEquals(datagenBefore, datagen(project).readText())
        assertFalse(Files.exists(tagsProvider(project)))
    }

    private fun project(enchantmentsSource: String, providerSource: String): Path {
        val project = Files.createTempDirectory("create-enchantment-bootstrap")
        val enchantments = enchantments(project)
        enchantments.parent.createDirectories()
        enchantments.writeText(enchantmentsSource)
        val provider = provider(project)
        provider.parent.createDirectories()
        provider.writeText(providerSource)
        val datagen = datagen(project)
        datagen.writeText(legacyDatagen())
        return project
    }

    private fun enchantments(project: Path): Path =
        project.resolve("src/main/java/com/simibubi/create/AllEnchantments.java")

    private fun provider(project: Path): Path =
        project.resolve(
            "src/main/java/com/simibubi/create/infrastructure/data/GeneratedEntriesProvider.java"
        )

    private fun datagen(project: Path): Path =
        project.resolve(
            "src/main/java/com/simibubi/create/infrastructure/data/CreateDatagen.java"
        )

    private fun tagsProvider(project: Path): Path =
        project.resolve(
            "src/main/java/com/simibubi/create/infrastructure/data/" +
                "CreateEnchantmentTagsProvider.java"
        )

    private fun legacyEnchantments(): String = """
        package com.simibubi.create;

        import com.simibubi.create.content.equipment.armor.CapacityEnchantment;
        import com.simibubi.create.foundation.data.CreateRegistrate;
        import com.tterrag.registrate.util.entry.RegistryEntry;

        import net.minecraft.world.entity.EquipmentSlot;
        import net.minecraft.world.item.enchantment.Enchantment;
        import net.minecraft.core.registries.Registries;
        public class AllEnchantments {

        public static final net.minecraft.resources.ResourceKey<Enchantment> POTATO_RECOVERY =
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "potato_recovery"));

        public static final net.minecraft.resources.ResourceKey<Enchantment> CAPACITY =
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "capacity"));

            public static void register() {
            }

        public static final java.util.List<net.minecraft.resources.ResourceKey<Enchantment>> ENCHANTMENTS =
                java.util.List.of(POTATO_RECOVERY, CAPACITY);
        }
    """.trimIndent()

    private fun legacyProvider(): String = """
        package com.simibubi.create.infrastructure.data;

        import java.util.Set;
        import java.util.concurrent.CompletableFuture;

        import com.simibubi.create.AllDamageTypes;
        import com.simibubi.create.Create;
        import com.simibubi.create.api.registry.CreateRegistries;
        import com.simibubi.create.content.equipment.potatoCannon.AllPotatoProjectileTypes;
        import com.simibubi.create.infrastructure.worldgen.AllBiomeModifiers;
        import com.simibubi.create.infrastructure.worldgen.AllConfiguredFeatures;
        import com.simibubi.create.infrastructure.worldgen.AllPlacedFeatures;

        import net.minecraft.core.HolderLookup;
        import net.minecraft.core.RegistrySetBuilder;
        import net.minecraft.core.registries.Registries;
        import net.minecraft.data.PackOutput;

        import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
        import net.minecraft.core.registries.BuiltInRegistries;
        import net.neoforged.neoforge.registries.NeoForgeRegistries;

        public class GeneratedEntriesProvider extends DatapackBuiltinEntriesProvider {
            private static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()
                .add(Registries.DAMAGE_TYPE, AllDamageTypes::bootstrap)
                .add(Registries.CONFIGURED_FEATURE, AllConfiguredFeatures::bootstrap)
                .add(Registries.PLACED_FEATURE, AllPlacedFeatures::bootstrap)
                .add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, AllBiomeModifiers::bootstrap)
                .add(CreateRegistries.POTATO_PROJECTILE_TYPE, AllPotatoProjectileTypes::bootstrap);

            public GeneratedEntriesProvider(
                PackOutput output,
                CompletableFuture<HolderLookup.Provider> registries
            ) {
                super(output, registries, BUILDER, Set.of(Create.ID));
            }

            @Override
            public String getName() {
                return "Create's Generated Registry Entries";
            }
        }
    """.trimIndent()

    private fun legacyDatagen(): String = """
        package com.simibubi.create.infrastructure.data;

        import com.simibubi.create.Create;
        import com.simibubi.create.foundation.data.DamageTypeTagGen;

        import net.minecraft.core.HolderLookup;
        import net.minecraft.data.DataGenerator;
        import net.minecraft.data.PackOutput;

        import net.neoforged.neoforge.common.data.ExistingFileHelper;
        import net.neoforged.neoforge.data.event.GatherDataEvent;

        public class CreateDatagen {
            public static void gatherData(GatherDataEvent event) {
                DataGenerator generator = event.getGenerator();
                PackOutput output = generator.getPackOutput();
                java.util.concurrent.CompletableFuture<HolderLookup.Provider> lookupProvider =
                        event.getLookupProvider();
                ExistingFileHelper existingFileHelper = event.getExistingFileHelper();

                generator.addProvider(event.includeServer(), new DamageTypeTagGen(output, lookupProvider, existingFileHelper));
            }
        }
    """.trimIndent()
}
