package com.modporter.integration

import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChunkQuiescenceRuntimeSourceTest {
    private lateinit var fixture: CompiledFixture
    private lateinit var brokenReflectionFixture: CompiledFixture

    @BeforeAll
    fun compileGeneratedHelper() {
        fixture = compileFixture(brokenPendingUnloadsField = false)
        brokenReflectionFixture = compileFixture(brokenPendingUnloadsField = true)
    }

    @AfterAll
    fun closeGeneratedHelper() {
        fixture.close()
        brokenReflectionFixture.close()
    }

    @Test
    fun `generated helper exposes the exact fail closed contract`() {
        val source = ChunkQuiescenceRuntimeSource.source()

        assertTrue(
            source.contains(
                "private static JsonObject sampleChunkQuiescenceLocked(\n" +
                    "    MinecraftServer server\n" +
                    ") throws Exception",
            ),
            source,
        )
        assertTrue(
            source.contains("private static boolean chunkQuiescent(JsonObject snapshot)"),
            source,
        )
        listOf(
            "\"updatingChunkMap\"",
            "\"pendingUnloads\"",
            "\"pendingGenerationTasks\"",
            "\"toDrop\"",
            "\"unloadQueue\"",
        ).forEach { fieldName ->
            assertTrue(source.contains(fieldName), "Missing reflected field $fieldName")
        }
        listOf(
            ".pollTask(",
            ".removeTicketsOnClosing(",
            ".runGenerationTasks(",
            ".increaseGenerationRefCount(",
            ".decreaseGenerationRefCount(",
            ".complete(",
        ).forEach { forbidden ->
            assertFalse(source.contains(forbidden), "Generated helper must not call $forbidden")
        }
    }

    @Test
    fun `every nonzero health metric rejects the actual generated predicate`() {
        val metricNames = listOf(
            *ChunkQuiescenceRuntimeSource.zeroCounterNames.toTypedArray(),
        )

        metricNames.forEach { metric ->
            val snapshot = fixture.sample(fixture.healthyLoadedServer())
            assertTrue(
                fixture.quiescent(snapshot),
                "A loaded holder with a pending save future should be quiescent",
            )
            assertEquals(0, fixture.blockerCount(snapshot))
            fixture.setNumber(snapshot, metric, 1L)
            assertFalse(
                fixture.quiescent(snapshot),
                "A nonzero $metric must reject quiescence",
            )
        }
    }

    @Test
    fun `empty sampling rejects while active generation and pending work can pass`() {
        val emptySnapshot = fixture.sample(fixture.emptyServer())
        assertFalse(
            fixture.quiescent(emptySnapshot),
            "A zero-holder sample must not produce a false green result",
        )

        val loadedSnapshot = fixture.sample(fixture.healthyLoadedServer())
        assertTrue(
            fixture.quiescent(loadedSnapshot),
            "Active viewport refs, main-thread work, and an unfinished save future are informational",
        )
        assertEquals(1L, fixture.number(loadedSnapshot, "holderCount"))
        assertEquals(7L, fixture.number(loadedSnapshot, "generationRefSum"))
        assertEquals(1L, fixture.number(loadedSnapshot, "generationRefNonzero"))
        assertEquals(7L, fixture.number(loadedSnapshot, "generationRefMax"))
        assertEquals(3L, fixture.number(loadedSnapshot, "mainThreadTasks"))
        assertEquals(0, fixture.blockerCount(loadedSnapshot))
    }

    @Test
    fun `pending unload refs queued unloads and exceptional futures reject`() {
        val pendingMap = fixture.newChunkMap()
        repeat(10) { index ->
            fixture.putPendingUnload(
                pendingMap,
                fixture.newHolder(
                    x = index,
                    z = -index,
                    generationRefs = 1,
                    saveFuture = CompletableFuture.completedFuture(null),
                    readyForSaving = false,
                ),
            )
        }
        val pendingSnapshot = fixture.sample(fixture.server(pendingMap))
        assertFalse(fixture.quiescent(pendingSnapshot))
        assertEquals(10L, fixture.number(pendingSnapshot, "pendingUnloads"))
        assertEquals(10L, fixture.number(pendingSnapshot, "pendingGenerationRefSum"))
        assertEquals(10L, fixture.number(pendingSnapshot, "pendingGenerationRefNonzero"))
        assertEquals(1L, fixture.number(pendingSnapshot, "pendingGenerationRefMax"))
        assertEquals(8, fixture.blockerCount(pendingSnapshot))

        val queuedMap = fixture.newChunkMap()
        fixture.putUpdating(
            queuedMap,
            fixture.newHolder(
                x = 0,
                z = 0,
                generationRefs = 0,
                saveFuture = CompletableFuture.completedFuture(null),
                readyForSaving = true,
            ),
        )
        fixture.queueUnload(queuedMap)
        val queuedSnapshot = fixture.sample(fixture.server(queuedMap))
        assertFalse(fixture.quiescent(queuedSnapshot))
        assertEquals(1L, fixture.number(queuedSnapshot, "unloadQueue"))

        val failedSave = CompletableFuture<Any?>()
        failedSave.completeExceptionally(IllegalStateException("save failed"))
        val failedMap = fixture.newChunkMap()
        fixture.putUpdating(
            failedMap,
            fixture.newHolder(
                x = 7,
                z = 11,
                generationRefs = 0,
                saveFuture = failedSave,
                readyForSaving = false,
            ),
        )
        val failedSnapshot = fixture.sample(fixture.server(failedMap))
        assertFalse(
            fixture.quiescent(failedSnapshot),
            "An exceptional save future must reject quiescence",
        )
        assertEquals(1L, fixture.number(failedSnapshot, "failedFutures"))
    }

    @Test
    fun `missing reflected native field and off thread sampling fail closed`() {
        val reflectionFailure = assertFailsWith<InvocationTargetException> {
            brokenReflectionFixture.sample(brokenReflectionFixture.emptyServer())
        }
        assertIs<NoSuchFieldException>(reflectionFailure.cause)

        val threadFailure = assertFailsWith<InvocationTargetException> {
            fixture.sample(fixture.emptyServer(sameThread = false))
        }
        assertIs<IllegalStateException>(threadFailure.cause)
    }

    private fun compileFixture(brokenPendingUnloadsField: Boolean): CompiledFixture {
        val root = createTempDirectory("chunk-quiescence-source-test-")
        val sources = root.resolve("src").createDirectories()
        val output = root.resolve("classes").createDirectories()
        fixtureSources(brokenPendingUnloadsField).forEach { (relativePath, source) ->
            val file = sources.resolve(relativePath)
            file.parent.createDirectories()
            file.writeText(source, StandardCharsets.UTF_8)
        }

        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) {
            "A JDK compiler is required to verify the generated Java helper"
        }
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(
            diagnostics,
            null,
            StandardCharsets.UTF_8,
        ).use { fileManager ->
            val paths = Files.walk(sources)
            val javaFiles = try {
                paths
                    .filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }
                    .toList()
            } finally {
                paths.close()
            }
            val units = fileManager.getJavaFileObjectsFromPaths(javaFiles)
            val compiled = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf("--release", "17", "-d", output.toString()),
                null,
                units,
            ).call()
            assertTrue(
                compiled,
                diagnostics.diagnostics.joinToString("\n") { it.toString() },
            )
        }
        return CompiledFixture(root, output)
    }

    private fun fixtureSources(
        brokenPendingUnloadsField: Boolean,
    ): Map<String, String> {
        val pendingField = if (brokenPendingUnloadsField) {
            "pendingUnloadz"
        } else {
            "pendingUnloads"
        }
        return linkedMapOf(
            "testharness/Harness.java" to
                """
                package testharness;

                import com.google.gson.JsonArray;
                import com.google.gson.JsonObject;
                import net.minecraft.server.MinecraftServer;

                public final class Harness {
                ${ChunkQuiescenceRuntimeSource.source().prependIndent("    ")}

                    public static JsonObject sample(MinecraftServer server)
                        throws Exception {
                        return sampleChunkQuiescenceLocked(server);
                    }

                    public static boolean isQuiescent(JsonObject snapshot) {
                        return chunkQuiescent(snapshot);
                    }
                }
                """.trimIndent(),
            "com/google/gson/JsonElement.java" to
                """
                package com.google.gson;

                public class JsonElement {
                    public long getAsLong() {
                        throw new UnsupportedOperationException();
                    }

                    public boolean getAsBoolean() {
                        throw new UnsupportedOperationException();
                    }
                }
                """.trimIndent(),
            "com/google/gson/JsonPrimitive.java" to
                """
                package com.google.gson;

                final class JsonPrimitive extends JsonElement {
                    private final Object value;

                    JsonPrimitive(Object value) {
                        this.value = value;
                    }

                    @Override
                    public long getAsLong() {
                        return ((Number) value).longValue();
                    }

                    @Override
                    public boolean getAsBoolean() {
                        return (Boolean) value;
                    }
                }
                """.trimIndent(),
            "com/google/gson/JsonArray.java" to
                """
                package com.google.gson;

                import java.util.ArrayList;
                import java.util.List;

                public final class JsonArray extends JsonElement {
                    private final List<JsonElement> values = new ArrayList<>();

                    public void add(JsonElement value) {
                        values.add(value);
                    }

                    public int size() {
                        return values.size();
                    }
                }
                """.trimIndent(),
            "com/google/gson/JsonObject.java" to
                """
                package com.google.gson;

                import java.util.LinkedHashMap;
                import java.util.Map;

                public final class JsonObject extends JsonElement {
                    private final Map<String, JsonElement> values =
                        new LinkedHashMap<>();

                    public void addProperty(String name, Number value) {
                        values.put(name, new JsonPrimitive(value));
                    }

                    public void addProperty(String name, Boolean value) {
                        values.put(name, new JsonPrimitive(value));
                    }

                    public void addProperty(String name, String value) {
                        values.put(name, new JsonPrimitive(value));
                    }

                    public void add(String name, JsonElement value) {
                        values.put(name, value);
                    }

                    public boolean has(String name) {
                        return values.containsKey(name);
                    }

                    public JsonElement get(String name) {
                        return values.get(name);
                    }
                }
                """.trimIndent(),
            "net/minecraft/world/level/ChunkPos.java" to
                """
                package net.minecraft.world.level;

                public final class ChunkPos {
                    public final int x;
                    public final int z;

                    public ChunkPos(int x, int z) {
                        this.x = x;
                        this.z = z;
                    }
                }
                """.trimIndent(),
            "net/minecraft/server/level/ChunkHolder.java" to
                """
                package net.minecraft.server.level;

                import java.util.concurrent.CompletableFuture;
                import net.minecraft.world.level.ChunkPos;

                public final class ChunkHolder {
                    private final ChunkPos pos;
                    private final int generationRefs;
                    private final CompletableFuture<?> saveFuture;
                    private final boolean readyForSaving;

                    public ChunkHolder(
                        ChunkPos pos,
                        int generationRefs,
                        CompletableFuture<?> saveFuture,
                        boolean readyForSaving
                    ) {
                        this.pos = pos;
                        this.generationRefs = generationRefs;
                        this.saveFuture = saveFuture;
                        this.readyForSaving = readyForSaving;
                    }

                    public int getGenerationRefCount() {
                        return generationRefs;
                    }

                    public CompletableFuture<?> getSaveSyncFuture() {
                        return saveFuture;
                    }

                    public boolean isReadyForSaving() {
                        return readyForSaving;
                    }

                    public ChunkPos getPos() {
                        return pos;
                    }
                }
                """.trimIndent(),
            "net/minecraft/server/level/ChunkMap.java" to
                """
                package net.minecraft.server.level;

                import java.util.ArrayDeque;
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.LinkedHashSet;
                import java.util.List;
                import java.util.Map;
                import java.util.Queue;
                import java.util.Set;

                public final class ChunkMap {
                    private final Map<Long, ChunkHolder> updatingChunkMap =
                        new LinkedHashMap<>();
                    private final Map<Long, ChunkHolder> $pendingField =
                        new LinkedHashMap<>();
                    private final List<Object> pendingGenerationTasks =
                        new ArrayList<>();
                    private final Set<Long> toDrop = new LinkedHashSet<>();
                    private final Queue<Runnable> unloadQueue = new ArrayDeque<>();

                    public void putUpdating(long key, ChunkHolder holder) {
                        updatingChunkMap.put(key, holder);
                    }

                    public void putPendingUnload(long key, ChunkHolder holder) {
                        $pendingField.put(key, holder);
                    }

                    public void queueUnload() {
                        unloadQueue.add(() -> {});
                    }
                }
                """.trimIndent(),
            "net/minecraft/server/level/ServerChunkCache.java" to
                """
                package net.minecraft.server.level;

                public final class ServerChunkCache {
                    public final ChunkMap chunkMap;
                    private final int pendingTasks;

                    public ServerChunkCache(ChunkMap chunkMap, int pendingTasks) {
                        this.chunkMap = chunkMap;
                        this.pendingTasks = pendingTasks;
                    }

                    public int getPendingTasksCount() {
                        return pendingTasks;
                    }
                }
                """.trimIndent(),
            "net/minecraft/server/level/DimensionKey.java" to
                """
                package net.minecraft.server.level;

                public final class DimensionKey {
                    private final ResourceLocation location;

                    public DimensionKey(String location) {
                        this.location = new ResourceLocation(location);
                    }

                    public ResourceLocation location() {
                        return location;
                    }
                }
                """.trimIndent(),
            "net/minecraft/server/level/ResourceLocation.java" to
                """
                package net.minecraft.server.level;

                public final class ResourceLocation {
                    private final String value;

                    public ResourceLocation(String value) {
                        this.value = value;
                    }

                    @Override
                    public String toString() {
                        return value;
                    }
                }
                """.trimIndent(),
            "net/minecraft/server/level/ServerLevel.java" to
                """
                package net.minecraft.server.level;

                public final class ServerLevel {
                    private final ServerChunkCache chunkSource;
                    private final DimensionKey dimension;

                    public ServerLevel(
                        ServerChunkCache chunkSource,
                        DimensionKey dimension
                    ) {
                        this.chunkSource = chunkSource;
                        this.dimension = dimension;
                    }

                    public ServerChunkCache getChunkSource() {
                        return chunkSource;
                    }

                    public DimensionKey dimension() {
                        return dimension;
                    }
                }
                """.trimIndent(),
            "net/minecraft/server/MinecraftServer.java" to
                """
                package net.minecraft.server;

                import java.util.List;
                import net.minecraft.server.level.ServerLevel;

                public final class MinecraftServer {
                    private final boolean sameThread;
                    private final List<ServerLevel> levels;

                    public MinecraftServer(
                        boolean sameThread,
                        List<ServerLevel> levels
                    ) {
                        this.sameThread = sameThread;
                        this.levels = levels;
                    }

                    public boolean isSameThread() {
                        return sameThread;
                    }

                    public Iterable<ServerLevel> getAllLevels() {
                        return levels;
                    }
                }
                """.trimIndent(),
        )
    }

    private class CompiledFixture(
        private val root: Path,
        output: Path,
    ) : AutoCloseable {
        private val loader = URLClassLoader(arrayOf(output.toUri().toURL()), null)
        private val harnessClass = loader.loadClass("testharness.Harness")
        private val serverClass = loader.loadClass("net.minecraft.server.MinecraftServer")
        private val serverLevelClass = loader.loadClass("net.minecraft.server.level.ServerLevel")
        private val serverChunkCacheClass =
            loader.loadClass("net.minecraft.server.level.ServerChunkCache")
        private val chunkMapClass = loader.loadClass("net.minecraft.server.level.ChunkMap")
        private val chunkHolderClass =
            loader.loadClass("net.minecraft.server.level.ChunkHolder")
        private val chunkPosClass = loader.loadClass("net.minecraft.world.level.ChunkPos")
        private val dimensionKeyClass =
            loader.loadClass("net.minecraft.server.level.DimensionKey")
        private val jsonElementClass = loader.loadClass("com.google.gson.JsonElement")
        private val jsonObjectClass = loader.loadClass("com.google.gson.JsonObject")
        private val sampleMethod = harnessClass.getMethod("sample", serverClass)
        private val quiescentMethod =
            harnessClass.getMethod("isQuiescent", jsonObjectClass)

        fun newChunkMap(): Any = chunkMapClass.getConstructor().newInstance()

        fun newHolder(
            x: Int,
            z: Int,
            generationRefs: Int,
            saveFuture: CompletableFuture<*>,
            readyForSaving: Boolean,
        ): Any {
            val pos = chunkPosClass
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(x, z)
            return chunkHolderClass.getConstructor(
                chunkPosClass,
                Int::class.javaPrimitiveType,
                CompletableFuture::class.java,
                Boolean::class.javaPrimitiveType,
            ).newInstance(pos, generationRefs, saveFuture, readyForSaving)
        }

        fun putUpdating(chunkMap: Any, holder: Any) {
            chunkMapClass
                .getMethod(
                    "putUpdating",
                    Long::class.javaPrimitiveType,
                    chunkHolderClass,
                )
                .invoke(chunkMap, System.identityHashCode(holder).toLong(), holder)
        }

        fun putPendingUnload(chunkMap: Any, holder: Any) {
            chunkMapClass
                .getMethod(
                    "putPendingUnload",
                    Long::class.javaPrimitiveType,
                    chunkHolderClass,
                )
                .invoke(chunkMap, System.identityHashCode(holder).toLong(), holder)
        }

        fun queueUnload(chunkMap: Any) {
            chunkMapClass.getMethod("queueUnload").invoke(chunkMap)
        }

        fun server(
            chunkMap: Any,
            sameThread: Boolean = true,
            pendingTasks: Int = 0,
        ): Any {
            val cache = serverChunkCacheClass
                .getConstructor(chunkMapClass, Int::class.javaPrimitiveType)
                .newInstance(chunkMap, pendingTasks)
            val dimension = dimensionKeyClass
                .getConstructor(String::class.java)
                .newInstance("minecraft:overworld")
            val level = serverLevelClass
                .getConstructor(serverChunkCacheClass, dimensionKeyClass)
                .newInstance(cache, dimension)
            return serverClass
                .getConstructor(Boolean::class.javaPrimitiveType, List::class.java)
                .newInstance(sameThread, listOf(level))
        }

        fun emptyServer(sameThread: Boolean = true): Any =
            server(newChunkMap(), sameThread)

        fun healthyLoadedServer(): Any {
            val chunkMap = newChunkMap()
            putUpdating(
                chunkMap,
                newHolder(
                    x = 0,
                    z = 0,
                    generationRefs = 7,
                    saveFuture = CompletableFuture<Any?>(),
                    readyForSaving = false,
                ),
            )
            return server(chunkMap, pendingTasks = 3)
        }

        fun sample(server: Any): Any = sampleMethod.invoke(null, server)

        fun quiescent(snapshot: Any): Boolean =
            quiescentMethod.invoke(null, snapshot) as Boolean

        fun setNumber(snapshot: Any, name: String, value: Long) {
            jsonObjectClass
                .getMethod(
                    "addProperty",
                    String::class.java,
                    Number::class.java,
                )
                .invoke(snapshot, name, value)
        }

        fun number(snapshot: Any, name: String): Long {
            val element = jsonObjectClass
                .getMethod("get", String::class.java)
                .invoke(snapshot, name)
            return jsonElementClass.getMethod("getAsLong").invoke(element) as Long
        }

        fun blockerCount(snapshot: Any): Int {
            val blockers = jsonObjectClass
                .getMethod("get", String::class.java)
                .invoke(snapshot, "blockers")
            return blockers.javaClass.getMethod("size").invoke(blockers) as Int
        }

        override fun close() {
            loader.close()
            root.toFile().deleteRecursively()
        }
    }
}
