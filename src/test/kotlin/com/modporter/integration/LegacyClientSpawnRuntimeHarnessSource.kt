package com.modporter.integration

internal object LegacyClientSpawnRuntimeHarnessSource {
    fun source(packageName: String, modId: String): String {
        require(packageName.matches(Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"""))) {
            "Invalid Java package name: $packageName"
        }
        require(modId.matches(Regex("""[a-z][a-z0-9_.-]*"""))) {
            "Invalid mod id: $modId"
        }

        return """
            package $packageName;

            import com.google.gson.Gson;
            import com.google.gson.GsonBuilder;
            import com.google.gson.JsonArray;
            import com.google.gson.JsonElement;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonParser;
            import com.simibubi.create.content.logistics.box.PackageEntity;
            import com.simibubi.create.content.logistics.box.PackageItem;
            import com.simibubi.create.content.logistics.box.PackageStyles;
            import java.io.File;
            import java.io.IOException;
            import java.io.InputStream;
            import java.lang.reflect.Field;
            import java.net.URL;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.AtomicMoveNotSupportedException;
            import java.nio.file.Files;
            import java.nio.file.LinkOption;
            import java.nio.file.Path;
            import java.nio.file.StandardCopyOption;
            import java.nio.file.StandardOpenOption;
            import java.security.MessageDigest;
            import java.security.NoSuchAlgorithmException;
            import java.util.ArrayList;
            import java.util.Arrays;
            import java.util.Collections;
            import java.util.LinkedHashMap;
            import java.util.List;
            import java.util.Locale;
            import java.util.Map;
            import java.util.Set;
            import java.util.UUID;
            import java.util.regex.Pattern;
            import java.util.zip.ZipFile;
            import net.minecraft.SharedConstants;
            import net.minecraft.client.Minecraft;
            import net.minecraft.client.multiplayer.ClientLevel;
            import net.minecraft.client.player.LocalPlayer;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.network.chat.Component;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.util.Mth;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.RelativeMovement;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.component.ItemContainerContents;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.phys.Vec3;
            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.bus.api.EventPriority;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.ModList;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
            import net.neoforged.fml.loading.targets.CommonLaunchHandler;
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            import net.neoforged.neoforge.event.entity.EntityEvent;
            import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
            import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
            import net.neoforged.neoforge.event.entity.player.PlayerEvent;
            import net.neoforged.neoforge.event.tick.EntityTickEvent;
            import net.neoforged.neoforge.event.tick.ServerTickEvent;

            @EventBusSubscriber(modid = "$modId", value = Dist.CLIENT)
            public final class ModPorterStrictSpawnHarness {
                private static final String EXPECTED_PROFILE_ID =
                    "legacy-client-spawn-factory/package-entity/v1";
                private static final String RECEIPT_SCHEMA =
                    "modporter.strict-review.legacy-client-spawn-factory.v1";
                private static final String RECEIPT_MARKER =
                    "[ModPorterStrictSpawn] Receipt sealed:";
                private static final String ENABLED_PROPERTY = "modporter.strictSpawn.enabled";
                private static final String PROFILE_PROPERTY = "modporter.strictSpawn.profileId";
                private static final String VALIDATION_SESSION_PROPERTY = "modporter.strictSpawn.sessionId";
                private static final String BINDINGS_PROPERTY = "modporter.strictSpawn.expectedBindings";
                private static final String RECEIPT_PROPERTY = "modporter.strictSpawn.receipt";
                private static final String EVIDENCE_DIR_PROPERTY = "modporter.strictSpawn.evidenceDir";
                private static final String COMPLETED_PROPERTY = "modporter.strictSpawn.completed";
                private static final String WRAPPER_SESSION_ENV = "MINECRAFT_MOD_TEST_SESSION_ID";
                private static final String EXPECTED_MINECRAFT_VERSION = "1.21.1";
                private static final String EXPECTED_NEOFORGE_VERSION = "21.1.230";
                private static final int READY_FENCE_TICKS = 40;
                private static final int CLEANUP_FENCE_TICKS = 3;
                private static final int STAGE_TIMEOUT_TICKS = 400;
                private static final int GLOBAL_TIMEOUT_TICKS = 2400;
                // one chunk past the native tracking boundary ChunkMap applies to the entity
                private static final double TRACKER_EXIT_MARGIN_BLOCKS = 16.0D;
                private static final double EPSILON = 1.0E-9D;
                private static final Object LOCK = new Object();
                private static final Gson GSON = new GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .create();
                private static final List<String> REQUESTED_SCENARIOS = List.of(
                    "non_quantized_motion",
                    "packet_clamped_motion",
                    "tracker_reentry"
                );
                private static final List<String> BASE_SEMANTIC_CHECKS = List.of(
                    "join.pre_insertion",
                    "join.client_position",
                    "join.raw_packet_motion",
                    "join.pending_true",
                    "join.default_box_dimensions",
                    "size.post_join",
                    "size.decoded_box_dimensions",
                    "size.pending_false",
                    "size.deferred_average",
                    "pre_tick.before_package_tick",
                    "pre_tick.authoritative_motion",
                    "pre_tick.decoded_box_dimensions"
                );
                private static final List<String> TRACKER_SEMANTIC_CHECKS = List.of(
                    "tracker.same_server_entity",
                    "tracker.fresh_client_instance",
                    "tracker.lifecycle_repeated"
                );
                private static final Field PENDING_FIELD = requiredField(
                    "modporterLegacySpawnMotionPending"
                );
                private static final Field PENDING_X_FIELD = requiredField(
                    "modporterLegacySpawnMotionX"
                );
                private static final Field PENDING_Y_FIELD = requiredField(
                    "modporterLegacySpawnMotionY"
                );
                private static final Field PENDING_Z_FIELD = requiredField(
                    "modporterLegacySpawnMotionZ"
                );
                private static final Field FIRST_TICK_FIELD = requiredEntityField(
                    "firstTick"
                );

                private static boolean initialized;
                private static boolean sealed;
                private static boolean finalExitRequested;
                private static String profileId;
                private static String wrapperSessionId;
                private static String validationSessionId;
                private static Path bindingsPath;
                private static Path receiptPath;
                private static Path evidenceDir;
                private static JsonObject bindings;
                private static String runtimeMinecraftVersion;
                private static String runtimeNeoForgeVersion;
                private static String classpathSha256;
                private static JsonArray classpathEntries;
                private static JsonObject classResources;
                private static String javaExecutable;
                private static String javaExecutableSha256;
                private static ClientLevel clientWorld;
                private static ResourceKey<Level> serverWorldKey;
                private static UUID playerUuid;
                private static int processStartCount = 1;
                private static int worldLoadCount;
                private static int cleanupFailureCount;
                private static int overlapFailureCount;
                private static int clientIdentityOrdinal;
                private static long clientTicks;
                private static long serverTicks;
                private static long readyClientTick = -1L;
                private static long stageStartedServerTick;
                private static long failureCleanupStableServerTick = -1L;
                private static int scenarioIndex;
                private static Stage stage = Stage.WAITING_FOR_WORLD;
                private static String terminalFailure;
                private static ScenarioEvidence activeScenario;
                private static final List<ScenarioEvidence> scenarios = new ArrayList<>();
                private static final List<String> observedScenarioIds = new ArrayList<>();

                static {
                    scenarios.add(new ScenarioEvidence(
                        "non_quantized_motion",
                        new Vec3(0.123456789D, -0.234567891D, 0.345678913D),
                        1
                    ));
                    scenarios.add(new ScenarioEvidence(
                        "packet_clamped_motion",
                        new Vec3(4.25D, -4.5D, 4.75D),
                        1
                    ));
                    scenarios.add(new ScenarioEvidence(
                        "tracker_reentry",
                        new Vec3(-0.456789123D, 0.222222223D, -0.333333337D),
                        2
                    ));
                }

                // Fingerprinting immutable classpath inputs must finish before an integrated
                // server starts ticking; doing this first on ServerTick stalls that server.
                @EventBusSubscriber(modid = "$modId", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
                public static final class ClientPreparation {
                    @SubscribeEvent
                    public static void onClientSetup(FMLClientSetupEvent event) {
                        if (!enabled()) return;
                        long started = System.nanoTime();
                        synchronized (LOCK) {
                            if (initializeLocked()) {
                                System.out.println(
                                    "[ModPorterStrictSpawn] Runtime evidence prepared before world startup; elapsedMs="
                                        + (System.nanoTime() - started) / 1_000_000L
                                        + " thread=" + Thread.currentThread().getName()
                                );
                            }
                        }
                    }
                }

                private enum Stage {
                    WAITING_FOR_WORLD,
                    READY_FENCE,
                    SPAWN,
                    WAIT_FIRST_LIFECYCLE,
                    TRACKER_MOVE_OUT,
                    WAIT_TRACKER_EXIT,
                    TRACKER_MOVE_BACK,
                    WAIT_SECOND_LIFECYCLE,
                    CLEANUP,
                    WAIT_CLEANUP,
                    FAILURE_CLEANUP,
                    SEAL,
                    COMPLETE
                }

                private ModPorterStrictSpawnHarness() {
                }

                @SubscribeEvent(priority = EventPriority.HIGHEST)
                public static void onEntityJoin(EntityJoinLevelEvent event) {
                    if (!enabled() || !event.getLevel().isClientSide) {
                        return;
                    }
                    if (!(event.getEntity() instanceof PackageEntity entity)) {
                        return;
                    }
                    synchronized (LOCK) {
                        if (!initializeLocked()) {
                            return;
                        }
                        if (!isActiveEntityLocked(entity)) {
                            return;
                        }
                        try {
                            int expectedLifecycle = expectedJoinLifecycleIndexLocked();
                            if (expectedLifecycle < 0) {
                                overlapFailureCount++;
                                failLocked("unexpected client PackageEntity Join in stage " + stage);
                                return;
                            }
                            if (activeScenario.lifecycles.size() != expectedLifecycle) {
                                overlapFailureCount++;
                                failLocked(
                                    "client Join lifecycle overlap: expected index "
                                        + expectedLifecycle
                                        + " but existing lifecycle count is "
                                        + activeScenario.lifecycles.size()
                                );
                                return;
                            }

                            LifecycleEvidence lifecycle = new LifecycleEvidence();
                            lifecycle.ordinal = expectedLifecycle + 1;
                            lifecycle.clientEntity = entity;
                            lifecycle.clientEntityId = entity.getId();
                            lifecycle.clientEntityUuid = entity.getUUID();
                            lifecycle.identityToken =
                                (++clientIdentityOrdinal)
                                    + ":"
                                    + Integer.toUnsignedString(
                                        System.identityHashCode(entity),
                                        16
                                    );
                            lifecycle.joinClientTick = clientTicks;
                            lifecycle.joinPosition = entity.position();
                            lifecycle.joinMotion = entity.getDeltaMovement();
                            lifecycle.joinClientPosition = entity.clientPosition;
                            lifecycle.joinPending = pending(entity);
                            lifecycle.joinPendingMotion = pendingMotion(entity);
                            lifecycle.joinBox = entity.getBox().copy();
                            lifecycle.joinWidth = entity.getBbWidth();
                            lifecycle.joinHeight = entity.getBbHeight();
                            lifecycle.joinFirstTick = firstTick(entity);
                            lifecycle.joinTickCount = entity.tickCount;
                            lifecycle.joined = true;
                            activeScenario.lifecycles.add(lifecycle);

                            lifecycle.joinPreInsertionPassed =
                                lifecycle.joinTickCount == 0
                                    && lifecycle.joinFirstTick
                                    && lifecycle.clientEntityId == activeScenario.serverEntityId
                                    && lifecycle.clientEntityUuid.equals(activeScenario.serverEntityUuid);
                            lifecycle.joinClientPositionPassed =
                                lifecycle.joinClientPosition != null
                                    && closeVec(
                                        lifecycle.joinClientPosition,
                                        activeScenario.spawnPosition,
                                        EPSILON
                                    )
                                    && closeVec(
                                        lifecycle.joinPosition,
                                        activeScenario.spawnPosition,
                                        EPSILON
                                    );
                            lifecycle.joinRawMotionPassed =
                                rawMotionMatches(
                                    lifecycle.joinMotion,
                                    activeScenario.packetRawMotion
                                );
                            lifecycle.joinPendingTruePassed =
                                lifecycle.joinPending
                                    && closeVec(
                                        lifecycle.joinPendingMotion,
                                        activeScenario.packetDecodedMotion,
                                        EPSILON
                                    );
                            lifecycle.joinDefaultBoxDimensionsPassed =
                                lifecycle.joinBox.isEmpty()
                                    && close(lifecycle.joinWidth, 1.0D, EPSILON)
                                    && close(lifecycle.joinHeight, 1.0D, EPSILON);

                            requireLifecycleCheckLocked(
                                lifecycle.joinPreInsertionPassed,
                                "Join was not the real pre-insertion client state"
                            );
                            requireLifecycleCheckLocked(
                                lifecycle.joinClientPositionPassed,
                                "Join clientPosition/position did not match the pairing position"
                            );
                            requireLifecycleCheckLocked(
                                lifecycle.joinRawMotionPassed,
                                "Join motion did not expose the real raw pairing packet integers"
                            );
                            requireLifecycleCheckLocked(
                                lifecycle.joinPendingTruePassed,
                                "Join did not retain the generated pending packet motion"
                            );
                            requireLifecycleCheckLocked(
                                lifecycle.joinDefaultBoxDimensionsPassed,
                                "Join did not precede payload box insertion at default dimensions"
                            );
                        } catch (Throwable throwable) {
                            failLocked("client Join observation failed: " + describe(throwable));
                        }
                    }
                }

                @SubscribeEvent(priority = EventPriority.HIGHEST)
                public static void onEntitySize(EntityEvent.Size event) {
                    if (!enabled()) {
                        return;
                    }
                    if (!(event.getEntity() instanceof PackageEntity entity)
                        || !entity.level().isClientSide) {
                        return;
                    }
                    synchronized (LOCK) {
                        if (!initialized || !isActiveEntityLocked(entity)) {
                            return;
                        }
                        LifecycleEvidence lifecycle = lifecycleForEntityLocked(entity);
                        if (lifecycle == null || !lifecycle.joined || lifecycle.sizeObserved) {
                            return;
                        }
                        if (entity.getBox().isEmpty()) {
                            return;
                        }
                        try {
                            lifecycle.sizeClientTick = clientTicks;
                            lifecycle.sizeMotion = entity.getDeltaMovement();
                            lifecycle.sizePending = pending(entity);
                            lifecycle.sizePendingMotion = pendingMotion(entity);
                            lifecycle.sizeBox = entity.getBox().copy();
                            lifecycle.sizeOldWidth = event.getOldSize().width();
                            lifecycle.sizeOldHeight = event.getOldSize().height();
                            lifecycle.sizeNewWidth = event.getNewSize().width();
                            lifecycle.sizeNewHeight = event.getNewSize().height();
                            lifecycle.sizeObserved = true;
                            lifecycle.sizePostJoinPassed =
                                lifecycle.sizeClientTick >= lifecycle.joinClientTick;
                            lifecycle.sizeDecodedBoxDimensionsPassed =
                                sameStack(
                                    lifecycle.sizeBox,
                                    activeScenario.expectedBox
                                )
                                    && close(
                                        lifecycle.sizeNewWidth,
                                        activeScenario.expectedWidth,
                                        EPSILON
                                    )
                                    && close(
                                        lifecycle.sizeNewHeight,
                                        activeScenario.expectedHeight,
                                        EPSILON
                                    );
                            lifecycle.sizePendingFalsePassed =
                                !lifecycle.sizePending;
                            lifecycle.sizeDeferredAveragePassed =
                                closeVec(
                                    lifecycle.sizeMotion,
                                    activeScenario.deferredAverageMotion,
                                    EPSILON
                                );

                            requireLifecycleCheckLocked(
                                lifecycle.sizePostJoinPassed,
                                "decoded Size event did not follow the correlated Join"
                            );
                            requireLifecycleCheckLocked(
                                lifecycle.sizeDecodedBoxDimensionsPassed,
                                "decoded Size event did not expose the exact box components/dimensions"
                            );
                            requireLifecycleCheckLocked(
                                lifecycle.sizePendingFalsePassed,
                                "generated pending flag was not cleared before decoded Size"
                            );
                            requireLifecycleCheckLocked(
                                lifecycle.sizeDeferredAveragePassed,
                                "decoded Size did not observe the deferred legacy average"
                            );
                        } catch (Throwable throwable) {
                            failLocked("client Size observation failed: " + describe(throwable));
                        }
                    }
                }

                @SubscribeEvent(priority = EventPriority.HIGHEST)
                public static void onEntityPreTick(EntityTickEvent.Pre event) {
                    if (!enabled() || !(event.getEntity() instanceof PackageEntity entity)) {
                        return;
                    }
                    synchronized (LOCK) {
                        if (!initializeLocked()) {
                            return;
                        }
                        if (activeScenario == null
                            || entity.getId() != activeScenario.serverEntityId
                            || !entity.getUUID().equals(activeScenario.serverEntityUuid)) {
                            return;
                        }
                        if (!entity.level().isClientSide) {
                            if (entity == activeScenario.serverEntity) {
                                event.setCanceled(true);
                            }
                            return;
                        }

                        LifecycleEvidence lifecycle = lifecycleForEntityLocked(entity);
                        if (lifecycle == null || lifecycle.preTickObserved) {
                            return;
                        }
                        if (!lifecycle.sizeObserved) {
                            failLocked("first client EntityTick.Pre arrived before decoded Size");
                            return;
                        }
                        try {
                            lifecycle.preTickClientTick = clientTicks;
                            lifecycle.preTickEntityTickCount = entity.tickCount;
                            lifecycle.preTickFirstTick = firstTick(entity);
                            lifecycle.preTickMotion = entity.getDeltaMovement();
                            lifecycle.preTickBox = entity.getBox().copy();
                            lifecycle.preTickWidth = entity.getBbWidth();
                            lifecycle.preTickHeight = entity.getBbHeight();
                            lifecycle.preTickObserved = true;
                            lifecycle.preTickBeforePackageTickPassed =
                                firstPackageTick(
                                    lifecycle.joinTickCount,
                                    lifecycle.preTickEntityTickCount,
                                    lifecycle.preTickFirstTick
                                );
                            lifecycle.preTickAuthoritativeMotionPassed =
                                closeVec(
                                    lifecycle.preTickMotion,
                                    activeScenario.payloadFloatMotion,
                                    0.0D
                                );
                            lifecycle.preTickDecodedBoxDimensionsPassed =
                                sameStack(
                                    lifecycle.preTickBox,
                                    activeScenario.expectedBox
                                )
                                    && close(
                                        lifecycle.preTickWidth,
                                        activeScenario.expectedWidth,
                                        EPSILON
                                    )
                                    && close(
                                        lifecycle.preTickHeight,
                                        activeScenario.expectedHeight,
                                        EPSILON
                                    );

                            requireLifecycleCheckLocked(
                                lifecycle.preTickBeforePackageTickPassed,
                                "EntityTick.Pre was not the first PackageEntity tick"
                            );
                            requireLifecycleCheckLocked(
                                lifecycle.preTickAuthoritativeMotionPassed,
                                "EntityTick.Pre did not expose exact payload float motion"
                            );
                            requireLifecycleCheckLocked(
                                lifecycle.preTickDecodedBoxDimensionsPassed,
                                "EntityTick.Pre did not retain exact decoded box dimensions/components"
                            );
                            lifecycle.complete = lifecycleBasePassed(lifecycle);
                            if (!lifecycle.complete) {
                                failLocked(
                                    "client lifecycle "
                                        + lifecycle.ordinal
                                        + " completed with a failed semantic check"
                                );
                                return;
                            }

                            if (activeScenario.expectedLifecycleCount == 2
                                && lifecycle.ordinal == 1) {
                                setStageLocked(Stage.TRACKER_MOVE_OUT);
                            } else if (lifecycle.ordinal
                                == activeScenario.expectedLifecycleCount) {
                                setStageLocked(Stage.CLEANUP);
                            }
                        } catch (Throwable throwable) {
                            failLocked(
                                "client EntityTick.Pre observation failed: "
                                    + describe(throwable)
                            );
                        }
                    }
                }

                @SubscribeEvent(priority = EventPriority.HIGHEST)
                public static void onEntityLeave(EntityLeaveLevelEvent event) {
                    if (!enabled() || !event.getLevel().isClientSide) {
                        return;
                    }
                    if (!(event.getEntity() instanceof PackageEntity entity)) {
                        return;
                    }
                    synchronized (LOCK) {
                        if (!initialized || activeScenario == null) {
                            return;
                        }
                        if (entity.getId() != activeScenario.serverEntityId
                            || !entity.getUUID().equals(activeScenario.serverEntityUuid)) {
                            return;
                        }
                        if (stage == Stage.WAIT_TRACKER_EXIT
                            && activeScenario.expectedLifecycleCount == 2
                            && !activeScenario.lifecycles.isEmpty()
                            && activeScenario.lifecycles.get(0).clientEntity == entity) {
                            activeScenario.clientTrackerLeaveObserved = true;
                            activeScenario.clientTrackerLeaveTick = clientTicks;
                        }
                    }
                }

                @SubscribeEvent(priority = EventPriority.HIGHEST)
                public static void onStartTracking(PlayerEvent.StartTracking event) {
                    if (!enabled() || !(event.getTarget() instanceof PackageEntity entity)) {
                        return;
                    }
                    synchronized (LOCK) {
                        if (!initialized || activeScenario == null) {
                            return;
                        }
                        if (entity == activeScenario.serverEntity
                            && event.getEntity().getUUID().equals(playerUuid)) {
                            activeScenario.serverStartTrackingCount++;
                            activeScenario.serverStartTrackingTicks.add(serverTicks);
                        }
                    }
                }

                @SubscribeEvent(priority = EventPriority.HIGHEST)
                public static void onStopTracking(PlayerEvent.StopTracking event) {
                    if (!enabled() || !(event.getTarget() instanceof PackageEntity entity)) {
                        return;
                    }
                    synchronized (LOCK) {
                        if (!initialized || activeScenario == null) {
                            return;
                        }
                        if (entity == activeScenario.serverEntity
                            && event.getEntity().getUUID().equals(playerUuid)) {
                            activeScenario.serverStopTrackingCount++;
                            activeScenario.serverStopTrackingTicks.add(serverTicks);
                        }
                    }
                }

                @SubscribeEvent
                public static void onClientTick(ClientTickEvent.Post event) {
                    if (!enabled()) {
                        return;
                    }
                    synchronized (LOCK) {
                        clientTicks++;
                        if (!initializeLocked()) {
                            return;
                        }
                        Minecraft minecraft = Minecraft.getInstance();
                        if (minecraft.level == null || minecraft.player == null) {
                            if (clientTicks > GLOBAL_TIMEOUT_TICKS) {
                                failLocked("client world/player did not become ready");
                            }
                            return;
                        }
                        if (clientWorld == null) {
                            clientWorld = minecraft.level;
                            playerUuid = minecraft.player.getUUID();
                            worldLoadCount = 1;
                            readyClientTick = clientTicks;
                            setStageLocked(Stage.READY_FENCE);
                        } else if (clientWorld != minecraft.level) {
                            worldLoadCount++;
                            failLocked("strict spawn harness observed more than one client world load");
                            return;
                        }

                        if (stage == Stage.READY_FENCE
                            && clientTicks - readyClientTick >= READY_FENCE_TICKS) {
                            setStageLocked(Stage.SPAWN);
                        }

                        if (serverTicks > GLOBAL_TIMEOUT_TICKS && stage != Stage.COMPLETE) {
                            failLocked("strict spawn harness exceeded global tick timeout");
                        }
                        if (stageTimedOutLocked()) {
                            failLocked("strict spawn harness stage timed out: " + stage);
                        }
                    }
                }

                @SubscribeEvent
                public static void onServerTick(ServerTickEvent.Post event) {
                    if (!enabled()) {
                        return;
                    }
                    synchronized (LOCK) {
                        serverTicks++;
                        if (!initializeLocked()) {
                            return;
                        }
                        try {
                            driveServerLocked(event.getServer());
                        } catch (Throwable throwable) {
                            failLocked("server state machine failed: " + describe(throwable));
                        }
                    }
                }

                private static void driveServerLocked(MinecraftServer server) {
                    if (stage == Stage.WAITING_FOR_WORLD
                        || stage == Stage.READY_FENCE
                        || stage == Stage.COMPLETE) {
                        return;
                    }
                    if (stage == Stage.FAILURE_CLEANUP) {
                        pollFailureCleanupLocked(server);
                        return;
                    }
                    ServerPlayer player = playerUuid == null
                        ? null
                        : server.getPlayerList().getPlayer(playerUuid);
                    if (player == null) {
                        failLocked("integrated server player is unavailable");
                        return;
                    }
                    if (serverWorldKey == null) {
                        serverWorldKey = player.serverLevel().dimension();
                    } else if (serverWorldKey != player.serverLevel().dimension()) {
                        failLocked("player changed dimensions during strict spawn validation");
                        return;
                    }

                    switch (stage) {
                        case SPAWN -> spawnScenarioLocked(player);
                        case TRACKER_MOVE_OUT -> moveTrackerOutLocked(player);
                        case WAIT_TRACKER_EXIT -> {
                            if (activeScenario.clientTrackerLeaveObserved
                                && activeScenario.serverStopTrackingCount >= 1) {
                                setStageLocked(Stage.TRACKER_MOVE_BACK);
                            }
                        }
                        case TRACKER_MOVE_BACK -> moveTrackerBackLocked(player);
                        case CLEANUP -> beginCleanupLocked(player.serverLevel());
                        case WAIT_CLEANUP -> pollCleanupLocked(player.serverLevel());
                        case FAILURE_CLEANUP -> pollFailureCleanupLocked(server);
                        case SEAL -> sealReceiptLocked(terminalFailure == null);
                        default -> {
                        }
                    }
                    if (stageTimedOutLocked()) {
                        failLocked("strict spawn harness stage timed out: " + stage);
                    }
                }

                private static void spawnScenarioLocked(ServerPlayer player) {
                    if (scenarioIndex < 0 || scenarioIndex >= scenarios.size()) {
                        failLocked("scenario index is outside the requested scenario set");
                        return;
                    }
                    if (activeScenario != null) {
                        overlapFailureCount++;
                        failLocked("attempted to spawn a scenario while another scenario is active");
                        return;
                    }
                    ScenarioEvidence scenario = scenarios.get(scenarioIndex);
                    scenario.startedAtTick = serverTicks;
                    scenario.spawnPosition = player.position().add(2.25D, 1.25D, 2.25D);
                    scenario.expectedBox = createBoxLocked(scenario);
                    scenario.expectedWidth = PackageItem.getWidth(scenario.expectedBox);
                    scenario.expectedHeight = PackageItem.getHeight(scenario.expectedBox);
                    scenario.packetRawMotion = rawPacketMotion(scenario.serverMotion);
                    scenario.packetDecodedMotion = new Vec3(
                        scenario.packetRawMotion[0] / 8000.0D,
                        scenario.packetRawMotion[1] / 8000.0D,
                        scenario.packetRawMotion[2] / 8000.0D
                    );
                    scenario.deferredAverageMotion = new Vec3(
                        (scenario.packetRawMotion[0] + scenario.packetDecodedMotion.x) * 0.5D,
                        (scenario.packetRawMotion[1] + scenario.packetDecodedMotion.y) * 0.5D,
                        (scenario.packetRawMotion[2] + scenario.packetDecodedMotion.z) * 0.5D
                    );
                    scenario.payloadFloatMotion = new Vec3(
                        (double)(float)scenario.serverMotion.x,
                        (double)(float)scenario.serverMotion.y,
                        (double)(float)scenario.serverMotion.z
                    );

                    PackageEntity entity = PackageEntity.fromItemStack(
                        player.serverLevel(),
                        scenario.spawnPosition,
                        scenario.expectedBox
                    );
                    entity.setNoGravity(true);
                    entity.setInvulnerable(true);
                    entity.setSilent(true);
                    entity.setDeltaMovement(scenario.serverMotion);
                    scenario.serverEntity = entity;
                    scenario.serverEntityId = entity.getId();
                    scenario.serverEntityUuid = entity.getUUID();
                    scenario.serverIdentityToken =
                        Integer.toUnsignedString(System.identityHashCode(entity), 16);
                    activeScenario = scenario;

                    boolean added = player.serverLevel().addFreshEntity(entity);
                    scenario.addFreshEntityReturned = added;
                    scenario.serverEntityId = entity.getId();
                    if (!added || !entity.isAddedToLevel()) {
                        failLocked("production PackageEntity addFreshEntity returned false");
                        return;
                    }
                    observedScenarioIds.add(scenario.id);
                    setStageLocked(Stage.WAIT_FIRST_LIFECYCLE);
                    System.out.println(
                        "[ModPorterStrictSpawn] Spawned "
                            + scenario.id
                            + " id="
                            + scenario.serverEntityId
                            + " uuid="
                            + scenario.serverEntityUuid
                    );
                }

                private static ItemStack createBoxLocked(ScenarioEvidence scenario) {
                    ItemStack box = PackageStyles.getDefaultBox();
                    if (!PackageItem.isPackage(box)) {
                        throw new IllegalStateException("PackageStyles.getDefaultBox was not a package");
                    }
                    box.set(
                        DataComponents.CUSTOM_NAME,
                        Component.literal("modporter-strict:" + scenario.id)
                    );
                    box.set(
                        DataComponents.CONTAINER,
                        ItemContainerContents.fromItems(
                            List.of(
                                new ItemStack(Items.DIAMOND, scenarioIndex + 1),
                                new ItemStack(Items.REDSTONE, 17 + scenarioIndex),
                                new ItemStack(Items.COPPER_INGOT, 5 + scenarioIndex)
                            )
                        )
                    );
                    PackageItem.addAddress(
                        box,
                        "strict://"
                            + validationSessionId
                            + "/"
                            + scenario.id
                    );
                    return box;
                }

                private static void moveTrackerOutLocked(ServerPlayer player) {
                    if (activeScenario == null
                        || activeScenario.expectedLifecycleCount != 2
                        || activeScenario.lifecycles.size() != 1
                        || !activeScenario.lifecycles.get(0).complete) {
                        failLocked("tracker exit was requested before the first lifecycle completed");
                        return;
                    }
                    // the baseline is taken once; the stage may wait for the destination to stream in
                    if (activeScenario.trackerPlayerSnapshot == null) {
                        activeScenario.trackerPlayerSnapshot = new PlayerSnapshot(player);
                        player.setInvulnerable(true);
                        player.setNoGravity(true);
                        player.setDeltaMovement(Vec3.ZERO);
                    }
                    PlayerSnapshot snapshot = activeScenario.trackerPlayerSnapshot;
                    PackageEntity entity = activeScenario.serverEntity;
                    if (entity == null
                        || entity.isRemoved()
                        || entity.level() != player.serverLevel()) {
                        failLocked("tracker exit has no live server entity to measure from");
                        return;
                    }
                    // ChunkMap tracks the entity within min(scaled type range, view distance);
                    // one chunk past it the client must drop the entity, in chunks the player holds
                    int typeRangeBlocks = entity.getType().clientTrackingRange() * 16;
                    int scaledRangeBlocks = player.server.getScaledTrackingDistance(typeRangeBlocks);
                    int viewDistanceBlocks = player.server.getPlayerList().getViewDistance() * 16;
                    double effectiveRangeBlocks = Math.min(scaledRangeBlocks, viewDistanceBlocks);
                    double destinationX = entity.getX() + effectiveRangeBlocks + TRACKER_EXIT_MARGIN_BLOCKS;
                    double exitDx = destinationX - entity.getX();
                    double exitDz = snapshot.position.z - entity.getZ();
                    activeScenario.trackerTypeRangeBlocks = typeRangeBlocks;
                    activeScenario.trackerScaledRangeBlocks = scaledRangeBlocks;
                    activeScenario.trackerViewDistanceBlocks = viewDistanceBlocks;
                    activeScenario.trackerEffectiveRangeBlocks = effectiveRangeBlocks;
                    activeScenario.trackerExitDistanceBlocks = Math.sqrt(exitDx * exitDx + exitDz * exitDz);
                    activeScenario.trackerDestinationResident = player.serverLevel().hasChunkAt(
                        BlockPos.containing(destinationX, snapshot.position.y, snapshot.position.z)
                    );
                    if (!activeScenario.trackerDestinationResident) {
                        // the player's own view is still streaming in natively; the stage bound
                        // fails the scenario if it never arrives, and nothing is loaded for it here
                        if (activeScenario.trackerExitWaitStartTick < 0L) {
                            activeScenario.trackerExitWaitStartTick = serverTicks;
                        }
                        return;
                    }
                    boolean teleported = player.teleportTo(
                        player.serverLevel(),
                        destinationX,
                        snapshot.position.y,
                        snapshot.position.z,
                        Collections.emptySet(),
                        snapshot.yRot,
                        snapshot.xRot
                    );
                    activeScenario.trackerMoveOutTick = serverTicks;
                    activeScenario.trackerMovedOut = teleported;
                    if (!teleported) {
                        failLocked("server player tracker-exit teleport failed");
                        return;
                    }
                    setStageLocked(Stage.WAIT_TRACKER_EXIT);
                }

                private static void moveTrackerBackLocked(ServerPlayer player) {
                    if (activeScenario == null
                        || activeScenario.trackerPlayerSnapshot == null) {
                        failLocked("tracker reentry has no player baseline snapshot");
                        return;
                    }
                    PlayerSnapshot snapshot = activeScenario.trackerPlayerSnapshot;
                    boolean teleported = player.teleportTo(
                        player.serverLevel(),
                        snapshot.position.x,
                        snapshot.position.y,
                        snapshot.position.z,
                        Collections.emptySet(),
                        snapshot.yRot,
                        snapshot.xRot
                    );
                    snapshot.restoreNonPositionState(player);
                    activeScenario.trackerMoveBackTick = serverTicks;
                    activeScenario.trackerMovedBack = teleported;
                    activeScenario.playerRestored = teleported && snapshot.matches(player);
                    if (!activeScenario.playerRestored) {
                        cleanupFailureCount++;
                        failLocked("server player baseline was not restored after tracker reentry");
                        return;
                    }
                    setStageLocked(Stage.WAIT_SECOND_LIFECYCLE);
                }

                private static void beginCleanupLocked(ServerLevel level) {
                    if (activeScenario == null) {
                        failLocked("cleanup requested without an active scenario");
                        return;
                    }
                    if (activeScenario.trackerPlayerSnapshot != null) {
                        ServerPlayer player = level.getServer()
                            .getPlayerList()
                            .getPlayer(playerUuid);
                        if (player == null
                            || !activeScenario.trackerPlayerSnapshot.matches(player)) {
                            cleanupFailureCount++;
                            failLocked("player baseline drifted before scenario cleanup");
                            return;
                        }
                    }
                    if (activeScenario.serverEntity != null
                        && !activeScenario.serverEntity.isRemoved()) {
                        activeScenario.serverEntity.discard();
                    }
                    activeScenario.cleanupRequestedTick = serverTicks;
                    activeScenario.cleanupClientAbsentTick = -1L;
                    activeScenario.cleanupServerAbsentTick = -1L;
                    setStageLocked(Stage.WAIT_CLEANUP);
                }

                private static void pollCleanupLocked(ServerLevel level) {
                    if (activeScenario == null) {
                        failLocked("cleanup poll has no active scenario");
                        return;
                    }
                    boolean serverAbsent =
                        level.getEntity(activeScenario.serverEntityId) == null
                            || activeScenario.serverEntity == null
                            || activeScenario.serverEntity.isRemoved();
                    Minecraft minecraft = Minecraft.getInstance();
                    boolean clientAbsent =
                        minecraft.level != null
                            && minecraft.level.getEntity(activeScenario.serverEntityId) == null;
                    if (serverAbsent && activeScenario.cleanupServerAbsentTick < 0L) {
                        activeScenario.cleanupServerAbsentTick = serverTicks;
                    }
                    if (clientAbsent && activeScenario.cleanupClientAbsentTick < 0L) {
                        activeScenario.cleanupClientAbsentTick = clientTicks;
                    }
                    if (!serverAbsent || !clientAbsent) {
                        return;
                    }
                    if (serverTicks - activeScenario.cleanupServerAbsentTick
                            < CLEANUP_FENCE_TICKS
                        || clientTicks - activeScenario.cleanupClientAbsentTick
                            < CLEANUP_FENCE_TICKS) {
                        return;
                    }

                    finalizeScenarioLocked(activeScenario);
                    activeScenario.cleanupPassed = true;
                    activeScenario.finishedAtTick = serverTicks;
                    if (!activeScenario.passed) {
                        failLocked(
                            "scenario "
                                + activeScenario.id
                                + " failed semantic validation"
                        );
                        return;
                    }
                    scenarioIndex++;
                    activeScenario = null;
                    if (scenarioIndex >= scenarios.size()) {
                        setStageLocked(Stage.SEAL);
                    } else {
                        setStageLocked(Stage.SPAWN);
                    }
                }

                private static void pollFailureCleanupLocked(MinecraftServer server) {
                    boolean cleanupReady = true;
                    ServerPlayer player = playerUuid == null
                        ? null
                        : server.getPlayerList().getPlayer(playerUuid);
                    Minecraft minecraft = Minecraft.getInstance();
                    if (activeScenario != null
                        && activeScenario.trackerPlayerSnapshot != null) {
                        if (player == null) {
                            cleanupReady = false;
                        } else if (!activeScenario.trackerPlayerSnapshot.matches(player)) {
                            PlayerSnapshot snapshot = activeScenario.trackerPlayerSnapshot;
                            player.teleportTo(
                                player.serverLevel(),
                                snapshot.position.x,
                                snapshot.position.y,
                                snapshot.position.z,
                                Collections.emptySet(),
                                snapshot.yRot,
                                snapshot.xRot
                            );
                            snapshot.restoreNonPositionState(player);
                            cleanupReady =
                                cleanupReady && snapshot.matches(player);
                        }
                    }
                    if (activeScenario != null
                        && activeScenario.serverEntity != null
                        && !activeScenario.serverEntity.isRemoved()) {
                        activeScenario.serverEntity.discard();
                    }
                    if (activeScenario != null
                        && activeScenario.serverEntity != null
                        && !activeScenario.serverEntity.isRemoved()) {
                        cleanupReady = false;
                    }
                    if (activeScenario != null
                        && minecraft.level != null
                        && minecraft.level.getEntity(activeScenario.serverEntityId) != null) {
                        cleanupReady = false;
                    }
                    if (cleanupReady) {
                        if (failureCleanupStableServerTick < 0L) {
                            failureCleanupStableServerTick = serverTicks;
                        }
                    } else {
                        failureCleanupStableServerTick = -1L;
                    }
                    if (failureCleanupStableServerTick >= 0L
                        && serverTicks - failureCleanupStableServerTick
                            >= CLEANUP_FENCE_TICKS) {
                        sealReceiptLocked(false);
                    } else if (serverTicks - stageStartedServerTick
                        >= STAGE_TIMEOUT_TICKS) {
                        cleanupFailureCount++;
                        sealReceiptLocked(false);
                    }
                }

                private static void finalizeScenarioLocked(ScenarioEvidence scenario) {
                    boolean lifecyclesComplete =
                        scenario.lifecycles.size() == scenario.expectedLifecycleCount
                            && scenario.lifecycles.stream().allMatch(
                                ModPorterStrictSpawnHarness::lifecycleBasePassed
                            );
                    scenario.sameServerEntityPassed =
                        scenario.expectedLifecycleCount == 1
                            || (
                                scenario.serverEntity != null
                                    && scenario.serverEntity.getId()
                                        == scenario.serverEntityId
                                    && scenario.serverEntity.getUUID()
                                        .equals(scenario.serverEntityUuid)
                            );
                    scenario.freshClientInstancePassed =
                        scenario.expectedLifecycleCount == 1
                            || (
                                scenario.lifecycles.size() == 2
                                    && scenario.lifecycles.get(0).clientEntity
                                        != scenario.lifecycles.get(1).clientEntity
                                    && !scenario.lifecycles.get(0).identityToken.equals(
                                        scenario.lifecycles.get(1).identityToken
                                    )
                            );
                    scenario.lifecycleRepeatedPassed =
                        scenario.expectedLifecycleCount == 1
                            || (
                                lifecyclesComplete
                                    && scenario.clientTrackerLeaveObserved
                                    && scenario.serverStopTrackingCount >= 1
                                    && scenario.serverStartTrackingCount >= 2
                                    && scenario.trackerMovedOut
                                    && scenario.trackerMovedBack
                                    && scenario.playerRestored
                            );
                    scenario.passed =
                        scenario.addFreshEntityReturned
                            && lifecyclesComplete
                            && scenario.sameServerEntityPassed
                            && scenario.freshClientInstancePassed
                            && scenario.lifecycleRepeatedPassed;
                }

                private static void requireLifecycleCheckLocked(
                    boolean passed,
                    String message
                ) {
                    if (!passed) {
                        failLocked(message);
                    }
                }

                private static boolean lifecycleBasePassed(LifecycleEvidence lifecycle) {
                    return lifecycle.joinPreInsertionPassed
                        && lifecycle.joinClientPositionPassed
                        && lifecycle.joinRawMotionPassed
                        && lifecycle.joinPendingTruePassed
                        && lifecycle.joinDefaultBoxDimensionsPassed
                        && lifecycle.sizePostJoinPassed
                        && lifecycle.sizeDecodedBoxDimensionsPassed
                        && lifecycle.sizePendingFalsePassed
                        && lifecycle.sizeDeferredAveragePassed
                        && lifecycle.preTickBeforePackageTickPassed
                        && lifecycle.preTickAuthoritativeMotionPassed
                        && lifecycle.preTickDecodedBoxDimensionsPassed;
                }

                private static int expectedJoinLifecycleIndexLocked() {
                    if (stage == Stage.WAIT_FIRST_LIFECYCLE) {
                        return 0;
                    }
                    if (stage == Stage.WAIT_SECOND_LIFECYCLE) {
                        return 1;
                    }
                    return -1;
                }

                private static LifecycleEvidence lifecycleForEntityLocked(
                    PackageEntity entity
                ) {
                    if (activeScenario == null) {
                        return null;
                    }
                    for (LifecycleEvidence lifecycle : activeScenario.lifecycles) {
                        if (lifecycle.clientEntity == entity) {
                            return lifecycle;
                        }
                    }
                    return null;
                }

                private static boolean isActiveEntityLocked(PackageEntity entity) {
                    return activeScenario != null
                        && entity.getId() == activeScenario.serverEntityId
                        && entity.getUUID().equals(activeScenario.serverEntityUuid);
                }

                private static boolean initializeLocked() {
                    if (initialized) {
                        return terminalFailure == null || !sealed;
                    }
                    initialized = true;
                    try {
                        profileId = requireProperty(PROFILE_PROPERTY);
                        wrapperSessionId = requireEnv(WRAPPER_SESSION_ENV);
                        validationSessionId = requireProperty(
                            VALIDATION_SESSION_PROPERTY
                        );
                        bindingsPath = requireAbsolutePath(BINDINGS_PROPERTY);
                        receiptPath = requireAbsolutePath(RECEIPT_PROPERTY);
                        evidenceDir = requireAbsolutePath(EVIDENCE_DIR_PROPERTY);
                        if (!profileId.equals(EXPECTED_PROFILE_ID)) {
                            throw new IllegalStateException(
                                "unexpected profile id: " + profileId
                            );
                        }
                        Files.createDirectories(receiptPath.getParent());
                        Files.createDirectories(evidenceDir);
                        Files.deleteIfExists(receiptPath);
                        bindings = readBindings(bindingsPath);
                        runtimeMinecraftVersion =
                            SharedConstants.getCurrentVersion().getName();
                        runtimeNeoForgeVersion = ModList.get()
                            .getModContainerById("neoforge")
                            .orElseThrow(
                                () -> new IllegalStateException(
                                    "NeoForge mod container is unavailable"
                                )
                            )
                            .getModInfo()
                            .getVersion()
                            .toString();
                        if (!EXPECTED_MINECRAFT_VERSION.equals(runtimeMinecraftVersion)) {
                            throw new IllegalStateException(
                                "unexpected Minecraft version: "
                                    + runtimeMinecraftVersion
                            );
                        }
                        if (!EXPECTED_NEOFORGE_VERSION.equals(runtimeNeoForgeVersion)) {
                            throw new IllegalStateException(
                                "unexpected NeoForge version: "
                                    + runtimeNeoForgeVersion
                            );
                        }
                        RuntimeClasspathFingerprint classpathFingerprint =
                            fingerprintClasspath();
                        classpathSha256 = classpathFingerprint.sha256;
                        classpathEntries = classpathFingerprint.entries;
                        classResources = classResources();
                        Path javaPath = javaExecutable();
                        javaExecutable = javaPath.toString();
                        javaExecutableSha256 = sha256(javaPath);
                        System.setProperty(COMPLETED_PROPERTY, "false");
                        System.out.println(
                            "[ModPorterStrictSpawn] Initialized profile="
                                + profileId
                                + " validationSession="
                                + validationSessionId
                        );
                        return true;
                    } catch (Throwable throwable) {
                        terminalFailure =
                            "strict spawn initialization failed: " + describe(throwable);
                        stage = Stage.FAILURE_CLEANUP;
                        stageStartedServerTick = serverTicks;
                        System.err.println(
                            "[ModPorterStrictSpawn] " + terminalFailure
                        );
                        if (receiptPath == null) {
                            System.setProperty(COMPLETED_PROPERTY, "true");
                            sealed = true;
                        }
                        return false;
                    }
                }

                private static JsonObject readBindings(Path path) throws IOException {
                    if (!Files.isRegularFile(path)) {
                        throw new IllegalStateException(
                            "expected bindings manifest is not a file: " + path
                        );
                    }
                    JsonElement parsed = JsonParser.parseString(
                        Files.readString(path, StandardCharsets.UTF_8)
                    );
                    if (!parsed.isJsonObject()) {
                        throw new IllegalStateException(
                            "expected bindings manifest is not a JSON object"
                        );
                    }
                    JsonObject root = parsed.getAsJsonObject();
                    validateOptionalManifestString(root, "profileId", profileId);
                    validateOptionalManifestString(
                        root,
                        "validationSessionId",
                        validationSessionId
                    );
                    JsonElement bindingsElement = root.has("bindings")
                        ? root.get("bindings")
                        : root;
                    if (!bindingsElement.isJsonObject()) {
                        throw new IllegalStateException(
                            "bindings manifest does not contain a bindings object"
                        );
                    }
                    return bindingsElement.getAsJsonObject().deepCopy();
                }

                private static void validateOptionalManifestString(
                    JsonObject root,
                    String key,
                    String expected
                ) {
                    if (root.has(key)
                        && !root.get(key).getAsString().equals(expected)) {
                        throw new IllegalStateException(
                            "bindings manifest "
                                + key
                                + " mismatch: "
                                + root.get(key)
                        );
                    }
                }

                private static void failLocked(String message) {
                    if (terminalFailure == null) {
                        terminalFailure = message;
                        if (activeScenario != null) {
                            activeScenario.failure = message;
                        }
                        System.err.println(
                            "[ModPorterStrictSpawn] FAILED: " + message
                        );
                    }
                    if (stage != Stage.COMPLETE && stage != Stage.FAILURE_CLEANUP) {
                        setStageLocked(Stage.FAILURE_CLEANUP);
                    }
                }

                private static void setStageLocked(Stage next) {
                    stage = next;
                    stageStartedServerTick = serverTicks;
                }

                private static boolean stageTimedOutLocked() {
                    if (stage == Stage.WAITING_FOR_WORLD
                        || stage == Stage.READY_FENCE
                        || stage == Stage.COMPLETE
                        || stage == Stage.SEAL) {
                        return false;
                    }
                    return serverTicks - stageStartedServerTick > STAGE_TIMEOUT_TICKS;
                }

                private static void sealReceiptLocked(boolean requestedPass) {
                    if (sealed) {
                        return;
                    }
                    boolean pass =
                        requestedPass
                            && terminalFailure == null
                            && scenarios.stream().allMatch(
                                scenario -> scenario.passed && scenario.cleanupPassed
                            )
                            && observedScenarioIds.equals(REQUESTED_SCENARIOS)
                            && processStartCount == 1
                            && worldLoadCount == 1
                            && cleanupFailureCount == 0
                            && overlapFailureCount == 0;
                    if (requestedPass && !pass && terminalFailure == null) {
                        terminalFailure =
                            "receipt preconditions did not prove all strict scenarios";
                    }
                    try {
                        for (ScenarioEvidence scenario : scenarios) {
                            writeScenarioArtifactLocked(scenario);
                        }
                        finalExitRequested = true;
                        JsonObject receipt = receiptJsonLocked(pass);
                        byte[] bytes = GSON.toJson(receipt)
                            .concat(System.lineSeparator())
                            .getBytes(StandardCharsets.UTF_8);
                        atomicWrite(receiptPath, bytes);
                        sealed = true;
                        stage = Stage.COMPLETE;
                        System.out.println(
                            RECEIPT_MARKER
                                + " "
                                + receiptPath
                                + " sha256="
                                + sha256(bytes)
                                + " status="
                                + (pass ? "PASS" : "FAIL")
                        );
                    } catch (Throwable throwable) {
                        terminalFailure =
                            "receipt sealing failed: " + describe(throwable);
                        sealed = true;
                        stage = Stage.COMPLETE;
                        System.err.println(
                            "[ModPorterStrictSpawn] " + terminalFailure
                        );
                    } finally {
                        System.setProperty(COMPLETED_PROPERTY, "true");
                    }
                }

                private static void writeScenarioArtifactLocked(
                    ScenarioEvidence scenario
                ) throws IOException {
                    JsonObject artifact = scenarioJsonLocked(scenario);
                    artifact.addProperty("artifactSchema", RECEIPT_SCHEMA + ".trace");
                    artifact.addProperty("profileId", profileId);
                    artifact.addProperty("sessionId", wrapperSessionId);
                    artifact.addProperty(
                        "validationSessionId",
                        validationSessionId
                    );
                    byte[] bytes = GSON.toJson(artifact)
                        .concat(System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8);
                    Path path = evidenceDir.resolve(
                        String.format(
                            Locale.ROOT,
                            "%02d-%s.json",
                            scenarios.indexOf(scenario) + 1,
                            scenario.id
                        )
                    ).toAbsolutePath().normalize();
                    if (!path.startsWith(evidenceDir)) {
                        throw new IllegalStateException(
                            "scenario artifact escaped evidenceDir: " + path
                        );
                    }
                    atomicWrite(path, bytes);
                    scenario.artifactPath = path;
                    scenario.artifactSha256 = sha256(bytes);
                }

                private static JsonObject receiptJsonLocked(boolean pass) {
                    JsonObject root = new JsonObject();
                    root.addProperty("schema", RECEIPT_SCHEMA);
                    root.addProperty("profileId", profileId);
                    root.addProperty("sessionId", wrapperSessionId);
                    root.addProperty("validationSessionId", validationSessionId);
                    root.addProperty("status", pass ? "PASS" : "FAIL");
                    root.addProperty("processStartCount", processStartCount);
                    root.addProperty("worldLoadCount", worldLoadCount);
                    root.addProperty("scenarioCount", scenarios.size());
                    long failedCount = scenarios.stream()
                        .filter(scenario -> !scenario.passed)
                        .count();
                    root.addProperty("failedCount", failedCount);
                    root.addProperty(
                        "cleanupFailureCount",
                        cleanupFailureCount
                    );
                    root.addProperty(
                        "overlapFailureCount",
                        overlapFailureCount
                    );
                    root.addProperty(
                        "allScenariosPassed",
                        pass && failedCount == 0
                    );
                    root.addProperty(
                        "finalExitRequested",
                        finalExitRequested
                    );
                    if (terminalFailure != null) {
                        root.addProperty("failure", terminalFailure);
                    }
                    root.add(
                        "requestedScenarios",
                        stringArray(REQUESTED_SCENARIOS)
                    );
                    root.add(
                        "observedScenarios",
                        stringArray(observedScenarioIds)
                    );
                    root.add("runtime", runtimeJsonLocked());
                    root.add(
                        "bindings",
                        bindings == null ? new JsonObject() : bindings.deepCopy()
                    );
                    JsonArray scenarioArray = new JsonArray();
                    for (ScenarioEvidence scenario : scenarios) {
                        scenarioArray.add(scenarioJsonLocked(scenario));
                    }
                    root.add("scenarios", scenarioArray);
                    return root;
                }

                private static JsonObject runtimeJsonLocked() {
                    JsonObject runtime = new JsonObject();
                    runtime.addProperty(
                        "javaVersion",
                        System.getProperty("java.version", "")
                    );
                    runtime.addProperty(
                        "javaVendor",
                        System.getProperty("java.vendor", "")
                    );
                    runtime.addProperty(
                        "javaHome",
                        System.getProperty("java.home", "")
                    );
                    runtime.addProperty(
                        "javaExecutable",
                        javaExecutable == null ? "" : javaExecutable
                    );
                    runtime.addProperty(
                        "javaExecutableSha256",
                        javaExecutableSha256 == null ? "" : javaExecutableSha256
                    );
                    runtime.addProperty(
                        "minecraftVersion",
                        runtimeMinecraftVersion == null
                            ? ""
                            : runtimeMinecraftVersion
                    );
                    runtime.addProperty(
                        "neoForgeVersion",
                        runtimeNeoForgeVersion == null
                            ? ""
                            : runtimeNeoForgeVersion
                    );
                    runtime.addProperty(
                        "classpathSha256",
                        classpathSha256 == null ? "" : classpathSha256
                    );
                    runtime.add(
                        "classpathEntries",
                        classpathEntries == null
                            ? new JsonArray()
                            : classpathEntries.deepCopy()
                    );
                    runtime.add(
                        "classResources",
                        classResources == null
                            ? new JsonObject()
                            : classResources.deepCopy()
                    );
                    return runtime;
                }

                private static JsonObject scenarioJsonLocked(
                    ScenarioEvidence scenario
                ) {
                    JsonObject result = new JsonObject();
                    result.addProperty("id", scenario.id);
                    result.addProperty("passed", scenario.passed);
                    result.addProperty(
                        "cleanupPassed",
                        scenario.cleanupPassed
                    );
                    result.addProperty("startedAtTick", scenario.startedAtTick);
                    result.addProperty("finishedAtTick", scenario.finishedAtTick);
                    result.addProperty("serverEntityId", scenario.serverEntityId);
                    result.addProperty(
                        "serverEntityUuid",
                        scenario.serverEntityUuid == null
                            ? ""
                            : scenario.serverEntityUuid.toString()
                    );
                    result.addProperty(
                        "serverIdentityToken",
                        scenario.serverIdentityToken == null
                            ? ""
                            : scenario.serverIdentityToken
                    );
                    if (scenario.failure != null) {
                        result.addProperty("failure", scenario.failure);
                    }
                    JsonArray clientIds = new JsonArray();
                    JsonArray clientUuids = new JsonArray();
                    JsonArray identityTokens = new JsonArray();
                    for (LifecycleEvidence lifecycle : scenario.lifecycles) {
                        clientIds.add(lifecycle.clientEntityId);
                        clientUuids.add(
                            lifecycle.clientEntityUuid == null
                                ? ""
                                : lifecycle.clientEntityUuid.toString()
                        );
                        identityTokens.add(lifecycle.identityToken);
                    }
                    result.add("clientEntityIds", clientIds);
                    result.add("clientEntityUuids", clientUuids);
                    result.add("clientIdentityTokens", identityTokens);
                    result.add(
                        "semanticChecks",
                        semanticChecksJsonLocked(scenario)
                    );
                    result.add("observations", observationsJsonLocked(scenario));
                    JsonArray artifacts = new JsonArray();
                    if (scenario.artifactPath != null) {
                        JsonObject artifact = new JsonObject();
                        artifact.addProperty(
                            "path",
                            scenario.artifactPath.toString()
                        );
                        artifact.addProperty(
                            "sha256",
                            scenario.artifactSha256
                        );
                        artifacts.add(artifact);
                    }
                    result.add("artifacts", artifacts);
                    return result;
                }

                private static JsonArray semanticChecksJsonLocked(
                    ScenarioEvidence scenario
                ) {
                    LinkedHashMap<String, Boolean> values = new LinkedHashMap<>();
                    for (String id : BASE_SEMANTIC_CHECKS) {
                        values.put(
                            id,
                            !scenario.lifecycles.isEmpty()
                                && scenario.lifecycles.size()
                                    == scenario.expectedLifecycleCount
                                && scenario.lifecycles.stream().allMatch(
                                    lifecycle -> lifecycleCheck(lifecycle, id)
                                )
                        );
                    }
                    if (scenario.expectedLifecycleCount == 2) {
                        values.put(
                            "tracker.same_server_entity",
                            scenario.sameServerEntityPassed
                        );
                        values.put(
                            "tracker.fresh_client_instance",
                            scenario.freshClientInstancePassed
                        );
                        values.put(
                            "tracker.lifecycle_repeated",
                            scenario.lifecycleRepeatedPassed
                        );
                    }
                    JsonArray checks = new JsonArray();
                    for (Map.Entry<String, Boolean> entry : values.entrySet()) {
                        JsonObject check = new JsonObject();
                        check.addProperty("id", entry.getKey());
                        check.addProperty("passed", entry.getValue());
                        checks.add(check);
                    }
                    return checks;
                }

                private static boolean lifecycleCheck(
                    LifecycleEvidence lifecycle,
                    String id
                ) {
                    return switch (id) {
                        case "join.pre_insertion" ->
                            lifecycle.joinPreInsertionPassed;
                        case "join.client_position" ->
                            lifecycle.joinClientPositionPassed;
                        case "join.raw_packet_motion" ->
                            lifecycle.joinRawMotionPassed;
                        case "join.pending_true" ->
                            lifecycle.joinPendingTruePassed;
                        case "join.default_box_dimensions" ->
                            lifecycle.joinDefaultBoxDimensionsPassed;
                        case "size.post_join" ->
                            lifecycle.sizePostJoinPassed;
                        case "size.decoded_box_dimensions" ->
                            lifecycle.sizeDecodedBoxDimensionsPassed;
                        case "size.pending_false" ->
                            lifecycle.sizePendingFalsePassed;
                        case "size.deferred_average" ->
                            lifecycle.sizeDeferredAveragePassed;
                        case "pre_tick.before_package_tick" ->
                            lifecycle.preTickBeforePackageTickPassed;
                        case "pre_tick.authoritative_motion" ->
                            lifecycle.preTickAuthoritativeMotionPassed;
                        case "pre_tick.decoded_box_dimensions" ->
                            lifecycle.preTickDecodedBoxDimensionsPassed;
                        default -> false;
                    };
                }

                private static JsonObject observationsJsonLocked(
                    ScenarioEvidence scenario
                ) {
                    JsonObject observations = new JsonObject();
                    JsonObject production = new JsonObject();
                    production.addProperty(
                        "factory",
                        "PackageEntity.fromItemStack"
                    );
                    production.addProperty(
                        "insertion",
                        "ServerLevel.addFreshEntity"
                    );
                    production.addProperty(
                        "addFreshEntityReturned",
                        scenario.addFreshEntityReturned
                    );
                    production.addProperty(
                        "serverStartTrackingCount",
                        scenario.serverStartTrackingCount
                    );
                    production.addProperty(
                        "serverStopTrackingCount",
                        scenario.serverStopTrackingCount
                    );
                    production.add(
                        "serverStartTrackingTicks",
                        longArray(scenario.serverStartTrackingTicks)
                    );
                    production.add(
                        "serverStopTrackingTicks",
                        longArray(scenario.serverStopTrackingTicks)
                    );
                    observations.add("production", production);

                    JsonObject expected = new JsonObject();
                    expected.add(
                        "spawnPosition",
                        vecJson(scenario.spawnPosition)
                    );
                    expected.add(
                        "serverAuthoritativeMotion",
                        vecJson(scenario.serverMotion)
                    );
                    expected.add(
                        "pairingPacketRawMotion",
                        intVectorJson(scenario.packetRawMotion)
                    );
                    expected.add(
                        "pairingPacketDecodedMotion",
                        vecJson(scenario.packetDecodedMotion)
                    );
                    expected.add(
                        "deferredAverageMotion",
                        vecJson(scenario.deferredAverageMotion)
                    );
                    expected.add(
                        "payloadAuthoritativeFloatMotion",
                        vecJson(scenario.payloadFloatMotion)
                    );
                    expected.add(
                        "box",
                        stackJson(scenario.expectedBox)
                    );
                    expected.addProperty("width", scenario.expectedWidth);
                    expected.addProperty("height", scenario.expectedHeight);
                    observations.add("expected", expected);

                    JsonArray lifecycleArray = new JsonArray();
                    for (LifecycleEvidence lifecycle : scenario.lifecycles) {
                        lifecycleArray.add(lifecycleJson(lifecycle));
                    }
                    observations.add("lifecycles", lifecycleArray);

                    JsonObject tracker = new JsonObject();
                    tracker.addProperty(
                        "clientLeaveObserved",
                        scenario.clientTrackerLeaveObserved
                    );
                    tracker.addProperty(
                        "clientLeaveTick",
                        scenario.clientTrackerLeaveTick
                    );
                    tracker.addProperty(
                        "moveOutTick",
                        scenario.trackerMoveOutTick
                    );
                    tracker.addProperty(
                        "moveBackTick",
                        scenario.trackerMoveBackTick
                    );
                    tracker.addProperty(
                        "playerRestored",
                        scenario.playerRestored
                    );
                    if (scenario.trackerPlayerSnapshot != null) {
                        tracker.add(
                            "playerBaseline",
                            scenario.trackerPlayerSnapshot.toJson()
                        );
                    }
                    if (scenario.expectedLifecycleCount == 2) {
                        JsonObject exit = new JsonObject();
                        exit.addProperty("typeRangeBlocks", scenario.trackerTypeRangeBlocks);
                        exit.addProperty("scaledRangeBlocks", scenario.trackerScaledRangeBlocks);
                        exit.addProperty("viewDistanceBlocks", scenario.trackerViewDistanceBlocks);
                        exit.addProperty("effectiveRangeBlocks", scenario.trackerEffectiveRangeBlocks);
                        exit.addProperty("exitDistanceBlocks", scenario.trackerExitDistanceBlocks);
                        exit.addProperty("marginBlocks", TRACKER_EXIT_MARGIN_BLOCKS);
                        exit.addProperty("destinationResident", scenario.trackerDestinationResident);
                        exit.addProperty("waitStartedAtTick", scenario.trackerExitWaitStartTick);
                        tracker.add("exit", exit);
                    }
                    observations.add("tracker", tracker);

                    JsonObject cleanup = new JsonObject();
                    cleanup.addProperty(
                        "requestedAtTick",
                        scenario.cleanupRequestedTick
                    );
                    cleanup.addProperty(
                        "serverAbsentTick",
                        scenario.cleanupServerAbsentTick
                    );
                    cleanup.addProperty(
                        "clientAbsentTick",
                        scenario.cleanupClientAbsentTick
                    );
                    cleanup.addProperty(
                        "fenceTicks",
                        CLEANUP_FENCE_TICKS
                    );
                    observations.add("cleanup", cleanup);
                    return observations;
                }

                private static JsonObject lifecycleJson(
                    LifecycleEvidence lifecycle
                ) {
                    JsonObject result = new JsonObject();
                    result.addProperty("ordinal", lifecycle.ordinal);
                    result.addProperty(
                        "identityToken",
                        lifecycle.identityToken
                    );
                    JsonObject join = new JsonObject();
                    join.addProperty("clientTick", lifecycle.joinClientTick);
                    join.addProperty(
                        "entityTickCount",
                        lifecycle.joinTickCount
                    );
                    join.addProperty("firstTick", lifecycle.joinFirstTick);
                    join.add("position", vecJson(lifecycle.joinPosition));
                    join.add(
                        "clientPosition",
                        vecJson(lifecycle.joinClientPosition)
                    );
                    join.add("motion", vecJson(lifecycle.joinMotion));
                    join.addProperty("pending", lifecycle.joinPending);
                    join.add(
                        "pendingMotion",
                        vecJson(lifecycle.joinPendingMotion)
                    );
                    join.add("box", stackJson(lifecycle.joinBox));
                    join.addProperty("width", lifecycle.joinWidth);
                    join.addProperty("height", lifecycle.joinHeight);
                    result.add("join", join);

                    JsonObject size = new JsonObject();
                    size.addProperty("clientTick", lifecycle.sizeClientTick);
                    size.add("motion", vecJson(lifecycle.sizeMotion));
                    size.addProperty("pending", lifecycle.sizePending);
                    size.add(
                        "pendingMotion",
                        vecJson(lifecycle.sizePendingMotion)
                    );
                    size.add("box", stackJson(lifecycle.sizeBox));
                    size.addProperty("oldWidth", lifecycle.sizeOldWidth);
                    size.addProperty("oldHeight", lifecycle.sizeOldHeight);
                    size.addProperty("newWidth", lifecycle.sizeNewWidth);
                    size.addProperty("newHeight", lifecycle.sizeNewHeight);
                    result.add("size", size);

                    JsonObject preTick = new JsonObject();
                    preTick.addProperty(
                        "clientTick",
                        lifecycle.preTickClientTick
                    );
                    preTick.addProperty(
                        "entityTickCount",
                        lifecycle.preTickEntityTickCount
                    );
                    preTick.addProperty(
                        "firstTick",
                        lifecycle.preTickFirstTick
                    );
                    preTick.add(
                        "motion",
                        vecJson(lifecycle.preTickMotion)
                    );
                    preTick.add("box", stackJson(lifecycle.preTickBox));
                    preTick.addProperty("width", lifecycle.preTickWidth);
                    preTick.addProperty("height", lifecycle.preTickHeight);
                    result.add("preTick", preTick);
                    return result;
                }

                private static JsonObject stackJson(ItemStack stack) {
                    JsonObject result = new JsonObject();
                    if (stack == null) {
                        result.addProperty("present", false);
                        return result;
                    }
                    result.addProperty("present", true);
                    result.addProperty("empty", stack.isEmpty());
                    result.addProperty(
                        "item",
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()
                    );
                    result.addProperty("count", stack.getCount());
                    result.addProperty(
                        "itemAndComponentsHash",
                        ItemStack.hashItemAndComponents(stack)
                    );
                    result.addProperty(
                        "hoverName",
                        stack.getHoverName().getString()
                    );
                    result.addProperty(
                        "address",
                        PackageItem.isPackage(stack)
                            ? PackageItem.getAddress(stack)
                            : ""
                    );
                    result.addProperty(
                        "componentPatch",
                        stack.getComponentsPatch().toString()
                    );
                    return result;
                }

                private static JsonObject vecJson(Vec3 value) {
                    JsonObject result = new JsonObject();
                    if (value == null) {
                        result.addProperty("present", false);
                        return result;
                    }
                    result.addProperty("present", true);
                    result.addProperty("x", value.x);
                    result.addProperty("y", value.y);
                    result.addProperty("z", value.z);
                    return result;
                }

                private static JsonObject intVectorJson(int[] values) {
                    JsonObject result = new JsonObject();
                    if (values == null || values.length != 3) {
                        result.addProperty("present", false);
                        return result;
                    }
                    result.addProperty("present", true);
                    result.addProperty("x", values[0]);
                    result.addProperty("y", values[1]);
                    result.addProperty("z", values[2]);
                    return result;
                }

                private static JsonArray stringArray(List<String> values) {
                    JsonArray result = new JsonArray();
                    for (String value : values) {
                        result.add(value);
                    }
                    return result;
                }

                private static JsonArray longArray(List<Long> values) {
                    JsonArray result = new JsonArray();
                    for (Long value : values) {
                        result.add(value);
                    }
                    return result;
                }

                private static RuntimeClasspathFingerprint fingerprintClasspath()
                    throws IOException {
                    JsonArray entries = new JsonArray();
                    MessageDigest aggregate = newSha256();
                    Set<Path> seen = new java.util.LinkedHashSet<>();

                    String javaClassPath = requireSystemProperty(
                        "java.class.path"
                    );
                    addPathList(
                        entries,
                        aggregate,
                        seen,
                        "java.class.path",
                        javaClassPath
                    );

                    String modulePath = requireSystemProperty(
                        "jdk.module.path"
                    );
                    addPathList(
                        entries,
                        aggregate,
                        seen,
                        "jdk.module.path",
                        modulePath
                    );

                    String legacyPathValue = requireSystemProperty(
                        "legacyClassPath.file"
                    );
                    Path legacyPath = Path.of(legacyPathValue).toRealPath();
                    if (!Files.isRegularFile(legacyPath)) {
                        throw new IllegalStateException(
                            "legacyClassPath.file is not a regular file: " + legacyPath
                        );
                    }
                    addRuntimePath(entries, aggregate, seen, legacyPath.toString());
                    byte[] legacyBytes = Files.readAllBytes(legacyPath);
                    String legacyText = new String(
                        legacyBytes,
                        StandardCharsets.UTF_8
                    );
                    int legacyIndex = 0;
                    for (String line : legacyText.split("\\R", -1)) {
                        String value = line.trim();
                        if (value.isEmpty()) {
                            continue;
                        }
                        addRuntimePath(
                            entries,
                            aggregate,
                            seen,
                            value
                        );
                        legacyIndex++;
                    }
                    if (legacyIndex == 0) {
                        throw new IllegalStateException(
                            "legacyClassPath.file contains no classpath entries"
                        );
                    }

                    String modFoldersFile = System.getProperty(
                        "fml.modFoldersFile",
                        ""
                    ).trim();
                    if (!modFoldersFile.isEmpty()) {
                        addRuntimePath(
                            entries,
                            aggregate,
                            seen,
                            modFoldersFile
                        );
                    }
                    Map<String, List<Path>> groupedModFolders =
                        CommonLaunchHandler.getGroupedModFolders();
                    int modRootCount = 0;
                    for (String modId : groupedModFolders.keySet()
                        .stream()
                        .sorted()
                        .toList()) {
                        for (Path path : groupedModFolders.get(modId)) {
                            addRuntimePath(
                                entries,
                                aggregate,
                                seen,
                                path.toString()
                            );
                            modRootCount++;
                        }
                    }
                    if (modRootCount == 0) {
                        throw new IllegalStateException(
                            "FML grouped mod folders contain no source roots"
                        );
                    }

                    if (entries.isEmpty()) {
                        throw new IllegalStateException(
                            "runtime loader input closure has no entries"
                        );
                    }
                    return new RuntimeClasspathFingerprint(
                        hex(aggregate.digest()),
                        entries
                    );
                }

                private static String requireSystemProperty(String name) {
                    String value = System.getProperty(name);
                    if (value == null || value.isBlank()) {
                        throw new IllegalStateException(
                            "missing runtime loader property " + name
                        );
                    }
                    return value;
                }

                private static void addPathList(
                    JsonArray entries,
                    MessageDigest aggregate,
                    Set<Path> seen,
                    String origin,
                    String value
                ) throws IOException {
                    for (String rawEntry : value.split(
                        Pattern.quote(File.pathSeparator),
                        -1
                    )) {
                        if (rawEntry.isBlank()) {
                            throw new IllegalStateException(
                                origin + " contains a blank entry"
                            );
                        }
                        addRuntimePath(
                            entries,
                            aggregate,
                            seen,
                            rawEntry
                        );
                    }
                }

                private static void addRuntimePath(
                    JsonArray entries,
                    MessageDigest aggregate,
                    Set<Path> seen,
                    String rawEntry
                ) throws IOException {
                    Path entry = Path.of(rawEntry).toRealPath();
                    if (!seen.add(entry)) {
                        return;
                    }
                    String kind;
                    String entrySha256;
                    if (Files.isRegularFile(entry)) {
                        kind = "file";
                        entrySha256 = sha256(entry);
                    } else if (Files.isDirectory(entry)) {
                        kind = "directory";
                        entrySha256 = sha256Tree(entry);
                    } else {
                        throw new IllegalStateException(
                            "runtime loader entry is not a file or directory: "
                                + entry
                        );
                    }
                    JsonObject encoded = new JsonObject();
                    encoded.addProperty("path", entry.toString());
                    encoded.addProperty("kind", kind);
                    encoded.addProperty("sha256", entrySha256);
                    entries.add(encoded);
                    updateDigest(aggregate, entry.toString());
                    aggregate.update((byte)0);
                    updateDigest(aggregate, kind);
                    aggregate.update((byte)0);
                    updateDigest(aggregate, entrySha256);
                    aggregate.update((byte)'\n');
                }

                private static String sha256Tree(Path root) throws IOException {
                    MessageDigest digest = newSha256();
                    List<Path> files = new ArrayList<>();
                    try (var stream = Files.walk(root)) {
                        for (Path path : stream.toList()) {
                            if (path.equals(root)) {
                                continue;
                            }
                            if (Files.isSymbolicLink(path)) {
                                throw new IllegalStateException(
                                    "runtime classpath directory contains a symbolic link: "
                                        + path
                                );
                            }
                            if (Files.isDirectory(path)) {
                                continue;
                            }
                            if (!Files.isRegularFile(path)) {
                                throw new IllegalStateException(
                                    "runtime classpath directory contains an unsupported entry: "
                                        + path
                                );
                            }
                            files.add(path);
                        }
                    }
                    // the auditor's sha256-relative-nul-file-sha256-lf-v1 orders by the relative
                    // '/'-separated name (code point order), not by the platform's
                    // case-insensitive absolute path comparison
                    files.sort(java.util.Comparator.comparing(
                        (Path path) -> root.relativize(path).toString().replace(File.separatorChar, '/')
                    ));
                    for (Path path : files) {
                        String relative = root.relativize(path)
                            .toString()
                            .replace(File.separatorChar, '/');
                        updateDigest(digest, relative);
                        digest.update((byte)0);
                        updateDigest(digest, sha256(path));
                        digest.update((byte)'\n');
                    }
                    return hex(digest.digest());
                }

                private static JsonObject classResources() throws IOException {
                    JsonObject resources = new JsonObject();
                    resources.add(
                        "packageEntity",
                        classResource(
                            "com/simibubi/create/content/logistics/box/PackageEntity.class"
                        )
                    );
                    String packagePath =
                        ModPorterStrictSpawnHarness.class.getPackageName()
                            .replace('.', '/');
                    resources.add(
                        "strictHarness",
                        classResource(
                            packagePath + "/ModPorterStrictSpawnHarness.class"
                        )
                    );
                    resources.add(
                        "genericHarness",
                        classResource(
                            packagePath + "/ModPorterClientWorldHarness.class"
                        )
                    );
                    return resources;
                }

                // NeoForge 21.1.230 ClientLevel.tickNonPassenger increments tickCount
                // before firing Pre, then calls entity.tick(). Entity.baseTick clears
                // firstTick. Join 0 -> Pre 1 with firstTick still true is the exact fence.
                private static boolean firstPackageTick(
                    int joinTickCount,
                    int preTickCount,
                    boolean firstTick
                ) {
                    return joinTickCount == 0
                        && preTickCount == joinTickCount + 1
                        && firstTick;
                }

                private static JsonObject classResource(String resource)
                    throws IOException {
                    ClassLoader loader =
                        ModPorterStrictSpawnHarness.class.getClassLoader();
                    URL url = loader.getResource(resource);
                    if (url == null) {
                        throw new IllegalStateException(
                            "runtime class resource is unavailable: " + resource
                        );
                    }
                    try (InputStream stream = url.openStream()) {
                        byte[] loadedBytes = stream.readAllBytes();
                        JsonObject result = new JsonObject();
                        result.addProperty("resource", resource);
                        result.addProperty("url", url.toExternalForm());
                        result.addProperty(
                            "sourcePath",
                            classResourceSourcePath(url, resource, loadedBytes).toString()
                        );
                        result.addProperty("sha256", sha256(loadedBytes));
                        return result;
                    }
                }

                private static Path classResourceSourcePath(
                    URL url,
                    String resource,
                    byte[] loadedBytes
                ) throws IOException {
                    if (classpathEntries == null) {
                        throw new IllegalStateException(
                            "bound runtime classpath is unavailable: " + url
                        );
                    }
                    List<Path> roots = new ArrayList<>();
                    for (JsonElement entry : classpathEntries) {
                        roots.add(Path.of(entry.getAsJsonObject()
                            .get("path").getAsString()));
                    }
                    return resolveClassResourceOrigin(url, resource, loadedBytes, roots);
                }

                // FML exposes classes through UnionFileSystem URLs. Their opaque union id
                // is not a host filesystem path; bind the actual loaded bytes to exactly
                // one already-fingerprinted classpath root instead of parsing that URL.
                private static Path resolveClassResourceOrigin(
                    URL url,
                    String resource,
                    byte[] loadedBytes,
                    List<Path> roots
                ) throws IOException {
                    List<Path> origins = new ArrayList<>();
                    byte[] candidate = null;
                    for (Path root : roots) {
                        byte[] bytes = readClasspathResource(root, resource);
                        if (bytes != null) {
                            origins.add(root.toRealPath());
                            candidate = bytes;
                        }
                    }
                    if (origins.size() != 1 || !Arrays.equals(candidate, loadedBytes)) {
                        throw new IllegalStateException(
                            "loaded class resource must match exactly one bound classpath root: "
                                + resource + " url=" + url + " origins=" + origins
                        );
                    }
                    return origins.get(0);
                }

                private static byte[] readClasspathResource(Path root, String resource)
                    throws IOException {
                    if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                        Path file = root.resolve(resource).normalize();
                        if (!file.startsWith(root)
                            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                            return null;
                        }
                        return Files.readAllBytes(file);
                    }
                    if (!Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)
                        || !root.getFileName().toString().endsWith(".jar")) {
                        return null;
                    }
                    try (ZipFile archive = new ZipFile(root.toFile())) {
                        var entry = archive.getEntry(resource);
                        if (entry == null || entry.isDirectory()) return null;
                        try (InputStream stream = archive.getInputStream(entry)) {
                            return stream.readAllBytes();
                        }
                    }
                }

                private static Path javaExecutable() throws IOException {
                    boolean windows = System.getProperty("os.name", "")
                        .toLowerCase(Locale.ROOT)
                        .startsWith("windows");
                    Path executable = Path.of(
                        System.getProperty("java.home", ""),
                        "bin",
                        windows ? "java.exe" : "java"
                    ).toRealPath();
                    if (!Files.isRegularFile(executable)) {
                        throw new IllegalStateException(
                            "runtime Java executable is not a regular file: "
                                + executable
                        );
                    }
                    return executable;
                }

                private static int[] rawPacketMotion(Vec3 motion) {
                    return new int[] {
                        (int)(Mth.clamp(motion.x, -3.9D, 3.9D) * 8000.0D),
                        (int)(Mth.clamp(motion.y, -3.9D, 3.9D) * 8000.0D),
                        (int)(Mth.clamp(motion.z, -3.9D, 3.9D) * 8000.0D)
                    };
                }

                private static boolean rawMotionMatches(
                    Vec3 actual,
                    int[] expected
                ) {
                    return actual != null
                        && expected != null
                        && actual.x == (double)expected[0]
                        && actual.y == (double)expected[1]
                        && actual.z == (double)expected[2];
                }

                private static boolean closeVec(
                    Vec3 actual,
                    Vec3 expected,
                    double epsilon
                ) {
                    return actual != null
                        && expected != null
                        && close(actual.x, expected.x, epsilon)
                        && close(actual.y, expected.y, epsilon)
                        && close(actual.z, expected.z, epsilon);
                }

                private static boolean close(
                    double actual,
                    double expected,
                    double epsilon
                ) {
                    if (epsilon == 0.0D) {
                        return Double.doubleToLongBits(actual)
                            == Double.doubleToLongBits(expected);
                    }
                    return Math.abs(actual - expected) <= epsilon;
                }

                private static boolean sameStack(ItemStack actual, ItemStack expected) {
                    return actual != null
                        && expected != null
                        && actual.getCount() == expected.getCount()
                        && ItemStack.isSameItemSameComponents(actual, expected);
                }

                private static boolean pending(PackageEntity entity)
                    throws IllegalAccessException {
                    return PENDING_FIELD.getBoolean(entity);
                }

                private static Vec3 pendingMotion(PackageEntity entity)
                    throws IllegalAccessException {
                    return new Vec3(
                        PENDING_X_FIELD.getDouble(entity),
                        PENDING_Y_FIELD.getDouble(entity),
                        PENDING_Z_FIELD.getDouble(entity)
                    );
                }

                private static boolean firstTick(Entity entity)
                    throws IllegalAccessException {
                    return FIRST_TICK_FIELD.getBoolean(entity);
                }

                private static Field requiredField(String name) {
                    try {
                        Field field = PackageEntity.class.getDeclaredField(name);
                        field.setAccessible(true);
                        return field;
                    } catch (ReflectiveOperationException exception) {
                        throw new ExceptionInInitializerError(exception);
                    }
                }

                private static Field requiredEntityField(String name) {
                    try {
                        Field field = Entity.class.getDeclaredField(name);
                        field.setAccessible(true);
                        return field;
                    } catch (ReflectiveOperationException exception) {
                        throw new ExceptionInInitializerError(exception);
                    }
                }

                private static String requireProperty(String name) {
                    String value = System.getProperty(name, "").trim();
                    if (value.isEmpty()) {
                        throw new IllegalStateException(
                            "missing system property " + name
                        );
                    }
                    return value;
                }

                private static String requireEnv(String name) {
                    String value = System.getenv(name);
                    if (value == null || value.isBlank()) {
                        throw new IllegalStateException(
                            "missing environment variable " + name
                        );
                    }
                    return value.trim();
                }

                private static Path requireAbsolutePath(String property) {
                    Path path = Path.of(requireProperty(property))
                        .toAbsolutePath()
                        .normalize();
                    if (!path.isAbsolute()) {
                        throw new IllegalStateException(
                            property + " must be absolute"
                        );
                    }
                    return path;
                }

                private static void atomicWrite(Path target, byte[] bytes)
                    throws IOException {
                    Path parent = target.getParent();
                    if (parent == null) {
                        throw new IllegalStateException(
                            "target has no parent: " + target
                        );
                    }
                    Files.createDirectories(parent);
                    Path temporary = parent.resolve(
                        target.getFileName().toString()
                            + ".tmp-"
                            + UUID.randomUUID()
                    );
                    try {
                        Files.write(
                            temporary,
                            bytes,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                        );
                        try {
                            Files.move(
                                temporary,
                                target,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING
                            );
                        } catch (AtomicMoveNotSupportedException ignored) {
                            Files.move(
                                temporary,
                                target,
                                StandardCopyOption.REPLACE_EXISTING
                            );
                        }
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                }

                private static String sha256(byte[] bytes) {
                    MessageDigest digest = newSha256();
                    return hex(digest.digest(bytes));
                }

                private static String sha256(Path path) throws IOException {
                    try (InputStream stream = Files.newInputStream(path)) {
                        return sha256(stream);
                    }
                }

                private static String sha256(InputStream stream)
                    throws IOException {
                    MessageDigest digest = newSha256();
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        digest.update(buffer, 0, count);
                    }
                    return hex(digest.digest());
                }

                private static MessageDigest newSha256() {
                    try {
                        return MessageDigest.getInstance("SHA-256");
                    } catch (NoSuchAlgorithmException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                }

                private static void updateDigest(
                    MessageDigest digest,
                    String value
                ) {
                    digest.update(value.getBytes(StandardCharsets.UTF_8));
                }

                private static String hex(byte[] hash) {
                    StringBuilder result = new StringBuilder(hash.length * 2);
                    for (byte value : hash) {
                        result.append(
                            String.format(
                                Locale.ROOT,
                                "%02x",
                                value & 0xff
                            )
                        );
                    }
                    return result.toString();
                }

                private static String describe(Throwable throwable) {
                    String message = throwable.getMessage();
                    return throwable.getClass().getName()
                        + (message == null ? "" : ": " + message);
                }

                private static boolean enabled() {
                    return Boolean.parseBoolean(
                        System.getProperty(ENABLED_PROPERTY, "false")
                    );
                }

                private static final class PlayerSnapshot {
                    private final Vec3 position;
                    private final Vec3 motion;
                    private final float yRot;
                    private final float xRot;
                    private final float yHeadRot;
                    private final float fallDistance;
                    private final boolean onGround;
                    private final boolean noGravity;
                    private final boolean invulnerable;

                    private PlayerSnapshot(ServerPlayer player) {
                        this.position = player.position();
                        this.motion = player.getDeltaMovement();
                        this.yRot = player.getYRot();
                        this.xRot = player.getXRot();
                        this.yHeadRot = player.getYHeadRot();
                        this.fallDistance = player.fallDistance;
                        this.onGround = player.onGround();
                        this.noGravity = player.isNoGravity();
                        this.invulnerable = player.isInvulnerable();
                    }

                    private void restoreNonPositionState(ServerPlayer player) {
                        player.setDeltaMovement(this.motion);
                        player.setYRot(this.yRot);
                        player.setXRot(this.xRot);
                        player.setYHeadRot(this.yHeadRot);
                        player.fallDistance = this.fallDistance;
                        player.setOnGround(this.onGround);
                        player.setNoGravity(this.noGravity);
                        player.setInvulnerable(this.invulnerable);
                    }

                    private boolean matches(ServerPlayer player) {
                        return closeVec(
                                player.position(),
                                this.position,
                                EPSILON
                            )
                            && closeVec(
                                player.getDeltaMovement(),
                                this.motion,
                                EPSILON
                            )
                            && close(player.getYRot(), this.yRot, 0.0D)
                            && close(player.getXRot(), this.xRot, 0.0D)
                            && close(
                                player.getYHeadRot(),
                                this.yHeadRot,
                                0.0D
                            )
                            && close(
                                player.fallDistance,
                                this.fallDistance,
                                0.0D
                            )
                            && player.onGround() == this.onGround
                            && player.isNoGravity() == this.noGravity
                            && player.isInvulnerable() == this.invulnerable;
                    }

                    private boolean matchesClient(LocalPlayer player) {
                        // Server-only invulnerability and server physics are checked by
                        // matches(ServerPlayer). Await the actual teleport and replicated
                        // gravity flag on the client before the scenario can start.
                        return closeVec(player.position(), this.position, EPSILON)
                            && close(player.getYRot(), this.yRot, 0.0D)
                            && close(player.getXRot(), this.xRot, 0.0D)
                            && player.isNoGravity() == this.noGravity;
                    }

                    private JsonObject toJson() {
                        JsonObject result = new JsonObject();
                        result.add("position", vecJson(this.position));
                        result.add("motion", vecJson(this.motion));
                        result.addProperty("yRot", this.yRot);
                        result.addProperty("xRot", this.xRot);
                        result.addProperty("yHeadRot", this.yHeadRot);
                        result.addProperty("fallDistance", this.fallDistance);
                        result.addProperty("onGround", this.onGround);
                        result.addProperty("noGravity", this.noGravity);
                        result.addProperty("invulnerable", this.invulnerable);
                        return result;
                    }
                }

                private static final class RuntimeClasspathFingerprint {
                    private final String sha256;
                    private final JsonArray entries;

                    private RuntimeClasspathFingerprint(
                        String sha256,
                        JsonArray entries
                    ) {
                        this.sha256 = sha256;
                        this.entries = entries;
                    }
                }

                private static final class ScenarioEvidence {
                    private final String id;
                    private final Vec3 serverMotion;
                    private final int expectedLifecycleCount;
                    private final List<LifecycleEvidence> lifecycles =
                        new ArrayList<>();
                    private final List<Long> serverStartTrackingTicks =
                        new ArrayList<>();
                    private final List<Long> serverStopTrackingTicks =
                        new ArrayList<>();
                    private PackageEntity serverEntity;
                    private int serverEntityId = -1;
                    private UUID serverEntityUuid;
                    private String serverIdentityToken;
                    private Vec3 spawnPosition;
                    private ItemStack expectedBox;
                    private float expectedWidth;
                    private float expectedHeight;
                    private int[] packetRawMotion;
                    private Vec3 packetDecodedMotion;
                    private Vec3 deferredAverageMotion;
                    private Vec3 payloadFloatMotion;
                    private long startedAtTick = -1L;
                    private long finishedAtTick = -1L;
                    private long cleanupRequestedTick = -1L;
                    private long cleanupClientAbsentTick = -1L;
                    private long cleanupServerAbsentTick = -1L;
                    private long clientTrackerLeaveTick = -1L;
                    private long trackerMoveOutTick = -1L;
                    private int trackerTypeRangeBlocks = -1;
                    private int trackerScaledRangeBlocks = -1;
                    private int trackerViewDistanceBlocks = -1;
                    private double trackerEffectiveRangeBlocks = -1.0D;
                    private double trackerExitDistanceBlocks = -1.0D;
                    private boolean trackerDestinationResident;
                    private long trackerExitWaitStartTick = -1L;
                    private long trackerMoveBackTick = -1L;
                    private int serverStartTrackingCount;
                    private int serverStopTrackingCount;
                    private boolean addFreshEntityReturned;
                    private boolean clientTrackerLeaveObserved;
                    private boolean trackerMovedOut;
                    private boolean trackerMovedBack;
                    private boolean playerRestored;
                    private boolean sameServerEntityPassed;
                    private boolean freshClientInstancePassed;
                    private boolean lifecycleRepeatedPassed;
                    private boolean cleanupPassed;
                    private boolean passed;
                    private String failure;
                    private PlayerSnapshot trackerPlayerSnapshot;
                    private Path artifactPath;
                    private String artifactSha256;

                    private ScenarioEvidence(
                        String id,
                        Vec3 serverMotion,
                        int expectedLifecycleCount
                    ) {
                        this.id = id;
                        this.serverMotion = serverMotion;
                        this.expectedLifecycleCount = expectedLifecycleCount;
                        this.playerRestored = expectedLifecycleCount == 1;
                    }
                }

                private static final class LifecycleEvidence {
                    private PackageEntity clientEntity;
                    private int ordinal;
                    private int clientEntityId = -1;
                    private UUID clientEntityUuid;
                    private String identityToken = "";
                    private long joinClientTick = -1L;
                    private Vec3 joinPosition;
                    private Vec3 joinMotion;
                    private Vec3 joinClientPosition;
                    private Vec3 joinPendingMotion;
                    private ItemStack joinBox;
                    private float joinWidth;
                    private float joinHeight;
                    private int joinTickCount = -1;
                    private boolean joinFirstTick;
                    private boolean joinPending;
                    private boolean joined;
                    private long sizeClientTick = -1L;
                    private Vec3 sizeMotion;
                    private Vec3 sizePendingMotion;
                    private ItemStack sizeBox;
                    private float sizeOldWidth;
                    private float sizeOldHeight;
                    private float sizeNewWidth;
                    private float sizeNewHeight;
                    private boolean sizePending;
                    private boolean sizeObserved;
                    private long preTickClientTick = -1L;
                    private int preTickEntityTickCount = -1;
                    private boolean preTickFirstTick;
                    private Vec3 preTickMotion;
                    private ItemStack preTickBox;
                    private float preTickWidth;
                    private float preTickHeight;
                    private boolean preTickObserved;
                    private boolean joinPreInsertionPassed;
                    private boolean joinClientPositionPassed;
                    private boolean joinRawMotionPassed;
                    private boolean joinPendingTruePassed;
                    private boolean joinDefaultBoxDimensionsPassed;
                    private boolean sizePostJoinPassed;
                    private boolean sizeDecodedBoxDimensionsPassed;
                    private boolean sizePendingFalsePassed;
                    private boolean sizeDeferredAveragePassed;
                    private boolean preTickBeforePackageTickPassed;
                    private boolean preTickAuthoritativeMotionPassed;
                    private boolean preTickDecodedBoxDimensionsPassed;
                    private boolean complete;
                }
            }
        """.trimIndent()
    }
}
