package com.modporter.integration

internal object LegacyCapabilityNativeRuntimeHarnessSource {
    const val FILE_NAME = "ModPorterLegacyCapabilityNativeHarness.java"
    const val RECEIPT_FILE_NAME = "modporter-legacy-capability-native-receipt.json"
    const val TRACE_FILE_NAME = "modporter-legacy-capability-native-trace.json"
    const val PROFILE_ID = "legacy-block-entity-capability-native-v1"

    fun source(harnessPackage: String, compatPackage: String): String = """
        package $harnessPackage;

        import $compatPackage.LazyOptional;
        import $compatPackage.LegacyBlockEntityCapabilityQueries;
        import $compatPackage.LegacyCapabilities;
        import com.google.gson.Gson;
        import com.google.gson.GsonBuilder;
        import com.google.gson.JsonArray;
        import com.google.gson.JsonObject;
        import java.io.IOException;
        import java.io.InputStream;
        import java.nio.charset.StandardCharsets;
        import java.nio.file.AtomicMoveNotSupportedException;
        import java.nio.file.Files;
        import java.nio.file.Path;
        import java.nio.file.StandardCopyOption;
        import java.security.MessageDigest;
        import java.util.HexFormat;
        import java.util.List;
        import java.util.UUID;
        import java.util.concurrent.atomic.AtomicBoolean;
        import java.util.concurrent.atomic.AtomicInteger;
        import java.util.concurrent.atomic.AtomicReference;
        import net.minecraft.client.Minecraft;
        import net.minecraft.client.multiplayer.ClientLevel;
        import net.minecraft.core.BlockPos;
        import net.minecraft.core.Direction;
        import net.minecraft.server.level.ServerLevel;
        import net.minecraft.server.level.ServerPlayer;
        import net.minecraft.world.level.ChunkPos;
        import net.minecraft.world.level.block.Blocks;
        import net.minecraft.world.level.block.entity.BlockEntity;
        import net.minecraft.world.level.block.state.BlockState;
        import net.neoforged.api.distmarker.Dist;
        import net.neoforged.bus.api.SubscribeEvent;
        import net.neoforged.fml.common.EventBusSubscriber;
        import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
        import net.neoforged.neoforge.capabilities.Capabilities;
        import net.neoforged.neoforge.client.event.ClientTickEvent;
        import net.neoforged.neoforge.event.tick.ServerTickEvent;
        import net.neoforged.neoforge.items.IItemHandler;

        @EventBusSubscriber(value = Dist.CLIENT)
        public final class ModPorterLegacyCapabilityNativeHarness {
            private static final String PROFILE_ID = "$PROFILE_ID";
            private static final String WRAPPER_SESSION_ENV = "MINECRAFT_MOD_TEST_SESSION_ID";
            private static final int CLIENT_FIXTURE_TIMEOUT_TICKS = 240;
            private static final int CLIENT_CLEANUP_FENCE_TICKS = 3;
            private static final Object LOCK = new Object();
            private static final Gson GSON = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
            private static final List<String> REQUESTED_SCENARIOS = List.of(
                "server-position-native",
                "server-chunk-nested",
                "server-chunk-exception",
                "client-position-default",
                "client-chunk-nested",
                "client-chunk-exception"
            );
            private static final JsonArray SCENARIOS = new JsonArray();

            private static volatile Throwable failure;
            private static volatile boolean serverScenarioPassed;
            private static volatile boolean clientScenarioPassed;
            private static volatile boolean clientScenarioFinished;
            private static volatile boolean mutationStarted;
            private static volatile boolean serverCleanupFinished;
            private static volatile boolean clientCleanupFencePassed;
            private static volatile boolean receiptSealed;
            private static volatile BlockPos probePos;
            private static volatile ServerLevel probeLevel;
            private static volatile BlockState originalState;
            private static volatile boolean originalBlockEntityPresent;
            private static int serverWaitTicks;
            private static int clientWaitTicks;
            private static int clientCleanupStableTicks;
            private static int serverNativeNotices;
            private static int serverLegacyNotices;
            private static int clientLegacyNotices;
            private static long serverCleanupTick = -1L;
            private static long clientCleanupStartTick = -1L;
            private static long clientCleanupEndTick = -1L;
            private static long lastClientCleanupGameTick = -1L;

            private ModPorterLegacyCapabilityNativeHarness() {
            }

            @SubscribeEvent
            public static void onServerTick(ServerTickEvent.Post event) {
                if (!enabled() || receiptSealed) {
                    return;
                }
                if (!runtimePrerequisitesComplete()) {
                    return;
                }
                try {
                    if (!serverScenarioPassed && failure == null) {
                        ServerPlayer player = event.getServer().getPlayerList().getPlayers().stream()
                            .findFirst()
                            .orElse(null);
                        if (player == null) {
                            return;
                        }
                        serverWaitTicks++;
                        if (serverWaitTicks < 20) {
                            return;
                        }
                        runServerScenario(player.serverLevel(), player);
                    }
                    if ((clientScenarioFinished || failure != null) && !serverCleanupFinished) {
                        cleanupServerFixture();
                    }
                } catch (Throwable throwable) {
                    fail("server", throwable);
                    boolean cleanupFailed = false;
                    try {
                        cleanupServerFixture();
                    } catch (Throwable cleanupFailure) {
                        cleanupFailed = true;
                        addSuppressed(cleanupFailure);
                    }
                    if (cleanupFailed || probeLevel == null || probePos == null || originalState == null) {
                        System.setProperty("modporter.capabilityProbe.passed", "false");
                        System.setProperty("modporter.capabilityProbe.completed", "true");
                    }
                }
            }

            @SubscribeEvent
            public static void onClientTick(ClientTickEvent.Post event) {
                if (!enabled() || receiptSealed) {
                    return;
                }
                Throwable terminalFailure = null;
                try {
                    Minecraft minecraft = Minecraft.getInstance();
                    ClientLevel level = minecraft.level;
                    if (level == null || probePos == null) {
                        return;
                    }
                    if (failure == null && serverScenarioPassed && !clientScenarioFinished) {
                        BlockEntity blockEntity = level.getBlockEntity(probePos);
                        if (blockEntity == null) {
                            clientWaitTicks++;
                            if (clientWaitTicks > CLIENT_FIXTURE_TIMEOUT_TICKS) {
                                throw new IllegalStateException(
                                    "client did not receive temporary chest block entity"
                                );
                            }
                            return;
                        }
                        runClientScenario(level, blockEntity);
                        clientScenarioPassed = true;
                        clientScenarioFinished = true;
                    }
                    if (serverCleanupFinished) {
                        observeClientCleanupFence(level);
                    }
                    if (clientCleanupFencePassed) {
                        sealReceipt(failure == null);
                        if (failure != null) {
                            terminalFailure = new IllegalStateException(
                                "Legacy capability native probe failed after cleanup",
                                failure
                            );
                        }
                    }
                } catch (Throwable throwable) {
                    clientScenarioFinished = true;
                    fail("client", throwable);
                }
                if (terminalFailure != null) {
                    if (terminalFailure instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new RuntimeException(terminalFailure);
                }
            }

            private static void runServerScenario(ServerLevel level, ServerPlayer player) {
                BlockPos selected = selectProbePosition(level, player.blockPosition());
                probeLevel = level;
                probePos = selected.immutable();
                originalState = level.getBlockState(selected);
                originalBlockEntityPresent = level.getBlockEntity(selected) != null;
                if (!originalState.isAir() || originalBlockEntityPresent) {
                    throw new IllegalStateException(
                        "selected capability probe position is not empty: " + selected
                    );
                }
                mutationStarted = true;
                if (!level.setBlock(selected, Blocks.CHEST.defaultBlockState(), 3)) {
                    throw new IllegalStateException(
                        "could not place temporary capability probe chest at " + selected
                    );
                }
                BlockEntity blockEntity = level.getBlockEntity(selected);
                if (blockEntity == null) {
                    throw new IllegalStateException(
                        "temporary chest has no block entity at " + selected
                    );
                }
                requireNativeItemHandler(level, blockEntity, "server baseline");

                AtomicInteger positionLegacy = new AtomicInteger();
                AtomicInteger positionNative = new AtomicInteger();
                AtomicReference<LazyOptional<IItemHandler>> positionReplacement = new AtomicReference<>();
                LazyOptional<IItemHandler> first = retained(blockEntity);
                requirePresent(first, "server position wrapper");
                if (retained(blockEntity) != first) {
                    throw new AssertionError(
                        "server retained wrapper identity changed before invalidation"
                    );
                }
                first.addListener(ignored -> {
                    if (positionNative.get() != 1) {
                        throw new AssertionError(
                            "legacy position listener ran before native callback"
                        );
                    }
                    positionLegacy.incrementAndGet();
                    positionReplacement.set(retained(blockEntity));
                });
                long positionStart = level.getGameTime();
                invokeNativeInvalidation(
                    "server position",
                    level,
                    selected,
                    first,
                    positionLegacy,
                    positionNative,
                    () -> level.invalidateCapabilities(selected)
                );
                LazyOptional<IItemHandler> second = positionReplacement.get();
                assertPhase(
                    "server position",
                    blockEntity,
                    first,
                    second,
                    positionLegacy.get(),
                    positionNative.get()
                );
                recordScenario(
                    "server-position-native",
                    "server",
                    positionStart,
                    level.getGameTime(),
                    positionNative.get(),
                    positionLegacy.get(),
                    true
                );

                AtomicInteger chunkLegacy = new AtomicInteger();
                AtomicInteger chunkNative = new AtomicInteger();
                AtomicReference<LazyOptional<IItemHandler>> chunkReplacement = new AtomicReference<>();
                second.addListener(ignored -> {
                    if (chunkNative.get() != 1) {
                        throw new AssertionError(
                            "nested legacy listener ran before native chunk callback"
                        );
                    }
                    chunkLegacy.incrementAndGet();
                    chunkReplacement.set(retained(blockEntity));
                });
                long chunkStart = level.getGameTime();
                invokeNativeInvalidation(
                    "server nested chunk",
                    level,
                    selected,
                    second,
                    chunkLegacy,
                    chunkNative,
                    () -> LegacyBlockEntityCapabilityQueries.runInvalidation(
                        level,
                        selected,
                        () -> level.invalidateCapabilities(new ChunkPos(selected))
                    )
                );
                LazyOptional<IItemHandler> third = chunkReplacement.get();
                assertPhase(
                    "server nested chunk",
                    blockEntity,
                    second,
                    third,
                    chunkLegacy.get(),
                    chunkNative.get()
                );
                recordScenario(
                    "server-chunk-nested",
                    "server",
                    chunkStart,
                    level.getGameTime(),
                    chunkNative.get(),
                    chunkLegacy.get(),
                    true
                );

                AtomicInteger exceptionalLegacy = new AtomicInteger();
                AtomicInteger exceptionalNative = new AtomicInteger();
                AtomicBoolean nativeBodyReturned = new AtomicBoolean();
                third.addListener(ignored -> {
                    if (!nativeBodyReturned.get()) {
                        throw new AssertionError(
                            "exceptional legacy listener ran before native call completed"
                        );
                    }
                    if (exceptionalNative.get() != 1) {
                        throw new AssertionError(
                            "exceptional legacy listener ran before native callback"
                        );
                    }
                    exceptionalLegacy.incrementAndGet();
                });
                MarkerException marker = new MarkerException();
                long exceptionalStart = level.getGameTime();
                try {
                    invokeNativeInvalidation(
                        "server exceptional chunk",
                        level,
                        selected,
                        third,
                        exceptionalLegacy,
                        exceptionalNative,
                        () -> LegacyBlockEntityCapabilityQueries.runInvalidation(
                            level,
                            selected,
                            () -> {
                                level.invalidateCapabilities(new ChunkPos(selected));
                                nativeBodyReturned.set(true);
                                throw marker;
                            }
                        )
                    );
                    throw new AssertionError("native marker exception was swallowed");
                } catch (MarkerException observed) {
                    if (observed != marker) {
                        throw new AssertionError(
                            "native marker exception identity changed"
                        );
                    }
                }
                if (third.isPresent() || exceptionalLegacy.get() != 1) {
                    throw new AssertionError(
                        "exceptional nested invalidation did not complete legacy notifications"
                    );
                }
                LazyOptional<IItemHandler> fourth = retained(blockEntity);
                requirePresent(fourth, "server exceptional replacement wrapper");
                if (fourth == third || retained(blockEntity) != fourth) {
                    throw new AssertionError(
                        "server exceptional replacement wrapper identity was not retained"
                    );
                }
                recordScenario(
                    "server-chunk-exception",
                    "server",
                    exceptionalStart,
                    level.getGameTime(),
                    exceptionalNative.get(),
                    exceptionalLegacy.get(),
                    true
                );

                serverNativeNotices =
                    positionNative.get() + chunkNative.get() + exceptionalNative.get();
                serverLegacyNotices =
                    positionLegacy.get() + chunkLegacy.get() + exceptionalLegacy.get();
                if (serverNativeNotices != 3 || serverLegacyNotices != 3) {
                    throw new AssertionError(
                        "server notice totals native=" + serverNativeNotices
                            + " legacy=" + serverLegacyNotices
                    );
                }
                serverScenarioPassed = true;
                System.out.println(
                    "[ModPorterCapabilityProbe] Server PASS pos=" + selected
                        + " nativeNotices=" + serverNativeNotices
                        + " legacyNotices=" + serverLegacyNotices
                );
            }

            private static void runClientScenario(ClientLevel level, BlockEntity blockEntity) {
                BlockPos pos = blockEntity.getBlockPos();
                requireNativeItemHandler(level, blockEntity, "client baseline");

                AtomicInteger positionNotices = new AtomicInteger();
                AtomicReference<LazyOptional<IItemHandler>> positionReplacement = new AtomicReference<>();
                LazyOptional<IItemHandler> first = retained(blockEntity);
                requirePresent(first, "client position wrapper");
                if (retained(blockEntity) != first) {
                    throw new AssertionError(
                        "client retained wrapper identity changed before invalidation"
                    );
                }
                first.addListener(ignored -> {
                    positionNotices.incrementAndGet();
                    positionReplacement.set(retained(blockEntity));
                });
                long positionStart = level.getGameTime();
                level.invalidateCapabilities(pos);
                LazyOptional<IItemHandler> second = positionReplacement.get();
                assertLegacyPhase(
                    "client position",
                    blockEntity,
                    first,
                    second,
                    positionNotices.get()
                );
                recordScenario(
                    "client-position-default",
                    "client",
                    positionStart,
                    level.getGameTime(),
                    0,
                    positionNotices.get(),
                    true
                );

                AtomicInteger chunkNotices = new AtomicInteger();
                AtomicReference<LazyOptional<IItemHandler>> chunkReplacement = new AtomicReference<>();
                second.addListener(ignored -> {
                    chunkNotices.incrementAndGet();
                    chunkReplacement.set(retained(blockEntity));
                });
                long chunkStart = level.getGameTime();
                LegacyBlockEntityCapabilityQueries.runInvalidation(
                    level,
                    pos,
                    () -> level.invalidateCapabilities(new ChunkPos(pos))
                );
                LazyOptional<IItemHandler> third = chunkReplacement.get();
                assertLegacyPhase(
                    "client nested chunk",
                    blockEntity,
                    second,
                    third,
                    chunkNotices.get()
                );
                recordScenario(
                    "client-chunk-nested",
                    "client",
                    chunkStart,
                    level.getGameTime(),
                    0,
                    chunkNotices.get(),
                    true
                );

                AtomicInteger exceptionalNotices = new AtomicInteger();
                AtomicBoolean nativeBodyReturned = new AtomicBoolean();
                third.addListener(ignored -> {
                    if (!nativeBodyReturned.get()) {
                        throw new AssertionError(
                            "client exceptional listener ran before default method returned"
                        );
                    }
                    exceptionalNotices.incrementAndGet();
                });
                MarkerException marker = new MarkerException();
                long exceptionalStart = level.getGameTime();
                try {
                    LegacyBlockEntityCapabilityQueries.runInvalidation(
                        level,
                        pos,
                        () -> {
                            level.invalidateCapabilities(new ChunkPos(pos));
                            nativeBodyReturned.set(true);
                            throw marker;
                        }
                    );
                    throw new AssertionError("client marker exception was swallowed");
                } catch (MarkerException observed) {
                    if (observed != marker) {
                        throw new AssertionError(
                            "client marker exception identity changed"
                        );
                    }
                }
                if (third.isPresent() || exceptionalNotices.get() != 1) {
                    throw new AssertionError(
                        "client exceptional invalidation did not complete legacy notifications"
                    );
                }
                LazyOptional<IItemHandler> fourth = retained(blockEntity);
                requirePresent(fourth, "client exceptional replacement wrapper");
                if (fourth == third || retained(blockEntity) != fourth) {
                    throw new AssertionError(
                        "client exceptional replacement wrapper identity was not retained"
                    );
                }
                recordScenario(
                    "client-chunk-exception",
                    "client",
                    exceptionalStart,
                    level.getGameTime(),
                    0,
                    exceptionalNotices.get(),
                    true
                );

                clientLegacyNotices =
                    positionNotices.get() + chunkNotices.get() + exceptionalNotices.get();
                if (clientLegacyNotices != 3) {
                    throw new AssertionError(
                        "client legacy notice total=" + clientLegacyNotices
                    );
                }
                System.out.println(
                    "[ModPorterCapabilityProbe] Client PASS pos=" + pos
                        + " legacyNotices=" + clientLegacyNotices
                );
            }

            private static void invokeNativeInvalidation(
                String name,
                ServerLevel level,
                BlockPos pos,
                LazyOptional<IItemHandler> wrapper,
                AtomicInteger legacyNotices,
                AtomicInteger nativeNotices,
                Runnable invalidation
            ) {
                AtomicBoolean callStarted = new AtomicBoolean();
                AtomicBoolean callFinished = new AtomicBoolean();
                BlockCapabilityCache<IItemHandler, Direction> cache = BlockCapabilityCache.create(
                    Capabilities.ItemHandler.BLOCK,
                    level,
                    pos,
                    Direction.UP,
                    () -> true,
                    () -> {
                        if (!callStarted.get() || callFinished.get()) {
                            throw new AssertionError(
                                name + " native callback was outside the native invalidation call"
                            );
                        }
                        if (wrapper.isPresent()) {
                            throw new AssertionError(
                                name + " retained wrapper was live in native callback"
                            );
                        }
                        if (legacyNotices.get() != 0) {
                            throw new AssertionError(
                                name + " legacy listener ran before native callback"
                            );
                        }
                        nativeNotices.incrementAndGet();
                    }
                );
                if (cache.getCapability() == null) {
                    throw new AssertionError(name + " native item capability is absent");
                }
                callStarted.set(true);
                try {
                    invalidation.run();
                } finally {
                    callFinished.set(true);
                }
            }

            private static void assertPhase(
                String name,
                BlockEntity blockEntity,
                LazyOptional<IItemHandler> oldWrapper,
                LazyOptional<IItemHandler> replacement,
                int legacyNoticeCount,
                int nativeNoticeCount
            ) {
                assertLegacyPhase(
                    name,
                    blockEntity,
                    oldWrapper,
                    replacement,
                    legacyNoticeCount
                );
                if (nativeNoticeCount != 1) {
                    throw new AssertionError(name + " native notices=" + nativeNoticeCount);
                }
            }

            private static void assertLegacyPhase(
                String name,
                BlockEntity blockEntity,
                LazyOptional<IItemHandler> oldWrapper,
                LazyOptional<IItemHandler> replacement,
                int legacyNoticeCount
            ) {
                if (oldWrapper.isPresent()) {
                    throw new AssertionError(name + " left the old wrapper live");
                }
                if (legacyNoticeCount != 1) {
                    throw new AssertionError(name + " legacy notices=" + legacyNoticeCount);
                }
                requirePresent(replacement, name + " replacement wrapper");
                if (replacement == oldWrapper) {
                    throw new AssertionError(name + " reused the invalidated wrapper");
                }
                if (retained(blockEntity) != replacement) {
                    throw new AssertionError(
                        name + " replacement wrapper identity was not retained"
                    );
                }
            }

            private static void requireNativeItemHandler(
                net.minecraft.world.level.Level level,
                BlockEntity blockEntity,
                String name
            ) {
                IItemHandler nativeValue = level.getCapability(
                    Capabilities.ItemHandler.BLOCK,
                    blockEntity.getBlockPos(),
                    blockEntity.getBlockState(),
                    blockEntity,
                    Direction.UP
                );
                if (nativeValue == null) {
                    throw new AssertionError(name + " native item capability is absent");
                }
            }

            private static LazyOptional<IItemHandler> retained(BlockEntity blockEntity) {
                return LegacyBlockEntityCapabilityQueries.getRetained(
                    blockEntity,
                    LegacyCapabilities.ITEM_HANDLER,
                    Direction.UP
                );
            }

            private static void requirePresent(LazyOptional<?> optional, String name) {
                if (optional == null || !optional.isPresent()) {
                    throw new AssertionError(name + " is absent");
                }
            }

            private static BlockPos selectProbePosition(ServerLevel level, BlockPos origin) {
                for (int height = 4; height <= 12; height++) {
                    BlockPos candidate = origin.above(height);
                    if (level.isLoaded(candidate)
                        && level.getBlockState(candidate).isAir()
                        && level.getBlockEntity(candidate) == null) {
                        return candidate.immutable();
                    }
                }
                throw new IllegalStateException(
                    "no empty loaded position above player for capability probe"
                );
            }

            private static void cleanupServerFixture() {
                if (serverCleanupFinished) {
                    return;
                }
                if (probeLevel == null || probePos == null || originalState == null) {
                    throw new IllegalStateException(
                        "capability probe cleanup lacks baseline state"
                    );
                }
                if (mutationStarted && !probeLevel.setBlock(probePos, originalState, 3)) {
                    throw new IllegalStateException(
                        "could not restore capability probe block at " + probePos
                    );
                }
                probeLevel.invalidateCapabilities(probePos);
                BlockState restoredState = probeLevel.getBlockState(probePos);
                boolean restoredBlockEntityPresent =
                    probeLevel.getBlockEntity(probePos) != null;
                if (!restoredState.equals(originalState)) {
                    throw new IllegalStateException(
                        "capability probe block state did not restore at " + probePos
                    );
                }
                if (restoredBlockEntityPresent != originalBlockEntityPresent) {
                    throw new IllegalStateException(
                        "capability probe block entity baseline did not restore at " + probePos
                    );
                }
                serverCleanupTick = probeLevel.getGameTime();
                serverCleanupFinished = true;
                System.out.println(
                    "[ModPorterCapabilityProbe] Server cleanup PASS pos=" + probePos
                        + " tick=" + serverCleanupTick
                );
            }

            private static void observeClientCleanupFence(ClientLevel level) {
                BlockState state = level.getBlockState(probePos);
                boolean blockEntityPresent = level.getBlockEntity(probePos) != null;
                boolean baselineObserved =
                    state.equals(originalState)
                        && blockEntityPresent == originalBlockEntityPresent;
                long gameTick = level.getGameTime();
                if (!baselineObserved) {
                    clientCleanupStableTicks = 0;
                    clientCleanupStartTick = -1L;
                    lastClientCleanupGameTick = -1L;
                    return;
                }
                if (gameTick <= lastClientCleanupGameTick) {
                    return;
                }
                if (lastClientCleanupGameTick >= 0L
                    && gameTick != lastClientCleanupGameTick + 1L) {
                    clientCleanupStableTicks = 0;
                    clientCleanupStartTick = -1L;
                }
                lastClientCleanupGameTick = gameTick;
                if (clientCleanupStableTicks == 0) {
                    clientCleanupStartTick = gameTick;
                }
                clientCleanupStableTicks++;
                clientCleanupEndTick = gameTick;
                if (clientCleanupStableTicks >= CLIENT_CLEANUP_FENCE_TICKS) {
                    clientCleanupFencePassed = true;
                }
            }

            private static void recordScenario(
                String id,
                String side,
                long startTick,
                long endTick,
                int nativeNotices,
                int legacyNotices,
                boolean replacementIdentityPassed
            ) {
                synchronized (LOCK) {
                    JsonObject scenario = new JsonObject();
                    scenario.addProperty("id", id);
                    scenario.addProperty("side", side);
                    scenario.addProperty("ordinal", SCENARIOS.size());
                    scenario.addProperty("startTick", startTick);
                    scenario.addProperty("endTick", endTick);
                    scenario.addProperty("nativeNotices", nativeNotices);
                    scenario.addProperty("legacyNotices", legacyNotices);
                    scenario.addProperty(
                        "replacementIdentityPassed",
                        replacementIdentityPassed
                    );
                    scenario.addProperty("result", "PASS");
                    SCENARIOS.add(scenario);
                }
            }

            private static synchronized void fail(String stage, Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                    System.err.println(
                        "[ModPorterCapabilityProbe] " + stage + " FAIL: " + throwable
                    );
                    throwable.printStackTrace();
                } else if (failure != throwable) {
                    failure.addSuppressed(throwable);
                }
            }

            private static synchronized void addSuppressed(Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                } else if (failure != throwable) {
                    failure.addSuppressed(throwable);
                }
            }

            private static synchronized void sealReceipt(boolean success) throws Exception {
                if (receiptSealed) {
                    return;
                }
                if (!serverCleanupFinished || !clientCleanupFencePassed) {
                    throw new IllegalStateException(
                        "cannot seal capability receipt before cleanup fences"
                    );
                }
                String validationSessionId = requireProperty(
                    "modporter.capabilityProbe.validationSessionId"
                );
                UUID.fromString(validationSessionId);
                String wrapperSessionId = requireEnvironment(WRAPPER_SESSION_ENV);
                UUID.fromString(wrapperSessionId);
                String requestedProfile = requireProperty(
                    "modporter.capabilityProbe.profileId"
                );
                if (!PROFILE_ID.equals(requestedProfile)) {
                    throw new IllegalStateException(
                        "unexpected capability probe profile " + requestedProfile
                    );
                }
                Path evidenceDir = Path.of(
                    requireProperty("modporter.capabilityProbe.evidenceDir")
                ).toAbsolutePath().normalize();
                Path trace = confinedPath(
                    evidenceDir,
                    requireProperty("modporter.capabilityProbe.trace")
                );
                Path receipt = confinedPath(
                    evidenceDir,
                    requireProperty("modporter.capabilityProbe.receipt")
                );
                if (Files.exists(trace) || Files.exists(receipt)) {
                    throw new IllegalStateException(
                        "capability probe evidence path already exists"
                    );
                }

                JsonObject artifacts = artifactHashes();
                JsonObject cleanup = new JsonObject();
                cleanup.addProperty("serverRestorePassed", serverCleanupFinished);
                cleanup.addProperty("serverRestoreTick", serverCleanupTick);
                cleanup.addProperty("clientBaselineObserved", clientCleanupFencePassed);
                cleanup.addProperty("clientFenceStartTick", clientCleanupStartTick);
                cleanup.addProperty("clientFenceEndTick", clientCleanupEndTick);
                cleanup.addProperty("clientFenceTicks", clientCleanupStableTicks);
                cleanup.addProperty(
                    "originalBlockEntityPresent",
                    originalBlockEntityPresent
                );

                JsonArray requested = new JsonArray();
                REQUESTED_SCENARIOS.forEach(requested::add);
                JsonArray observed = new JsonArray();
                synchronized (LOCK) {
                    for (int index = 0; index < SCENARIOS.size(); index++) {
                        observed.add(
                            SCENARIOS.get(index).getAsJsonObject().get("id").getAsString()
                        );
                    }
                }
                boolean semanticPass =
                    success
                        && serverScenarioPassed
                        && clientScenarioPassed
                        && REQUESTED_SCENARIOS.size() == SCENARIOS.size()
                        && requested.equals(observed)
                        && serverNativeNotices == 3
                        && serverLegacyNotices == 3
                        && clientLegacyNotices == 3;
                if (success && !semanticPass) {
                    throw new IllegalStateException(
                        "capability probe semantic trace is incomplete"
                    );
                }

                JsonObject traceRoot = new JsonObject();
                traceRoot.addProperty("schemaVersion", 1);
                traceRoot.addProperty("artifactSchema", PROFILE_ID + ".trace");
                traceRoot.addProperty("profileId", PROFILE_ID);
                traceRoot.addProperty("sessionId", wrapperSessionId);
                traceRoot.addProperty("validationSessionId", validationSessionId);
                traceRoot.add("requestedScenarios", requested);
                traceRoot.add("observedScenarios", observed);
                synchronized (LOCK) {
                    traceRoot.add("scenarios", SCENARIOS.deepCopy());
                }
                traceRoot.add("cleanup", cleanup);
                traceRoot.add("artifactHashes", artifacts);
                traceRoot.addProperty("status", semanticPass ? "PASS" : "FAIL");
                if (failure != null) {
                    traceRoot.addProperty(
                        "failure",
                        failure.getClass().getName() + ": " + String.valueOf(failure.getMessage())
                    );
                }
                atomicWriteJson(trace, traceRoot);
                String traceSha256 = sha256(trace);

                JsonObject receiptRoot = new JsonObject();
                receiptRoot.addProperty("schemaVersion", 1);
                receiptRoot.addProperty("artifactSchema", PROFILE_ID + ".receipt");
                receiptRoot.addProperty("profileId", PROFILE_ID);
                receiptRoot.addProperty("sessionId", wrapperSessionId);
                receiptRoot.addProperty("validationSessionId", validationSessionId);
                receiptRoot.addProperty("processStartCount", 1);
                receiptRoot.addProperty("worldLoadCount", 1);
                receiptRoot.addProperty("scenarioCount", SCENARIOS.size());
                receiptRoot.addProperty("failedCount", semanticPass ? 0 : 1);
                receiptRoot.addProperty("cleanupFailureCount", 0);
                receiptRoot.addProperty("allScenariosPassed", semanticPass);
                receiptRoot.add("requestedScenarios", requested.deepCopy());
                receiptRoot.add("observedScenarios", observed.deepCopy());
                receiptRoot.addProperty("serverNativeNotices", serverNativeNotices);
                receiptRoot.addProperty("serverLegacyNotices", serverLegacyNotices);
                receiptRoot.addProperty("clientLegacyNotices", clientLegacyNotices);
                receiptRoot.addProperty("cleanupPassed", true);
                receiptRoot.addProperty("tracePath", trace.toString());
                receiptRoot.addProperty("traceSha256", traceSha256);
                receiptRoot.add("artifactHashes", artifacts.deepCopy());
                receiptRoot.addProperty("status", semanticPass ? "PASS" : "FAIL");
                atomicWriteJson(receipt, receiptRoot);
                receiptSealed = true;
                System.setProperty(
                    "modporter.capabilityProbe.passed",
                    Boolean.toString(semanticPass)
                );
                System.setProperty("modporter.capabilityProbe.completed", "true");
                System.out.println(
                    "[ModPorterCapabilityProbe] Receipt sealed path=" + receipt
                        + " sha256=" + sha256(receipt)
                        + " traceSha256=" + traceSha256
                        + " status=" + (semanticPass ? "PASS" : "FAIL")
                );
            }

            private static JsonObject artifactHashes() throws Exception {
                JsonObject hashes = new JsonObject();
                String compatPath = "$compatPackage".replace('.', '/');
                String harnessPath = "$harnessPackage".replace('.', '/');
                hashes.addProperty(
                    "helperClassSha256",
                    sha256Resource(compatPath + "/LegacyBlockEntityCapabilityQueries.class")
                );
                hashes.addProperty(
                    "serverMixinClassSha256",
                    sha256Resource(
                        compatPath + "/mixin/LegacyCapabilityServerLevelMixin.class"
                    )
                );
                hashes.addProperty(
                    "levelExtensionMixinClassSha256",
                    sha256Resource(
                        compatPath + "/mixin/LegacyCapabilityLevelExtensionMixin.class"
                    )
                );
                hashes.addProperty(
                    "harnessClassSha256",
                    sha256Resource(
                        harnessPath + "/ModPorterLegacyCapabilityNativeHarness.class"
                    )
                );
                String mixinResource = requireProperty(
                    "modporter.capabilityProbe.mixinConfigResource"
                );
                String mixinSha256 = sha256Resource(mixinResource);
                String expectedMixinSha256 = requireProperty(
                    "modporter.capabilityProbe.mixinConfigSha256"
                );
                if (!mixinSha256.equals(expectedMixinSha256)) {
                    throw new IllegalStateException(
                        "runtime mixin config hash changed: " + mixinSha256
                            + " != " + expectedMixinSha256
                    );
                }
                hashes.addProperty("mixinConfigSha256", mixinSha256);
                return hashes;
            }

            private static String sha256Resource(String resource) throws Exception {
                ClassLoader loader =
                    ModPorterLegacyCapabilityNativeHarness.class.getClassLoader();
                try (InputStream input = loader.getResourceAsStream(resource)) {
                    if (input == null) {
                        throw new IllegalStateException(
                            "missing runtime classpath resource " + resource
                        );
                    }
                    return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())
                    );
                }
            }

            private static Path confinedPath(Path root, String raw) {
                Path path = Path.of(raw).toAbsolutePath().normalize();
                if (!path.startsWith(root)) {
                    throw new IllegalStateException(
                        "capability probe evidence escapes evidence directory: " + path
                    );
                }
                return path;
            }

            private static void atomicWriteJson(Path output, JsonObject value)
                    throws IOException {
                Files.createDirectories(output.getParent());
                Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
                Files.writeString(
                    temporary,
                    GSON.toJson(value) + "\n",
                    StandardCharsets.UTF_8
                );
                try {
                    Files.move(
                        temporary,
                        output,
                        StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, output);
                }
            }

            private static String sha256(Path path) throws Exception {
                return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
                );
            }

            private static String requireProperty(String name) {
                String value = System.getProperty(name, "").trim();
                if (value.isEmpty()) {
                    throw new IllegalStateException("missing system property " + name);
                }
                return value;
            }

            private static String requireEnvironment(String name) {
                String value = System.getenv(name);
                if (value == null || value.trim().isEmpty()) {
                    throw new IllegalStateException("missing environment variable " + name);
                }
                return value.trim();
            }

            private static boolean runtimePrerequisitesComplete() {
                if (Boolean.getBoolean("modporter.strictSpawn.enabled")
                    && !Boolean.getBoolean("modporter.strictSpawn.completed")) {
                    return false;
                }
                return !Boolean.getBoolean("modporter.clientWorldValidation.enabled")
                    || Boolean.getBoolean("modporter.clientWorldValidation.completed");
            }

            private static boolean enabled() {
                return Boolean.getBoolean("modporter.capabilityProbe.enabled");
            }

            private static final class MarkerException extends RuntimeException {
            }
        }
    """.trimIndent()
}
