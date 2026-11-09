package com.modporter.transforms

import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateClientPlayerDistLinkage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.URLClassLoader
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateClientPlayerDistLinkageMigrationTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `client fieldref moves out of common owner while server and client semantics survive`() {
        val legacy = legacyBacktank()
        val changes = mutableListOf<Change>()
        val migrated = migrate(legacy, changes)

        assertNotEquals(legacy, migrated)
        assertEquals(1, changes.size)
        assertEquals(
            "struct-client-player-dist-linkage",
            changes.single().ruleId
        )
        assertEquals(Confidence.HIGH, changes.single().confidence)
        assertFalse(
            migrated.contains("import net.minecraft.client.Minecraft;"),
            migrated
        )
        assertEquals(
            3,
            Regex("""ModPorterClientPlayerAccess\.get\(\)""")
                .findAll(migrated)
                .count(),
            migrated
        )
        assertTrue(
            migrated.contains(
                "private static final class ModPorterClientPlayerAccess"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "private static net.minecraft.world.entity.player.Player get()"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "return net.minecraft.client.Minecraft.getInstance().player;"
            ),
            migrated
        )
        assertEquals(migrated, migrate(migrated))

        val legacyRoot = directory.resolve("legacy")
        val legacyClasses = legacyRoot.resolve("classes").createDirectories()
        compileJava(
            commonSources(legacyRoot) + listOf(
                writeJava(
                    legacyRoot,
                    BACKTANK_PATH,
                    legacy
                )
            ),
            legacyClasses
        )
        val legacyOuterRefs = fieldRefs(
            legacyClasses.resolve(BACKTANK_CLASS).readBytes()
        )
        assertTrue(
            legacyOuterRefs.contains(
                FieldRef(
                    owner = "net/minecraft/client/Minecraft",
                    name = "player",
                    descriptor =
                        "Lnet/minecraft/client/player/LocalPlayer;"
                )
            ),
            legacyOuterRefs.joinToString("\n")
        )

        val migratedRoot = directory.resolve("migrated")
        val migratedClasses = migratedRoot.resolve("classes").createDirectories()
        compileJava(
            commonSources(migratedRoot) +
                listOf(
                    writeJava(migratedRoot, BACKTANK_PATH, migrated)
                ) +
                runtimeSources(migratedRoot),
            migratedClasses
        )

        val migratedOuterRefs = fieldRefs(
            migratedClasses.resolve(BACKTANK_CLASS).readBytes()
        )
        assertTrue(
            migratedOuterRefs.none {
                it.owner.startsWith("net/minecraft/client/")
            },
            migratedOuterRefs.joinToString("\n")
        )
        assertTrue(
            migratedOuterRefs.none {
                "net/minecraft/client/player/LocalPlayer" in it.descriptor
            },
            migratedOuterRefs.joinToString("\n")
        )

        val holderRefs = fieldRefs(
            migratedClasses.resolve(BACKTANK_HOLDER_CLASS).readBytes()
        )
        assertTrue(
            holderRefs.contains(
                FieldRef(
                    owner = "net/minecraft/client/Minecraft",
                    name = "player",
                    descriptor =
                        "Lnet/minecraft/client/player/LocalPlayer;"
                )
            ),
            holderRefs.joinToString("\n")
        )

        val deniedClientLoads = mutableListOf<String>()
        val serverLoader = object : URLClassLoader(
            arrayOf(migratedClasses.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        ) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("net.minecraft.client.")) {
                    deniedClientLoads += name
                    throw ClassNotFoundException(
                        "Dedicated-server fixture rejects $name"
                    )
                }
                return super.loadClass(name, resolve)
            }
        }
        serverLoader.use { loader ->
            assertEquals(
                "passed",
                loader.loadClass("probe.ServerProbe")
                    .getMethod("verify")
                    .invoke(null)
            )
        }
        assertTrue(
            deniedClientLoads.isEmpty(),
            "Common init or server methods attempted client loads: " +
                deniedClientLoads.joinToString()
        )

        URLClassLoader(
            arrayOf(migratedClasses.toUri().toURL()),
            ClassLoader.getPlatformClassLoader()
        ).use { loader ->
            assertEquals(
                "passed",
                loader.loadClass("probe.ClientProbe")
                    .getMethod("verify")
                    .invoke(null)
            )
        }
    }

    @Test
    fun `ambiguous types changed gates and mixed owners fail closed transactionally`() {
        val legacy = legacyBacktank()
        val rejected = linkedMapOf(
            "var local" to legacy.replace(
                "Player player =",
                "var player ="
            ),
            "LocalPlayer local" to legacy
                .replace(
                    "import net.minecraft.client.Minecraft;",
                    """
                        import net.minecraft.client.Minecraft;
                        import net.minecraft.client.player.LocalPlayer;
                    """.trimIndent()
                )
                .replace("Player player =", "LocalPlayer player ="),
            "wrong Minecraft owner" to legacy.replace(
                "import net.minecraft.client.Minecraft;",
                "import example.Minecraft;"
            ),
            "side effect in gate" to legacy.replace(
                "FMLLoader.getDist() == Dist.CLIENT",
                "audit() && FMLLoader.getDist() == Dist.CLIENT"
            ),
            "one different dist" to legacy.replaceFirst(
                "FMLLoader.getDist() == Dist.CLIENT",
                "FMLLoader.getDist() == Dist.DEDICATED_SERVER"
            ),
            "one non-null fallback" to legacy.replaceFirst(
                ": null)",
                ": new Player())"
            ),
            "existing holder" to legacy.replace(
                "public final class BacktankUtil {",
                """
                    public final class BacktankUtil {
                        private static final class ModPorterClientPlayerAccess {
                        }
                """.trimIndent()
            ),
            "unrelated Minecraft use" to legacy.replace(
                "public final class BacktankUtil {",
                """
                    public final class BacktankUtil {
                        public static Object leakMinecraft() {
                            return Minecraft.getInstance();
                        }
                """.trimIndent()
            )
        )

        for ((label, source) in rejected) {
            val changes = mutableListOf<Change>()
            assertEquals(
                source,
                migrate(source, changes),
                label
            )
            assertTrue(changes.isEmpty(), label)
        }
    }

    private fun migrate(
        source: String,
        changes: MutableList<Change> = mutableListOf()
    ): String = StructuralRefactorPass().migrateClientPlayerDistLinkage(
        source,
        Path.of("BacktankUtil.java"),
        changes
    )

    private fun commonSources(root: Path): List<Path> = listOf(
        writeJava(
            root,
            "net/minecraft/world/entity/LivingEntity.java",
            """
                package net.minecraft.world.entity;
                public class LivingEntity {
                }
            """
        ),
        writeJava(
            root,
            "net/minecraft/world/entity/player/Player.java",
            """
                package net.minecraft.world.entity.player;
                import net.minecraft.world.entity.LivingEntity;
                public class Player extends LivingEntity {
                    public final int marker;
                    public Player() {
                        this(0);
                    }
                    public Player(int marker) {
                        this.marker = marker;
                    }
                }
            """
        ),
        writeJava(
            root,
            "net/minecraft/world/item/ItemStack.java",
            """
                package net.minecraft.world.item;
                public final class ItemStack {
                    public final String id;
                    public ItemStack(String id) {
                        this.id = id;
                    }
                }
            """
        ),
        writeJava(
            root,
            "net/minecraft/client/player/LocalPlayer.java",
            """
                package net.minecraft.client.player;
                import net.minecraft.world.entity.player.Player;
                public final class LocalPlayer extends Player {
                    public LocalPlayer(int marker) {
                        super(marker);
                    }
                }
            """
        ),
        writeJava(
            root,
            "net/minecraft/client/Minecraft.java",
            """
                package net.minecraft.client;
                import net.minecraft.client.player.LocalPlayer;
                public final class Minecraft {
                    public static final Minecraft INSTANCE = new Minecraft();
                    public static int calls;
                    public LocalPlayer player;
                    private Minecraft() {
                    }
                    public static Minecraft getInstance() {
                        calls++;
                        return INSTANCE;
                    }
                }
            """
        ),
        writeJava(
            root,
            "net/neoforged/api/distmarker/Dist.java",
            """
                package net.neoforged.api.distmarker;
                public enum Dist {
                    CLIENT,
                    DEDICATED_SERVER
                }
            """
        ),
        writeJava(
            root,
            "net/neoforged/fml/loading/FMLLoader.java",
            """
                package net.neoforged.fml.loading;
                import net.neoforged.api.distmarker.Dist;
                public final class FMLLoader {
                    public static Dist DIST = Dist.DEDICATED_SERVER;
                    private FMLLoader() {
                    }
                    public static Dist getDist() {
                        return DIST;
                    }
                }
            """
        )
    )

    private fun runtimeSources(root: Path): List<Path> = listOf(
        writeJava(
            root,
            "com/simibubi/create/foundation/events/EventBus.java",
            """
                package com.simibubi.create.foundation.events;
                public final class EventBus {
                    public static int registrations;
                    private EventBus() {
                    }
                    public static void register(String id) {
                        if (!"curios".equals(id))
                            throw new AssertionError("event bus id changed");
                        registrations++;
                    }
                }
            """
        ),
        writeJava(
            root,
            "com/simibubi/create/foundation/events/InterModComms.java",
            """
                package com.simibubi.create.foundation.events;
                public final class InterModComms {
                    public static int sends;
                    private InterModComms() {
                    }
                    public static void send(String id) {
                        if (!"curios".equals(id))
                            throw new AssertionError("IMC id changed");
                        sends++;
                    }
                }
            """
        ),
        writeJava(
            root,
            "com/simibubi/create/content/equipment/goggles/GogglesItem.java",
            """
                package com.simibubi.create.content.equipment.goggles;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.function.Predicate;
                import net.minecraft.world.entity.player.Player;
                public final class GogglesItem {
                    public static final List<Predicate<Player>> PREDICATES =
                        new ArrayList<>();
                    private GogglesItem() {
                    }
                    public static void addIsWearingPredicate(
                            Predicate<Player> predicate) {
                        PREDICATES.add(predicate);
                    }
                }
            """
        ),
        writeJava(
            root,
            "com/simibubi/create/compat/curios/Curios.java",
            """
                package com.simibubi.create.compat.curios;
                import com.simibubi.create.content.equipment.armor.BacktankUtil;
                import com.simibubi.create.content.equipment.goggles.GogglesItem;
                import com.simibubi.create.foundation.events.EventBus;
                import com.simibubi.create.foundation.events.InterModComms;
                import net.minecraft.world.item.ItemStack;
                public final class Curios {
                    public static int inits;
                    private Curios() {
                    }
                    public static void init() {
                        inits++;
                        EventBus.register("curios");
                        InterModComms.send("curios");
                        GogglesItem.addIsWearingPredicate(player -> true);
                        BacktankUtil.addBacktankSupplier(
                            entity -> new ItemStack("backtank"));
                    }
                }
            """
        ),
        writeJava(
            root,
            "probe/ServerProbe.java",
            """
                package probe;
                import com.simibubi.create.compat.curios.Curios;
                import com.simibubi.create.content.equipment.armor.BacktankUtil;
                import com.simibubi.create.content.equipment.goggles.GogglesItem;
                import com.simibubi.create.foundation.events.EventBus;
                import com.simibubi.create.foundation.events.InterModComms;
                import net.minecraft.world.entity.LivingEntity;
                import net.minecraft.world.entity.player.Player;
                import net.minecraft.world.item.ItemStack;
                import net.neoforged.api.distmarker.Dist;
                import net.neoforged.fml.loading.FMLLoader;
                public final class ServerProbe {
                    private ServerProbe() {
                    }
                    public static String verify() {
                        FMLLoader.DIST = Dist.DEDICATED_SERVER;
                        Curios.init();
                        if (Curios.inits != 1)
                            throw new AssertionError("Curios common init removed");
                        if (EventBus.registrations != 1)
                            throw new AssertionError("event bus registration removed");
                        if (InterModComms.sends != 1)
                            throw new AssertionError("IMC registration removed");
                        if (GogglesItem.PREDICATES.size() != 1 ||
                                !GogglesItem.PREDICATES.get(0)
                                    .test(new Player(3)))
                            throw new AssertionError("goggles predicate changed");
                        if (BacktankUtil.supplierCount() != 1)
                            throw new AssertionError("backtank callback removed");
                        ItemStack supplied =
                            BacktankUtil.resolve(new LivingEntity());
                        if (supplied == null || !"backtank".equals(supplied.id))
                            throw new AssertionError("backtank callback changed");

                        ItemStack stack = new ItemStack("probe");
                        if (BacktankUtil.isBarVisible(stack, 7))
                            throw new AssertionError("server player was non-null");
                        if (BacktankUtil.lastPlayer != null)
                            throw new AssertionError("server visible leaked player");
                        if (BacktankUtil.getBarWidth(stack, 7) != 0)
                            throw new AssertionError("server width fallback changed");
                        if (BacktankUtil.lastPlayer != null)
                            throw new AssertionError("server width leaked player");
                        if (BacktankUtil.getBarColor(stack, 7) != 0xFFFFFF)
                            throw new AssertionError("server color fallback changed");
                        if (BacktankUtil.lastPlayer != null)
                            throw new AssertionError("server color leaked player");
                        return "passed";
                    }
                }
            """
        ),
        writeJava(
            root,
            "probe/ClientProbe.java",
            """
                package probe;
                import com.simibubi.create.content.equipment.armor.BacktankUtil;
                import net.minecraft.client.Minecraft;
                import net.minecraft.client.player.LocalPlayer;
                import net.minecraft.world.entity.player.Player;
                import net.minecraft.world.item.ItemStack;
                import net.neoforged.api.distmarker.Dist;
                import net.neoforged.fml.loading.FMLLoader;
                public final class ClientProbe {
                    private static final ItemStack STACK =
                        new ItemStack("probe");
                    private ClientProbe() {
                    }
                    private static void seed(LocalPlayer player) {
                        Minecraft.INSTANCE.player = player;
                        Minecraft.calls = 0;
                        BacktankUtil.lastPlayer = new Player(-1);
                    }
                    private static void exact(
                            LocalPlayer expected, String method) {
                        if (BacktankUtil.lastPlayer != expected)
                            throw new AssertionError(
                                method + " changed player identity/null");
                        if (Minecraft.calls != 1)
                            throw new AssertionError(
                                method + " called getInstance " +
                                    Minecraft.calls + " times");
                    }
                    public static String verify() {
                        FMLLoader.DIST = Dist.CLIENT;
                        LocalPlayer player = new LocalPlayer(41);

                        seed(player);
                        if (!BacktankUtil.isBarVisible(STACK, 5))
                            throw new AssertionError("visible result changed");
                        exact(player, "isBarVisible/player");
                        seed(null);
                        if (BacktankUtil.isBarVisible(STACK, 5))
                            throw new AssertionError("visible null changed");
                        exact(null, "isBarVisible/null");

                        seed(player);
                        if (BacktankUtil.getBarWidth(STACK, 5) != 46)
                            throw new AssertionError("width result changed");
                        exact(player, "getBarWidth/player");
                        seed(null);
                        if (BacktankUtil.getBarWidth(STACK, 5) != 0)
                            throw new AssertionError("width null changed");
                        exact(null, "getBarWidth/null");

                        seed(player);
                        if (BacktankUtil.getBarColor(STACK, 5) != (41 ^ 5))
                            throw new AssertionError("color result changed");
                        exact(player, "getBarColor/player");
                        seed(null);
                        if (BacktankUtil.getBarColor(STACK, 5) != 0xFFFFFF)
                            throw new AssertionError("color null changed");
                        exact(null, "getBarColor/null");
                        return "passed";
                    }
                }
            """
        )
    )

    private fun legacyBacktank(): String = """
        package com.simibubi.create.content.equipment.armor;

        import java.util.ArrayList;
        import java.util.List;
        import java.util.function.Function;

        import net.minecraft.client.Minecraft;
        import net.minecraft.world.entity.LivingEntity;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.item.ItemStack;
        import net.neoforged.api.distmarker.Dist;
        import net.neoforged.fml.loading.FMLLoader;

        public final class BacktankUtil {
            private static final List<Function<LivingEntity, ItemStack>>
                BACKTANK_SUPPLIERS = new ArrayList<>();
            public static Player lastPlayer;

            private BacktankUtil() {
            }

            public static void addBacktankSupplier(
                    Function<LivingEntity, ItemStack> supplier) {
                BACKTANK_SUPPLIERS.add(supplier);
            }

            public static int supplierCount() {
                return BACKTANK_SUPPLIERS.size();
            }

            public static ItemStack resolve(LivingEntity entity) {
                if (BACKTANK_SUPPLIERS.isEmpty())
                    return null;
                return BACKTANK_SUPPLIERS.get(0).apply(entity);
            }

            public static boolean isBarVisible(
                    ItemStack stack, int usesPerTank) {
                Player player =
                    (FMLLoader.getDist() == Dist.CLIENT
                        ? Minecraft.getInstance().player : null);
                lastPlayer = player;
                return player != null;
            }

            public static int getBarWidth(
                    ItemStack stack, int usesPerTank) {
                Player player =
                    (FMLLoader.getDist() == Dist.CLIENT
                        ? Minecraft.getInstance().player : null);
                lastPlayer = player;
                return player == null ? 0 : player.marker + usesPerTank;
            }

            public static int getBarColor(
                    ItemStack stack, int usesPerTank) {
                Player player =
                    (FMLLoader.getDist() == Dist.CLIENT
                        ? Minecraft.getInstance().player : null);
                lastPlayer = player;
                return player == null ? 0xFFFFFF : player.marker ^ usesPerTank;
            }
        }
    """.trimIndent()

    private fun writeJava(
        root: Path,
        relative: String,
        source: String
    ): Path {
        val path = root.resolve("sources").resolve(relative)
        path.parent.createDirectories()
        path.writeText(source.trimIndent() + "\n")
        return path
    }

    private fun compileJava(sources: List<Path>, output: Path) {
        val diagnostics = ByteArrayOutputStream()
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("JDK compiler is required for client-boundary semantics")
        val result = compiler.run(
            null,
            diagnostics,
            diagnostics,
            "-proc:none",
            "-d",
            output.toString(),
            *sources.map(Path::toString).toTypedArray()
        )
        assertEquals(0, result, diagnostics.toString(Charsets.UTF_8))
    }

    private fun fieldRefs(bytes: ByteArray): List<FieldRef> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertEquals(0xCAFEBABE.toInt(), input.readInt())
            input.readUnsignedShort()
            input.readUnsignedShort()
            val count = input.readUnsignedShort()
            val tags = IntArray(count)
            val first = IntArray(count)
            val second = IntArray(count)
            val utf8 = arrayOfNulls<String>(count)
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte()
                tags[index] = tag
                when (tag) {
                    1 -> utf8[index] = input.readUTF()
                    3, 4 -> input.readInt()
                    5, 6 -> {
                        input.readLong()
                        index++
                    }
                    7, 8, 16, 19, 20 -> {
                        first[index] = input.readUnsignedShort()
                    }
                    9, 10, 11, 12, 17, 18 -> {
                        first[index] = input.readUnsignedShort()
                        second[index] = input.readUnsignedShort()
                    }
                    15 -> {
                        first[index] = input.readUnsignedByte()
                        second[index] = input.readUnsignedShort()
                    }
                    else -> error(
                        "Unsupported classfile constant-pool tag $tag"
                    )
                }
                index++
            }
            buildList {
                for (entry in 1 until count) {
                    if (tags[entry] != 9) continue
                    val classEntry = first[entry]
                    val nameAndTypeEntry = second[entry]
                    check(tags[classEntry] == 7)
                    check(tags[nameAndTypeEntry] == 12)
                    add(
                        FieldRef(
                            owner = utf8[first[classEntry]]
                                ?: error("missing field owner"),
                            name = utf8[first[nameAndTypeEntry]]
                                ?: error("missing field name"),
                            descriptor = utf8[second[nameAndTypeEntry]]
                                ?: error("missing field descriptor")
                        )
                    )
                }
            }
        }

    private data class FieldRef(
        val owner: String,
        val name: String,
        val descriptor: String
    )

    private companion object {
        const val BACKTANK_PATH =
            "com/simibubi/create/content/equipment/armor/BacktankUtil.java"
        const val BACKTANK_CLASS =
            "com/simibubi/create/content/equipment/armor/BacktankUtil.class"
        const val BACKTANK_HOLDER_CLASS =
            "com/simibubi/create/content/equipment/armor/" +
                "BacktankUtil${'$'}ModPorterClientPlayerAccess.class"
    }
}
