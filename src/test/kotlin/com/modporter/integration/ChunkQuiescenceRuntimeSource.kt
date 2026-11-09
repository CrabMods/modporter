package com.modporter.integration

/**
 * Generates a self-contained Java helper that can be embedded inside the
 * generated strict client runtime harness.
 */
internal object ChunkQuiescenceRuntimeSource {
    val informationalCounterNames: List<String> = listOf(
        "generationRefSum",
        "generationRefNonzero",
        "generationRefMax",
        "mainThreadTasks",
    )

    val zeroCounterNames: List<String> = listOf(
        "pendingGenerationRefSum",
        "pendingGenerationRefNonzero",
        "pendingGenerationRefMax",
        "pendingGenerationTasks",
        "pendingUnloads",
        "toDrop",
        "unloadQueue",
        "failedFutures",
    )

    val snapshotKeys: List<String> = listOf(
        "sampledOnServerThread",
        "holderCount",
        *informationalCounterNames.toTypedArray(),
        *zeroCounterNames.toTypedArray(),
        "blockers",
        "quiescent",
    )

    fun source(): String =
        """
        private static final int CHUNK_QUIESCENCE_MAX_BLOCKERS = 8;
        private static final String CHUNK_QUIESCENCE_UPDATING_FIELD =
            "updatingChunkMap";
        private static final String CHUNK_QUIESCENCE_PENDING_UNLOADS_FIELD =
            "pendingUnloads";
        private static final String CHUNK_QUIESCENCE_PENDING_GENERATION_FIELD =
            "pendingGenerationTasks";
        private static final String CHUNK_QUIESCENCE_TO_DROP_FIELD =
            "toDrop";
        private static final String CHUNK_QUIESCENCE_UNLOAD_QUEUE_FIELD =
            "unloadQueue";

        private static JsonObject sampleChunkQuiescenceLocked(
            MinecraftServer server
        ) throws Exception {
            if (server == null || !server.isSameThread()) {
                throw new IllegalStateException(
                    "Chunk quiescence must be sampled on the server thread"
                );
            }

            java.lang.reflect.Field updatingField =
                requiredChunkQuiescenceField(
                    CHUNK_QUIESCENCE_UPDATING_FIELD,
                    java.util.Map.class
                );
            java.lang.reflect.Field pendingUnloadsField =
                requiredChunkQuiescenceField(
                    CHUNK_QUIESCENCE_PENDING_UNLOADS_FIELD,
                    java.util.Map.class
                );
            java.lang.reflect.Field pendingGenerationField =
                requiredChunkQuiescenceField(
                    CHUNK_QUIESCENCE_PENDING_GENERATION_FIELD,
                    java.util.List.class
                );
            java.lang.reflect.Field toDropField =
                requiredChunkQuiescenceField(
                    CHUNK_QUIESCENCE_TO_DROP_FIELD,
                    java.util.Set.class
                );
            java.lang.reflect.Field unloadQueueField =
                requiredChunkQuiescenceField(
                    CHUNK_QUIESCENCE_UNLOAD_QUEUE_FIELD,
                    java.util.Queue.class
                );

            long generationRefSum = 0L;
            long generationRefNonzero = 0L;
            int generationRefMax = 0;
            long pendingGenerationRefSum = 0L;
            long pendingGenerationRefNonzero = 0L;
            int pendingGenerationRefMax = 0;
            long pendingGenerationTasks = 0L;
            long pendingUnloads = 0L;
            long toDrop = 0L;
            long unloadQueue = 0L;
            long mainThreadTasks = 0L;
            long failedFutures = 0L;
            long holderCount = 0L;
            JsonArray blockers = new JsonArray();

            for (net.minecraft.server.level.ServerLevel level
                : server.getAllLevels()) {
                net.minecraft.server.level.ServerChunkCache chunkSource =
                    level.getChunkSource();
                net.minecraft.server.level.ChunkMap chunkMap =
                    chunkSource.chunkMap;
                java.util.Map<?, ?> updating = requiredChunkQuiescenceValue(
                    updatingField,
                    chunkMap,
                    java.util.Map.class
                );
                java.util.Map<?, ?> pending = requiredChunkQuiescenceValue(
                    pendingUnloadsField,
                    chunkMap,
                    java.util.Map.class
                );
                java.util.List<?> generationTasks =
                    requiredChunkQuiescenceValue(
                        pendingGenerationField,
                        chunkMap,
                        java.util.List.class
                    );
                java.util.Set<?> dropping = requiredChunkQuiescenceValue(
                    toDropField,
                    chunkMap,
                    java.util.Set.class
                );
                java.util.Queue<?> queuedUnloads =
                    requiredChunkQuiescenceValue(
                        unloadQueueField,
                        chunkMap,
                        java.util.Queue.class
                    );

                pendingGenerationTasks = Math.addExact(
                    pendingGenerationTasks,
                    generationTasks.size()
                );
                pendingUnloads = Math.addExact(
                    pendingUnloads,
                    pending.size()
                );
                toDrop = Math.addExact(toDrop, dropping.size());
                unloadQueue = Math.addExact(
                    unloadQueue,
                    queuedUnloads.size()
                );
                mainThreadTasks = Math.addExact(
                    mainThreadTasks,
                    chunkSource.getPendingTasksCount()
                );

                java.util.IdentityHashMap<
                    net.minecraft.server.level.ChunkHolder,
                    Integer
                > holderKinds = new java.util.IdentityHashMap<>();
                java.util.ArrayList<
                    net.minecraft.server.level.ChunkHolder
                > holders = new java.util.ArrayList<>();
                collectChunkQuiescenceHolders(
                    updating,
                    1,
                    holderKinds,
                    holders
                );
                collectChunkQuiescenceHolders(
                    pending,
                    2,
                    holderKinds,
                    holders
                );

                String dimension = level.dimension().location().toString();
                for (net.minecraft.server.level.ChunkHolder holder : holders) {
                    holderCount = Math.addExact(holderCount, 1L);
                    int generationRefs = holder.getGenerationRefCount();
                    generationRefSum = Math.addExact(
                        generationRefSum,
                        generationRefs
                    );
                    if (generationRefs != 0) {
                        generationRefNonzero = Math.addExact(
                            generationRefNonzero,
                            1L
                        );
                    }
                    generationRefMax = Math.max(
                        generationRefMax,
                        generationRefs
                    );

                    java.util.concurrent.CompletableFuture<?> saveFuture =
                        holder.getSaveSyncFuture();
                    if (saveFuture == null) {
                        throw new IllegalStateException(
                            "ChunkHolder returned a null save future"
                        );
                    }
                    boolean saveCancelled = saveFuture.isCancelled();
                    boolean saveFailed =
                        saveFuture.isCompletedExceptionally();
                    if (saveCancelled || saveFailed) {
                        failedFutures = Math.addExact(
                            failedFutures,
                            1L
                        );
                    }

                    int kinds = holderKinds.get(holder);
                    boolean pendingUnload = (kinds & 2) != 0;
                    if (pendingUnload) {
                        pendingGenerationRefSum = Math.addExact(
                            pendingGenerationRefSum,
                            generationRefs
                        );
                        if (generationRefs != 0) {
                            pendingGenerationRefNonzero = Math.addExact(
                                pendingGenerationRefNonzero,
                                1L
                            );
                        }
                        pendingGenerationRefMax = Math.max(
                            pendingGenerationRefMax,
                            generationRefs
                        );
                    }
                    if (
                        blockers.size() < CHUNK_QUIESCENCE_MAX_BLOCKERS
                            && (
                                pendingUnload
                                    || saveCancelled
                                    || saveFailed
                            )
                    ) {
                        net.minecraft.world.level.ChunkPos pos =
                            holder.getPos();
                        JsonObject blocker = new JsonObject();
                        blocker.addProperty("dimension", dimension);
                        blocker.addProperty(
                            "pos",
                            pos.x + "," + pos.z
                        );
                        blocker.addProperty("x", pos.x);
                        blocker.addProperty("z", pos.z);
                        blocker.addProperty(
                            "generationRefs",
                            generationRefs
                        );
                        blocker.addProperty(
                            "updating",
                            (kinds & 1) != 0
                        );
                        blocker.addProperty(
                            "pendingUnload",
                            pendingUnload
                        );
                        blocker.addProperty(
                            "saveDone",
                            saveFuture.isDone()
                        );
                        blocker.addProperty(
                            "saveCancelled",
                            saveCancelled
                        );
                        blocker.addProperty(
                            "saveFailed",
                            saveFailed
                        );
                        blocker.addProperty(
                            "saveState",
                            saveCancelled
                                ? "CANCELLED"
                                : saveFailed
                                    ? "FAILED"
                                    : saveFuture.isDone()
                                        ? "DONE"
                                        : "PENDING"
                        );
                        blocker.addProperty(
                            "readyForSaving",
                            holder.isReadyForSaving()
                        );
                        blockers.add(blocker);
                    }
                }
            }

            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("sampledOnServerThread", true);
            snapshot.addProperty("holderCount", holderCount);
            snapshot.addProperty(
                "generationRefSum",
                generationRefSum
            );
            snapshot.addProperty(
                "generationRefNonzero",
                generationRefNonzero
            );
            snapshot.addProperty(
                "generationRefMax",
                generationRefMax
            );
            snapshot.addProperty(
                "pendingGenerationRefSum",
                pendingGenerationRefSum
            );
            snapshot.addProperty(
                "pendingGenerationRefNonzero",
                pendingGenerationRefNonzero
            );
            snapshot.addProperty(
                "pendingGenerationRefMax",
                pendingGenerationRefMax
            );
            snapshot.addProperty(
                "pendingGenerationTasks",
                pendingGenerationTasks
            );
            snapshot.addProperty("pendingUnloads", pendingUnloads);
            snapshot.addProperty("toDrop", toDrop);
            snapshot.addProperty("unloadQueue", unloadQueue);
            snapshot.addProperty("mainThreadTasks", mainThreadTasks);
            snapshot.addProperty("failedFutures", failedFutures);
            snapshot.add("blockers", blockers);
            snapshot.addProperty(
                "quiescent",
                chunkQuiescent(snapshot)
            );
            return snapshot;
        }

        private static boolean chunkQuiescent(JsonObject snapshot) {
            if (snapshot == null) {
                return false;
            }
            try {
                return snapshot.has("sampledOnServerThread")
                    && snapshot.get("sampledOnServerThread").getAsBoolean()
                    && snapshot.has("holderCount")
                    && snapshot.get("holderCount").getAsLong() > 0L
                    && snapshot.has("generationRefSum")
                    && snapshot.get("generationRefSum").getAsLong() >= 0L
                    && snapshot.has("generationRefNonzero")
                    && snapshot.get("generationRefNonzero").getAsLong() >= 0L
                    && snapshot.has("generationRefMax")
                    && snapshot.get("generationRefMax").getAsLong() >= 0L
                    && snapshot.has("mainThreadTasks")
                    && snapshot.get("mainThreadTasks").getAsLong() >= 0L
                    && snapshot.has("pendingGenerationRefSum")
                    && snapshot.get("pendingGenerationRefSum").getAsLong() == 0L
                    && snapshot.has("pendingGenerationRefNonzero")
                    && snapshot.get("pendingGenerationRefNonzero").getAsLong() == 0L
                    && snapshot.has("pendingGenerationRefMax")
                    && snapshot.get("pendingGenerationRefMax").getAsLong() == 0L
                    && snapshot.has("pendingGenerationTasks")
                    && snapshot.get("pendingGenerationTasks").getAsLong() == 0L
                    && snapshot.has("pendingUnloads")
                    && snapshot.get("pendingUnloads").getAsLong() == 0L
                    && snapshot.has("toDrop")
                    && snapshot.get("toDrop").getAsLong() == 0L
                    && snapshot.has("unloadQueue")
                    && snapshot.get("unloadQueue").getAsLong() == 0L
                    && snapshot.has("failedFutures")
                    && snapshot.get("failedFutures").getAsLong() == 0L;
            } catch (RuntimeException malformedSnapshot) {
                return false;
            }
        }

        private static java.lang.reflect.Field requiredChunkQuiescenceField(
            String name,
            Class<?> expectedType
        ) throws ReflectiveOperationException {
            java.lang.reflect.Field field =
                net.minecraft.server.level.ChunkMap.class
                    .getDeclaredField(name);
            if (!expectedType.isAssignableFrom(field.getType())) {
                throw new NoSuchFieldException(
                    "ChunkMap."
                        + name
                        + " has incompatible type "
                        + field.getType().getName()
                );
            }
            field.setAccessible(true);
            return field;
        }

        private static <T> T requiredChunkQuiescenceValue(
            java.lang.reflect.Field field,
            Object owner,
            Class<T> expectedType
        ) throws IllegalAccessException {
            Object value = field.get(owner);
            if (!expectedType.isInstance(value)) {
                throw new IllegalStateException(
                    field.getDeclaringClass().getName()
                        + "."
                        + field.getName()
                        + " did not contain "
                        + expectedType.getName()
                );
            }
            return expectedType.cast(value);
        }

        private static void collectChunkQuiescenceHolders(
            java.util.Map<?, ?> source,
            int kind,
            java.util.IdentityHashMap<
                net.minecraft.server.level.ChunkHolder,
                Integer
            > holderKinds,
            java.util.List<
                net.minecraft.server.level.ChunkHolder
            > holders
        ) {
            for (Object value : source.values()) {
                if (!(value instanceof net.minecraft.server.level.ChunkHolder)) {
                    throw new IllegalStateException(
                        "ChunkMap holder collection contained "
                            + (
                                value == null
                                    ? "null"
                                    : value.getClass().getName()
                            )
                    );
                }
                net.minecraft.server.level.ChunkHolder holder =
                    (net.minecraft.server.level.ChunkHolder) value;
                Integer existing = holderKinds.get(holder);
                if (existing == null) {
                    holderKinds.put(holder, kind);
                    holders.add(holder);
                } else {
                    holderKinds.put(holder, existing | kind);
                }
            }
        }
        """.trimIndent()
}
