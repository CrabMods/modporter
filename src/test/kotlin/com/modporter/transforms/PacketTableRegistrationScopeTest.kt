package com.modporter.transforms

import com.modporter.core.transforms.ast.AstTransformPass
import com.modporter.core.transforms.structural.PacketTablePayloads
import com.modporter.core.transforms.text.TextReplacementPass
import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PacketTableRegistrationScopeTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `registration uses the proven mod bus in the same constructor`() {
        val migrated = migrate(
            """
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.neoforge.common.NeoForge;

            @Mod("example")
            class Example {
                Example(ModContainer container) {
                    IEventBus modBus = container.getEventBus();
                    IEventBus gameBus = NeoForge.EVENT_BUS;
                    AllPackets.registerPackets();
                }

                void unrelated() {
                    IEventBus other = net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext
                        .get().getModEventBus();
                }
            }
            """.trimIndent()
        )

        assertTrue(migrated.contains("modBus.addListener(AllPackets::registerPackets);"), migrated)
        assertFalse(migrated.contains("gameBus.addListener"), migrated)
        assertFalse(migrated.contains("other.addListener"), migrated)
    }

    @Test
    fun `registration does not borrow a bus from another method`() {
        val source = """
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;

            @Mod("example")
            class Example {
                Example() {
                    AllPackets.registerPackets();
                }

                void unrelated() {
                    IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
                }
            }
        """.trimIndent()

        assertTrue(migrate(source).contains("AllPackets.registerPackets();"))
    }

    @Test
    fun `registration rejects shadow source types and annotations`() {
        val source = """
            @interface Mod {
                String value();
            }
            class IEventBus {
                void addListener(Object listener) {}
            }
            class ModContainer {
                IEventBus getEventBus() { return new IEventBus(); }
            }

            @Mod("example")
            class Example {
                Example(ModContainer container) {
                    IEventBus bus = container.getEventBus();
                    AllPackets.registerPackets();
                }
            }
        """.trimIndent()

        assertTrue(migrate(source).contains("AllPackets.registerPackets();"))
    }

    @Test
    fun `registration rejects shadow loading context and a reassigned bus`() {
        val shadowSource = """
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod("example")
            class Example {
                static class FMLJavaModLoadingContext {
                    static FMLJavaModLoadingContext get() {
                        return new FMLJavaModLoadingContext();
                    }
                    IEventBus getModEventBus() { return null; }
                }

                void init() {
                    IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
                    AllPackets.registerPackets();
                }
            }
        """.trimIndent()
        assertTrue(migrate(shadowSource).contains("AllPackets.registerPackets();"))

        val reassignedSource = """
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.neoforge.common.NeoForge;

            @Mod("example")
            class Example {
                Example(ModContainer container) {
                    IEventBus bus = container.getEventBus();
                    bus = NeoForge.EVENT_BUS;
                    AllPackets.registerPackets();
                }
            }
        """.trimIndent()
        assertTrue(migrate(reassignedSource).contains("AllPackets.registerPackets();"))
    }

    @Test
    fun `registration accepts native fully qualified and constructor injected buses`() {
        val loadingContext = migrate(
            """
            @net.neoforged.fml.common.Mod("example")
            class Example {
                void init() {
                    net.neoforged.bus.api.IEventBus bus =
                        net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext
                            .get().getModEventBus();
                    AllPackets.registerPackets();
                }
            }
            """.trimIndent()
        )
        assertTrue(loadingContext.contains("bus.addListener(AllPackets::registerPackets);"), loadingContext)

        val injected = migrate(
            """
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod("example")
            class Example {
                Example(IEventBus modBus) {
                    AllPackets.registerPackets();
                }
            }
            """.trimIndent()
        )
        assertTrue(injected.contains("modBus.addListener(AllPackets::registerPackets);"), injected)
    }

    @Test
    fun `text and AST keep loading context ownership until the real packet table call migrates`() {
        val srcDir = tempDir.resolve("src/main/java/com/simibubi/create")
        srcDir.createDirectories()
        val createFile = srcDir.resolve("Create.java")
        createFile.writeText(
            """
            package com.simibubi.create;

            import net.minecraftforge.common.MinecraftForge;
            import net.minecraftforge.eventbus.api.IEventBus;
            import net.minecraftforge.fml.common.Mod;
            import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

            @Mod(Create.ID)
            public class Create {
                public static final String ID = "create";

                public Create() {
                    onCtor();
                }

                public static void onCtor() {
                    IEventBus modEventBus = FMLJavaModLoadingContext.get()
                        .getModEventBus();
                    IEventBus forgeEventBus = MinecraftForge.EVENT_BUS;
                    AllPackets.registerPackets();
                }
            }
            """.trimIndent()
        )
        srcDir.resolve("SimplePacketBase.java").writeText(
            """
            package com.simibubi.create;

            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraftforge.network.NetworkEvent.Context;

            public abstract class SimplePacketBase {
                public abstract void write(FriendlyByteBuf buffer);
                public abstract boolean handle(Context context);
            }
            """.trimIndent()
        )
        srcDir.resolve("ExamplePacket.java").writeText(
            """
            package com.simibubi.create;

            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraftforge.network.NetworkEvent.Context;

            public class ExamplePacket extends SimplePacketBase {
                public ExamplePacket(FriendlyByteBuf buffer) {}
                public void write(FriendlyByteBuf buffer) {}
                public boolean handle(Context context) { return true; }
            }
            """.trimIndent()
        )
        val tableFile = srcDir.resolve("AllPackets.java")
        tableFile.writeText(
            """
            package com.simibubi.create;

            import static net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER;

            import java.util.function.Function;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraftforge.network.NetworkDirection;
            import net.minecraftforge.network.NetworkRegistry;
            import net.minecraftforge.network.simple.SimpleChannel;

            public enum AllPackets {
                EXAMPLE(ExamplePacket.class, ExamplePacket::new, PLAY_TO_SERVER);

                private static final String NETWORK_VERSION_STR = "1";
                private static SimpleChannel channel;
                private PacketType<?> packetType;

                <T extends SimplePacketBase> AllPackets(
                    Class<T> type,
                    Function<FriendlyByteBuf, T> factory,
                    NetworkDirection direction
                ) {
                    packetType = new PacketType<>(type, factory, direction);
                }

                public static void registerPackets() {
                    channel = NetworkRegistry.ChannelBuilder
                        .named(new net.minecraft.resources.ResourceLocation("create", "main"))
                        .networkProtocolVersion(() -> NETWORK_VERSION_STR)
                        .simpleChannel();
                    for (AllPackets packet : values())
                        packet.packetType.register();
                }

                public static SimpleChannel getChannel() {
                    return channel;
                }

                private static class PacketType<T extends SimplePacketBase> {
                    private PacketType(
                        Class<T> type,
                        Function<FriendlyByteBuf, T> factory,
                        NetworkDirection direction
                    ) {}

                    private void register() {
                        getChannel().messageBuilder(null, 0, null).add();
                    }
                }
            }
            """.trimIndent()
        )

        val mappings = MappingDatabase.loadDefault()
        TextReplacementPass(mappings).apply(tempDir)
        val afterText = createFile.readText()
        assertTrue(
            afterText.contains("import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;"),
            afterText
        )

        AstTransformPass(mappings).apply(tempDir)
        val afterAst = createFile.readText()
        assertTrue(
            afterAst.contains("import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;"),
            afterAst
        )

        PacketTablePayloads.migrate(tempDir, "create", dryRun = false)
        val migratedCreate = createFile.readText()
        val migratedTable = tableFile.readText()
        assertTrue(
            migratedCreate.contains("modEventBus.addListener(AllPackets::registerPackets);"),
            migratedCreate
        )
        assertFalse(migratedCreate.contains("AllPackets.registerPackets();"), migratedCreate)
        assertFalse(
            migratedCreate.contains("forgeEventBus.addListener(AllPackets::registerPackets);"),
            migratedCreate
        )
        assertTrue(
            migratedTable.contains(
                "public static void registerPackets(RegisterPayloadHandlersEvent event)"
            ),
            migratedTable
        )
    }

    @Test
    fun `text and AST ignore loading context mentions in documentation and strings`() {
        val srcDir = tempDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val textFile = srcDir.resolve("TextOnly.java")
        textFile.writeText(
            """
            package com.example;

            import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

            class TextOnly {
                // FMLJavaModLoadingContext is mentioned for migration history.
                String name = "FMLJavaModLoadingContext";
            }
            """.trimIndent()
        )

        val mappings = MappingDatabase.loadDefault()
        TextReplacementPass(mappings).apply(tempDir)
        assertFalse(textFile.readText().contains("import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;"))

        val astFile = srcDir.resolve("AstOnly.java")
        astFile.writeText(
            """
            package com.example;

            import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;

            class AstOnly {
                // FMLJavaModLoadingContext is mentioned for migration history.
                String name = "FMLJavaModLoadingContext";
            }
            """.trimIndent()
        )
        AstTransformPass(mappings).apply(tempDir)
        assertFalse(astFile.readText().contains("import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;"))
    }

    private fun migrate(source: String): String {
        val method = PacketTablePayloads::class.java.getDeclaredMethod(
            "migrateRegistrationCall",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(PacketTablePayloads, source, "AllPackets") as String
    }
}
