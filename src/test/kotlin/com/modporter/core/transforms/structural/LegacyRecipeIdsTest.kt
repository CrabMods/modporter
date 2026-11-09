package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyRecipeIdsTest {
    @TempDir
    lateinit var tempDir: Path

    private val javaRoot: Path
        get() = tempDir.resolve("src/main/java/com/example")

    private fun java(name: String, source: String) {
        javaRoot.createDirectories()
        javaRoot.resolve("$name.java").writeText(source.trimIndent() + "\n")
    }

    private fun read(name: String): String =
        javaRoot.resolve("$name.java").readText()

    private fun migrate() {
        val pass = StructuralRefactorPass()
        val files = javaRoot.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.toPath() }
            .toList()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        LegacyRecipeIds.migrate(
            pass = pass,
            projectDir = tempDir,
            modId = "example",
            compatPackage = "com.modporter.generated.example.compat",
            dryRun = false
        )
    }

    @Test
    fun `compat package supplier stays lazy when no recipe identity candidate exists`() {
        java("NbtFixture", """
            package com.example;

            import net.minecraft.nbt.CompoundTag;

            public final class NbtFixture {
                public String getId() {
                    return "unrelated";
                }

                public CompoundTag save(CompoundTag tag) {
                    return tag;
                }
            }
        """)
        val pass = StructuralRefactorPass()
        val files = javaRoot.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.toPath() }
            .toList()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        var supplierCalls = 0

        val changes = LegacyRecipeIds.migrate(
            pass = pass,
            projectDir = tempDir,
            modId = null,
            compatPackageSupplier = {
                supplierCalls++
                "com.modporter.generated.example.compat"
            },
            dryRun = false
        )

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(0, supplierCalls)
        assertFalse(read("NbtFixture").contains("LegacyRecipeIds"))
    }

    @Test
    fun `raw recipe ids use the helper while source owned project ids remain intact`() {
        java("OwnedRecipe", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeInput;

            public abstract class OwnedRecipe implements Recipe<RecipeInput> {
                private final ResourceLocation id;

                protected OwnedRecipe(ResourceLocation id) {
                    this.id = id;
                }

                public ResourceLocation getId() {
                    return id;
                }
            }
        """)
        java("ChildOwnedRecipe", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;

            public abstract class ChildOwnedRecipe extends OwnedRecipe {
                protected ChildOwnedRecipe(ResourceLocation id) {
                    super(id);
                }
            }
        """)
        java("GeneratedPendingRecipe", """
            package com.example;

            import com.modporter.generated.example.compat.LegacyRecipeCodecs;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeInput;

            public abstract class GeneratedPendingRecipe
                    implements Recipe<RecipeInput>, LegacyRecipeCodecs.Identified {
                private ResourceLocation id;

                public ResourceLocation getId() {
                    return id;
                }

                public void modporter${'$'}setRecipeId(ResourceLocation id) {
                    this.id = id;
                }
            }
        """)
        java("UnrelatedIdentifiedRecipe", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeInput;
            import other.LegacyRecipeCodecs;

            public abstract class UnrelatedIdentifiedRecipe
                    implements Recipe<RecipeInput>, LegacyRecipeCodecs.Identified {
                public ResourceLocation getId() {
                    return ResourceLocation.fromNamespaceAndPath("example", "ordinary");
                }
            }
        """)
        java("LegacyBase", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;

            public abstract class LegacyBase {
                private final ResourceLocation id;

                protected LegacyBase(ResourceLocation id) {
                    this.id = id;
                }

                public ResourceLocation getId() {
                    return id;
                }
            }
        """)
        java("DefaultLegacyId", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;

            public interface DefaultLegacyId {
                default ResourceLocation getId() {
                    return ResourceLocation.fromNamespaceAndPath("example", "default");
                }
            }
        """)
        java("AbstractLegacyId", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;

            public interface AbstractLegacyId {
                ResourceLocation getId();
            }
        """)
        java("BaseInheritedRecipe", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeInput;

            public abstract class BaseInheritedRecipe extends LegacyBase
                    implements Recipe<RecipeInput> {
                protected BaseInheritedRecipe(ResourceLocation id) {
                    super(id);
                }
            }
        """)
        java("DefaultInheritedRecipe", """
            package com.example;

            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeInput;

            public abstract class DefaultInheritedRecipe
                    implements Recipe<RecipeInput>, DefaultLegacyId {
            }
        """)
        java("AbstractInterfaceRecipe", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeInput;

            public abstract class AbstractInterfaceRecipe extends LegacyBase
                    implements Recipe<RecipeInput>, AbstractLegacyId {
                protected AbstractInterfaceRecipe(ResourceLocation id) {
                    super(id);
                }
            }
        """)
        java("Unrelated", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;

            public final class Unrelated {
                public ResourceLocation getId() {
                    return ResourceLocation.fromNamespaceAndPath("example", "unrelated");
                }
            }
        """)
        java("Lookups", """
            package com.example;

            import java.util.function.Predicate;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.item.crafting.Recipe;

            public final class Lookups {
                public static final Predicate<? super Recipe<?>> ALLOWED =
                    recipe -> !recipe.getId().getPath().endsWith("_manual_only");

                public ResourceLocation raw(Recipe<?> recipe) {
                    return recipe.getId();
                }

                public ResourceLocation crafting(CraftingRecipe recipe) {
                    return recipe.getId();
                }

                public <T extends Recipe<?>> ResourceLocation generic(T recipe) {
                    return recipe.getId();
                }

                public ResourceLocation owned(OwnedRecipe recipe) {
                    return recipe.getId();
                }

                public ResourceLocation inherited(ChildOwnedRecipe recipe) {
                    return recipe.getId();
                }

                public ResourceLocation baseInherited(BaseInheritedRecipe recipe) {
                    return recipe.getId();
                }

                public ResourceLocation defaultInherited(DefaultInheritedRecipe recipe) {
                    return recipe.getId();
                }

                public ResourceLocation abstractInterface(AbstractInterfaceRecipe recipe) {
                    return recipe.getId();
                }

                public ResourceLocation unrelated(Unrelated value) {
                    String ignored = "value.getId()";
                    // value.getId() in a comment is not evidence.
                    return value.getId();
                }
            }
        """)

        migrate()

        val owned = read("OwnedRecipe")
        val child = read("ChildOwnedRecipe")
        val generatedPending = read("GeneratedPendingRecipe")
        val unrelatedIdentified = read("UnrelatedIdentifiedRecipe")
        val baseInherited = read("BaseInheritedRecipe")
        val defaultInherited = read("DefaultInheritedRecipe")
        val abstractInterface = read("AbstractInterfaceRecipe")
        val lookup = read("Lookups")
        assertTrue(owned.contains("implements Recipe<RecipeInput>, LegacyRecipeIds.SourceOwned") ||
            owned.contains("implements LegacyRecipeIds.SourceOwned, Recipe<RecipeInput>"), owned)
        assertTrue(owned.contains("public ResourceLocation getId()"), owned)
        assertTrue(owned.contains("public ResourceLocation modporter\$sourceRecipeId()"), owned)
        assertTrue(owned.contains("return getId();"), owned)
        listOf(child, baseInherited, defaultInherited, abstractInterface).forEach { migrated ->
            assertTrue(migrated.contains("LegacyRecipeIds.SourceOwned"), migrated)
            assertTrue(migrated.contains("modporter\$sourceRecipeId()"), migrated)
            assertTrue(migrated.contains("return getId();"), migrated)
        }
        assertTrue(
            generatedPending.contains("LegacyRecipeIds.PendingSourceOwned"),
            generatedPending
        )
        assertTrue(generatedPending.contains("modporter\$sourceRecipeId()"), generatedPending)
        assertTrue(unrelatedIdentified.contains("LegacyRecipeIds.SourceOwned"), unrelatedIdentified)
        assertFalse(
            unrelatedIdentified.contains("LegacyRecipeIds.PendingSourceOwned"),
            unrelatedIdentified
        )

        assertTrue(lookup.contains("LegacyRecipeIds.id(recipe).getPath()"), lookup)
        assertTrue(Regex("""return\s+LegacyRecipeIds\.id\(recipe\);""").findAll(lookup).count() == 3, lookup)
        assertTrue(lookup.contains("ResourceLocation owned(OwnedRecipe recipe) {\n        return recipe.getId();"), lookup)
        assertTrue(lookup.contains("ResourceLocation inherited(ChildOwnedRecipe recipe) {\n        return recipe.getId();"), lookup)
        assertTrue(lookup.contains("return recipe.getId();"), lookup)
        assertTrue(lookup.contains("return value.getId();"), lookup)
        assertTrue(lookup.contains("\"value.getId()\""), lookup)
    }

    @Test
    fun `records enums and exact wildcard imports participate in recipe identity migration`() {
        java("WildcardRecordRecipe", """
            package com.example;

            import net.minecraft.resources.*;
            import net.minecraft.world.item.crafting.*;

            public record WildcardRecordRecipe(ResourceLocation id)
                    implements Recipe<RecipeInput> {
                public ResourceLocation getId() {
                    return id;
                }
            }
        """)
        java("WildcardEnumRecipe", """
            package com.example;

            import net.minecraft.resources.*;
            import net.minecraft.world.item.crafting.*;

            public enum WildcardEnumRecipe implements Recipe<RecipeInput> {
                INSTANCE;

                public ResourceLocation getId() {
                    return ResourceLocation.fromNamespaceAndPath("example", "enum");
                }
            }
        """)
        java("RawRecordRecipe", """
            package com.example;

            import net.minecraft.world.item.crafting.*;

            public record RawRecordRecipe() implements Recipe<RecipeInput> {
            }
        """)
        java("RawEnumRecipe", """
            package com.example;

            import net.minecraft.world.item.crafting.*;

            public enum RawEnumRecipe implements Recipe<RecipeInput> {
                INSTANCE
            }
        """)
        java("AggregateLookups", """
            package com.example;

            import net.minecraft.resources.*;

            public final class AggregateLookups {
                public ResourceLocation recordId(RawRecordRecipe recipe) {
                    return recipe.getId();
                }

                public ResourceLocation enumId(RawEnumRecipe recipe) {
                    return recipe.getId();
                }
            }
        """)

        migrate()

        val record = read("WildcardRecordRecipe")
        val enum = read("WildcardEnumRecipe")
        val lookup = read("AggregateLookups")
        listOf(record, enum).forEach { migrated ->
            assertTrue(migrated.contains("LegacyRecipeIds.SourceOwned"), migrated)
            assertTrue(migrated.contains("modporter\$sourceRecipeId()"), migrated)
        }
        assertTrue(lookup.contains("LegacyRecipeIds.id(recipe)"), lookup)
        assertTrue(Regex("""LegacyRecipeIds\.id\(recipe\)""").findAll(lookup).count() == 2, lookup)
    }

    @Test
    fun `same package shadows and unresolved wildcard names fail closed`() {
        java("Recipe", """
            package com.example;

            public final class Recipe {
                public String getId() {
                    return "local";
                }
            }
        """)
        java("ShadowLookup", """
            package com.example;

            import net.minecraft.world.item.crafting.*;

            public final class ShadowLookup {
                public String id(Recipe recipe) {
                    return recipe.getId();
                }
            }
        """)
        java("UnknownWildcard", """
            package com.example;

            import unknown.recipes.*;

            public abstract class UnknownWildcard implements MaybeRecipe {
                public Object id(MaybeRecipe recipe) {
                    return recipe.getId();
                }
            }
        """)

        migrate()

        assertFalse(read("ShadowLookup").contains("LegacyRecipeIds.id"), read("ShadowLookup"))
        assertFalse(read("UnknownWildcard").contains("LegacyRecipeIds.id"), read("UnknownWildcard"))
    }

    @Test
    fun `only the exact transitive JEI transfer override is left for holder migration`() {
        java("ProjectTransfer", """
            package com.example;

            import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
            import net.minecraft.world.inventory.AbstractContainerMenu;

            public interface ProjectTransfer<R>
                    extends IRecipeTransferHandler<AbstractContainerMenu, R> {
            }
        """)
        java("BaseTransferHandler", """
            package com.example;

            import net.minecraft.world.item.crafting.CraftingRecipe;

            public abstract class BaseTransferHandler
                    implements ProjectTransfer<CraftingRecipe> {
            }
        """)
        java("BlueprintTransferHandler", """
            package com.example;

            import java.util.Optional;
            import mezz.jei.api.constants.RecipeTypes;
            import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
            import mezz.jei.api.recipe.RecipeType;
            import mezz.jei.api.recipe.transfer.IRecipeTransferError;
            import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.inventory.AbstractContainerMenu;
            import net.minecraft.world.inventory.MenuType;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.item.crafting.Recipe;

            public final class BlueprintTransferHandler extends BaseTransferHandler {
                public Class<AbstractContainerMenu> getContainerClass() {
                    return AbstractContainerMenu.class;
                }

                public Optional<MenuType<AbstractContainerMenu>> getMenuType() {
                    return Optional.empty();
                }

                public RecipeType<CraftingRecipe> getRecipeType() {
                    return null;
                }

                public IRecipeTransferError transferRecipe(
                        AbstractContainerMenu menu,
                        CraftingRecipe craftingRecipe,
                        IRecipeSlotsView slots,
                        Player player,
                        boolean maxTransfer,
                        boolean doTransfer) {
                    ResourceLocation id = craftingRecipe.getId();
                    return null;
                }

                public ResourceLocation transferRecipe(
                        Object unrelated,
                        Recipe<?> rawRecipe) {
                    return rawRecipe.getId();
                }
            }
        """)
        java("GenericTransferHandler", """
            package com.example;

            import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
            import mezz.jei.api.recipe.transfer.IRecipeTransferError;
            import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.inventory.AbstractContainerMenu;
            import net.minecraft.world.item.crafting.Recipe;

            public abstract class GenericTransferHandler<R extends Recipe<?>>
                    implements IRecipeTransferHandler<AbstractContainerMenu, R> {
                public IRecipeTransferError transferRecipe(
                        AbstractContainerMenu menu,
                        R recipe,
                        IRecipeSlotsView slots,
                        Player player,
                        boolean maxTransfer,
                        boolean doTransfer) {
                    ResourceLocation id = recipe.getId();
                    return null;
                }
            }
        """)
        java("HolderTransferHandler", """
            package com.example;

            import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
            import mezz.jei.api.recipe.transfer.IRecipeTransferError;
            import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.inventory.AbstractContainerMenu;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.item.crafting.RecipeHolder;

            public abstract class HolderTransferHandler
                    implements IRecipeTransferHandler<
                        AbstractContainerMenu,
                        RecipeHolder<CraftingRecipe>> {
                public IRecipeTransferError transferRecipe(
                        AbstractContainerMenu menu,
                        RecipeHolder<CraftingRecipe> recipe,
                        IRecipeSlotsView slots,
                        Player player,
                        boolean maxTransfer,
                        boolean doTransfer) {
                    return null;
                }

                public IRecipeTransferError transferRecipe(
                        AbstractContainerMenu menu,
                        CraftingRecipe rawRecipe,
                        IRecipeSlotsView slots,
                        Player player,
                        boolean maxTransfer,
                        boolean doTransfer) {
                    ResourceLocation id = rawRecipe.getId();
                    return null;
                }
            }
        """)

        migrate()

        val handler = read("BlueprintTransferHandler")
        assertTrue(handler.contains("craftingRecipe.getId()"), handler)
        assertFalse(handler.contains("LegacyRecipeIds.id(craftingRecipe)"), handler)
        assertTrue(handler.contains("LegacyRecipeIds.id(rawRecipe)"), handler)
        val generic = read("GenericTransferHandler")
        assertTrue(generic.contains("recipe.getId()"), generic)
        assertFalse(generic.contains("LegacyRecipeIds.id(recipe)"), generic)
        val holder = read("HolderTransferHandler")
        assertTrue(holder.contains("LegacyRecipeIds.id(rawRecipe)"), holder)
    }

    @Test
    fun `generated helper is exact weak identity and fails closed`() {
        java("Lookup", """
            package com.example;

            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;

            public final class Lookup {
                public ResourceLocation id(Recipe<?> recipe) {
                    return recipe.getId();
                }
            }
        """)

        migrate()

        val compat = tempDir.resolve(
            "src/main/java/com/modporter/generated/example/compat/LegacyRecipeIds.java"
        ).readText()
        assertTrue(compat.contains("ReferenceQueue<Recipe<?>>"), compat)
        assertTrue(compat.contains("ReferenceQueue<RecipeManager>"), compat)
        assertTrue(compat.contains("System.identityHashCode(recipe)"), compat)
        assertTrue(compat.contains("recipe == identity.get()"), compat)
        assertTrue(compat.contains("ManagerState previous = ACTIVE_SIDES.remove(side)"), compat)
        assertTrue(compat.contains("removeManagerState(previous);"), compat)
        assertTrue(compat.contains("assignServerIds"), compat)
        assertTrue(compat.contains("assignClientIds"), compat)
        assertTrue(compat.contains("clearServerIds"), compat)
        assertTrue(compat.contains("clearClientIds"), compat)
        assertTrue(compat.contains("interface PendingSourceOwned extends SourceOwned"), compat)
        assertTrue(compat.contains("active recipe managers disagree"), compat)
        assertTrue(compat.contains("the same object is held under multiple ids"), compat)
        assertTrue(compat.contains("\"pending_recipe_id\""), compat)
        assertTrue(
            compat.indexOf("sourceOwned.modporter\$sourceRecipeId()") <
                compat.indexOf("ResourceLocation loaded = loadedId(recipe)"),
            compat
        )
        assertTrue(compat.contains("if (sourceId == null)"), compat)
        assertTrue(compat.contains("throw unavailable(recipe"), compat)
        assertFalse(compat.contains("WeakHashMap"), compat)
        assertFalse(compat.contains("return PENDING_ID"), compat)
        assertFalse(compat.contains("default_recipe"), compat)

        val serverEvents = tempDir.resolve(
            "src/main/java/com/modporter/generated/example/compat/LegacyRecipeIdentityEvents.java"
        ).readText()
        val clientEvents = tempDir.resolve(
            "src/main/java/com/modporter/generated/example/compat/LegacyRecipeIdentityClientEvents.java"
        ).readText()
        assertTrue(serverEvents.contains("ServerStartingEvent"), serverEvents)
        assertTrue(serverEvents.contains("ServerStoppedEvent"), serverEvents)
        assertTrue(serverEvents.contains("OnDatapackSyncEvent"), serverEvents)
        assertTrue(clientEvents.contains("RecipesUpdatedEvent"), clientEvents)
        assertTrue(clientEvents.contains("EventPriority.HIGHEST"), clientEvents)
        assertTrue(
            clientEvents.contains("@SubscribeEvent(priority = EventPriority.HIGHEST)"),
            clientEvents
        )
        assertTrue(clientEvents.contains("ClientPlayerNetworkEvent.LoggingOut"), clientEvents)
        assertFalse(serverEvents.contains("ServerAboutToStartEvent"), serverEvents)
    }
}
