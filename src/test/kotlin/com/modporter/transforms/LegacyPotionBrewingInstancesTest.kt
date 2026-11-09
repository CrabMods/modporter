package com.modporter.transforms

import com.modporter.core.transforms.structural.LegacyPotionBrewingInstances
import com.modporter.core.transforms.structural.StructuralRefactorPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyPotionBrewingInstancesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `checkout ancestor names do not suppress the declared brewing source roots`() {
        val original = directory
        try {
            for (ancestor in listOf(
                "build/benchmark/runs/copied-project",
                "src/references/copied-project",
                "com/modporter/generated/copied-project"
            )) {
                directory = original.resolve(ancestor)
                `actual brewing instance flows through arbitrary static field names on server and client`()
            }
        } finally {
            directory = original
        }
    }

    @Test
    fun `actual brewing instance flows through arbitrary static field names on server and client`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("BrewCatalog.java")
        catalog.writeText("""
            package example;

            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.neoforged.neoforge.common.brewing.BrewingRecipe;
            import net.neoforged.neoforge.common.brewing.BrewingRecipeRegistry;
            import net.neoforged.neoforge.common.brewing.IBrewingRecipe;

            public final class BrewCatalog {
                static final List<Item> BOTTLES =
                    List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION);
                public static final List<String> FERMENTATIONS = assemble();
                public static final Map<Item, List<String>> INDEX = index(FERMENTATIONS);

                private static List<String> assemble() {
                    List<String> result = new ArrayList<>();
                    for (Item bottle : BOTTLES) {
                        if (!PotionBrewing.ALLOWED_CONTAINER.test(new ItemStack(bottle)))
                            continue;
                        for (PotionBrewing.Mix<Potion> step : PotionBrewing.POTION_MIXES)
                            result.add(describe(step));
                    }
                    for (PotionBrewing.Mix<Item> step : PotionBrewing.CONTAINER_MIXES)
                        result.add(describe(step));
                    for (IBrewingRecipe recipe : BrewingRecipeRegistry.getRecipes())
                        if (recipe instanceof BrewingRecipe simple)
                            result.add(simple.getInput() + ":" + simple.getIngredient() + ":" + simple.getOutput());
                    return result;
                }

                private static String describe(PotionBrewing.Mix<?> step) {
                    return step.from.value() + ":" + step.ingredient + ":" + step.to.value();
                }

                private static Map<Item, List<String>> index(List<String> recipes) {
                    Map<Item, List<String>> result = new HashMap<>();
                    result.put(Items.POTION, recipes);
                    return result;
                }
            }
        """.trimIndent())

        val derived = java.resolve("DerivedViews.java")
        derived.writeText("""
            package example;
            import java.util.LinkedHashSet;
            import java.util.Set;
            final class DerivedViews {
                static final Set<String> DISTINCT =
                    new LinkedHashSet<>(BrewCatalog.FERMENTATIONS);
            }
        """.trimIndent())

        val machine = java.resolve("FermentingMachine.java")
        machine.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Set;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;
            abstract class BasinMachine extends BlockEntity {
                BasinMachine(BlockPos pos, BlockState state) {
                    super(null, pos, state);
                }
                protected boolean updateBasin() {
                    if (level == null || level.isClientSide)
                        return true;
                    getMatchingRecipes();
                    return true;
                }
                protected List<?> getMatchingRecipes() {
                    return new ArrayList<>();
                }
            }
            final class FermentingMachine extends BasinMachine {
                FermentingMachine(BlockPos pos, BlockState state) {
                    super(pos, state);
                }
                @Override
                protected List<?> getMatchingRecipes() {
                    List<String> matching = BrewCatalog.INDEX.get(Items.POTION);
                    Set<String> distinct = DerivedViews.DISTINCT;
                    return matching.isEmpty() ? distinct.stream().toList() : matching;
                }
            }
        """.trimIndent())

        val viewer = java.resolve("RecipeViewer.java")
        viewer.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.Collection;
            import java.util.List;
            import java.util.function.Consumer;
            import java.util.function.Supplier;
            import mezz.jei.api.IModPlugin;
            import mezz.jei.api.JeiPlugin;
            import mezz.jei.api.registration.IRecipeCategoryRegistration;
            import mezz.jei.api.registration.IRecipeRegistration;
            import net.minecraft.client.Minecraft;
            @JeiPlugin
            final class RecipeViewer implements IModPlugin {
                private final List<RecipeCategory<?>> allCategories = new ArrayList<>();
                private void loadCategories() {
                    builder(String.class)
                        .addTypedRecipes(new TypeInfo() {})
                        .addTypedRecipes(() -> "smoking")
                        .addRecipes(() -> BrewCatalog.FERMENTATIONS)
                        .build(RecipeCategory::new);
                }
                private <T> CategoryBuilder<T> builder(Class<T> valueType) {
                    return new CategoryBuilder<>(valueType);
                }
                @Override
                public void registerCategories(IRecipeCategoryRegistration registration) {
                    loadCategories();
                    registration.addRecipeCategories(
                        allCategories.toArray(
                            mezz.jei.api.recipe.category.IRecipeCategory[]::new));
                }
                @Override
                public void registerRecipes(IRecipeRegistration registration) {
                    allCategories.forEach(category -> category.registerRecipes(registration));
                }
                interface TypeInfo {
                    default String getType() {
                        return "type";
                    }
                }
                final class CategoryBuilder<T> {
                    private final Class<T> valueType;
                    private final List<Consumer<List<T>>> recipeListConsumers = new ArrayList<>();
                    CategoryBuilder(Class<T> valueType) {
                        this.valueType = valueType;
                    }
                    CategoryBuilder<T> addRecipeListConsumer(Consumer<List<T>> consumer) {
                        recipeListConsumers.add(consumer);
                        return this;
                    }
                    CategoryBuilder<T> addRecipes(Supplier<Collection<? extends T>> recipes) {
                        return addRecipeListConsumer(values -> values.addAll(recipes.get()));
                    }
                    CategoryBuilder<T> addTypedRecipes(TypeInfo type) {
                        return addTypedRecipes(type::getType);
                    }
                    CategoryBuilder<T> addTypedRecipes(Supplier<String> type) {
                        return addRecipeListConsumer(values ->
                            consumeTypedRecipes(values, type.get()));
                    }
                    RecipeCategory<T> build(Factory<T> factory) {
                        Supplier<List<T>> recipesSupplier = () -> {
                            List<T> recipes = new ArrayList<>();
                            for (Consumer<List<T>> consumer : recipeListConsumers)
                                consumer.accept(recipes);
                            return recipes;
                        };
                        Info<T> info = new Info<>(recipesSupplier);
                        RecipeCategory<T> category = factory.create(info);
                        allCategories.add(category);
                        return category;
                    }
                }
                record Info<T>(Supplier<List<T>> recipes) {}
                interface Factory<T> {
                    RecipeCategory<T> create(Info<T> info);
                }
                static <T> void consumeTypedRecipes(List<T> recipes, String type) {
                    Minecraft.getInstance().getConnection().getRecipeManager();
                }
                static final class RecipeCategory<T> {
                    private final Supplier<List<T>> recipes;
                    RecipeCategory(Info<T> info) {
                        this.recipes = info.recipes();
                    }
                    void registerRecipes(IRecipeRegistration registration) {
                        registration.addRecipes(null, recipes.get());
                    }
                    Class<?> valueType() {
                        return Object.class;
                    }
                    RecipeCategory<T> unrelatedOverload(Object ignored) {
                        return this;
                    }
                }
            }
        """.trimIndent())

        val pass = StructuralRefactorPass()
        val changes = LegacyPotionBrewingInstances.migrate(pass, directory, false)
        assertTrue(changes.isNotEmpty(), changes.toString())
        assertTrue(changes.all { it.ruleId == LegacyPotionBrewingInstances.RULE_ID })

        val migratedCatalog = catalog.readText()
        assertFalse(migratedCatalog.contains("POTION_MIXES"), migratedCatalog)
        assertFalse(migratedCatalog.contains("CONTAINER_MIXES"), migratedCatalog)
        assertFalse(migratedCatalog.contains("ALLOWED_CONTAINER"), migratedCatalog)
        assertFalse(migratedCatalog.contains("BrewingRecipeRegistry.getRecipes"), migratedCatalog)
        assertFalse(migratedCatalog.contains("static final List<String> FERMENTATIONS"), migratedCatalog)
        assertFalse(migratedCatalog.contains("static final Map<Item, List<String>> INDEX"), migratedCatalog)
        assertTrue(migratedCatalog.contains("WeakHashMap"), migratedCatalog)
        assertTrue(migratedCatalog.contains(
            "java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>())"
        ), migratedCatalog)
        assertTrue(migratedCatalog.contains(
            "java.util.Objects.requireNonNull(modporterPotionBrewing, \"potionBrewing\")"
        ), migratedCatalog)
        assertTrue(migratedCatalog.contains(".containsKey(modporterPotionBrewing)"), migratedCatalog)
        assertTrue(migratedCatalog.contains("modporter\$get\$FERMENTATIONS"), migratedCatalog)
        assertTrue(migratedCatalog.contains("modporter\$get\$INDEX"), migratedCatalog)
        assertTrue(migratedCatalog.contains("PotionBrewing modporterPotionBrewing"), migratedCatalog)
        assertTrue(migratedCatalog.contains("modporterPotionBrewing.isContainer("), migratedCatalog)
        assertTrue(migratedCatalog.contains("modporterPotionBrewing.potionMixes"), migratedCatalog)
        assertTrue(migratedCatalog.contains("modporterPotionBrewing.containerMixes"), migratedCatalog)
        assertTrue(migratedCatalog.contains("step.from().value()"), migratedCatalog)
        assertTrue(migratedCatalog.contains("step.ingredient()"), migratedCatalog)
        assertTrue(migratedCatalog.contains("step.to().value()"), migratedCatalog)
        assertTrue(migratedCatalog.contains("modporterPotionBrewing.getRecipes()"), migratedCatalog)
        assertTrue(migratedCatalog.contains("recipe instanceof BrewingRecipe simple"), migratedCatalog)
        assertFalse(migratedCatalog.contains("addVanillaMixes"), migratedCatalog)
        assertFalse(migratedCatalog.contains("PotionBrewing.bootstrap"), migratedCatalog)
        assertFalse(migratedCatalog.contains("PotionBrewing.EMPTY"), migratedCatalog)
        assertFalse(migratedCatalog.contains("net.minecraft.client.Minecraft"), migratedCatalog)
        assertFalse(migratedCatalog.contains("Level"), migratedCatalog)

        val migratedDerived = derived.readText()
        assertTrue(migratedDerived.contains("WeakHashMap"), migratedDerived)
        assertTrue(migratedDerived.contains("modporter\$get\$DISTINCT"), migratedDerived)
        assertTrue(migratedDerived.contains("BrewCatalog.modporter\$get\$FERMENTATIONS(modporterPotionBrewing)"), migratedDerived)

        val migratedMachine = machine.readText()
        assertTrue(migratedMachine.contains("this.getLevel().potionBrewing()"), migratedMachine)
        assertTrue(migratedMachine.contains("level == null || level.isClientSide"), migratedMachine)
        assertTrue(migratedMachine.contains("BrewCatalog.modporter\$get\$INDEX("), migratedMachine)
        assertTrue(migratedMachine.contains("DerivedViews.modporter\$get\$DISTINCT("), migratedMachine)

        val migratedViewer = viewer.readText()
        assertTrue(
            migratedViewer.contains(
                "net.minecraft.client.Minecraft.getInstance().getConnection().potionBrewing()"
            ),
            migratedViewer
        )

        val at = directory.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(at.exists())
        val access = at.readText()
        assertTrue(access.contains("public net.minecraft.world.item.alchemy.PotionBrewing potionMixes"), access)
        assertTrue(access.contains("public net.minecraft.world.item.alchemy.PotionBrewing containerMixes"), access)
        assertTrue(access.contains(
            "public net.minecraft.world.item.alchemy.PotionBrewing isContainer(Lnet/minecraft/world/item/ItemStack;)Z"
        ), access)
        val mixClassAccess =
            "public net.minecraft.world.item.alchemy.PotionBrewing\$Mix"
        val mixConstructorAccess =
            "public net.minecraft.world.item.alchemy.PotionBrewing\$Mix " +
                "<init>(Lnet/minecraft/core/Holder;" +
                "Lnet/minecraft/world/item/crafting/Ingredient;" +
                "Lnet/minecraft/core/Holder;)V"
        assertEquals(
            1, access.lineSequence().count { it == mixClassAccess }, access
        )
        assertEquals(
            1, access.lineSequence().count { it == mixConstructorAccess }, access
        )

        val stable = listOf(catalog, derived, machine, viewer, at).associateWith { it.readText() }
        val second = LegacyPotionBrewingInstances.migrate(pass, directory, false)
        assertTrue(second.isEmpty(), second.toString())
        stable.forEach { (file, content) -> assertEquals(content, file.readText()) }
    }

    @Test
    fun `actual Mix holder values retain their declared Potion and Item types`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("ActualMixValues.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class ActualMixValues {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(potionValue(mix.from.value()));
                    for (PotionBrewing.Mix<Item> mix : PotionBrewing.CONTAINER_MIXES)
                        values.add(itemValue(mix.to.value()));
                    return values;
                }
                private static String potionValue(Potion value) {
                    return value.toString();
                }
                private static String itemValue(Item value) {
                    return value.toString();
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())

        LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        val migrated = catalog.readText()
        assertTrue(migrated.contains("mix.from().value()"), migrated)
        assertTrue(migrated.contains("mix.to().value()"), migrated)
        assertTrue(migrated.contains("potionValue(mix.from().value())"), migrated)
        assertTrue(migrated.contains("itemValue(mix.to().value())"), migrated)
        assertFalse(migrated.contains("PotionBrewing.POTION_MIXES"), migrated)
        assertFalse(migrated.contains("PotionBrewing.CONTAINER_MIXES"), migrated)
    }

    @Test
    fun `implicit java lang String survives project helpers and constructor retention`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("ImplicitJavaLangString.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class ImplicitJavaLangString {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES) {
                        String id = id(mix);
                        values.add(copy(id));
                    }
                    return values;
                }
                private static String id(PotionBrewing.Mix<Potion> mix) {
                    return mix.from.value().toString();
                }
                private static String copy(String id) {
                    return new String(id);
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())

        LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        val migrated = catalog.readText()
        assertTrue(migrated.contains("String id = id(mix);"), migrated)
        assertTrue(migrated.contains("values.add(copy(id));"), migrated)
        assertTrue(migrated.contains("return new String(id);"), migrated)
        assertFalse(migrated.contains("PotionBrewing.POTION_MIXES"), migrated)
    }

    @Test
    fun `public target ItemStack getter retains its exact Item return`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("NativeItemStackGetter.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class NativeItemStackGetter {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    ItemStack stack = new ItemStack(Items.POTION);
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(render(stack.getItem()));
                    return values;
                }
                private static String render(Item item) {
                    return item.toString();
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())

        LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        val migrated = catalog.readText()
        assertTrue(migrated.contains("render(stack.getItem())"), migrated)
        assertFalse(migrated.contains("PotionBrewing.POTION_MIXES"), migrated)
    }

    @Test
    fun `native custom data constant and factory are retained through container mutation`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("NativeCustomDataComponent.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class NativeCustomDataComponent {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    ItemStack stack = new ItemStack(Items.POTION);
                    net.minecraft.nbt.CompoundTag customData =
                        new net.minecraft.nbt.CompoundTag();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES) {
                        stack.set(
                            net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of(customData));
                        values.add(mix.from.value().toString());
                    }
                    return values;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())

        LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        val migrated = catalog.readText()
        assertTrue(
            migrated.contains(
                "net.minecraft.world.item.component.CustomData.of(customData)"
            ),
            migrated
        )
        assertFalse(migrated.contains("PotionBrewing.POTION_MIXES"), migrated)
    }

    @Test
    fun `owned generated potion helper copies retained safe custom effects`() {
        val generatedPackage = "com.modporter.generated.example.compat"
        val helper = directory.resolve(
            "src/main/java/${generatedPackage.replace('.', '/')}/LegacyPotionUtils.java"
        )
        helper.parent.createDirectories()
        helper.writeText(
            javaClass.getResource("/compat/LegacyPotionUtils.java.template")!!
                .readText()
                .replace("@PACKAGE@", generatedPackage)
        )
        val helperBefore = helper.readText()
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("GeneratedPotionHelperCache.java")
        catalog.writeText("""
            package example;
            import $generatedPackage.LegacyPotionUtils;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class GeneratedPotionHelperCache {
                static final List<MobEffectInstance> VALUES = build();
                private static List<MobEffectInstance> build() {
                    List<MobEffectInstance> values = new ArrayList<>();
                    ItemStack stack = new ItemStack(Items.POTION);
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.addAll(LegacyPotionUtils.getCustomEffects(stack));
                    return values;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())

        LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        val migrated = catalog.readText()
        assertTrue(
            migrated.contains("LegacyPotionUtils.getCustomEffects(stack)"),
            migrated
        )
        assertFalse(migrated.contains("PotionBrewing.POTION_MIXES"), migrated)
        assertEquals(helperBefore, helper.readText())
    }

    @Test
    fun `modified generated potion helper cannot borrow canonical copy proof`() {
        val generatedPackage = "com.modporter.generated.example.compat"
        val helper = directory.resolve(
            "src/main/java/${generatedPackage.replace('.', '/')}/LegacyPotionUtils.java"
        )
        helper.parent.createDirectories()
        val canonical = javaClass.getResource("/compat/LegacyPotionUtils.java.template")!!
            .readText()
            .replace("@PACKAGE@", generatedPackage)
        helper.writeText(
            canonical.replace(
                "return new ArrayList<>(stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).customEffects());",
                "return new ArrayList<>();"
            )
        )
        val helperBefore = helper.readText()
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("ModifiedGeneratedPotionHelper.java")
        catalog.writeText("""
            package example;
            import $generatedPackage.LegacyPotionUtils;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class ModifiedGeneratedPotionHelper {
                static final List<MobEffectInstance> VALUES = build();
                private static List<MobEffectInstance> build() {
                    List<MobEffectInstance> values = new ArrayList<>();
                    ItemStack stack = new ItemStack(Items.POTION);
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.addAll(LegacyPotionUtils.getCustomEffects(stack));
                    return values;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains(
                "LegacyPotionUtils.getCustomEffects(stack)"
            ),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertEquals(helperBefore, helper.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `generated potion helper outside owned compat path remains opaque`() {
        val generatedPackage = "com.modporter.generated.example.other"
        val helper = directory.resolve(
            "src/main/java/${generatedPackage.replace('.', '/')}/LegacyPotionUtils.java"
        )
        helper.parent.createDirectories()
        helper.writeText(
            javaClass.getResource("/compat/LegacyPotionUtils.java.template")!!
                .readText()
                .replace("@PACKAGE@", generatedPackage)
        )
        val helperBefore = helper.readText()
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("WrongOwnedGeneratedPotionHelper.java")
        catalog.writeText("""
            package example;
            import $generatedPackage.LegacyPotionUtils;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.effect.MobEffectInstance;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class WrongOwnedGeneratedPotionHelper {
                static final List<MobEffectInstance> VALUES = build();
                private static List<MobEffectInstance> build() {
                    List<MobEffectInstance> values = new ArrayList<>();
                    ItemStack stack = new ItemStack(Items.POTION);
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.addAll(LegacyPotionUtils.getCustomEffects(stack));
                    return values;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains(
                "LegacyPotionUtils.getCustomEffects(stack)"
            ),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertEquals(helperBefore, helper.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `unowned opaque call with unknown return remains rejected`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("UnknownOpaqueReturn.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class UnknownOpaqueReturn {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    ItemStack stack = new ItemStack(Items.POTION);
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(unownedOpaque(stack));
                    return values;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("unownedOpaque(stack)"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `project DataComponents owner cannot borrow native custom data constant contract`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("FakeDataComponentsOwner.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class DataComponents {
                static final Level CUSTOM_DATA = null;
            }
            final class FakeDataComponentsOwner {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    ItemStack stack = new ItemStack(Items.POTION);
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES) {
                        stack.set(DataComponents.CUSTOM_DATA, null);
                        values.add(mix.from.value().toString());
                    }
                    return values;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("net.minecraft.world.level.Level"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `project CustomData owner cannot borrow native factory contract`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("FakeCustomDataOwner.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class CustomData {
                static Level of(CompoundTag tag) {
                    return null;
                }
            }
            final class FakeCustomDataOwner {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    ItemStack stack = new ItemStack(Items.POTION);
                    CompoundTag tag = new CompoundTag();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES) {
                        stack.set(
                            net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            CustomData.of(tag));
                        values.add(mix.from.value().toString());
                    }
                    return values;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("net.minecraft.world.level.Level"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `value shadow cannot impersonate native CustomData factory owner`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("ShadowedCustomDataOwner.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class CustomDataFactory {
                Level of(CompoundTag tag) {
                    return null;
                }
            }
            final class ShadowedCustomDataOwner {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    ItemStack stack = new ItemStack(Items.POTION);
                    CompoundTag tag = new CompoundTag();
                    CustomDataFactory CustomData = new CustomDataFactory();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES) {
                        stack.set(
                            net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            CustomData.of(tag));
                        values.add(mix.from.value().toString());
                    }
                    return values;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("net.minecraft.world.level.Level"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `native CustomData factory call with wrong argument ABI fails closed`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("WrongCustomDataFactoryAbi.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class WrongCustomDataFactoryAbi {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    ItemStack stack = new ItemStack(Items.POTION);
                    Level retained = null;
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES) {
                        stack.set(
                            net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of(retained));
                        values.add(mix.from.value().toString());
                    }
                    return values;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("CustomData.of(retained)"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `project ItemStack getter cannot borrow native target return contract`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("FakeItemStackGetter.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class ItemStack {
                private final Level retained;
                ItemStack(Level retained) {
                    this.retained = retained;
                }
                Level getItem() {
                    return retained;
                }
            }
            final class FakeItemStackGetter {
                static final List<java.lang.String> VALUES = build();
                private static List<java.lang.String> build() {
                    List<java.lang.String> values = new ArrayList<>();
                    ItemStack stack = new ItemStack(null);
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(render(stack.getItem()));
                    return values;
                }
                private static java.lang.String render(Object value) {
                    return java.lang.String.valueOf(value);
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("net.minecraft.world.level.Level"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `package String retains its exact project owner and rejected Level state`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("PackageStringCollision.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class String {
                private final Level retained;
                String(Level retained) {
                    this.retained = retained;
                }
                @Override
                public java.lang.String toString() {
                    return "retained";
                }
            }
            final class PackageStringCollision {
                static final List<java.lang.String> VALUES = build();
                private static List<java.lang.String> build() {
                    List<java.lang.String> values = new ArrayList<>();
                    String id = new String(null);
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(render(id));
                    return values;
                }
                private static java.lang.String render(String id) {
                    return id.toString();
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("net.minecraft.world.level.Level"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `String type parameter cannot impersonate implicit java lang String`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("TypeParameterStringShadow.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class GenericStringBox<String> {
                private final String value;
                GenericStringBox(String value) {
                    this.value = value;
                }
            }
            final class TypeParameterStringShadow {
                static final List<java.lang.String> VALUES = build();
                private static List<java.lang.String> build() {
                    List<java.lang.String> values = new ArrayList<>();
                    GenericStringBox<Level> retained = new GenericStringBox<>(null);
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(render(retained));
                    return values;
                }
                private static java.lang.String render(
                    GenericStringBox<Level> retained
                ) {
                    return retained.toString();
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(failure.message.orEmpty().contains("unknown object graph"), failure.message)
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `exact source enum constant with closed primitive state is retained`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("ClosedEnumConstant.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            enum HeatCondition {
                NONE(0xffffff), HEATED(0xE88300);
                private int color;
                private HeatCondition(int color) {
                    this.color = color;
                }
            }
            final class ClosedEnumConstant {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(keep(HeatCondition.HEATED).name());
                    return values;
                }
                private static HeatCondition keep(HeatCondition condition) {
                    return condition;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())

        LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        val migrated = catalog.readText()
        assertTrue(migrated.contains("keep(HeatCondition.HEATED).name()"), migrated)
        assertFalse(migrated.contains("PotionBrewing.POTION_MIXES"), migrated)
    }

    @Test
    fun `closed source enum factory return seeds typed value and inherited name`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("ClosedEnumFactory.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            enum BottleType {
                REGULAR, SPLASH, LINGERING
            }
            final class ClosedEnumFactory {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    BottleType bottleType = bottleTypeFromFlag(true);
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(render(bottleType));
                    return values;
                }
                private static BottleType bottleTypeFromFlag(boolean splash) {
                    if (splash)
                        return BottleType.SPLASH;
                    return BottleType.REGULAR;
                }
                private static String render(BottleType bottleType) {
                    return bottleType.name();
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())

        LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        val migrated = catalog.readText()
        assertTrue(migrated.contains("render(bottleType)"), migrated)
        assertTrue(migrated.contains("return bottleType.name();"), migrated)
        assertFalse(migrated.contains("PotionBrewing.POTION_MIXES"), migrated)
    }

    @Test
    fun `typed source enum value cannot replace exact constant proof`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("TypedEnumValue.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            enum HeatCondition {
                NONE(0xffffff), HEATED(0xE88300);
                private int color;
                private HeatCondition(int color) {
                    this.color = color;
                }
            }
            final class TypedEnumValue {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    HeatCondition retained = HeatCondition.HEATED;
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(name(retained));
                    return values;
                }
                private static String name(HeatCondition condition) {
                    return condition.name();
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("without an exact qualified constant"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `java Enum receiver cannot use source enum inherited name proof`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("WidenedEnumName.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            enum HeatCondition {
                HEATED(0xE88300);
                private final int color;
                private HeatCondition(int color) {
                    this.color = color;
                }
            }
            final class WidenedEnumName {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    Enum<?> widened = HeatCondition.HEATED;
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES) {
                        values.add(keep(HeatCondition.HEATED).name());
                        values.add(widened.name());
                    }
                    return values;
                }
                private static HeatCondition keep(HeatCondition condition) {
                    return condition;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(failure.message.orEmpty().contains("widened.name()"), failure.message)
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `source enum constant with object state aborts before any write`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("StatefulEnumConstant.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            enum StatefulHeat {
                HEATED(new Object());
                private final Object state;
                private StatefulHeat(Object state) {
                    this.state = state;
                }
            }
            final class StatefulEnumConstant {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(name(StatefulHeat.HEATED));
                    return values;
                }
                private static String name(StatefulHeat condition) {
                    return condition.name();
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("closed primitive instance state"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `source enum constant with computed constructor input aborts before any write`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("ComputedEnumConstant.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            enum ComputedHeat {
                HEATED(color());
                private final int color;
                private ComputedHeat(int color) {
                    this.color = color;
                }
                private static int color() {
                    return 0xE88300;
                }
            }
            final class ComputedEnumConstant {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(name(ComputedHeat.HEATED));
                    return values;
                }
                private static String name(ComputedHeat condition) {
                    return condition.name();
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("closed primitive constructor"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `anonymous source enum constant aborts before any write`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("AnonymousEnumConstant.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            enum AnonymousHeat {
                HEATED(0xE88300) {
                    @Override int color() {
                        return 0;
                    }
                };
                private final int color;
                private AnonymousHeat(int color) {
                    this.color = color;
                }
                int color() {
                    return color;
                }
            }
            final class AnonymousEnumConstant {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(name(AnonymousHeat.HEATED));
                    return values;
                }
                private static String name(AnonymousHeat condition) {
                    return condition.name();
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(failure.message.orEmpty().contains("anonymous class body"), failure.message)
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `exact rich source enum singleton retains closed registry and supplier state`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("RichEnumConstant.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import java.util.function.Supplier;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            interface ProcessingFactory<T> {
                T create(Params params);
            }
            final class Params {}
            final class RichRecipe {
                RichRecipe(Params params) {}
            }
            final class Serializer<T> {
                private final ProcessingFactory<T> factory;
                Serializer(ProcessingFactory<T> factory) {
                    this.factory = factory;
                }
            }
            final class Holder<T> implements Supplier<T> {
                private final Supplier<T> supplier;
                Holder(Supplier<T> supplier) {
                    this.supplier = supplier;
                }
                public T get() {
                    return supplier.get();
                }
            }
            final class Register {
                static <T> Holder<T> register(Supplier<T> supplier) {
                    return new Holder<>(supplier);
                }
            }
            enum RichRecipeType {
                MIXING(RichRecipe::new);
                private final Holder<Serializer<RichRecipe>> serializerObject;
                private final Supplier<String> type;
                RichRecipeType(ProcessingFactory<RichRecipe> factory) {
                    serializerObject =
                        Register.register(() -> new Serializer<>(factory));
                    type = () -> "mixing";
                }
            }
            final class RichEnumConstant {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(keep(RichRecipeType.MIXING).name());
                    return values;
                }
                private static RichRecipeType keep(RichRecipeType type) {
                    return type;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())

        LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        val migrated = catalog.readText()
        assertTrue(
            migrated.contains("keep(RichRecipeType.MIXING).name()"),
            migrated
        )
        assertFalse(migrated.contains("PotionBrewing.POTION_MIXES"), migrated)
    }

    @Test
    fun `rich source enum callback capturing level aborts before any write`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("RichEnumProviderCapture.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import java.util.function.Supplier;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            enum ProviderCapturingType {
                MIXING(provider());
                private final Supplier<String> type;
                ProviderCapturingType(Level provider) {
                    type = () -> provider.toString();
                }
                private static Level provider() {
                    return null;
                }
            }
            final class RichEnumProviderCapture {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(keep(ProviderCapturingType.MIXING).name());
                    return values;
                }
                private static ProviderCapturingType keep(
                    ProviderCapturingType type
                ) {
                    return type;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("net.minecraft.world.level.Level"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `rich source enum static provider initializer aborts before any write`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("RichEnumStaticProvider.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import java.util.function.Supplier;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            enum StaticProviderType {
                MIXING;
                private static final Level PROVIDER = null;
                private final Supplier<String> type =
                    () -> PROVIDER.toString();
            }
            final class RichEnumStaticProvider {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(keep(StaticProviderType.MIXING).name());
                    return values;
                }
                private static StaticProviderType keep(StaticProviderType type) {
                    return type;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("net.minecraft.world.level.Level"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `project Items owner cannot impersonate native retained item constants`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("FakeItemsOwner.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class Items {
                static final Level POTION = null;
            }
            final class FakeItemsOwner {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> values = new ArrayList<>();
                    values.add(stringify(Items.POTION));
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(mix.from.value().toString());
                    return values;
                }
                private static String stringify(Object value) {
                    return String.valueOf(value);
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("net.minecraft.world.level.Level"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `public ungrounded caller aborts before source or access transformer writes`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("Catalog.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            final class Catalog {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    List<String> result = new ArrayList<>();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        result.add(mix.from.value().toString());
                    return result;
                }
                public static List<String> values() {
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(failure.message.orEmpty().contains("not a private source method"), failure.message)
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `anonymous cache value capture aborts before any write`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("AnonymousCapture.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            final class AnonymousCapture {
                static final List<String> VALUES = build();
                private static List<String> build() {
                    return new ArrayList<>() {{
                        for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                            add(mix.from.value().toString());
                    }};
                }
                static List<?> values(net.minecraft.world.level.Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(failure.message.orEmpty().contains("captures"), failure.message)
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `dead JEI connection witness does not prove deferred registration lifecycle`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        java.resolve("Catalog.java").writeText("""
            package example;
            import java.util.List;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            final class Catalog {
                static final List<PotionBrewing.Mix<Potion>> VALUES = build();
                private static List<PotionBrewing.Mix<Potion>> build() {
                    return PotionBrewing.POTION_MIXES;
                }
            }
        """.trimIndent())
        val unsafe = java.resolve("UnsafePlugin.java")
        unsafe.writeText("""
            package example;
            import java.util.Collection;
            import java.util.function.Supplier;
            import mezz.jei.api.IModPlugin;
            import mezz.jei.api.JeiPlugin;
            import mezz.jei.api.registration.IRecipeRegistration;
            import net.minecraft.client.Minecraft;
            @JeiPlugin
            final class UnsafePlugin implements IModPlugin {
                void categories(CategoryBuilder builder) {
                    builder.addRecipes(() -> Catalog.VALUES);
                }
                void deadConnectionWitness() {
                    Minecraft.getInstance().getConnection().getRecipeManager();
                }
                @Override
                public void registerRecipes(IRecipeRegistration registration) {
                }
                static final class CategoryBuilder {
                    CategoryBuilder addRecipes(Supplier<Collection<?>> recipes) {
                        return this;
                    }
                }
            }
        """.trimIndent())
        val before = unsafe.readText()

        assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertEquals(before, unsafe.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `dead factory construction does not prove JEI supplier transfer`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        java.resolve("Catalog.java").writeText("""
            package example;
            import java.util.List;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            final class Catalog {
                static final List<PotionBrewing.Mix<Potion>> VALUES = build();
                private static List<PotionBrewing.Mix<Potion>> build() {
                    return PotionBrewing.POTION_MIXES;
                }
            }
        """.trimIndent())
        val unsafe = java.resolve("DeadFactoryPlugin.java")
        unsafe.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.Collection;
            import java.util.List;
            import java.util.function.Consumer;
            import java.util.function.Supplier;
            import mezz.jei.api.IModPlugin;
            import mezz.jei.api.JeiPlugin;
            import mezz.jei.api.registration.IRecipeCategoryRegistration;
            import mezz.jei.api.registration.IRecipeRegistration;
            import net.minecraft.client.Minecraft;
            @JeiPlugin
            final class DeadFactoryPlugin implements IModPlugin {
                private final List<Category<?>> allCategories = new ArrayList<>();
                private void loadCategories() {
                    builder()
                        .addTypedRecipes(() -> "type")
                        .addRecipes(() -> Catalog.VALUES)
                        .build(DeadFactoryPlugin::factory);
                }
                private <T> Builder<T> builder() {
                    return new Builder<>();
                }
                @Override
                public void registerCategories(IRecipeCategoryRegistration registration) {
                    loadCategories();
                    registration.addRecipeCategories(
                        allCategories.toArray(
                            mezz.jei.api.recipe.category.IRecipeCategory[]::new));
                }
                @Override
                public void registerRecipes(IRecipeRegistration registration) {
                    allCategories.forEach(category -> category.registerRecipes(registration));
                }
                private static <T> Category<T> factory(Info<T> info) {
                    new Category<>(info);
                    return new Category<>(new Info<>(List::of));
                }
                final class Builder<T> {
                    private final List<Consumer<List<T>>> consumers = new ArrayList<>();
                    Builder<T> addConsumer(Consumer<List<T>> consumer) {
                        consumers.add(consumer);
                        return this;
                    }
                    Builder<T> addRecipes(Supplier<Collection<? extends T>> recipes) {
                        return addConsumer(values -> values.addAll(recipes.get()));
                    }
                    Builder<T> addTypedRecipes(Supplier<String> type) {
                        return addConsumer(values -> consume(values, type.get()));
                    }
                    Category<T> build(Factory<T> factory) {
                        Supplier<List<T>> recipes = () -> {
                            List<T> values = new ArrayList<>();
                            for (Consumer<List<T>> consumer : consumers)
                                consumer.accept(values);
                            return values;
                        };
                        Info<T> info = new Info<>(recipes);
                        Category<T> category = factory.create(info);
                        allCategories.add(category);
                        return category;
                    }
                }
                record Info<T>(Supplier<List<T>> recipes) {}
                interface Factory<T> {
                    Category<T> create(Info<T> info);
                }
                static <T> void consume(List<T> values, String type) {
                    Minecraft.getInstance().getConnection().getRecipeManager();
                }
                static final class Category<T> {
                    private final Supplier<List<T>> recipes;
                    Category(Info<T> info) {
                        this.recipes = info.recipes();
                    }
                    void registerRecipes(IRecipeRegistration registration) {
                        registration.addRecipes(null, recipes.get());
                    }
                }
            }
        """.trimIndent())
        val before = unsafe.readText()

        assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertEquals(before, unsafe.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `exact static imports migrate without widening unrelated same named values`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val file = java.resolve("Imported.java")
        file.writeText("""
            package example;
            import static net.minecraft.world.item.alchemy.PotionBrewing.POTION_MIXES;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            final class Imported {
                static final List<PotionBrewing.Mix<Potion>> VALUES = build();
                private static List<PotionBrewing.Mix<Potion>> build() {
                    return POTION_MIXES;
                }
                static final class Other {
                    static final String POTION_MIXES = "unrelated";
                }
                static String localValue() {
                    String POTION_MIXES = "local";
                    return POTION_MIXES;
                }
                static List<?> use(net.minecraft.world.level.Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())

        LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        val migrated = file.readText()
        assertFalse(migrated.contains("import static net.minecraft.world.item.alchemy.PotionBrewing.POTION_MIXES"), migrated)
        assertTrue(migrated.contains("modporterPotionBrewing.potionMixes"), migrated)
        assertTrue(migrated.contains("static final String POTION_MIXES = \"unrelated\""), migrated)
        assertTrue(migrated.contains("return POTION_MIXES;"), migrated)
        assertTrue(migrated.contains("level.potionBrewing()"), migrated)
    }

    @Test
    fun `canonical level server and client connection providers use their owned brewing instance`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("Catalog.java")
        catalog.writeText("""
            package example;
            import java.util.List;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            final class Catalog {
                static final List<PotionBrewing.Mix<Potion>> VALUES = build();
                private static List<PotionBrewing.Mix<Potion>> build() {
                    return PotionBrewing.POTION_MIXES;
                }
            }
        """.trimIndent())
        val consumers = java.resolve("Consumers.java")
        consumers.writeText("""
            package example;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.client.multiplayer.ClientPacketListener;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class Consumers {
                static List<?> fromBrewing(PotionBrewing brewing) {
                    Objects.requireNonNull(brewing);
                    return Catalog.VALUES;
                }
                static List<?> fromLevel(Level level) {
                    Objects.requireNonNull(level);
                    return Catalog.VALUES;
                }
                static List<?> fromServer(MinecraftServer server) {
                    Objects.requireNonNull(server);
                    return Catalog.VALUES;
                }
                static List<?> fromConnection(ClientPacketListener connection) {
                    Objects.requireNonNull(connection);
                    return Catalog.VALUES;
                }
            }
        """.trimIndent())

        LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        val migrated = consumers.readText()
        assertTrue(migrated.contains(
            "Catalog.modporter\$get\$VALUES(brewing)"
        ), migrated)
        assertTrue(migrated.contains(
            "Catalog.modporter\$get\$VALUES(level.potionBrewing())"
        ), migrated)
        assertTrue(migrated.contains(
            "Catalog.modporter\$get\$VALUES(server.potionBrewing())"
        ), migrated)
        assertTrue(migrated.contains(
            "Catalog.modporter\$get\$VALUES(connection.potionBrewing())"
        ), migrated)
        assertFalse(migrated.contains("PotionBrewing.bootstrap"), migrated)
        assertFalse(migrated.contains("PotionBrewing.EMPTY"), migrated)
        assertFalse(migrated.contains("Minecraft.getInstance"), migrated)
    }

    @Test
    fun `direct PotionBrewing cache elements remain forbidden`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("DirectOwnerCache.java")
        catalog.writeText("""
            package example;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class DirectOwnerCache {
                static final List<PotionBrewing> VALUES = build();
                private static List<PotionBrewing> build() {
                    PotionBrewing.POTION_MIXES.size();
                    return List.of();
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains(
                "retained owner type net.minecraft.world.item.alchemy.PotionBrewing"
            ),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `nullable level parameter without a dominating guard is not a provider root`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("NullableProvider.java")
        catalog.writeText("""
            package example;
            import java.util.List;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class NullableProvider {
                static final List<PotionBrewing.Mix<Potion>> VALUES = build();
                private static List<PotionBrewing.Mix<Potion>> build() {
                    return PotionBrewing.POTION_MIXES;
                }
                public static List<?> values(Level level) {
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("no real PotionBrewing provider"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `provider reassigned after a null guard is not a provider root`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("ReassignedProvider.java")
        catalog.writeText("""
            package example;
            import java.util.List;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class ReassignedProvider {
                static final List<PotionBrewing.Mix<Potion>> VALUES = build();
                private static List<PotionBrewing.Mix<Potion>> build() {
                    return PotionBrewing.POTION_MIXES;
                }
                public static List<?> values(Level level) {
                    if (level == null)
                        throw new IllegalArgumentException();
                    level = null;
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("no real PotionBrewing provider"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `block entity field guard is invalidated by an intervening source mutator`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("MutatedBlockEntityProvider.java")
        catalog.writeText("""
            package example;
            import java.util.List;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.state.BlockState;
            final class MutatedBlockEntityProvider extends BlockEntity {
                static final List<PotionBrewing.Mix<Potion>> VALUES = build();
                private static List<PotionBrewing.Mix<Potion>> build() {
                    return PotionBrewing.POTION_MIXES;
                }
                MutatedBlockEntityProvider(BlockPos pos, BlockState state) {
                    super(null, pos, state);
                }
                protected boolean update() {
                    if (level == null)
                        return false;
                    clearLevel();
                    return !VALUES.isEmpty();
                }
                private void clearLevel() {
                    level = null;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(
            failure.message.orEmpty().contains("no real PotionBrewing provider"),
            failure.message
        )
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `unknown Object cache element graph aborts before writes`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("UnknownWorldCache.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class WorldState {
                static final Level CURRENT = null;
            }
            final class UnknownWorldCache {
                static final List<Object> VALUES = build();
                private static List<Object> build() {
                    List<Object> values = new ArrayList<>();
                    values.add(WorldState.CURRENT);
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(mix.from.value());
                    return values;
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(failure.message.orEmpty().contains("unknown Object"), failure.message)
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `public factory closure retaining a world aborts before writes`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("RetainedWorldCache.java")
        catalog.writeText("""
            package example;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Objects;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            final class RetainedWorldState {
                static final Level CURRENT = null;
            }
            final class RetainedWorldCache {
                static final List<Carrier> VALUES = build();
                private static List<Carrier> build() {
                    List<Carrier> values = new ArrayList<>();
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES)
                        values.add(make());
                    return values;
                }
                public static Carrier make() {
                    return new Carrier();
                }
                static List<?> values(Level level) {
                    Objects.requireNonNull(level);
                    return VALUES;
                }
            }
            final class Carrier {
                final Level level = RetainedWorldState.CURRENT;
                Carrier() {}
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(failure.message.orEmpty().contains("cache lifetime"), failure.message)
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `mutable provider dependent field aborts before any write`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val catalog = java.resolve("MutableCatalog.java")
        catalog.writeText("""
            package example;
            import java.util.List;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            final class MutableCatalog {
                static List<PotionBrewing.Mix<Potion>> VALUES = build();
                private static List<PotionBrewing.Mix<Potion>> build() {
                    return PotionBrewing.POTION_MIXES;
                }
                static List<?> values(net.minecraft.world.level.Level level) {
                    return VALUES;
                }
            }
        """.trimIndent())
        val before = catalog.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(failure.message.orEmpty().contains("static final"), failure.message)
        assertEquals(before, catalog.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `provider dependent field cycle aborts before any write`() {
        val java = directory.resolve("src/main/java/example").createDirectories()
        val cycle = java.resolve("Cycle.java")
        cycle.writeText("""
            package example;
            import java.util.List;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            final class Cycle {
                static final List<PotionBrewing.Mix<Potion>> FIRST = Cycle.SECOND;
                static final List<PotionBrewing.Mix<Potion>> SECOND = build(FIRST);
                private static List<PotionBrewing.Mix<Potion>> build(
                    List<PotionBrewing.Mix<Potion>> ignored
                ) {
                    return PotionBrewing.POTION_MIXES;
                }
                static List<?> values(net.minecraft.world.level.Level level) {
                    return SECOND;
                }
            }
        """.trimIndent())
        val before = cycle.readText()

        val failure = assertFailsWith<IllegalStateException> {
            LegacyPotionBrewingInstances.migrate(StructuralRefactorPass(), directory, false)
        }
        assertTrue(failure.message.orEmpty().contains("cycle"), failure.message)
        assertEquals(before, cycle.readText())
        assertFalse(directory.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }
}
