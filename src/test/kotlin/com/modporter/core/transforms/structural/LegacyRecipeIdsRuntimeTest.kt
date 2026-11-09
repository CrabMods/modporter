package com.modporter.core.transforms.structural

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class LegacyRecipeIdsRuntimeTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `templates compile and identity generations behave deterministically`() {
        val sources = mutableListOf<Path>()

        fun java(relative: String, source: String) {
            val file = tempDir.resolve("src").resolve(relative)
            file.parent.createDirectories()
            file.writeText(source.trimIndent() + "\n")
            sources.add(file)
        }

        fun template(name: String, vararg replacements: Pair<String, String>): String {
            var content = requireNotNull(javaClass.getResource("/compat/$name.java.template")) {
                "Missing compatibility template $name"
            }.readText().replace("@PACKAGE@", "example")
            replacements.forEach { (placeholder, value) ->
                content = content.replace(placeholder, value)
            }
            return content
        }

        java("example/LegacyRecipeIds.java", template("LegacyRecipeIds"))
        java(
            "example/LegacyRecipeIdentityEvents.java",
            template("LegacyRecipeIdentityEvents", "@MOD_ID_ARGUMENT@" to "modid = \"example\"")
        )
        java(
            "example/LegacyRecipeIdentityClientEvents.java",
            template(
                "LegacyRecipeIdentityClientEvents",
                "@CLIENT_SUBSCRIBER_ARGUMENTS@" to
                    "modid = \"example\", value = Dist.CLIENT"
            )
        )

        java("net/minecraft/resources/ResourceLocation.java", """
            package net.minecraft.resources;

            import java.util.Objects;

            public final class ResourceLocation {
                private final String namespace;
                private final String path;

                private ResourceLocation(String namespace, String path) {
                    this.namespace = namespace;
                    this.path = path;
                }

                public static ResourceLocation fromNamespaceAndPath(
                        String namespace,
                        String path) {
                    return new ResourceLocation(namespace, path);
                }

                public String getNamespace() {
                    return namespace;
                }

                public String getPath() {
                    return path;
                }

                @Override
                public boolean equals(Object other) {
                    return other instanceof ResourceLocation id
                        && namespace.equals(id.namespace)
                        && path.equals(id.path);
                }

                @Override
                public int hashCode() {
                    return Objects.hash(namespace, path);
                }

                @Override
                public String toString() {
                    return namespace + ":" + path;
                }
            }
        """)
        java("net/minecraft/world/item/crafting/Recipe.java", """
            package net.minecraft.world.item.crafting;

            public interface Recipe<T> {
            }
        """)
        java("net/minecraft/world/item/crafting/RecipeHolder.java", """
            package net.minecraft.world.item.crafting;

            import net.minecraft.resources.ResourceLocation;

            public record RecipeHolder<T extends Recipe<?>>(
                    ResourceLocation id,
                    T value) {
            }
        """)
        java("net/minecraft/world/item/crafting/RecipeManager.java", """
            package net.minecraft.world.item.crafting;

            import java.util.List;

            public final class RecipeManager {
                private List<RecipeHolder<?>> recipes;

                public RecipeManager(List<RecipeHolder<?>> recipes) {
                    this.recipes = recipes;
                }

                public List<RecipeHolder<?>> getRecipes() {
                    return recipes;
                }

                public void setRecipes(List<RecipeHolder<?>> recipes) {
                    this.recipes = recipes;
                }
            }
        """)
        java("net/minecraft/server/MinecraftServer.java", """
            package net.minecraft.server;

            import net.minecraft.world.item.crafting.RecipeManager;

            public final class MinecraftServer {
                private final RecipeManager recipes;

                public MinecraftServer(RecipeManager recipes) {
                    this.recipes = recipes;
                }

                public RecipeManager getRecipeManager() {
                    return recipes;
                }
            }
        """)
        java("net/minecraft/server/players/PlayerList.java", """
            package net.minecraft.server.players;

            import net.minecraft.server.MinecraftServer;

            public final class PlayerList {
                private final MinecraftServer server;

                public PlayerList(MinecraftServer server) {
                    this.server = server;
                }

                public MinecraftServer getServer() {
                    return server;
                }
            }
        """)
        java("net/neoforged/api/distmarker/Dist.java", """
            package net.neoforged.api.distmarker;

            public enum Dist {
                CLIENT,
                DEDICATED_SERVER
            }
        """)
        java("net/neoforged/bus/api/SubscribeEvent.java", """
            package net.neoforged.bus.api;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface SubscribeEvent {
                EventPriority priority() default EventPriority.NORMAL;
            }
        """)
        java("net/neoforged/bus/api/EventPriority.java", """
            package net.neoforged.bus.api;

            public enum EventPriority {
                HIGHEST,
                HIGH,
                NORMAL,
                LOW,
                LOWEST
            }
        """)
        java("net/neoforged/fml/common/EventBusSubscriber.java", """
            package net.neoforged.fml.common;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import net.neoforged.api.distmarker.Dist;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface EventBusSubscriber {
                String modid() default "";
                Dist[] value() default {};
            }
        """)
        java("net/neoforged/neoforge/event/server/ServerStartingEvent.java", """
            package net.neoforged.neoforge.event.server;

            import net.minecraft.server.MinecraftServer;

            public final class ServerStartingEvent {
                private final MinecraftServer server;

                public ServerStartingEvent(MinecraftServer server) {
                    this.server = server;
                }

                public MinecraftServer getServer() {
                    return server;
                }
            }
        """)
        java("net/neoforged/neoforge/event/server/ServerStoppedEvent.java", """
            package net.neoforged.neoforge.event.server;

            import net.minecraft.server.MinecraftServer;

            public final class ServerStoppedEvent {
                private final MinecraftServer server;

                public ServerStoppedEvent(MinecraftServer server) {
                    this.server = server;
                }

                public MinecraftServer getServer() {
                    return server;
                }
            }
        """)
        java("net/neoforged/neoforge/event/OnDatapackSyncEvent.java", """
            package net.neoforged.neoforge.event;

            import net.minecraft.server.players.PlayerList;

            public final class OnDatapackSyncEvent {
                private final PlayerList players;

                public OnDatapackSyncEvent(PlayerList players) {
                    this.players = players;
                }

                public PlayerList getPlayerList() {
                    return players;
                }
            }
        """)
        java("net/neoforged/neoforge/client/event/RecipesUpdatedEvent.java", """
            package net.neoforged.neoforge.client.event;

            import net.minecraft.world.item.crafting.RecipeManager;

            public final class RecipesUpdatedEvent {
                private final RecipeManager recipes;

                public RecipesUpdatedEvent(RecipeManager recipes) {
                    this.recipes = recipes;
                }

                public RecipeManager getRecipeManager() {
                    return recipes;
                }
            }
        """)
        java("net/neoforged/neoforge/client/event/ClientPlayerNetworkEvent.java", """
            package net.neoforged.neoforge.client.event;

            public abstract class ClientPlayerNetworkEvent {
                public static final class LoggingOut extends ClientPlayerNetworkEvent {
                }
            }
        """)
        java("example/SourceOwnedShapes.java", """
            package example;

            import net.minecraft.resources.*;
            import net.minecraft.world.item.crafting.*;

            public final class SourceOwnedShapes {
                private SourceOwnedShapes() {
                }

                public abstract static class LegacyBase {
                    private final ResourceLocation id;

                    protected LegacyBase(ResourceLocation id) {
                        this.id = id;
                    }

                    public ResourceLocation getId() {
                        return id;
                    }
                }

                public interface DefaultLegacyId {
                    default ResourceLocation getId() {
                        return ResourceLocation.fromNamespaceAndPath(
                            "example",
                            "default-interface"
                        );
                    }
                }

                public interface AbstractLegacyId {
                    ResourceLocation getId();
                }

                public static final class InheritedRecipe extends LegacyBase
                        implements Recipe<Object>, LegacyRecipeIds.SourceOwned {
                    public InheritedRecipe(ResourceLocation id) {
                        super(id);
                    }

                    @Override
                    public ResourceLocation modporter${'$'}sourceRecipeId() {
                        return getId();
                    }
                }

                public static final class AbstractContractRecipe extends LegacyBase
                        implements Recipe<Object>,
                        AbstractLegacyId,
                        LegacyRecipeIds.SourceOwned {
                    public AbstractContractRecipe(ResourceLocation id) {
                        super(id);
                    }

                    @Override
                    public ResourceLocation modporter${'$'}sourceRecipeId() {
                        return getId();
                    }
                }

                public static final class DefaultRecipe
                        implements Recipe<Object>,
                        DefaultLegacyId,
                        LegacyRecipeIds.SourceOwned {
                    @Override
                    public ResourceLocation modporter${'$'}sourceRecipeId() {
                        return getId();
                    }
                }

                public record RecordRecipe(ResourceLocation id)
                        implements Recipe<Object>, LegacyRecipeIds.SourceOwned {
                    public ResourceLocation getId() {
                        return id;
                    }

                    @Override
                    public ResourceLocation modporter${'$'}sourceRecipeId() {
                        return getId();
                    }
                }

                public enum EnumRecipe
                        implements Recipe<Object>, LegacyRecipeIds.SourceOwned {
                    INSTANCE;

                    public ResourceLocation getId() {
                        return ResourceLocation.fromNamespaceAndPath(
                            "example",
                            "enum"
                        );
                    }

                    @Override
                    public ResourceLocation modporter${'$'}sourceRecipeId() {
                        return getId();
                    }
                }
            }
        """)
        java("example/Probe.java", """
            package example;

            import java.lang.reflect.Method;
            import java.util.ArrayList;
            import java.util.Comparator;
            import java.util.List;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeHolder;
            import net.minecraft.world.item.crafting.RecipeManager;
            import net.neoforged.bus.api.EventPriority;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
            import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
            import net.neoforged.neoforge.event.server.ServerStoppedEvent;

            public final class Probe {
                private static ResourceLocation id(String path) {
                    return ResourceLocation.fromNamespaceAndPath("example", path);
                }

                private static RecipeManager manager(Recipe<?> recipe, String path) {
                    return new RecipeManager(List.of(new RecipeHolder<>(id(path), recipe)));
                }

                private static ResourceLocation rawRecipeId(Recipe<?> recipe) {
                    return LegacyRecipeIds.id(recipe);
                }

                private static void expectFailure(Runnable action, String message) {
                    try {
                        action.run();
                    } catch (IllegalStateException expected) {
                        return;
                    }
                    throw new AssertionError(message);
                }

                private record OrderedListener(EventPriority priority, Runnable action) {
                }

                private static void postRecipesUpdatedLikeNeoForge(
                        RecipeManager manager,
                        Runnable normalConsumer) {
                    final Method identityMethod;
                    try {
                        identityMethod = LegacyRecipeIdentityClientEvents.class.getMethod(
                            "onRecipesUpdated",
                            RecipesUpdatedEvent.class
                        );
                    } catch (ReflectiveOperationException exception) {
                        throw new AssertionError("client identity listener is unavailable", exception);
                    }
                    SubscribeEvent subscription =
                        identityMethod.getAnnotation(SubscribeEvent.class);
                    if (subscription == null) {
                        throw new AssertionError("client identity listener lost @SubscribeEvent");
                    }

                    List<OrderedListener> listeners = new ArrayList<>();
                    listeners.add(new OrderedListener(EventPriority.NORMAL, normalConsumer));
                    listeners.add(new OrderedListener(
                        subscription.priority(),
                        () -> LegacyRecipeIdentityClientEvents.onRecipesUpdated(
                            new RecipesUpdatedEvent(manager)
                        )
                    ));
                    listeners.sort(Comparator.comparingInt(
                        listener -> listener.priority().ordinal()
                    ));
                    listeners.forEach(listener -> listener.action().run());
                }

                private static final class ValueRecipe implements Recipe<Object> {
                    private final String value;

                    private ValueRecipe(String value) {
                        this.value = value;
                    }

                    @Override
                    public boolean equals(Object other) {
                        return other instanceof ValueRecipe;
                    }

                    @Override
                    public int hashCode() {
                        return 1;
                    }
                }

                private static final class OwnedRecipe
                        implements Recipe<Object>, LegacyRecipeIds.SourceOwned {
                    private final ResourceLocation sourceId;

                    private OwnedRecipe(ResourceLocation sourceId) {
                        this.sourceId = sourceId;
                    }

                    @Override
                    public ResourceLocation modporter${'$'}sourceRecipeId() {
                        return sourceId;
                    }
                }

                private static final class CountingOwnedRecipe
                        implements Recipe<Object>, LegacyRecipeIds.SourceOwned {
                    private final ResourceLocation alias;
                    private int calls;

                    private CountingOwnedRecipe(ResourceLocation alias) {
                        this.alias = alias;
                    }

                    public ResourceLocation getId() {
                        calls++;
                        return alias;
                    }

                    @Override
                    public ResourceLocation modporter${'$'}sourceRecipeId() {
                        return getId();
                    }

                    public int calls() {
                        return calls;
                    }
                }

                private static final class PendingOwnedRecipe
                        implements Recipe<Object>, LegacyRecipeIds.PendingSourceOwned {
                    private final ResourceLocation sourceId;
                    private int calls;

                    private PendingOwnedRecipe(ResourceLocation sourceId) {
                        this.sourceId = sourceId;
                    }

                    @Override
                    public ResourceLocation modporter${'$'}sourceRecipeId() {
                        calls++;
                        return sourceId;
                    }

                    public int calls() {
                        return calls;
                    }
                }

                public static void check() {
                    ValueRecipe firstClientConsumerRecipe =
                        new ValueRecipe("first-client-consumer");
                    RecipeManager firstClientConsumerManager =
                        manager(firstClientConsumerRecipe, "first-client-consumer");
                    postRecipesUpdatedLikeNeoForge(
                        firstClientConsumerManager,
                        () -> {
                            if (!LegacyRecipeIds.id(firstClientConsumerRecipe)
                                    .equals(id("first-client-consumer"))) {
                                throw new AssertionError(
                                    "normal recipe-sync consumer ran before identity assignment"
                                );
                            }
                        }
                    );
                    LegacyRecipeIds.clearClientIds();

                    ValueRecipe shared = new ValueRecipe("shared");
                    RecipeManager retainedOld = manager(shared, "old");
                    LegacyRecipeIds.assignServerIds(retainedOld);
                    if (!LegacyRecipeIds.id(shared).equals(id("old"))) {
                        throw new AssertionError("initial server identity missing");
                    }

                    RecipeManager replacement = manager(shared, "replacement");
                    LegacyRecipeIds.assignServerIds(replacement);
                    LegacyRecipeIds.clearServerIds(retainedOld);
                    if (!LegacyRecipeIds.id(shared).equals(id("replacement"))) {
                        throw new AssertionError("retained old manager survived generation replacement");
                    }

                    replacement.setRecipes(List.of(new RecipeHolder<>(id("reload"), shared)));
                    LegacyRecipeIds.assignServerIds(replacement);
                    if (!LegacyRecipeIds.id(shared).equals(id("reload"))) {
                        throw new AssertionError("same-manager reload did not replace its snapshot");
                    }

                    RecipeManager client = manager(shared, "reload");
                    LegacyRecipeIds.assignClientIds(client);
                    if (!LegacyRecipeIds.id(shared).equals(id("reload"))) {
                        throw new AssertionError("matching client and server managers did not coexist");
                    }

                    RecipeManager disagreeingClient = manager(shared, "client-disagrees");
                    LegacyRecipeIds.assignClientIds(disagreeingClient);
                    expectFailure(
                        () -> LegacyRecipeIds.id(shared),
                        "cross-side disagreement did not fail closed"
                    );
                    LegacyRecipeIdentityClientEvents.onClientLogout(
                        new ClientPlayerNetworkEvent.LoggingOut()
                    );
                    if (!LegacyRecipeIds.id(shared).equals(id("reload"))) {
                        throw new AssertionError("client disconnect removed server generation");
                    }

                    ValueRecipe firstEqual = new ValueRecipe("first");
                    ValueRecipe secondEqual = new ValueRecipe("second");
                    RecipeManager identityManager = new RecipeManager(List.of(
                        new RecipeHolder<>(id("first"), firstEqual),
                        new RecipeHolder<>(id("second"), secondEqual)
                    ));
                    LegacyRecipeIds.assignClientIds(identityManager);
                    if (!LegacyRecipeIds.id(firstEqual).equals(id("first"))
                            || !LegacyRecipeIds.id(secondEqual).equals(id("second"))) {
                        throw new AssertionError("equals replaced exact object identity");
                    }

                    RecipeManager ambiguous = new RecipeManager(List.of(
                        new RecipeHolder<>(id("one"), shared),
                        new RecipeHolder<>(id("two"), shared)
                    ));
                    LegacyRecipeIds.assignServerIds(ambiguous);
                    expectFailure(
                        () -> LegacyRecipeIds.id(shared),
                        "same object under multiple holder ids was accepted"
                    );

                    LegacyRecipeIdentityEvents.onServerStopped(
                        new ServerStoppedEvent(new MinecraftServer(ambiguous))
                    );
                    LegacyRecipeIds.clearClientIds();
                    expectFailure(
                        () -> LegacyRecipeIds.id(shared),
                        "cleared managers left a loaded identity"
                    );

                    RecipeManager nullHolder = new RecipeManager(List.of(
                        new RecipeHolder<>(null, shared)
                    ));
                    LegacyRecipeIds.assignServerIds(nullHolder);
                    expectFailure(
                        () -> LegacyRecipeIds.id(shared),
                        "null loaded holder id was accepted"
                    );
                    RecipeManager pendingHolder = new RecipeManager(List.of(
                        new RecipeHolder<>(
                            ResourceLocation.fromNamespaceAndPath(
                                "modporter",
                                "pending_recipe_id"
                            ),
                            shared
                        )
                    ));
                    LegacyRecipeIds.assignServerIds(pendingHolder);
                    expectFailure(
                        () -> LegacyRecipeIds.id(shared),
                        "pending loaded holder id was accepted"
                    );
                    LegacyRecipeIds.clearServerIds(pendingHolder);

                    OwnedRecipe owned = new OwnedRecipe(id("source"));
                    if (!LegacyRecipeIds.id(owned).equals(id("source"))) {
                        throw new AssertionError("fresh source-owned id was not used");
                    }
                    if (!LegacyRecipeIds.id(
                            new SourceOwnedShapes.InheritedRecipe(id("base"))
                        ).equals(id("base"))) {
                        throw new AssertionError("inherited source getter bridge failed");
                    }
                    if (!LegacyRecipeIds.id(
                            new SourceOwnedShapes.DefaultRecipe()
                        ).equals(id("default-interface"))) {
                        throw new AssertionError("default interface getter bridge failed");
                    }
                    if (!LegacyRecipeIds.id(
                            new SourceOwnedShapes.AbstractContractRecipe(id("abstract-contract"))
                        ).equals(id("abstract-contract"))) {
                        throw new AssertionError("abstract interface getter bridge failed");
                    }
                    if (!LegacyRecipeIds.id(
                            new SourceOwnedShapes.RecordRecipe(id("record"))
                        ).equals(id("record"))) {
                        throw new AssertionError("record source getter bridge failed");
                    }
                    if (!LegacyRecipeIds.id(
                            SourceOwnedShapes.EnumRecipe.INSTANCE
                        ).equals(id("enum"))) {
                        throw new AssertionError("enum source getter bridge failed");
                    }
                    RecipeManager loadedOwned = manager(owned, "loaded");
                    LegacyRecipeIds.assignServerIds(loadedOwned);
                    if (!LegacyRecipeIds.id(owned).equals(id("source"))) {
                        throw new AssertionError("loaded metadata overrode source virtual semantics");
                    }
                    LegacyRecipeIds.clearServerIds(loadedOwned);

                    CountingOwnedRecipe loadedAlias =
                        new CountingOwnedRecipe(id("virtual-alias"));
                    RecipeManager physicalHolder = manager(loadedAlias, "physical-holder");
                    LegacyRecipeIds.assignServerIds(physicalHolder);
                    if (!rawRecipeId(loadedAlias).equals(id("virtual-alias"))
                            || loadedAlias.calls() != 1) {
                        throw new AssertionError(
                            "raw Recipe dispatch did not invoke the virtual getter exactly once"
                        );
                    }
                    LegacyRecipeIds.clearServerIds(physicalHolder);

                    CountingOwnedRecipe ambiguousAlias =
                        new CountingOwnedRecipe(id("unambiguous-virtual"));
                    RecipeManager ambiguousPhysical = new RecipeManager(List.of(
                        new RecipeHolder<>(id("physical-one"), ambiguousAlias),
                        new RecipeHolder<>(id("physical-two"), ambiguousAlias)
                    ));
                    LegacyRecipeIds.assignServerIds(ambiguousPhysical);
                    if (!rawRecipeId(ambiguousAlias).equals(id("unambiguous-virtual"))
                            || ambiguousAlias.calls() != 1) {
                        throw new AssertionError(
                            "ambiguous holder metadata overrode a valid virtual getter"
                        );
                    }
                    LegacyRecipeIds.clearServerIds(ambiguousPhysical);

                    PendingOwnedRecipe pendingOwned = new PendingOwnedRecipe(
                        ResourceLocation.fromNamespaceAndPath(
                            "modporter",
                            "pending_recipe_id"
                        )
                    );
                    RecipeManager decodedHolder = manager(pendingOwned, "decoded-holder");
                    LegacyRecipeIds.assignServerIds(decodedHolder);
                    if (!LegacyRecipeIds.id(pendingOwned).equals(id("decoded-holder"))
                            || pendingOwned.calls() != 1) {
                        throw new AssertionError(
                            "pending source getter was not called exactly once before fallback"
                        );
                    }
                    LegacyRecipeIds.clearServerIds(decodedHolder);

                    OwnedRecipe nullOwned = new OwnedRecipe(null);
                    RecipeManager nullOwnedHolder = manager(nullOwned, "must-not-fallback");
                    LegacyRecipeIds.assignServerIds(nullOwnedHolder);
                    expectFailure(
                        () -> LegacyRecipeIds.id(nullOwned),
                        "null source id incorrectly fell back to loaded identity"
                    );
                    LegacyRecipeIds.clearServerIds(nullOwnedHolder);

                    expectFailure(
                        () -> LegacyRecipeIds.id(new OwnedRecipe(null)),
                        "null source id was accepted"
                    );
                    expectFailure(
                        () -> LegacyRecipeIds.id(
                            new OwnedRecipe(
                                ResourceLocation.fromNamespaceAndPath(
                                    "modporter",
                                    "pending_recipe_id"
                                )
                            )
                        ),
                        "pending source id was accepted"
                    );
                    expectFailure(
                        () -> LegacyRecipeIds.id(new ValueRecipe("fresh")),
                        "unknown fresh recipe was assigned a fabricated id"
                    );
                }
            }
        """)

        val classes = tempDir.resolve("classes").createDirectories()
        val diagnostics = ByteArrayOutputStream()
        val arguments = listOf("-d", classes.toString()) + sources.map { it.toString() }
        val result = ToolProvider.getSystemJavaCompiler().run(
            null,
            diagnostics,
            diagnostics,
            *arguments.toTypedArray()
        )
        assertEquals(0, result, diagnostics.toString())

        URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            loader.loadClass("example.Probe").getMethod("check").invoke(null)
        }
    }
}
