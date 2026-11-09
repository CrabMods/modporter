package com.modporter.integration

/**
 * Generated source template for the Create station-map strict runtime harness.
 *
 * The benchmark writes this source into an isolated converted project before
 * compiling and running the runtime acceptance scenarios.
 */
internal object CreateStationMapRuntimeHarnessSource {
    fun source(packageName: String, modId: String): String {
        require(packageName.matches(Regex("""[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*"""))) {
            "Invalid Java package name: $packageName"
        }
        require(modId.matches(Regex("""[a-z][a-z0-9_.-]*"""))) {
            "Invalid mod id: $modId"
        }

        return listOf(
            """
            package $packageName;

            import com.google.gson.Gson;
            import com.google.gson.GsonBuilder;
            import com.google.gson.JsonArray;
            import com.google.gson.JsonElement;
            import com.google.gson.JsonObject;
            import com.google.gson.JsonParser;
            import com.mojang.blaze3d.platform.NativeImage;
            import com.mojang.blaze3d.systems.RenderSystem;
            import com.mojang.blaze3d.vertex.PoseStack;
            import com.simibubi.create.AllMapDecorationTypes;
            import com.simibubi.create.content.trains.station.StationMapData;
            import com.simibubi.create.content.trains.station.StationMarker;
            import io.netty.buffer.ByteBuf;
            import io.netty.buffer.Unpooled;
            import java.io.ByteArrayOutputStream;
            import java.io.DataOutputStream;
            import java.io.File;
            import java.io.IOException;
            import java.lang.reflect.Field;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.AtomicMoveNotSupportedException;
            import java.nio.file.Files;
            import java.nio.file.LinkOption;
            import java.nio.file.Path;
            import java.nio.file.StandardCopyOption;
            import java.nio.file.StandardOpenOption;
            import java.security.MessageDigest;
            import java.util.ArrayList;
            import java.util.Arrays;
            import java.util.Comparator;
            import java.util.HashSet;
            import java.util.LinkedHashMap;
            import java.util.List;
            import java.util.Locale;
            import java.util.Map;
            import java.util.Optional;
            import java.util.Set;
            import java.util.UUID;
            import java.util.zip.ZipFile;
            import net.minecraft.SharedConstants;
            import net.minecraft.client.CameraType;
            import net.minecraft.client.Minecraft;
            import net.minecraft.client.Screenshot;
            import net.minecraft.client.gui.MapRenderer;
            import net.minecraft.client.gui.screens.Screen;
            import net.minecraft.client.multiplayer.ClientLevel;
            import net.minecraft.client.renderer.MultiBufferSource;
            import net.minecraft.client.renderer.texture.AbstractTexture;
            import net.minecraft.client.renderer.texture.DynamicTexture;
            import net.minecraft.client.renderer.texture.TextureManager;
            import net.minecraft.client.resources.MapDecorationTextureManager;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.component.DataComponents;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.NbtIo;
            import net.minecraft.nbt.Tag;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.network.chat.Component;
            import net.minecraft.network.protocol.Packet;
            import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.saveddata.maps.MapDecoration;
            import net.minecraft.world.level.saveddata.maps.MapDecorationType;
            import net.minecraft.world.level.saveddata.maps.MapId;
            import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.bus.api.EventPriority;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.ModList;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
            import net.neoforged.fml.loading.targets.CommonLaunchHandler;
            import net.neoforged.neoforge.client.event.ClientTickEvent;
            import net.neoforged.neoforge.client.gui.map.IMapDecorationRenderer;
            import net.neoforged.neoforge.client.gui.map.MapDecorationRendererManager;
            import net.neoforged.neoforge.client.gui.map.RegisterMapDecorationRenderersEvent;
            import net.neoforged.neoforge.event.tick.ServerTickEvent;

            @EventBusSubscriber(modid = "$modId", value = Dist.CLIENT)
            public final class ModPorterStrictStationMapHarness {
                private static final String PROFILE_ID =
                    "create-station-map/native-roundtrip/v1";
                private static final String SPAWN_PROFILE_ID =
                    "legacy-client-spawn-factory/package-entity/v1";
                private static final String STATION_SCHEMA =
                    "modporter.strict-review.create-station-map.v1";
                private static final String COORDINATOR_SCHEMA =
                    "modporter.strict-review.client-world-coordinator.v1";
                private static final String SPAWN_SCHEMA =
                    "modporter.strict-review.legacy-client-spawn-factory.v1";
                private static final String SCENARIO_ID =
                    "station_map_native_roundtrip";
                private static final String ENABLED_PROPERTY =
                    "modporter.strictStation.enabled";
                private static final String PROFILE_PROPERTY =
                    "modporter.strictStation.profileId";
                private static final String VALIDATION_SESSION_PROPERTY =
                    "modporter.strictStation.sessionId";
                private static final String BINDINGS_PROPERTY =
                    "modporter.strictStation.expectedBindings";
                private static final String RECEIPT_PROPERTY =
                    "modporter.strictStation.receipt";
                private static final String EVIDENCE_DIR_PROPERTY =
                    "modporter.strictStation.evidenceDir";
                private static final String COMPLETED_PROPERTY =
                    "modporter.strictStation.completed";
                private static final String SPAWN_RECEIPT_PROPERTY =
                    "modporter.strictSpawn.receipt";
                private static final String SPAWN_COMPLETED_PROPERTY =
                    "modporter.strictSpawn.completed";
                private static final String BROWSE_COMPLETED_PROPERTY =
                    "modporter.benchmark.clientBrowse.completed";
                private static final String COORDINATOR_RECEIPT_PROPERTY =
                    "modporter.clientWorldValidation.receipt";
                private static final String COORDINATOR_COMPLETED_PROPERTY =
                    "modporter.clientWorldValidation.completed";
                private static final String WRAPPER_SESSION_ENV =
                    "MINECRAFT_MOD_TEST_SESSION_ID";
                private static final String EXPECTED_MINECRAFT_VERSION = "1.21.1";
                private static final String EXPECTED_NEOFORGE_VERSION = "21.1.230";
                private static final String EXPECTED_RENDERER =
                    "com.simibubi.create.foundation.map.StationMapDecorationRenderer";
                private static final String STATION_TYPE_KEY = "create:station";
                private static final int GLOBAL_TIMEOUT_TICKS = 2400;
                private static final int STAGE_TIMEOUT_TICKS = 400;
                private static final int CLEANUP_FENCE_TICKS = 3;
                private static final int MAX_MAP_ID_RESERVATION_ATTEMPTS = 1024;
                private static final Object LOCK = new Object();
                private static final Gson GSON = new GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .create();
                private static final List<String> REQUESTED_SCENARIOS =
                    List.of(SCENARIO_ID);
                private static final List<String> SEMANTIC_CHECKS = List.of(
                    "station.provider_save_reload",
                    "station.packet_codec_client_update",
                    "station.first_person_native_renderer"
                );
                private static final List<String> COORDINATOR_SCENARIOS = List.of(
                    "non_quantized_motion",
                    "packet_clamped_motion",
                    "tracker_reentry",
                    SCENARIO_ID
                );

                private static Stage stage = Stage.WAIT_PREREQUISITES;
                private static boolean initialized;
                private static boolean stationSealed;
                private static boolean coordinatorSealed;
                private static boolean cleanupPassed;
                private static boolean rendererRestored;
                private static boolean serverRestoreIssued;
                private static boolean clientMapRemoved;
                private static boolean mapRendererEntryRemoved;
                private static boolean dynamicTextureReleased;
                private static boolean uiRestored;
                private static boolean serverHandsRestored;
                private static boolean clientHandsRestored;
                private static boolean itemInHandCacheRestored;
                private static boolean serverMapAbsent;
                private static boolean renderObserved;
                private static boolean productionDelegateReturned;
                private static boolean renderThread;
                private static boolean itemInHandRenderMapFrame;
                private static boolean mapRendererRenderFrame;
                private static boolean mapInstanceDrawFrame;
                private static boolean rendererManagerRenderFrame;
                private static boolean packetFullyConsumed;
                private static boolean packetDeepFieldsEqual;
                private static boolean mapPatchBytesEqual;
                private static boolean sentThroughServerConnection;
                private static boolean clientObjectDistinctFromServer;
                private static boolean clientStateReserved;
                private static long clientTicks;
                private static long serverTicks;
                private static long stageStartedTick;
                private static long fixtureReadyTick = -1L;
                private static long scenarioOwnedTick = -1L;
                private static long scenarioStartedAtTick = -1L;
                private static long scenarioFinishedAtTick = -1L;
                private static long cleanupStableClientTick = -1L;
                private static JsonObject firstChunkQuiescence;
                private static JsonObject bestChunkQuiescence;
                private static JsonObject lastChunkQuiescence;
                private static double bestChunkQuiescenceScore = Double.POSITIVE_INFINITY;
                private static long lastChunkSampleServerTick = -1L;
                private static long chunkStableSinceServerTick = -1L;
                private static int chunkStableServerTicks;
                private static boolean chunkExitHealthPassed;
                private static int cleanupFailureCount;
                private static int overlapFailureCount;
                private static int screenshotWidth;
                private static int screenshotHeight;
                private static int sampledDistinctColors;
                private static int encodedByteCount;
                private static int ownedMapIdValue;
                private static int heldMapIdValue;
                private static int mapIdReservationAttempts;
                private static String terminalFailure;
                private static String wrapperSessionId;
                private static String validationSessionId;
                private static Path bindingsPath;
                private static Path stationReceiptPath;
                private static Path spawnReceiptPath;
                private static Path coordinatorReceiptPath;
                private static Path evidenceDir;
                private static Path tracePath;
                private static Path screenshotPath;
                private static String traceSha256;
                private static String screenshotSha256;
                private static String savedTagSha256;
                private static int savedTagByteCount;
                private static String encodedSha256;
                private static String connectionType;
                private static String providerClass;
                private static JsonObject bindings;
                private static JsonObject expectedDecoration;
                private static JsonObject reloadedDecoration;
                private static JsonObject decodedDecoration;
                private static JsonObject clientDecoration;
                private static JsonObject renderedDecoration;
                private static UUID playerUuid;
                private static MapId ownedMapId;
                private static MapItemSavedData serverData;
                private static MapItemSavedData reloadedData;
                private static MapItemSavedData observedClientData;
                private static ItemStack serverMainSnapshot;
                private static ItemStack serverOffSnapshot;
                private static ItemStack clientMainSnapshot;
                private static ItemStack clientOffSnapshot;
                private static Screen clientScreenSnapshot;
                private static CameraType cameraSnapshot;
                private static boolean hideGuiSnapshot;
                private static MapDecorationType stationType;
                private static Map<MapDecorationType, IMapDecorationRenderer>
                    rendererRegistry;
                private static IMapDecorationRenderer productionRenderer;
                private static IMapDecorationRenderer recordingRenderer;
                private static String registrationFailure;
                private static Field clientMapDataField;
                private static Field mapRendererMapsField;
                private static Field mapInstanceTextureField;
                private static Field textureManagerByPathField;
                private static JsonArray runtimeClasspathEntries;
                private static String runtimeClasspathSha256;
                private static JsonObject runtimeClassResources;
                private static String runtimeJavaExecutable;
                private static String runtimeJavaExecutableSha256;

                private enum Stage {
                    WAIT_PREREQUISITES,
                    CLIENT_RESERVE,
                    SERVER_PREPARE,
                    WAIT_CLIENT_HAND,
                    SERVER_CODEC_SEND,
                    WAIT_CLIENT_DATA,
                    WAIT_PRODUCTION_RENDER,
                    CAPTURE_SCREENSHOT,
                    SERVER_RESTORE,
                    WAIT_CLIENT_RESTORE,
                    CLIENT_CLEANUP,
                    CLEANUP_FENCE,
                    FAILURE_CLEANUP,
                    SEAL,
                    COMPLETE
                }

                private ModPorterStrictStationMapHarness() {
                }

                /**
                 * FML 4.0.42 ignores EventBusSubscriber.bus and automatically
                 * routes IModBusEvent handlers to the mod bus.
                 */
                @EventBusSubscriber(modid = "$modId", value = Dist.CLIENT)
                public static final class RegistrationObserver {
                    private RegistrationObserver() {
                    }

                    @SubscribeEvent(priority = EventPriority.LOWEST)
                    public static void onRegister(
                            RegisterMapDecorationRenderersEvent event) {
                        if (!enabled()) {
                            return;
                        }
                        synchronized (LOCK) {
                            if (rendererRegistry != null || registrationFailure != null) {
                                registrationFailure =
                                    "station renderer registration event was observed more than once";
                                return;
                            }
                            try {
                                Field field = RegisterMapDecorationRenderersEvent.class
                                    .getDeclaredField("renderers");
                                field.setAccessible(true);
                                @SuppressWarnings("unchecked")
                                Map<MapDecorationType, IMapDecorationRenderer> renderers =
                                    (Map<MapDecorationType, IMapDecorationRenderer>) field.get(event);
                                stationType =
                                    AllMapDecorationTypes.STATION_MAP_DECORATION.value();
                                ResourceLocation key =
                                    BuiltInRegistries.MAP_DECORATION_TYPE.getKey(stationType);
                                if (key == null || !STATION_TYPE_KEY.equals(key.toString())) {
                                    throw new IllegalStateException(
                                        "unexpected station decoration type key: " + key
                                    );
                                }
                                IMapDecorationRenderer existing = renderers.get(stationType);
                                if (existing == null) {
                                    throw new IllegalStateException(
                                        "production station renderer was not registered"
                                    );
                                }
                                if (!EXPECTED_RENDERER.equals(existing.getClass().getName())) {
                                    throw new IllegalStateException(
                                        "unexpected production station renderer: "
                                            + existing.getClass().getName()
                                    );
                                }
                                rendererRegistry = renderers;
                                productionRenderer = existing;
                                recordingRenderer = new RecordingRenderer(existing);
                                IMapDecorationRenderer replaced =
                                    renderers.put(stationType, recordingRenderer);
                                if (replaced != existing
                                    || renderers.get(stationType) != recordingRenderer) {
                                    throw new IllegalStateException(
                                        "failed to install recording renderer by exact identity"
                                    );
                                }
                            } catch (Throwable throwable) {
                                registrationFailure =
                                    "station renderer observation failed: " + describe(throwable);
                            }
                        }
                    }
                }

                @EventBusSubscriber(modid = "$modId", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
                public static final class ClientPreparation {
                    @SubscribeEvent
                    public static void onClientSetup(FMLClientSetupEvent event) {
                        if (!enabled()) return;
                        long started = System.nanoTime();
                        synchronized (LOCK) {
                            try {
                                RuntimeFingerprint fingerprint = fingerprintClasspath();
                                runtimeClasspathEntries = fingerprint.entries;
                                runtimeClasspathSha256 = fingerprint.sha256;
                                System.out.println(
                                    "[ModPorterStrictStation] Runtime fingerprint prepared before world startup; elapsedMs="
                                        + (System.nanoTime() - started) / 1_000_000L
                                        + " thread=" + Thread.currentThread().getName()
                                );
                            } catch (Throwable throwable) {
                                registrationFailure = "station fingerprint preparation failed: " + describe(throwable);
                            }
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
                        try {
                            driveClientLocked(Minecraft.getInstance());
                        } catch (Throwable throwable) {
                            failLocked(
                                "client state machine failed: " + describe(throwable)
                            );
                        }
                        checkTimeoutLocked();
                    }
                }

                @SubscribeEvent
                public static void onServerTick(ServerTickEvent.Post event) {
                    if (!enabled()) {
                        return;
                    }
                    synchronized (LOCK) {
                        serverTicks++;
                        if (!initialized) {
                            return;
                        }
                        try {
                            driveServerLocked(event.getServer());
                        } catch (Throwable throwable) {
                            failLocked(
                                "server state machine failed: " + describe(throwable)
                            );
                        }
                        checkTimeoutLocked();
                    }
                }

                private static void driveClientLocked(Minecraft minecraft)
                        throws Exception {
                    switch (stage) {
                        case WAIT_PREREQUISITES -> waitForPrerequisitesLocked(minecraft);
                        case CLIENT_RESERVE -> reserveClientStateLocked(minecraft);
                        case WAIT_CLIENT_HAND -> waitForClientHandLocked(minecraft);
                        case WAIT_CLIENT_DATA -> waitForClientDataLocked(minecraft);
                        case WAIT_PRODUCTION_RENDER -> {
                            if (renderObserved) {
                                setStageLocked(Stage.CAPTURE_SCREENSHOT);
                            }
                        }
                        case CAPTURE_SCREENSHOT -> captureScreenshotLocked(minecraft);
                        case WAIT_CLIENT_RESTORE ->
                            waitForClientRestoreLocked(minecraft);
                        case CLIENT_CLEANUP -> cleanupClientLocked(minecraft);
                        case CLEANUP_FENCE -> pollCleanupFenceLocked(minecraft);
                        case FAILURE_CLEANUP ->
                            cleanupFailureLocked(minecraft);
                        case SEAL -> sealLocked();
                        default -> {
                        }
                    }
                }

                private static void driveServerLocked(MinecraftServer server)
                        throws Exception {
                    switch (stage) {
                        case SERVER_PREPARE -> prepareServerDataLocked(server);
                        case SERVER_CODEC_SEND -> encodeAndSendLocked(server);
                        case SERVER_RESTORE -> restoreServerLocked(server);
                        case CLEANUP_FENCE -> pollChunkQuiescenceLocked(server);
                        default -> {
                        }
                    }
                }

                private static void waitForPrerequisitesLocked(Minecraft minecraft) {
                    if (minecraft.level == null
                        || minecraft.player == null
                        || minecraft.gameMode == null
                        || minecraft.getSingleplayerServer() == null) {
                        return;
                    }
                    if (fixtureReadyTick < 0L) {
                        fixtureReadyTick = currentTickLocked();
                        System.out.println(
                            "[ModPorterStrictStation] Fixture ready; waiting for ordered scenario ownership at tick="
                                + fixtureReadyTick
                        );
                    }
                    if (!Boolean.getBoolean(BROWSE_COMPLETED_PROPERTY)
                        || !Boolean.getBoolean(SPAWN_COMPLETED_PROPERTY)) {
                        return;
                    }
                    if (scenarioOwnedTick < 0L) {
                        scenarioOwnedTick = currentTickLocked();
                        scenarioStartedAtTick = clientTicks;
                        stageStartedTick = scenarioOwnedTick;
                        System.out.println(
                            "[ModPorterStrictStation] Ordered station scenario acquired at tick="
                                + scenarioOwnedTick
                        );
                    }
                    if (terminalFailure != null) {
                        setStageLocked(Stage.FAILURE_CLEANUP);
                        return;
                    }
                    if (registrationFailure != null) {
                        failLocked(registrationFailure);
                        return;
                    }
                    if (rendererRegistry == null
                        || productionRenderer == null
                        || recordingRenderer == null
                        || stationType == null
                        || rendererRegistry.get(stationType) != recordingRenderer) {
                        failLocked(
                            "production station renderer was not captured and wrapped"
                        );
                        return;
                    }
                    setStageLocked(Stage.CLIENT_RESERVE);
                }

                private static void reserveClientStateLocked(Minecraft minecraft)
                        throws Exception {
                    clientScreenSnapshot = minecraft.screen;
                    cameraSnapshot = minecraft.options.getCameraType();
                    hideGuiSnapshot = minecraft.options.hideGui;
                    clientMainSnapshot = minecraft.player.getMainHandItem().copy();
                    clientOffSnapshot = minecraft.player.getOffhandItem().copy();
                    playerUuid = minecraft.player.getUUID();
                    clientStateReserved = true;

                    int candidate;
                    if (ownedMapId == null) {
                        candidate = -1 - Math.floorMod(
                            validationSessionId.hashCode(),
                            1_000_000
                        );
                        mapIdReservationAttempts = 0;
                    } else {
                        candidate = ownedMapIdValue;
                    }
                    Map<MapId, MapItemSavedData> clientMaps =
                        clientMapDataLocked(minecraft.level);
                    Map<Integer, Object> rendererMaps =
                        mapRendererMapsLocked(minecraft.gameRenderer.getMapRenderer());
                    while (clientMaps.containsKey(new MapId(candidate))
                        || rendererMaps.containsKey(candidate)) {
                        mapIdReservationAttempts++;
                        if (mapIdReservationAttempts
                            > MAX_MAP_ID_RESERVATION_ATTEMPTS) {
                            throw new IllegalStateException(
                                "could not reserve a collision-free negative "
                                    + "map id across client state"
                            );
                        }
                        candidate--;
                    }
                    ownedMapIdValue = candidate;
                    heldMapIdValue = Integer.MAX_VALUE;
                    ownedMapId = new MapId(candidate);
                    setStageLocked(Stage.SERVER_PREPARE);
                }

                private static void prepareServerDataLocked(MinecraftServer server)
                        throws Exception {
                    ServerPlayer player = requireServerPlayerLocked(server);
                    if (player.serverLevel().getMapData(ownedMapId) != null) {
                        mapIdReservationAttempts++;
                        if (mapIdReservationAttempts
                            > MAX_MAP_ID_RESERVATION_ATTEMPTS) {
                            throw new IllegalStateException(
                                "could not reserve a collision-free negative "
                                    + "map id across server state"
                            );
                        }
                        ownedMapIdValue--;
                        ownedMapId = new MapId(ownedMapIdValue);
                        setStageLocked(Stage.CLIENT_RESERVE);
                        return;
                    }
                    serverMapAbsent = true;
                    serverMainSnapshot = player.getMainHandItem().copy();
                    serverOffSnapshot = player.getOffhandItem().copy();

                    serverData = MapItemSavedData.createFresh(
                        player.getX(),
                        player.getZ(),
                        (byte) 0,
                        false,
                        false,
                        player.serverLevel().dimension()
                    );
                    String stationName =
                        "modporter-" + validationSessionId.substring(0, 8);
                    BlockPos target = BlockPos.containing(
                        serverData.centerX + 8.0D,
                        player.getY(),
                        serverData.centerZ + 10.0D
                    );
                    StationMarker marker = new StationMarker(
                        target,
                        target,
                        Component.literal(stationName)
                    );
                    ((StationMapData) serverData).addStationMarker(marker);
                    expectedDecoration = exactStationDecorationLocked(
                        serverData,
                        stationName
                    );

                    providerClass = server.registryAccess().getClass().getName();
                    CompoundTag saved = serverData.save(
                        new CompoundTag(),
                        server.registryAccess()
                    );
                    int stationListSize = saved
                        .getList("create:stations", Tag.TAG_COMPOUND)
                        .size();
                    if (stationListSize != 1) {
                        throw new IllegalStateException(
                            "saved create:stations entry count was "
                                + stationListSize
                        );
                    }
                    byte[] savedBytes = nativeNbtBytes(saved);
                    savedTagByteCount = savedBytes.length;
                    savedTagSha256 = sha256(savedBytes);
                    reloadedData = MapItemSavedData.load(
                        saved,
                        server.registryAccess()
                    );
                    if (!(reloadedData instanceof StationMapData)) {
                        throw new IllegalStateException(
                            "reloaded map does not implement StationMapData"
                        );
                    }
                    reloadedDecoration =
                        exactStationDecorationLocked(reloadedData, stationName);
            """.trimIndent(),
            """
                    requireDecorationEqual(
                        expectedDecoration,
                        reloadedDecoration,
                        "provider save/load"
                    );

                    ItemStack filledMap = new ItemStack(Items.FILLED_MAP);
                    filledMap.set(DataComponents.MAP_ID, ownedMapId);
                    player.setItemSlot(EquipmentSlot.MAINHAND, filledMap);
                    player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                    player.containerMenu.broadcastChanges();
                    setStageLocked(Stage.WAIT_CLIENT_HAND);
                }

                private static void waitForClientHandLocked(Minecraft minecraft) {
                    MapId playerMap = minecraft.player
                        .getMainHandItem()
                        .get(DataComponents.MAP_ID);
                    MapId cachedMap = minecraft.gameRenderer
                        .itemInHandRenderer
                        .mainHandItem
                        .get(DataComponents.MAP_ID);
                    if (!ownedMapId.equals(playerMap)
                        || !ownedMapId.equals(cachedMap)
                        || !minecraft.player.getOffhandItem().isEmpty()) {
                        return;
                    }
                    heldMapIdValue = playerMap.id();
                    setStageLocked(Stage.SERVER_CODEC_SEND);
                }

                private static void encodeAndSendLocked(MinecraftServer server)
                        throws Exception {
                    ServerPlayer player = requireServerPlayerLocked(server);
                    ItemStack held = player.getMainHandItem();
                    if (!ownedMapId.equals(held.get(DataComponents.MAP_ID))) {
                        throw new IllegalStateException(
                            "server selected hand lost the owned map"
                        );
                    }
                    reloadedData.tickCarriedBy(player, held);
                    Packet<?> rawPacket =
                        reloadedData.getUpdatePacket(ownedMapId, player);
                    if (!(rawPacket instanceof ClientboundMapItemDataPacket packet)) {
                        throw new IllegalStateException(
                            "native map update packet was unavailable: " + rawPacket
                        );
                    }
                    List<MapDecoration> packetDecorations =
                        packet.decorations().orElse(List.of());
                    decodedDecoration = exactStationDecorationLocked(
                        packetDecorations,
                        expectedDecoration.get("name").getAsString()
                    );
                    requireDecorationEqual(
                        expectedDecoration,
                        decodedDecoration,
                        "original native packet"
                    );

                    ByteBuf ownedBuffer = Unpooled.buffer();
                    ClientboundMapItemDataPacket decoded;
                    byte[] encoded;
                    try {
                        RegistryFriendlyByteBuf buffer =
                            new RegistryFriendlyByteBuf(
                                ownedBuffer,
                                server.registryAccess(),
                                player.connection.getConnectionType()
                            );
                        connectionType =
                            player.connection.getConnectionType().toString();
                        ClientboundMapItemDataPacket.STREAM_CODEC.encode(
                            buffer,
                            packet
                        );
                        encoded = new byte[buffer.readableBytes()];
                        buffer.getBytes(buffer.readerIndex(), encoded);
                        if (encoded.length == 0) {
                            throw new IllegalStateException(
                                "native map packet encoded to zero bytes"
                            );
                        }
                        buffer.readerIndex(0);
                        decoded =
                            ClientboundMapItemDataPacket.STREAM_CODEC.decode(buffer);
                        packetFullyConsumed = buffer.readableBytes() == 0;
                        if (!packetFullyConsumed) {
                            throw new IllegalStateException(
                                "native map packet decoder left "
                                    + buffer.readableBytes()
                                    + " unread byte(s)"
                            );
                        }
                    } finally {
                        if (ownedBuffer.refCnt() > 0) {
                            ownedBuffer.release(ownedBuffer.refCnt());
                        }
                    }
                    encodedByteCount = encoded.length;
                    encodedSha256 = sha256(encoded);
                    comparePacketsLocked(packet, decoded);
                    decodedDecoration = exactStationDecorationLocked(
                        decoded.decorations().orElse(List.of()),
                        expectedDecoration.get("name").getAsString()
                    );
                    requireDecorationEqual(
                        expectedDecoration,
                        decodedDecoration,
                        "decoded native packet"
                    );
                    player.connection.send(decoded);
                    sentThroughServerConnection = true;
                    setStageLocked(Stage.WAIT_CLIENT_DATA);
                }

                private static void waitForClientDataLocked(Minecraft minecraft) {
                    MapItemSavedData data = minecraft.level.getMapData(ownedMapId);
                    if (data == null) {
                        return;
                    }
                    observedClientData = data;
                    clientObjectDistinctFromServer =
                        data != serverData && data != reloadedData;
                    if (!clientObjectDistinctFromServer) {
                        failLocked(
                            "client map object was aliased to a server map object"
                        );
                        return;
                    }
                    clientDecoration = exactStationDecorationLocked(
                        data,
                        expectedDecoration.get("name").getAsString()
                    );
                    requireDecorationEqual(
                        expectedDecoration,
                        clientDecoration,
                        "client packet handling"
                    );
                    minecraft.setScreen(null);
                    minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                    minecraft.options.hideGui = false;
                    setStageLocked(Stage.WAIT_PRODUCTION_RENDER);
                }

                private static void captureScreenshotLocked(Minecraft minecraft)
                        throws Exception {
                    if (!renderObserved) {
                        throw new IllegalStateException(
                            "screenshot requested before production renderer observation"
                        );
                    }
                    if (Files.exists(screenshotPath, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalStateException(
                            "station screenshot already exists: " + screenshotPath
                        );
                    }
                    NativeImage image = Screenshot.takeScreenshot(
                        minecraft.getMainRenderTarget()
                    );
                    try {
                        screenshotWidth = image.getWidth();
                        screenshotHeight = image.getHeight();
                        if (screenshotWidth <= 0 || screenshotHeight <= 0) {
                            throw new IllegalStateException(
                                "captured screenshot has invalid dimensions"
                            );
                        }
                        long screenshotPixels =
                            (long) screenshotWidth * screenshotHeight;
                        if (screenshotWidth > 4096
                            || screenshotHeight > 4096
                            || screenshotPixels > 16_777_216L) {
                            throw new IllegalStateException(
                                "captured screenshot exceeds validator bounds"
                            );
                        }
                        Set<Integer> colors = new HashSet<>();
                        for (int x = 0; x < screenshotWidth; x++) {
                            for (int y = 0; y < screenshotHeight; y++) {
                                colors.add(image.getPixelRGBA(x, y));
                            }
                        }
                        sampledDistinctColors = colors.size();
                        if (sampledDistinctColors < 2) {
                            throw new IllegalStateException(
                                "captured screenshot has no sampled pixel diversity"
                            );
                        }
                        image.writeToFile(screenshotPath);
                    } finally {
                        image.close();
                    }
                    screenshotSha256 = sha256(screenshotPath);
                    setStageLocked(Stage.SERVER_RESTORE);
                }

                private static void restoreServerLocked(MinecraftServer server) {
                    ServerPlayer player = requireServerPlayerLocked(server);
                    if (serverMainSnapshot != null) {
                        player.setItemSlot(
                            EquipmentSlot.MAINHAND,
                            serverMainSnapshot.copy()
                        );
                    }
                    if (serverOffSnapshot != null) {
                        player.setItemSlot(
                            EquipmentSlot.OFFHAND,
                            serverOffSnapshot.copy()
                        );
                    }
                    player.containerMenu.broadcastChanges();
                    serverRestoreIssued = true;
                    serverHandsRestored =
                        serverMainSnapshot != null
                            && serverOffSnapshot != null
                            && ItemStack.matches(
                                player.getMainHandItem(),
                                serverMainSnapshot
                            )
                            && ItemStack.matches(
                                player.getOffhandItem(),
                                serverOffSnapshot
                            );
                    serverMapAbsent =
                        player.serverLevel().getMapData(ownedMapId) == null;
                    setStageLocked(
                        terminalFailure == null
                            ? Stage.WAIT_CLIENT_RESTORE
                            : Stage.FAILURE_CLEANUP
                    );
                }

                private static void waitForClientRestoreLocked(
                        Minecraft minecraft) {
                    clientHandsRestored =
                        clientMainSnapshot != null
                            && clientOffSnapshot != null
                            && ItemStack.matches(
                                minecraft.player.getMainHandItem(),
                                clientMainSnapshot
                            )
                            && ItemStack.matches(
                                minecraft.player.getOffhandItem(),
                                clientOffSnapshot
                            );
                    itemInHandCacheRestored =
                        clientMainSnapshot != null
                            && ItemStack.matches(
                                minecraft.gameRenderer
                                    .itemInHandRenderer
                                    .mainHandItem,
                                clientMainSnapshot
                            );
                    if (!clientHandsRestored || !itemInHandCacheRestored) {
                        return;
                    }
                    setStageLocked(
                        terminalFailure == null
                            ? Stage.CLIENT_CLEANUP
                            : Stage.FAILURE_CLEANUP
                    );
                }

                private static void cleanupClientLocked(Minecraft minecraft)
                        throws Exception {
                    minecraft.setScreen(clientScreenSnapshot);
                    minecraft.options.setCameraType(cameraSnapshot);
                    minecraft.options.hideGui = hideGuiSnapshot;
                    uiRestored =
                        minecraft.screen == clientScreenSnapshot
                            && minecraft.options.getCameraType() == cameraSnapshot
                            && minecraft.options.hideGui == hideGuiSnapshot;

                    Map<MapId, MapItemSavedData> clientMaps =
                        clientMapDataLocked(minecraft.level);
                    MapItemSavedData removedData = clientMaps.remove(ownedMapId);
                    clientMapRemoved =
                        removedData == observedClientData
                            && !clientMaps.containsKey(ownedMapId);

                    MapRenderer mapRenderer =
                        minecraft.gameRenderer.getMapRenderer();
                    Map<Integer, Object> rendererMaps =
                        mapRendererMapsLocked(mapRenderer);
                    Object mapInstance = rendererMaps.remove(ownedMapIdValue);
                    mapRendererEntryRemoved =
                        mapInstance != null
                            && !rendererMaps.containsKey(ownedMapIdValue);
                    if (mapInstance == null) {
                        throw new IllegalStateException(
                            "owned map renderer instance was not present"
                        );
                    }
                    DynamicTexture texture =
                        (DynamicTexture) mapInstanceTextureField.get(mapInstance);
                    TextureManager textureManager = minecraft.getTextureManager();
                    @SuppressWarnings("unchecked")
                    Map<ResourceLocation, AbstractTexture> textures =
                        (Map<ResourceLocation, AbstractTexture>)
                            textureManagerByPathField.get(textureManager);
                    List<ResourceLocation> ownedTextureKeys = textures
                        .entrySet()
                        .stream()
                        .filter(entry -> entry.getValue() == texture)
                        .map(Map.Entry::getKey)
                        .toList();
                    if (ownedTextureKeys.size() != 1) {
                        throw new IllegalStateException(
                            "owned map texture identity resolved to "
                                + ownedTextureKeys.size()
                                + " TextureManager entries"
                        );
                    }
                    ResourceLocation textureKey = ownedTextureKeys.get(0);
                    textureManager.release(textureKey);
                    dynamicTextureReleased =
                        textures.entrySet()
                            .stream()
                            .noneMatch(entry -> entry.getValue() == texture)
                            && !textures.containsKey(textureKey);

                    if (rendererRegistry == null
                        || stationType == null
                        || productionRenderer == null
                        || recordingRenderer == null) {
                        throw new IllegalStateException(
                            "renderer restoration state was unavailable"
                        );
                    }
                    IMapDecorationRenderer current =
                        rendererRegistry.get(stationType);
                    if (current != recordingRenderer) {
                        throw new IllegalStateException(
                            "recording renderer identity changed before cleanup"
                        );
                    }
                    IMapDecorationRenderer replaced =
                        rendererRegistry.put(stationType, productionRenderer);
                    rendererRestored =
                        replaced == recordingRenderer
                            && rendererRegistry.get(stationType)
                                == productionRenderer;
                    cleanupStableClientTick = clientTicks;
                    setStageLocked(Stage.CLEANUP_FENCE);
                }

                private static void cleanupFailureLocked(Minecraft minecraft) {
                    boolean cleanupOk = true;
                    if (clientStateReserved) {
                        try {
                            minecraft.setScreen(clientScreenSnapshot);
                            if (cameraSnapshot != null) {
                                minecraft.options.setCameraType(cameraSnapshot);
                            }
                            minecraft.options.hideGui = hideGuiSnapshot;
                            uiRestored =
                                minecraft.screen == clientScreenSnapshot
                                    && (cameraSnapshot == null
                                        || minecraft.options.getCameraType()
                                            == cameraSnapshot)
                                    && minecraft.options.hideGui
                                        == hideGuiSnapshot;
                        } catch (Throwable throwable) {
                            cleanupOk = false;
                            System.err.println(
                                "[ModPorterStrictStation] failure UI cleanup: "
                                    + describe(throwable)
                            );
                        }
                    } else {
                        uiRestored = true;
                        clientHandsRestored = true;
                        itemInHandCacheRestored = true;
                    }

                    if (minecraft.level != null
                        && ownedMapId != null
                        && clientMapDataField != null) {
                        try {
                            Map<MapId, MapItemSavedData> clientMaps =
                                clientMapDataLocked(minecraft.level);
                            clientMaps.remove(ownedMapId);
                            clientMapRemoved =
                                !clientMaps.containsKey(ownedMapId);
                        } catch (Throwable throwable) {
                            cleanupOk = false;
                            System.err.println(
                                "[ModPorterStrictStation] failure map cleanup: "
                                    + describe(throwable)
                            );
                        }
                    } else {
                        clientMapRemoved = true;
                    }

                    if (ownedMapId != null
                        && mapRendererMapsField != null
                        && minecraft.gameRenderer != null) {
                        try {
                            MapRenderer mapRenderer =
                                minecraft.gameRenderer.getMapRenderer();
                            Map<Integer, Object> rendererMaps =
                                mapRendererMapsLocked(mapRenderer);
                            Object mapInstance =
                                rendererMaps.remove(ownedMapIdValue);
                            mapRendererEntryRemoved =
                                !rendererMaps.containsKey(ownedMapIdValue);
                            if (mapInstance != null
                                && mapInstanceTextureField != null
                                && textureManagerByPathField != null) {
                                DynamicTexture texture = (DynamicTexture)
                                    mapInstanceTextureField.get(mapInstance);
                                TextureManager textureManager =
                                    minecraft.getTextureManager();
                                @SuppressWarnings("unchecked")
                                Map<ResourceLocation, AbstractTexture> textures =
                                    (Map<ResourceLocation, AbstractTexture>)
                                        textureManagerByPathField.get(
                                            textureManager
                                        );
                                List<ResourceLocation> keys = textures
                                    .entrySet()
                                    .stream()
                                    .filter(entry -> entry.getValue() == texture)
                                    .map(Map.Entry::getKey)
                                    .toList();
                                for (ResourceLocation key : keys) {
                                    textureManager.release(key);
                                }
                                dynamicTextureReleased = textures
                                    .entrySet()
                                    .stream()
                                    .noneMatch(
                                        entry -> entry.getValue() == texture
                                    );
                            } else {
                                dynamicTextureReleased = true;
                            }
                        } catch (Throwable throwable) {
                            cleanupOk = false;
                            System.err.println(
                                "[ModPorterStrictStation] failure renderer cleanup: "
                                    + describe(throwable)
                            );
                        }
                    } else {
                        mapRendererEntryRemoved = true;
                        dynamicTextureReleased = true;
                    }

                    try {
                        if (rendererRegistry == null
                            || stationType == null
                            || recordingRenderer == null
                            || productionRenderer == null) {
                            rendererRestored = true;
                        } else {
                            IMapDecorationRenderer current =
                                rendererRegistry.get(stationType);
                            if (current == recordingRenderer) {
                                rendererRegistry.put(
                                    stationType,
                                    productionRenderer
                                );
                            }
                            rendererRestored =
                                rendererRegistry.get(stationType)
                                    == productionRenderer;
                            if (!rendererRestored) {
                                cleanupOk = false;
                            }
                        }
                    } catch (Throwable throwable) {
                        cleanupOk = false;
                        System.err.println(
                            "[ModPorterStrictStation] failure delegate cleanup: "
                                + describe(throwable)
                        );
                    }

                    if (clientStateReserved
                        && minecraft.player != null
                        && clientMainSnapshot != null
                        && clientOffSnapshot != null) {
                        clientHandsRestored =
                            ItemStack.matches(
                                minecraft.player.getMainHandItem(),
                                clientMainSnapshot
                            )
                                && ItemStack.matches(
                                    minecraft.player.getOffhandItem(),
                                    clientOffSnapshot
                                );
                        itemInHandCacheRestored =
                            minecraft.gameRenderer != null
                                && ItemStack.matches(
                                    minecraft.gameRenderer
                                        .itemInHandRenderer
                                        .mainHandItem,
                                    clientMainSnapshot
                                );
                    }
                    if (serverMainSnapshot == null) {
                        serverHandsRestored = true;
                    }
                    if (ownedMapId == null) {
                        serverMapAbsent = true;
                    }
                    if (!cleanupOk) {
                        cleanupFailureCount++;
                    }
                    cleanupPassed = cleanupOk;
                    scenarioFinishedAtTick = clientTicks;
                    if (canSealFailureLocked()
                        && Files.isRegularFile(
                            spawnReceiptPath,
                            LinkOption.NOFOLLOW_LINKS)) {
                        setStageLocked(Stage.SEAL);
                    } else {
                        System.setProperty(COMPLETED_PROPERTY, "true");
                        System.setProperty(
                            COORDINATOR_COMPLETED_PROPERTY,
                            "true"
                        );
                        stage = Stage.COMPLETE;
                    }
                }

                private static void pollCleanupFenceLocked(Minecraft minecraft)
                        throws Exception {
                    Map<MapId, MapItemSavedData> clientMaps =
                        clientMapDataLocked(minecraft.level);
                    Map<Integer, Object> rendererMaps =
                        mapRendererMapsLocked(
                            minecraft.gameRenderer.getMapRenderer()
                        );
                    boolean stable =
                        serverRestoreIssued
                            && serverHandsRestored
                            && clientHandsRestored
                            && itemInHandCacheRestored
                            && serverMapAbsent
                            && clientMapRemoved
                            && mapRendererEntryRemoved
                            && dynamicTextureReleased
                            && rendererRestored
                            && uiRestored
                            && !clientMaps.containsKey(ownedMapId)
                            && !rendererMaps.containsKey(ownedMapIdValue)
                            && ItemStack.matches(
                                minecraft.player.getMainHandItem(),
                                clientMainSnapshot
                            )
                            && ItemStack.matches(
                                minecraft.player.getOffhandItem(),
                                clientOffSnapshot
                            )
                            && ItemStack.matches(
                                minecraft.gameRenderer
                                    .itemInHandRenderer
                                    .mainHandItem,
                                clientMainSnapshot
                            )
                            && rendererRegistry.get(stationType)
                                == productionRenderer;
                    if (!stable) {
                        cleanupFailureCount++;
                        failLocked(
                            "owned station-map state did not remain cleaned"
                        );
                        return;
                    }
                    if (clientTicks - cleanupStableClientTick
                        < CLEANUP_FENCE_TICKS) {
                        return;
                    }
                    if (!chunkExitHealthPassed) {
                        return;
                    }
                    cleanupPassed = true;
                    scenarioFinishedAtTick = clientTicks;
                    setStageLocked(Stage.SEAL);
                }

                private static void sealLocked() throws Exception {
                    boolean pass =
                        terminalFailure == null
                            && cleanupPassed
                            && renderObserved
                            && productionDelegateReturned
                            && renderThread
                            && itemInHandRenderMapFrame
                            && mapRendererRenderFrame
                            && mapInstanceDrawFrame
                            && rendererManagerRenderFrame
                            && packetFullyConsumed
                            && packetDeepFieldsEqual
                            && mapPatchBytesEqual
                            && sentThroughServerConnection
                            && clientObjectDistinctFromServer
                            && ownedMapIdValue < 0
                            && heldMapIdValue == ownedMapIdValue
                            && cleanupFailureCount == 0
                            && overlapFailureCount == 0;
                    JsonObject scenario = scenarioJsonLocked(pass);
                    writeTraceLocked(scenario);
                    scenario = scenarioJsonLocked(pass);
                    JsonObject station = stationReceiptJsonLocked(
                        pass,
                        scenario
                    );
                    atomicWriteJson(stationReceiptPath, station);
                    stationSealed = true;
                    System.out.println(
                        "[ModPorterStrictStation] Receipt sealed: "
                            + stationReceiptPath
                            + " sha256="
                            + sha256(stationReceiptPath)
                            + " status="
                            + (pass ? "PASS" : "FAIL")
                    );

                    JsonObject coordinator =
                        coordinatorReceiptJsonLocked(station, scenario, pass);
                    atomicWriteJson(coordinatorReceiptPath, coordinator);
                    coordinatorSealed = true;
                    System.out.println(
                        "[ModPorterClientWorld] Coordinator sealed: "
                            + coordinatorReceiptPath
                            + " sha256="
                            + sha256(coordinatorReceiptPath)
                            + " status="
                            + coordinator.get("status").getAsString()
                    );
                    System.setProperty(COMPLETED_PROPERTY, "true");
                    System.setProperty(COORDINATOR_COMPLETED_PROPERTY, "true");
                    stage = Stage.COMPLETE;
                }

                private static JsonObject stationReceiptJsonLocked(
                        boolean pass,
                        JsonObject scenario) {
                    JsonObject root = new JsonObject();
                    root.addProperty("schema", STATION_SCHEMA);
                    root.addProperty("profileId", PROFILE_ID);
                    root.addProperty("sessionId", wrapperSessionId);
                    root.addProperty(
                        "validationSessionId",
                        validationSessionId
                    );
                    root.addProperty("status", pass ? "PASS" : "FAIL");
                    root.addProperty("processStartCount", 1);
                    root.addProperty("worldLoadCount", 1);
                    root.addProperty("scenarioCount", 1);
                    root.addProperty("failedCount", pass ? 0 : 1);
                    root.addProperty(
                        "cleanupFailureCount",
                        cleanupFailureCount
                    );
                    root.addProperty(
                        "overlapFailureCount",
                        overlapFailureCount
                    );
                    root.addProperty("allScenariosPassed", pass);
                    if (terminalFailure != null) {
                        root.addProperty("failure", terminalFailure);
                    }
                    root.add(
                        "requestedScenarios",
                        stringArray(REQUESTED_SCENARIOS)
                    );
                    root.add(
                        "observedScenarios",
                        stringArray(List.of(SCENARIO_ID))
                    );
                    root.add("runtime", runtimeJsonLocked());
                    root.add(
                        "bindings",
                        bindings == null
                            ? new JsonObject()
                            : bindings.deepCopy()
                    );
                    JsonArray scenarios = new JsonArray();
                    scenarios.add(scenario);
                    root.add("scenarios", scenarios);
                    return root;
                }

                private static JsonObject coordinatorReceiptJsonLocked(
                        JsonObject station,
                        JsonObject stationScenario,
                        boolean stationPass)
                        throws Exception {
                    JsonObject spawn = readJsonObject(spawnReceiptPath);
                    requireChildIdentity(
                        spawn,
                        SPAWN_SCHEMA,
                        SPAWN_PROFILE_ID,
                        3,
                        "spawn"
                    );
                    requireChildIdentity(
                        station,
                        STATION_SCHEMA,
                        PROFILE_ID,
                        1,
                        "station"
                    );
                    boolean spawnPass =
                        "PASS".equals(string(spawn, "status"))
                            && booleanValue(spawn, "allScenariosPassed")
                            && intValue(spawn, "failedCount") == 0
                            && intValue(spawn, "cleanupFailureCount") == 0;
                    boolean coordinatorPass =
                        stationPass
                            && spawnPass
                            && Boolean.getBoolean(BROWSE_COMPLETED_PROPERTY);

                    JsonObject root = new JsonObject();
                    root.addProperty("schema", COORDINATOR_SCHEMA);
                    root.addProperty("profileId", SPAWN_PROFILE_ID);
                    root.addProperty("sessionId", wrapperSessionId);
                    root.addProperty(
                        "validationSessionId",
                        validationSessionId
                    );
                    root.addProperty(
                        "status",
                        coordinatorPass ? "PASS" : "FAIL"
                    );
                    root.addProperty("processStartCount", 1);
                    root.addProperty("worldLoadCount", 1);
                    root.addProperty("scenarioCount", 4);
                    root.addProperty(
                        "failedCount",
                        intValue(spawn, "failedCount")
                            + (stationPass ? 0 : 1)
                    );
                    root.addProperty(
                        "cleanupFailureCount",
                        intValue(spawn, "cleanupFailureCount")
                            + cleanupFailureCount
                    );
                    root.addProperty(
                        "overlapFailureCount",
                        intValue(spawn, "overlapFailureCount")
                            + overlapFailureCount
                    );
                    root.addProperty(
                        "allScenariosPassed",
                        coordinatorPass
                    );
                    root.addProperty("finalExitRequested", true);
                    root.addProperty(
                        "clientBrowseCompleted",
                        Boolean.getBoolean(BROWSE_COMPLETED_PROPERTY)
                    );
                    if (!coordinatorPass && terminalFailure != null) {
                        root.addProperty("failure", terminalFailure);
                    }
                    root.add(
                        "requestedScenarios",
                        stringArray(COORDINATOR_SCENARIOS)
                    );
                    root.add(
                        "observedScenarios",
                        stringArray(COORDINATOR_SCENARIOS)
                    );

                    JsonObject children = new JsonObject();
                    children.add(
                        "spawn",
                        childReference(
            """.trimIndent(),
            """
                            spawnReceiptPath,
                            spawn,
                            SPAWN_SCHEMA,
                            SPAWN_PROFILE_ID,
                            3
                        )
                    );
                    children.add(
                        "station",
                        childReference(
                            stationReceiptPath,
                            station,
                            STATION_SCHEMA,
                            PROFILE_ID,
                            1
                        )
                    );
                    root.add("children", children);

                    JsonArray projectedScenarios = new JsonArray();
                    JsonArray spawnScenarios =
                        spawn.getAsJsonArray("scenarios");
                    for (int index = 0; index < 3; index++) {
                        projectedScenarios.add(
                            projectScenario(
                                spawnScenarios.get(index).getAsJsonObject(),
                                "spawn",
                                index
                            )
                        );
                    }
                    projectedScenarios.add(
                        projectScenario(stationScenario, "station", 0)
                    );
                    root.add("scenarios", projectedScenarios);
                    return root;
                }

                private static JsonObject scenarioJsonLocked(boolean pass) {
                    JsonObject scenario = new JsonObject();
                    scenario.addProperty("id", SCENARIO_ID);
                    scenario.addProperty("passed", pass);
                    scenario.addProperty("cleanupPassed", cleanupPassed);
                    scenario.addProperty(
                        "startedAtTick",
                        scenarioStartedAtTick
                    );
                    scenario.addProperty(
                        "finishedAtTick",
                        scenarioFinishedAtTick
                    );
                    scenario.add(
                        "semanticChecks",
                        stringArray(SEMANTIC_CHECKS)
                    );
                    scenario.add("observations", observationsJsonLocked());
                    JsonArray artifacts = new JsonArray();
                    artifacts.add(
                        artifactJson("trace", tracePath, traceSha256)
                    );
                    artifacts.add(
                        artifactJson(
                            "first_person_png",
                            screenshotPath,
                            screenshotSha256
                        )
                    );
                    scenario.add("artifacts", artifacts);
                    if (terminalFailure != null) {
                        scenario.addProperty("failure", terminalFailure);
                    }
                    return scenario;
                }

                private static JsonObject observationsJsonLocked() {
                    JsonObject observations = new JsonObject();

                    JsonObject provider = new JsonObject();
                    provider.addProperty("providerClass", providerClass);
                    provider.addProperty(
                        "savedTagByteCount",
                        savedTagByteCount
                    );
                    provider.addProperty(
                        "savedTagSha256",
                        savedTagSha256
                    );
                    provider.addProperty("stationListSize", 1);
                    provider.addProperty(
                        "reloadedImplementsStationMapData",
                        reloadedData instanceof StationMapData
                    );
                    provider.add(
                        "serverDecoration",
                        expectedDecoration == null
                            ? new JsonObject()
                            : expectedDecoration.deepCopy()
                    );
                    provider.add(
                        "reloadedDecoration",
                        reloadedDecoration == null
                            ? new JsonObject()
                            : reloadedDecoration.deepCopy()
                    );
                    observations.add("providerSaveReload", provider);

                    JsonObject packet = new JsonObject();
                    packet.addProperty(
                        "packetClass",
                        ClientboundMapItemDataPacket.class.getName()
                    );
                    packet.addProperty("connectionType", connectionType);
                    packet.addProperty(
                        "encodedByteCount",
                        encodedByteCount
                    );
                    packet.addProperty("encodedSha256", encodedSha256);
                    packet.addProperty(
                        "fullyConsumed",
                        packetFullyConsumed
                    );
                    packet.addProperty(
                        "deepFieldsEqual",
                        packetDeepFieldsEqual
                    );
                    packet.addProperty(
                        "mapPatchBytesEqual",
                        mapPatchBytesEqual
                    );
                    packet.addProperty(
                        "sentThroughServerConnection",
                        sentThroughServerConnection
                    );
                    packet.addProperty("directApplyInvoked", false);
                    packet.addProperty(
                        "directClientHandlerInvoked",
                        false
                    );
                    packet.addProperty(
                        "clientObjectDistinctFromServer",
                        clientObjectDistinctFromServer
                    );
                    packet.add(
                        "decodedDecoration",
                        decodedDecoration == null
                            ? new JsonObject()
                            : decodedDecoration.deepCopy()
                    );
                    packet.add(
                        "clientDecoration",
                        clientDecoration == null
                            ? new JsonObject()
                            : clientDecoration.deepCopy()
                    );
                    observations.add("packetCodecClientUpdate", packet);

                    JsonObject renderer = new JsonObject();
                    renderer.addProperty(
                        "productionDelegateClass",
                        productionRenderer == null
                            ? ""
                            : productionRenderer.getClass().getName()
                    );
                    renderer.addProperty(
                        "productionDelegateReturned",
                        productionDelegateReturned
                    );
                    renderer.addProperty("renderThread", renderThread);
                    renderer.addProperty("ownedMapId", ownedMapIdValue);
                    renderer.addProperty("heldMapId", heldMapIdValue);
                    renderer.addProperty(
                        "itemInHandRenderMapFrame",
                        itemInHandRenderMapFrame
                    );
                    renderer.addProperty(
                        "mapRendererRenderFrame",
                        mapRendererRenderFrame
                    );
                    renderer.addProperty(
                        "mapInstanceDrawFrame",
                        mapInstanceDrawFrame
                    );
                    renderer.addProperty(
                        "rendererManagerRenderFrame",
                        rendererManagerRenderFrame
                    );
                    renderer.addProperty(
                        "screenshotWidth",
                        screenshotWidth
                    );
                    renderer.addProperty(
                        "screenshotHeight",
                        screenshotHeight
                    );
                    renderer.addProperty(
                        "sampledDistinctColors",
                        sampledDistinctColors
                    );
                    renderer.add(
                        "renderedDecoration",
                        renderedDecoration == null
                            ? new JsonObject()
                            : renderedDecoration.deepCopy()
                    );
                    observations.add(
                        "firstPersonNativeRenderer",
                        renderer
                    );

                    JsonObject cleanup = new JsonObject();
                    cleanup.addProperty(
                        "serverHandsRestored",
                        serverHandsRestored
                    );
                    cleanup.addProperty(
                        "clientHandsRestored",
                        clientHandsRestored
                    );
                    cleanup.addProperty(
                        "itemInHandCacheRestored",
                        itemInHandCacheRestored
                    );
                    cleanup.addProperty(
                        "screenRestored",
                        uiRestored
                    );
                    cleanup.addProperty(
                        "cameraRestored",
                        uiRestored
                    );
                    cleanup.addProperty(
                        "hideGuiRestored",
                        uiRestored
                    );
                    cleanup.addProperty(
                        "rendererDelegateRestoredByIdentity",
                        rendererRestored
                    );
                    cleanup.addProperty(
                        "serverMapAbsent",
                        serverMapAbsent
                    );
                    cleanup.addProperty(
                        "clientMapAbsent",
                        clientMapRemoved
                    );
                    cleanup.addProperty(
                        "mapRendererEntryAbsent",
                        mapRendererEntryRemoved
                    );
                    cleanup.addProperty(
                        "dynamicTextureEntryAbsent",
                        dynamicTextureReleased
                    );
                    cleanup.addProperty(
                        "cleanupFenceTicks",
                        cleanupPassed ? CLEANUP_FENCE_TICKS : 0
                    );
                    cleanup.addProperty("chunkQuiescencePassed", chunkExitHealthPassed);
                    JsonObject chunks = new JsonObject();
                    chunks.add("first", firstChunkQuiescence == null ? new JsonObject() : firstChunkQuiescence.deepCopy());
                    chunks.add("best", bestChunkQuiescence == null ? new JsonObject() : bestChunkQuiescence.deepCopy());
                    chunks.add("last", lastChunkQuiescence == null ? new JsonObject() : lastChunkQuiescence.deepCopy());
                    chunks.addProperty("stableServerTicks", chunkStableServerTicks);
                    chunks.addProperty("stableSinceServerTick", chunkStableSinceServerTick);
                    chunks.addProperty("lastSampleServerTick", lastChunkSampleServerTick);
                    chunks.addProperty("requiredFenceTicks", CLEANUP_FENCE_TICKS);
                    cleanup.add("chunkQuiescence", chunks);
                    observations.add("cleanup", cleanup);
                    return observations;
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
                        runtimeJavaExecutable
                    );
                    runtime.addProperty(
                        "javaExecutableSha256",
                        runtimeJavaExecutableSha256
                    );
                    runtime.addProperty(
                        "minecraftVersion",
                        SharedConstants.getCurrentVersion().getName()
                    );
                    runtime.addProperty(
                        "neoForgeVersion",
                        ModList.get()
                            .getModContainerById("neoforge")
                            .orElseThrow()
                            .getModInfo()
                            .getVersion()
                            .toString()
                    );
                    runtime.addProperty(
                        "classpathSha256",
                        runtimeClasspathSha256
                    );
                    runtime.add(
                        "classpathEntries",
                        runtimeClasspathEntries == null
                            ? new JsonArray()
                            : runtimeClasspathEntries.deepCopy()
                    );
                    runtime.add(
                        "classResources",
                        runtimeClassResources == null
                            ? new JsonObject()
                            : runtimeClassResources.deepCopy()
                    );
                    return runtime;
                }

                private static void writeTraceLocked(JsonObject scenario)
                        throws Exception {
                    JsonObject trace = scenario.deepCopy();
                    trace.remove("artifacts");
                    trace.addProperty(
                        "artifactSchema",
                        STATION_SCHEMA + ".trace"
                    );
                    trace.addProperty("profileId", PROFILE_ID);
                    trace.addProperty("sessionId", wrapperSessionId);
                    trace.addProperty(
                        "validationSessionId",
                        validationSessionId
                    );
                    atomicWriteJson(tracePath, trace);
                    traceSha256 = sha256(tracePath);
                }

                private static JsonObject artifactJson(
                        String kind,
                        Path path,
                        String hash) {
                    JsonObject artifact = new JsonObject();
                    artifact.addProperty("kind", kind);
                    artifact.addProperty(
                        "path",
                        path == null ? "" : path.toString()
                    );
                    artifact.addProperty(
                        "sha256",
                        hash == null ? "" : hash
                    );
                    return artifact;
                }

                private static JsonObject childReference(
                        Path path,
                        JsonObject child,
                        String schema,
                        String profile,
                        int scenarioCount)
                        throws Exception {
                    JsonObject reference = new JsonObject();
                    reference.addProperty("path", path.toString());
                    reference.addProperty("sha256", sha256(path));
                    reference.addProperty("schema", schema);
                    reference.addProperty("profileId", profile);
                    reference.addProperty(
                        "sessionId",
                        string(child, "sessionId")
                    );
                    reference.addProperty(
                        "validationSessionId",
                        string(child, "validationSessionId")
                    );
                    reference.addProperty(
                        "scenarioCount",
                        scenarioCount
                    );
                    return reference;
                }

                private static JsonObject projectScenario(
                        JsonObject source,
                        String child,
                        int childIndex) {
                    JsonObject projection = new JsonObject();
                    projection.addProperty("id", string(source, "id"));
                    projection.addProperty("child", child);
                    projection.addProperty("childIndex", childIndex);
                    projection.addProperty(
                        "passed",
                        booleanValue(source, "passed")
                    );
                    projection.addProperty(
                        "cleanupPassed",
                        booleanValue(source, "cleanupPassed")
                    );
                    return projection;
                }

                private static void requireChildIdentity(
                        JsonObject child,
                        String schema,
                        String profile,
                        int scenarioCount,
                        String label) {
                    if (!schema.equals(string(child, "schema"))
                        || !profile.equals(string(child, "profileId"))
                        || !wrapperSessionId.equals(string(child, "sessionId"))
                        || !validationSessionId.equals(
                            string(child, "validationSessionId")
                        )
                        || intValue(child, "scenarioCount") != scenarioCount) {
                        throw new IllegalStateException(
                            label + " child identity mismatch"
                        );
                    }
                }

                private static JsonObject exactStationDecorationLocked(
                        MapItemSavedData data,
                        String expectedName) {
                    List<MapDecoration> values = new ArrayList<>();
                    data.getDecorations().forEach(values::add);
                    return exactStationDecorationLocked(values, expectedName);
                }

                private static JsonObject exactStationDecorationLocked(
                        Iterable<MapDecoration> values,
                        String expectedName) {
                    List<MapDecoration> stations = new ArrayList<>();
                    for (MapDecoration decoration : values) {
                        if (decoration.type().value() == stationType) {
                            stations.add(decoration);
                        }
                    }
                    if (stations.size() != 1) {
                        throw new IllegalStateException(
                            "expected one station decoration, found "
                                + stations.size()
                        );
                    }
                    JsonObject result = decorationJson(stations.get(0));
                    if (!STATION_TYPE_KEY.equals(
                            result.get("typeKey").getAsString())
                        || result.get("rotation").getAsInt() != 0
                        || !expectedName.equals(
                            result.get("name").getAsString())) {
                        throw new IllegalStateException(
                            "station decoration fields were not exact: " + result
                        );
                    }
                    return result;
                }

                private static JsonObject decorationJson(
                        MapDecoration decoration) {
                    ResourceLocation typeKey =
                        BuiltInRegistries.MAP_DECORATION_TYPE.getKey(
                            decoration.type().value()
                        );
                    JsonObject result = new JsonObject();
                    result.addProperty(
                        "typeKey",
                        typeKey == null ? "" : typeKey.toString()
                    );
                    result.addProperty("x", (int) decoration.x());
                    result.addProperty("y", (int) decoration.y());
                    result.addProperty(
                        "rotation",
                        (int) decoration.rot()
                    );
                    result.addProperty(
                        "name",
                        decoration.name()
                            .map(Component::getString)
                            .orElse("")
                    );
                    return result;
                }

                private static void requireDecorationEqual(
                        JsonObject expected,
                        JsonObject actual,
                        String stageLabel) {
                    if (expected == null || !expected.equals(actual)) {
                        throw new IllegalStateException(
                            stageLabel
                                + " station decoration mismatch expected="
                                + expected
                                + " actual="
                                + actual
                        );
                    }
                }

                private static void comparePacketsLocked(
                        ClientboundMapItemDataPacket original,
                        ClientboundMapItemDataPacket decoded) {
                    boolean headerEqual =
                        original.mapId().equals(decoded.mapId())
                            && original.scale() == decoded.scale()
                            && original.locked() == decoded.locked();
                    boolean decorationsEqual =
                        decorationListsEqual(
                            original.decorations(),
                            decoded.decorations()
                        );
                    mapPatchBytesEqual =
                        mapPatchesEqual(
                            original.colorPatch(),
                            decoded.colorPatch()
                        );
                    packetDeepFieldsEqual =
                        headerEqual
                            && decorationsEqual
                            && mapPatchBytesEqual;
                    if (!packetDeepFieldsEqual) {
                        throw new IllegalStateException(
                            "native map packet codec roundtrip changed fields"
                        );
                    }
                }

                private static boolean decorationListsEqual(
                        Optional<List<MapDecoration>> left,
                        Optional<List<MapDecoration>> right) {
                    if (left.isPresent() != right.isPresent()) {
                        return false;
                    }
                    if (left.isEmpty()) {
                        return true;
                    }
                    List<MapDecoration> a = left.get();
                    List<MapDecoration> b = right.get();
                    if (a.size() != b.size()) {
                        return false;
                    }
                    for (int index = 0; index < a.size(); index++) {
                        if (!decorationJson(a.get(index))
                            .equals(decorationJson(b.get(index)))) {
                            return false;
                        }
                    }
                    return true;
                }

                private static boolean mapPatchesEqual(
                        Optional<MapItemSavedData.MapPatch> left,
                        Optional<MapItemSavedData.MapPatch> right) {
                    if (left.isPresent() != right.isPresent()) {
                        return false;
                    }
                    if (left.isEmpty()) {
                        return true;
                    }
                    MapItemSavedData.MapPatch a = left.get();
                    MapItemSavedData.MapPatch b = right.get();
                    return a.startX() == b.startX()
                        && a.startY() == b.startY()
                        && a.width() == b.width()
                        && a.height() == b.height()
                        && Arrays.equals(a.mapColors(), b.mapColors());
                }

                private static byte[] nativeNbtBytes(CompoundTag tag)
                        throws IOException {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    try (DataOutputStream output = new DataOutputStream(bytes)) {
                        NbtIo.write(tag, output);
                    }
                    return bytes.toByteArray();
                }

                private static ServerPlayer requireServerPlayerLocked(
                        MinecraftServer server) {
                    ServerPlayer player = playerUuid == null
                        ? null
                        : server.getPlayerList().getPlayer(playerUuid);
                    if (player == null) {
                        throw new IllegalStateException(
                            "integrated server player is unavailable"
                        );
                    }
                    return player;
                }

                @SuppressWarnings("unchecked")
                private static Map<MapId, MapItemSavedData>
                        clientMapDataLocked(ClientLevel level)
                        throws IllegalAccessException {
                    return (Map<MapId, MapItemSavedData>)
                        clientMapDataField.get(level);
                }

                @SuppressWarnings("unchecked")
                private static Map<Integer, Object> mapRendererMapsLocked(
                        MapRenderer renderer)
                        throws IllegalAccessException {
                    return (Map<Integer, Object>)
                        mapRendererMapsField.get(renderer);
                }

                ${ChunkQuiescenceRuntimeSource.source()}

                private static void pollChunkQuiescenceLocked(MinecraftServer server)
                        throws Exception {
                    JsonObject snapshot = sampleChunkQuiescenceLocked(server);
                    if (firstChunkQuiescence == null) firstChunkQuiescence = snapshot.deepCopy();
                    double score = 0;
                    for (String counter : new String[] {${ChunkQuiescenceRuntimeSource.zeroCounterNames.joinToString(", ") { "\"$it\"" }}}) {
                        score += snapshot.get(counter).getAsDouble();
                    }
                    if (bestChunkQuiescence == null || score < bestChunkQuiescenceScore) {
                        bestChunkQuiescence = snapshot.deepCopy();
                        bestChunkQuiescenceScore = score;
                    }
                    lastChunkQuiescence = snapshot;
                    if (chunkQuiescent(snapshot)) {
                        if (lastChunkSampleServerTick == serverTicks - 1 && chunkStableServerTicks > 0) {
                            chunkStableServerTicks++;
                        } else {
                            chunkStableServerTicks = 1;
                            chunkStableSinceServerTick = serverTicks;
                        }
                    } else {
                        chunkStableServerTicks = 0;
                        chunkStableSinceServerTick = -1L;
                    }
                    lastChunkSampleServerTick = serverTicks;
                    chunkExitHealthPassed = chunkStableServerTicks >= CLEANUP_FENCE_TICKS;
                }

                private static boolean initializeLocked() {
                    if (initialized) {
                        return true;
                    }
                    try {
                        String requestedProfile =
                            requiredProperty(PROFILE_PROPERTY);
                        if (!PROFILE_ID.equals(requestedProfile)) {
                            throw new IllegalStateException(
                                "unexpected station profile: "
                                    + requestedProfile
                            );
                        }
                        validationSessionId =
                            requiredProperty(VALIDATION_SESSION_PROPERTY);
                        UUID.fromString(validationSessionId);
                        wrapperSessionId =
                            requiredEnvironment(WRAPPER_SESSION_ENV);
                        UUID.fromString(wrapperSessionId);
                        bindingsPath = confinedAbsolutePath(
                            requiredProperty(BINDINGS_PROPERTY),
                            null
                        );
                        stationReceiptPath = confinedAbsolutePath(
                            requiredProperty(RECEIPT_PROPERTY),
                            null
                        );
                        spawnReceiptPath = confinedAbsolutePath(
                            requiredProperty(SPAWN_RECEIPT_PROPERTY),
                            null
                        );
                        coordinatorReceiptPath = confinedAbsolutePath(
                            requiredProperty(COORDINATOR_RECEIPT_PROPERTY),
                            null
                        );
                        evidenceDir = confinedAbsolutePath(
                            requiredProperty(EVIDENCE_DIR_PROPERTY),
                            null
                        );
                        if (!Files.isDirectory(
                                evidenceDir,
                                LinkOption.NOFOLLOW_LINKS)) {
                            throw new IllegalStateException(
                                "station evidence directory is unavailable: "
                                    + evidenceDir
                            );
                        }
            """.trimIndent(),
            """
                        requireNewOutput(stationReceiptPath);
                        requireNewOutput(coordinatorReceiptPath);
                        tracePath = evidenceDir.resolve(
                            "04-station-map-native-roundtrip.json"
                        ).toAbsolutePath().normalize();
                        screenshotPath = evidenceDir.resolve(
                            "04-station-map-first-person.png"
                        ).toAbsolutePath().normalize();
                        if (!tracePath.startsWith(evidenceDir)
                            || !screenshotPath.startsWith(evidenceDir)) {
                            throw new IllegalStateException(
                                "station evidence path escaped evidenceDir"
                            );
                        }
                        requireNewOutput(tracePath);
                        requireNewOutput(screenshotPath);
                        bindings = readBindings(bindingsPath);
                        initializeReflectionLocked();
                        validateRuntimeLocked();
                        if (runtimeClasspathEntries == null || runtimeClasspathSha256 == null) {
                            throw new IllegalStateException(
                                "station runtime fingerprint was not prepared during client setup"
                            );
                        }
                        Path javaExecutable = resolveJavaExecutable();
                        runtimeJavaExecutable = javaExecutable.toString();
                        runtimeJavaExecutableSha256 = sha256(javaExecutable);
                        runtimeClassResources = classResources();
                        System.setProperty(COMPLETED_PROPERTY, "false");
                        System.setProperty(
                            COORDINATOR_COMPLETED_PROPERTY,
                            "false"
                        );
                        initialized = true;
                        stageStartedTick = currentTickLocked();
                        return true;
                    } catch (Throwable throwable) {
                        terminalFailure =
                            "station harness initialization failed: "
                                + describe(throwable);
                        System.err.println(
                            "[ModPorterStrictStation] " + terminalFailure
                        );
                        if (canSealFailureLocked()) {
                            System.setProperty(COMPLETED_PROPERTY, "false");
                            System.setProperty(
                                COORDINATOR_COMPLETED_PROPERTY,
                                "false"
                            );
                            initialized = true;
                            stage = Stage.WAIT_PREREQUISITES;
                            stageStartedTick = currentTickLocked();
                            return true;
                        }
                        System.setProperty(COMPLETED_PROPERTY, "true");
                        System.setProperty(
                            COORDINATOR_COMPLETED_PROPERTY,
                            "true"
                        );
                        stage = Stage.COMPLETE;
                        return false;
                    }
                }

                private static boolean canSealFailureLocked() {
                    return stationReceiptPath != null
                        && coordinatorReceiptPath != null
                        && spawnReceiptPath != null
                        && evidenceDir != null
                        && tracePath != null
                        && screenshotPath != null
                        && !Files.exists(
                            stationReceiptPath,
                            LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(
                            coordinatorReceiptPath,
                            LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(
                            tracePath,
                            LinkOption.NOFOLLOW_LINKS);
                }

                private static void initializeReflectionLocked()
                        throws Exception {
                    clientMapDataField =
                        ClientLevel.class.getDeclaredField("mapData");
                    clientMapDataField.setAccessible(true);
                    mapRendererMapsField =
                        MapRenderer.class.getDeclaredField("maps");
                    mapRendererMapsField.setAccessible(true);
                    Class<?> mapInstanceClass = Class.forName(
                        "net.minecraft.client.gui.MapRenderer"
                            + "${'$'}"
                            + "MapInstance"
                    );
                    mapInstanceTextureField =
                        mapInstanceClass.getDeclaredField("texture");
                    mapInstanceTextureField.setAccessible(true);
                    textureManagerByPathField =
                        TextureManager.class.getDeclaredField("byPath");
                    textureManagerByPathField.setAccessible(true);
                }

                private static void validateRuntimeLocked() {
                    String minecraftVersion =
                        SharedConstants.getCurrentVersion().getName();
                    String neoForgeVersion = ModList.get()
                        .getModContainerById("neoforge")
                        .orElseThrow()
                        .getModInfo()
                        .getVersion()
                        .toString();
                    if (!EXPECTED_MINECRAFT_VERSION.equals(minecraftVersion)) {
                        throw new IllegalStateException(
                            "unexpected Minecraft version: "
                                + minecraftVersion
                        );
                    }
                    if (!EXPECTED_NEOFORGE_VERSION.equals(neoForgeVersion)) {
                        throw new IllegalStateException(
                            "unexpected NeoForge version: "
                                + neoForgeVersion
                        );
                    }
                    String javaVersion =
                        System.getProperty("java.version", "");
                    int dot = javaVersion.indexOf('.');
                    String major = dot < 0
                        ? javaVersion
                        : javaVersion.substring(0, dot);
                    if (!"21".equals(major)) {
                        throw new IllegalStateException(
                            "station harness requires Java 21: "
                                + javaVersion
                        );
                    }
                }

                private static JsonObject readBindings(Path path)
                        throws IOException {
                    JsonObject root = readJsonObject(path);
                    if (root.has("profileId")
                        && !SPAWN_PROFILE_ID.equals(
                            string(root, "profileId"))) {
                        throw new IllegalStateException(
                            "bindings manifest profile mismatch"
                        );
                    }
                    if (root.has("validationSessionId")
                        && !validationSessionId.equals(
                            string(root, "validationSessionId"))) {
                        throw new IllegalStateException(
                            "bindings manifest validation session mismatch"
                        );
                    }
                    JsonElement element = root.has("bindings")
                        ? root.get("bindings")
                        : root;
                    if (!element.isJsonObject()) {
                        throw new IllegalStateException(
                            "bindings manifest has no bindings object"
                        );
                    }
                    return element.getAsJsonObject().deepCopy();
                }

                private static JsonObject classResources()
                        throws Exception {
                    if (runtimeClasspathEntries == null) {
                        throw new IllegalStateException(
                            "classpath fingerprint is unavailable"
                        );
                    }
                    LinkedHashMap<String, Class<?>> classes =
                        new LinkedHashMap<>();
                    classes.put(
                        ClientboundMapItemDataPacket.class.getName(),
                        ClientboundMapItemDataPacket.class
                    );
                    classes.put(
                        MapDecoration.class.getName(),
                        MapDecoration.class
                    );
                    classes.put(
                        MapItemSavedData.class.getName(),
                        MapItemSavedData.class
                    );
                    classes.put(
                        "net.minecraft.client.multiplayer.ClientPacketListener",
                        Class.forName(
                            "net.minecraft.client.multiplayer.ClientPacketListener"
                        )
                    );
                    classes.put(
                        "net.minecraft.client.renderer.ItemInHandRenderer",
                        Class.forName(
                            "net.minecraft.client.renderer.ItemInHandRenderer"
                        )
                    );
                    classes.put(MapRenderer.class.getName(), MapRenderer.class);
                    classes.put(
                        MapDecorationRendererManager.class.getName(),
                        MapDecorationRendererManager.class
                    );
                    classes.put(
                        AllMapDecorationTypes.class.getName(),
                        AllMapDecorationTypes.class
                    );
                    classes.put(
                        StationMarker.class.getName(),
                        StationMarker.class
                    );
                    classes.put(
                        StationMapData.class.getName(),
                        StationMapData.class
                    );
                    classes.put(
                        EXPECTED_RENDERER,
                        Class.forName(EXPECTED_RENDERER)
                    );
                    classes.put(
                        ModPorterStrictStationMapHarness.class.getName(),
                        ModPorterStrictStationMapHarness.class
                    );
                    JsonObject result = new JsonObject();
                    for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                        Class<?> type = entry.getValue();
                        String resource =
                            type.getName().replace('.', '/') + ".class";
                        List<Path> origins = new ArrayList<>();
                        byte[] resourceBytes = null;
                        for (JsonElement classpathElement
                                : runtimeClasspathEntries) {
                            JsonObject classpathEntry =
                                classpathElement.getAsJsonObject();
                            Path origin = Path.of(
                                classpathEntry.get("path").getAsString()
                            ).toAbsolutePath().normalize();
                            byte[] candidate = readClasspathResource(
                                origin,
                                classpathEntry.get("kind").getAsString(),
                                resource
                            );
                            if (candidate != null) {
                                origins.add(origin);
                                resourceBytes = candidate;
                            }
                        }
                        if (origins.size() != 1 || resourceBytes == null) {
                            throw new IllegalStateException(
                                "class resource must resolve from exactly one "
                                    + "bound classpath entry: "
                                    + entry.getKey()
                                    + " resource="
                                    + resource
                                    + " matches="
                                    + origins
                            );
                        }
                        JsonObject value = new JsonObject();
                        value.addProperty("resource", resource);
                        value.addProperty(
                            "origin",
                            origins.get(0).toString()
                        );
                        value.addProperty(
                            "sha256",
                            sha256(resourceBytes)
                        );
                        result.add(entry.getKey(), value);
                    }
                    return result;
                }

                private static byte[] readClasspathResource(
                        Path origin,
                        String kind,
                        String resource)
                        throws Exception {
                    if ("directory".equals(kind)) {
                        Path file = origin.resolve(resource).normalize();
                        if (!file.startsWith(origin)
                            || !Files.isRegularFile(
                                file,
                                LinkOption.NOFOLLOW_LINKS)) {
                            return null;
                        }
                        return Files.readAllBytes(file);
                    }
                    if (!"file".equals(kind)
                        || !Files.isRegularFile(
                            origin,
                            LinkOption.NOFOLLOW_LINKS)) {
                        return null;
                    }
                    try (ZipFile zip = new ZipFile(origin.toFile())) {
                        var zipEntry = zip.getEntry(resource);
                        if (zipEntry == null || zipEntry.isDirectory()) {
                            return null;
                        }
                        try (var input = zip.getInputStream(zipEntry)) {
                            return input.readAllBytes();
                        }
                    } catch (IOException ignored) {
                        return null;
                    }
                }

                private static Path resolveJavaExecutable() {
                    Path javaHome = Path.of(
                        System.getProperty("java.home", "")
                    ).toAbsolutePath().normalize();
                    List<Path> candidates = List.of(
                        javaHome.resolve("bin").resolve("java.exe"),
                        javaHome.resolve("bin").resolve("java")
                    );
                    List<Path> existing = candidates.stream()
                        .filter(path -> Files.isRegularFile(
                            path,
                            LinkOption.NOFOLLOW_LINKS))
                        .toList();
                    if (existing.size() != 1) {
                        throw new IllegalStateException(
                            "expected one Java executable under java.home, found "
                                + existing
                        );
                    }
                    Path executable = existing.get(0)
                        .toAbsolutePath()
                        .normalize();
                    if (Files.isSymbolicLink(executable)) {
                        throw new IllegalStateException(
                            "Java executable is a symbolic link: " + executable
                        );
                    }
                    try {
                        Path real = executable.toRealPath(
                            LinkOption.NOFOLLOW_LINKS
                        );
                        if (!real.equals(executable)) {
                            throw new IllegalStateException(
                                "Java executable is not its real path: "
                                    + executable
                            );
                        }
                        return real;
                    } catch (IOException exception) {
                        throw new IllegalStateException(
                            "Java executable real path is unavailable: "
                                + executable,
                            exception
                        );
                    }
                }

                private static RuntimeFingerprint fingerprintClasspath()
                        throws Exception {
                    MessageDigest combined = MessageDigest.getInstance("SHA-256");
                    JsonArray entries = new JsonArray();
                    Set<Path> seen = new java.util.LinkedHashSet<>();

                    addPathList(
                        entries,
                        combined,
                        seen,
                        "java.class.path",
                        requiredSystemProperty("java.class.path")
                    );
                    addPathList(
                        entries,
                        combined,
                        seen,
                        "jdk.module.path",
                        requiredSystemProperty("jdk.module.path")
                    );

                    Path legacyClassPathFile = Path.of(
                        requiredSystemProperty("legacyClassPath.file")
                    ).toRealPath(LinkOption.NOFOLLOW_LINKS);
                    addRuntimePath(
                        entries,
                        combined,
                        seen,
                        legacyClassPathFile.toString()
                    );
                    int legacyEntryCount = 0;
                    for (String line : Files.readString(
                            legacyClassPathFile,
                            StandardCharsets.UTF_8).split("\\R", -1)) {
                        String value = line.trim();
                        if (value.isEmpty()) {
                            continue;
                        }
                        addRuntimePath(
                            entries,
                            combined,
                            seen,
                            value
                        );
                        legacyEntryCount++;
                    }
                    if (legacyEntryCount == 0) {
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
                            combined,
                            seen,
                            modFoldersFile
                        );
                    }
                    Map<String, List<Path>> groupedModFolders =
                        CommonLaunchHandler.getGroupedModFolders();
                    int modRootCount = 0;
                    for (String loadedModId : groupedModFolders.keySet()
                            .stream()
                            .sorted()
                            .toList()) {
                        for (Path path : groupedModFolders.get(loadedModId)) {
                            addRuntimePath(
                                entries,
                                combined,
                                seen,
                                path.toString()
                            );
                            modRootCount++;
                        }
                    }
                    if (modRootCount == 0 || entries.isEmpty()) {
                        throw new IllegalStateException(
                            "runtime loader input closure is incomplete"
                        );
                    }
                    return new RuntimeFingerprint(
                        hex(combined.digest()),
                        entries
                    );
                }

                private static String requiredSystemProperty(String name) {
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
                        MessageDigest combined,
                        Set<Path> seen,
                        String origin,
                        String value)
                        throws Exception {
                    for (String rawEntry : value.split(
                            java.util.regex.Pattern.quote(File.pathSeparator),
                            -1)) {
                        if (rawEntry.isBlank()) {
                            throw new IllegalStateException(
                                origin + " contains a blank entry"
                            );
                        }
                        addRuntimePath(
                            entries,
                            combined,
                            seen,
                            rawEntry
                        );
                    }
                }

                private static void addRuntimePath(
                        JsonArray entries,
                        MessageDigest combined,
                        Set<Path> seen,
                        String rawEntry)
                        throws Exception {
                    Path path = Path.of(rawEntry)
                        .toRealPath(LinkOption.NOFOLLOW_LINKS);
                    if (Files.isSymbolicLink(path)) {
                        throw new IllegalStateException(
                            "runtime loader entry is symbolic link: " + path
                        );
                    }
                    if (!seen.add(path)) {
                        return;
                    }
                    String kind;
                    String hash;
                    if (Files.isRegularFile(
                            path,
                            LinkOption.NOFOLLOW_LINKS)) {
                        kind = "file";
                        hash = sha256(path);
                    } else if (Files.isDirectory(
                            path,
                            LinkOption.NOFOLLOW_LINKS)) {
                        kind = "directory";
                        hash = sha256Tree(path);
                    } else {
                        throw new IllegalStateException(
                            "runtime loader entry is not a file or directory: "
                                + path
                        );
                    }
                    combined.update(
                        path.toString().getBytes(StandardCharsets.UTF_8)
                    );
                    combined.update((byte) 0);
                    combined.update(kind.getBytes(StandardCharsets.UTF_8));
                    combined.update((byte) 0);
                    combined.update(hash.getBytes(StandardCharsets.UTF_8));
                    combined.update((byte) '\n');
                    JsonObject entry = new JsonObject();
                    entry.addProperty("path", path.toString());
                    entry.addProperty("kind", kind);
                    entry.addProperty("sha256", hash);
                    entries.add(entry);
                }

                private static String sha256Tree(Path root)
                        throws Exception {
                    MessageDigest digest =
                        MessageDigest.getInstance("SHA-256");
                    List<Path> files;
                    try (var stream = Files.walk(root)) {
                        files = stream
                            .peek(path -> {
                                if (Files.isSymbolicLink(path)) {
                                    throw new IllegalStateException(
                                        "classpath directory contains symbolic link: "
                                            + path
                                    );
                                }
                            })
                            .filter(path -> Files.isRegularFile(
                                path,
                                LinkOption.NOFOLLOW_LINKS
                            ))
                            // the auditor's sha256-relative-nul-file-sha256-nul-v1 orders by the
                            // relative '/'-separated name (code point order), not by the
                            // platform's case-insensitive absolute path comparison
                            .sorted(java.util.Comparator.comparing(
                                (Path path) -> root.relativize(path)
                                    .toString()
                                    .replace('\\', '/')
                            ))
                            .toList();
                    }
                    for (Path file : files) {
                        digest.update(
                            root.relativize(file)
                                .toString()
                                .replace('\\', '/')
                                .getBytes(StandardCharsets.UTF_8)
                        );
                        digest.update((byte) 0);
                        digest.update(
                            sha256(file).getBytes(StandardCharsets.UTF_8)
                        );
                        digest.update((byte) 0);
                    }
                    return hex(digest.digest());
                }

                private static void checkTimeoutLocked() {
                    if (stage == Stage.COMPLETE
                        || stage == Stage.SEAL
                        || !initialized) {
                        return;
                    }
                    long current = currentTickLocked();
                    if (current > GLOBAL_TIMEOUT_TICKS) {
                        failLocked(
                            "station harness exceeded global timeout"
                        );
                    } else if (scenarioOwnedTick >= 0L
                        && current - stageStartedTick > STAGE_TIMEOUT_TICKS) {
                        failLocked(
                            "station harness stage timed out: " + stage
                        );
                    }
                }

                private static void failLocked(String message) {
                    if (terminalFailure == null) {
                        if (stage == Stage.CLEANUP_FENCE && !chunkExitHealthPassed) {
                            cleanupFailureCount++;
                            message += "; lastChunkQuiescence=" + lastChunkQuiescence;
                        }
                        terminalFailure = message;
                        System.err.println(
                            "[ModPorterStrictStation] FAILED: " + message
                        );
                    }
                    if (stage == Stage.COMPLETE) {
                        return;
                    }
                    if (stage == Stage.SEAL) {
                        System.setProperty(COMPLETED_PROPERTY, "true");
                        System.setProperty(
                            COORDINATOR_COMPLETED_PROPERTY,
                            "true"
                        );
                        stage = Stage.COMPLETE;
                        return;
                    }
                    if (stage == Stage.SERVER_RESTORE) {
                        setStageLocked(Stage.FAILURE_CLEANUP);
                        return;
                    }
                    if (serverMainSnapshot != null && !serverRestoreIssued) {
                        setStageLocked(Stage.SERVER_RESTORE);
                    } else if (initialized
                        && Minecraft.getInstance().level != null) {
                        setStageLocked(Stage.FAILURE_CLEANUP);
                    } else {
                        System.setProperty(COMPLETED_PROPERTY, "true");
                        System.setProperty(
                            COORDINATOR_COMPLETED_PROPERTY,
                            "true"
                        );
                        stage = Stage.COMPLETE;
                    }
                }

                private static void setStageLocked(Stage next) {
                    stage = next;
                    stageStartedTick = currentTickLocked();
                }

                // Stage starts and expiry checks must use the same clock even when the
                // integrated server advanced while the client's first tick was loading.
                private static long currentTickLocked() {
                    return Math.max(clientTicks, serverTicks);
                }

                private static boolean enabled() {
                    return Boolean.getBoolean(ENABLED_PROPERTY);
                }

                private static String requiredProperty(String name) {
                    String value = System.getProperty(name);
                    if (value == null || value.isBlank()) {
                        throw new IllegalStateException(
                            "missing required system property: " + name
                        );
                    }
                    return value.trim();
                }

                private static String requiredEnvironment(String name) {
                    String value = System.getenv(name);
                    if (value == null || value.isBlank()) {
                        throw new IllegalStateException(
                            "missing required environment value: " + name
                        );
                    }
                    return value.trim();
                }

                private static Path confinedAbsolutePath(
                        String value,
                        Path parent) {
                    Path path = Path.of(value)
                        .toAbsolutePath()
                        .normalize();
                    if (parent != null && !path.startsWith(parent)) {
                        throw new IllegalStateException(
                            "path escaped declared parent: " + path
                        );
                    }
                    return path;
                }

                private static void requireNewOutput(Path path) {
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalStateException(
                            "strict output already exists: " + path
                        );
                    }
                }

                private static JsonObject readJsonObject(Path path)
                        throws IOException {
                    if (!Files.isRegularFile(
                            path,
                            LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalStateException(
                            "JSON file is unavailable: " + path
                        );
                    }
                    JsonElement element = JsonParser.parseString(
                        Files.readString(path, StandardCharsets.UTF_8)
                    );
                    if (!element.isJsonObject()) {
                        throw new IllegalStateException(
                            "JSON root is not an object: " + path
                        );
                    }
                    return element.getAsJsonObject();
                }

                private static void atomicWriteJson(
                        Path path,
                        JsonObject value)
                        throws IOException {
                    byte[] bytes = GSON.toJson(value)
                        .concat(System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8);
                    Path parent = path.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Path temporary = path.resolveSibling(
                        path.getFileName()
                            + "."
                            + UUID.randomUUID()
                            + ".tmp"
                    );
                    Files.write(
                        temporary,
                        bytes,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                    );
                    try {
                        Files.move(
                            temporary,
                            path,
                            StandardCopyOption.ATOMIC_MOVE
                        );
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, path);
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                }

                private static String sha256(Path path)
                        throws Exception {
                    return sha256(Files.readAllBytes(path));
                }

                private static String sha256(byte[] bytes)
                        throws Exception {
                    return hex(
                        MessageDigest.getInstance("SHA-256").digest(bytes)
                    );
                }

                private static String hex(byte[] bytes) {
                    return java.util.HexFormat.of().formatHex(bytes);
                }

                private static JsonArray stringArray(List<String> values) {
                    JsonArray result = new JsonArray();
                    values.forEach(result::add);
                    return result;
                }

                private static String string(JsonObject object, String key) {
                    JsonElement value = object.get(key);
                    if (value == null
                        || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isString()) {
                        throw new IllegalStateException(
                            "expected string field: " + key
                        );
                    }
                    return value.getAsString();
                }

                private static int intValue(
                        JsonObject object,
                        String key) {
                    JsonElement value = object.get(key);
                    if (value == null
                        || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isNumber()) {
                        throw new IllegalStateException(
                            "expected integer field: " + key
                        );
                    }
                    return value.getAsInt();
                }

                private static boolean booleanValue(
                        JsonObject object,
                        String key) {
                    JsonElement value = object.get(key);
                    if (value == null
                        || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isBoolean()) {
                        throw new IllegalStateException(
                            "expected boolean field: " + key
                        );
                    }
                    return value.getAsBoolean();
                }

                private static String describe(Throwable throwable) {
                    String message = throwable.getMessage();
                    return throwable.getClass().getName()
                        + (message == null || message.isBlank()
                            ? ""
                            : ": " + message);
                }

                private static final class RuntimeFingerprint {
                    private final String sha256;
            """.trimIndent(),
            """
                    private final JsonArray entries;

                    private RuntimeFingerprint(
                            String sha256,
                            JsonArray entries) {
                        this.sha256 = sha256;
                        this.entries = entries;
                    }
                }

                private static final class RecordingRenderer
                        implements IMapDecorationRenderer {
                    private final IMapDecorationRenderer delegate;

                    private RecordingRenderer(
                            IMapDecorationRenderer delegate) {
                        this.delegate = delegate;
                    }

                    @Override
                    public boolean render(
                            MapDecoration decoration,
                            PoseStack poseStack,
                            MultiBufferSource bufferSource,
                            MapItemSavedData mapData,
                            MapDecorationTextureManager decorationTextures,
                            boolean inItemFrame,
                            int packedLight,
                            int index) {
                        boolean result;
                        try {
                            result = delegate.render(
                                decoration,
                                poseStack,
                                bufferSource,
                                mapData,
                                decorationTextures,
                                inItemFrame,
                                packedLight,
                                index
                            );
                        } catch (Throwable throwable) {
                            synchronized (LOCK) {
                                failLocked(
                                    "production station renderer threw: "
                                        + describe(throwable)
                                );
                            }
                            return false;
                        }
                        synchronized (LOCK) {
                            if (stage != Stage.WAIT_PRODUCTION_RENDER
                                || renderObserved
                                || mapData != observedClientData
                                || stationType == null
                                || decoration.type().value() != stationType) {
                                return result;
                            }
                            MapId held = Minecraft.getInstance()
                                .player
                                .getMainHandItem()
                                .get(DataComponents.MAP_ID);
                            if (!ownedMapId.equals(held)) {
                                return result;
                            }
                            heldMapIdValue = held.id();
                            renderedDecoration = decorationJson(decoration);
                            try {
                                requireDecorationEqual(
                                    expectedDecoration,
                                    renderedDecoration,
                                    "production renderer"
                                );
                            } catch (Throwable throwable) {
                                failLocked(describe(throwable));
                                return result;
                            }
                            renderThread = RenderSystem.isOnRenderThread();
                            productionDelegateReturned = result;
                            Set<String> frames = new HashSet<>();
                            for (StackTraceElement frame
                                    : Thread.currentThread().getStackTrace()) {
                                frames.add(
                                    frame.getClassName()
                                        + "#"
                                        + frame.getMethodName()
                                );
                            }
                            itemInHandRenderMapFrame = frames.contains(
                                "net.minecraft.client.renderer.ItemInHandRenderer"
                                    + "#renderMap"
                            );
                            mapRendererRenderFrame = frames.contains(
                                "net.minecraft.client.gui.MapRenderer#render"
                            );
                            mapInstanceDrawFrame = frames.contains(
                                "net.minecraft.client.gui.MapRenderer"
                                    + "${'$'}"
                                    + "MapInstance#draw"
                            );
                            rendererManagerRenderFrame = frames.contains(
                                "net.neoforged.neoforge.client.gui.map."
                                    + "MapDecorationRendererManager#render"
                            );
                            if (!renderThread
                                || !productionDelegateReturned
                                || !itemInHandRenderMapFrame
                                || !mapRendererRenderFrame
                                || !mapInstanceDrawFrame
                                || !rendererManagerRenderFrame) {
                                failLocked(
                                    "station renderer call did not prove the "
                                        + "complete first-person production chain"
                                );
                                return result;
                            }
                            renderObserved = true;
                        }
                        return result;
                    }
                }
            }
            """.trimIndent()
        ).joinToString("\n")
    }
}
