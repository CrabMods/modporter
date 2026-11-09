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

class NbtProviderCompilerTrancheTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `detached tank serialization receives the caller provider before the world callback`() {
        val pipe = write("src/main/java/example/OpenEndedPipe.java", """
            package example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
            class OpenEndedPipe {
                private Level world;
                private OpenEndFluidHandler fluidHandler = new OpenEndFluidHandler();
                public void manageSource(Level world) { this.world = world; }
                public CompoundTag serializeNBT() {
                    CompoundTag tag = new CompoundTag();
                    fluidHandler.writeToNBT(tag);
                    return tag;
                }
                private class OpenEndFluidHandler extends FluidTank {
                    OpenEndFluidHandler() { super(1000); }
                }
            }
        """.trimIndent())
        val caller = write("src/main/java/example/Caller.java", """
            package example;
            import java.util.Optional;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;
            public class Caller {
                public static CompoundTag verify(HolderLookup.Provider registries) {
                    Optional<Object> source = Optional.of(new OpenEndedPipe());
                    return ((OpenEndedPipe) source.get()).serializeNBT();
                }
            }
        """.trimIndent())
        val bound = write("src/main/java/example/BoundTankOwner.java", """
            package example;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
            class BoundTankOwner {
                private Level world;
                private FluidTank tank = new FluidTank(1000);
                BoundTankOwner(Level world) { this.world = world; }
                CompoundTag serializeNBT() {
                    CompoundTag tag = new CompoundTag();
                    tank.writeToNBT(tag);
                    return tag;
                }
            }
        """.trimIndent())
        val pass = StructuralRefactorPass()
        val files = listOf(pipe, caller, bound)
        val inheritance = with(pass) { collectJavaInheritanceIndex(files) }
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        val methods = with(pass) { collectItemStackNbtMethodsNeedingHolderLookup(files, inheritance) }
        assertTrue(methods.any { it.owner == "OpenEndedPipe" && it.methodName == "serializeNBT" }, methods.toString())
        assertTrue(methods.any { it.owner == "BoundTankOwner" }, methods.toString())
        for (file in files) {
            file.writeText(with(pass) {
                migrateFluidTankNbtProviderCalls(
                    migrateProjectItemStackNbtMethods(file.readText(), methods, inheritance), inheritance
                )
            })
        }
        assertTrue(pipe.readText().contains("writeToNBT(registries, tag)"), pipe.readText())
        assertFalse(pipe.readText().contains("world.registryAccess()"), pipe.readText())
        assertTrue(caller.readText().contains("serializeNBT(registries)"), caller.readText())
        assertTrue(bound.readText().contains("serializeNBT(HolderLookup.Provider registries)"), bound.readText())
        assertTrue(bound.readText().contains("writeToNBT(registries, tag)"), bound.readText())
        assertFalse(bound.readText().contains("world.registryAccess()"), bound.readText())
        val stubs = listOf(
            write("compiler-stubs/net/minecraft/core/HolderLookup.java", """
                package net.minecraft.core;
                public interface HolderLookup { interface Provider {} }
            """.trimIndent()),
            write("compiler-stubs/net/minecraft/nbt/CompoundTag.java", """
                package net.minecraft.nbt;
                public class CompoundTag { public boolean written; }
            """.trimIndent()),
            write("compiler-stubs/net/minecraft/world/level/Level.java", """
                package net.minecraft.world.level;
                import net.minecraft.core.HolderLookup;
                public class Level {
                    public HolderLookup.Provider registryAccess() { throw new AssertionError("Deferred world accessed"); }
                }
            """.trimIndent()),
            write("compiler-stubs/net/neoforged/neoforge/fluids/capability/templates/FluidTank.java", """
                package net.neoforged.neoforge.fluids.capability.templates;
                import net.minecraft.core.HolderLookup;
                import net.minecraft.nbt.CompoundTag;
                public class FluidTank {
                    public FluidTank(int capacity) {}
                    public CompoundTag writeToNBT(HolderLookup.Provider registries, CompoundTag tag) {
                        if (registries == null) throw new AssertionError("Missing caller provider");
                        tag.written = true;
                        return tag;
                    }
                }
            """.trimIndent())
        )
        val classes = tempDir.resolve("compiler-classes").createDirectories()
        val compiler = requireNotNull(javax.tools.ToolProvider.getSystemJavaCompiler())
        val diagnostics = javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { manager ->
            val units = manager.getJavaFileObjectsFromFiles((files + stubs).map { it.toFile() })
            val success = compiler.getTask(null, manager, diagnostics,
                listOf("-d", classes.toString(), "-classpath", classes.toString(), "-proc:none"), null, units).call()
            assertTrue(success, diagnostics.diagnostics.joinToString("\n"))
        }
        java.net.URLClassLoader(arrayOf(classes.toUri().toURL()), null).use { loader ->
            val providerType = loader.loadClass("net.minecraft.core.HolderLookup\$Provider")
            val provider = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(providerType)) { _, _, _ -> null }
            val value = loader.loadClass("example.Caller").getMethod("verify", providerType).invoke(null, provider)
            assertTrue(value.javaClass.getField("written").getBoolean(value))
        }
    }

    @Test
    fun `map values entry values generic bounds and qualified interface super keep the real provider`() {
        val target = write(
            "src/main/java/example/Targets.java",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            interface ClipboardContract {
                default boolean writeToClipboard(CompoundTag tag) {
                    return true;
                }
            }

            class Target {
                CompoundTag write() {
                    return new CompoundTag();
                }

                void read(CompoundTag tag) {}
            }

            class Manager {
                java.util.Map<String, Target> targets;
            }
            """.trimIndent()
        )
        val surface = write(
            "src/main/java/example/Surface.java",
            """
            package example;

            import java.util.Map;
            import net.createmod.catnip.nbt.NBTHelper;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.nbt.CompoundTag;

            class Surface<T extends Target> implements ClipboardContract {
                Manager manager;
                Map<String, Target> localTargets;

                void save(CompoundTag tag, HolderLookup.Provider registries) {
                    NBTHelper.writeCompoundList(manager.targets.values(), value -> value.write());
                    NBTHelper.writeCompoundList(localTargets.entrySet(), entry -> entry.getValue().write());
                    T point = null;
                    point.read(tag);
                    ClipboardContract.super.writeToClipboard(tag);
                }
            }
            """.trimIndent()
        )

        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(target, surface))
        val migrated = with(pass) {
            migrateProjectItemStackNbtMethods(
                surface.readText(),
                setOf(
                    HolderLookupNbtMethod("Target", "write", 0),
                    HolderLookupNbtMethod("Target", "read", 1),
                    HolderLookupNbtMethod("ClipboardContract", "writeToClipboard", 1)
                )
            )
        }

        assertTrue(migrated.contains("value.write(registries)"), migrated)
        assertTrue(migrated.contains("entry.getValue().write(registries)"), migrated)
        assertTrue(migrated.contains("point.read(tag, registries)"), migrated)
        assertTrue(migrated.contains("ClipboardContract.super.writeToClipboard(tag, registries)"), migrated)
    }

    @Test
    fun `map contracts require canonical jdk owners and preserve project method semantics`() {
        val file = write(
            "src/main/java/example/Shadows.java",
            """
            package example;

            class Map<K, V> {
                Object values() {
                    return null;
                }
            }

            class Entry<K, V> {
                Object getValue() {
                    return null;
                }
            }

            class Shadows {
                Map<String, Target> map;
                Entry<String, Target> entry;
                java.util.Map rawMap;
                java.util.Map<String, ?> unknownValues;

                void probe() {
                    map.values();
                    entry.getValue();
                    rawMap.values();
                    unknownValues.values();
                }
            }

            class Target {}
            """.trimIndent()
        )
        val source = file.readText()
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(file))
        val values = source.indexOf("map.values()")
        val entry = source.indexOf("entry.getValue()")
        val raw = source.indexOf("rawMap.values()")
        val unknown = source.indexOf("unknownValues.values()")
        val types = SourceValueTypes(pass, source)

        assertEquals("java.lang.Object", types.typeOf("map.values()", values))
        assertEquals("java.lang.Object", types.typeOf("entry.getValue()", entry))
        assertEquals(null, types.iterableElementType("rawMap.values()", raw))
        assertEquals(null, types.iterableElementType("unknownValues.values()", unknown))
    }

    @Test
    fun `negated pattern guard exposes the bound project receiver after its early return`() {
        val file = write(
            "src/main/java/example/TableClothBlock.java",
            """
            package example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.level.storage.loot.LootParams;

            class AutoRequestData {
                void writeToItem(BlockPos pos, ItemStack stack) {}
            }

            class TableClothBlockEntity {
                AutoRequestData requestData;
                BlockPos getBlockPos() { return null; }
            }

            class TableClothBlock {
                void drops(LootParams.Builder params, ItemStack drop) {
                    if (!(params.getOptionalParameter(null) instanceof TableClothBlockEntity dcbe))
                        return;
                    dcbe.requestData.writeToItem(dcbe.getBlockPos(), drop);
                }
            }
            """.trimIndent()
        )
        val source = file.readText()
        val pass = StructuralRefactorPass()
        pass.projectValueFacts = ProjectValueFacts.collect(pass, listOf(file))
        val call = source.indexOf("dcbe.requestData.writeToItem")
        val values = SourceValueTypes(pass, source)

        assertEquals("example.AutoRequestData", values.typeOf("dcbe.requestData", call))

        val migrated = with(pass) {
            migrateProjectItemStackNbtMethods(
                source,
                setOf(HolderLookupNbtMethod("AutoRequestData", "writeToItem", 2))
            )
        }
        assertTrue(
            migrated.contains(
                "dcbe.requestData.writeToItem(dcbe.getBlockPos(), drop, params.getLevel().registryAccess())"
            ),
            migrated
        )
    }

    @Test
    fun `nested source proven fluid tank subtype uses the caller level provider`() {
        val pipe = write(
            "src/main/java/example/OpenEndedPipe.java",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

            class OpenEndedPipe {
                private OpenEndFluidHandler fluidHandler;

                static OpenEndedPipe fromNBT(CompoundTag compound, int position) {
                    OpenEndedPipe pipe = new OpenEndedPipe();
                    pipe.fluidHandler.readFromNBT(compound);
                    return pipe;
                }

                private class OpenEndFluidHandler extends FluidTank {
                    OpenEndFluidHandler() {
                        super(1000);
                    }
                }
            }
            """.trimIndent()
        )
        val caller = write(
            "src/main/java/example/PipeCaller.java",
            """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;

            class PipeCaller {
                OpenEndedPipe read(Level level, CompoundTag tag) {
                    return OpenEndedPipe.fromNBT(tag, 1);
                }
            }
            """.trimIndent()
        )
        val pass = StructuralRefactorPass()
        val files = listOf(pipe, caller)
        val inheritance = with(pass) { collectJavaInheritanceIndex(files) }
        pass.projectValueFacts = ProjectValueFacts.collect(pass, files)
        assertEquals(
            setOf("fluidHandler"),
            with(pass) { collectSourceProvenFluidTankValueNames(pipe.readText(), inheritance) }
        )
        assertTrue(
            with(pass) {
                collectSourceProvenFluidTankValueNames(
                    """
                    import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
                    class OpenEndFluidHandler extends FluidTank {}
                    """.trimIndent(),
                    inheritance
                )
            }.isEmpty()
        )
        assertTrue(
            with(pass) {
                collectSourceProvenFluidTankValueNames(
                    """
                    class FluidTank {}
                    class ProjectTank extends FluidTank {}
                    class Holder { private ProjectTank tank; }
                    """.trimIndent(),
                    collectJavaInheritanceIndex(files)
                )
            }.isEmpty()
        )
        val methods = with(pass) {
            collectItemStackNbtMethodsNeedingHolderLookup(files, inheritance)
        }

        assertTrue(
            methods.any {
                it.owner == "OpenEndedPipe" &&
                    it.methodName == "fromNBT" &&
                    it.parameterCount == 2
            },
            methods.toString()
        )

        val widenedPipe = with(pass) {
            migrateProjectItemStackNbtMethods(pipe.readText(), methods, inheritance)
        }
        val migratedPipe = with(pass) {
            migrateFluidTankNbtProviderCalls(widenedPipe, inheritance)
        }
        val migratedCaller = with(pass) {
            migrateProjectItemStackNbtMethods(caller.readText(), methods, inheritance)
        }

        assertTrue(
            migratedPipe.contains("import net.minecraft.core.HolderLookup;"),
            migratedPipe
        )
        assertTrue(
            migratedPipe.contains(
                "static OpenEndedPipe fromNBT(CompoundTag compound, int position, HolderLookup.Provider registries)"
            ),
            migratedPipe
        )
        assertTrue(
            migratedPipe.contains("pipe.fluidHandler.readFromNBT(registries, compound)"),
            migratedPipe
        )
        assertFalse(
            migratedCaller.contains("import net.minecraft.core.HolderLookup;"),
            migratedCaller
        )
        assertTrue(
            migratedCaller.contains("OpenEndedPipe read(Level level, CompoundTag tag)"),
            migratedCaller
        )
        assertTrue(
            migratedCaller.contains("OpenEndedPipe.fromNBT(tag, 1, level.registryAccess())"),
            migratedCaller
        )
        assertFalse(migratedCaller.contains("HolderLookup.Provider registries"), migratedCaller)
    }

    @Test
    fun `fluid tank provider migration is idempotent when the provider expression is a call`() {
        val source = """
            package example;

            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

            class TankOwner {
                private FluidTank fluidHandler;

                void transfer(
                    Level world,
                    CompoundTag missingWrite,
                    CompoundTag missingRead,
                    CompoundTag readyWrite,
                    CompoundTag readyRead
                ) {
                    fluidHandler.writeToNBT(missingWrite);
                    fluidHandler.readFromNBT(missingRead);
                    fluidHandler.writeToNBT(world.registryAccess(), readyWrite);
                    fluidHandler.readFromNBT(world.registryAccess(), readyRead);
                }
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        val migrated = with(pass) { migrateFluidTankNbtProviderCalls(source) }
        val replayed = with(pass) { migrateFluidTankNbtProviderCalls(migrated) }

        assertTrue(
            migrated.contains("fluidHandler.writeToNBT(world.registryAccess(), missingWrite)"),
            migrated
        )
        assertTrue(
            migrated.contains("fluidHandler.readFromNBT(world.registryAccess(), missingRead)"),
            migrated
        )
        assertTrue(
            migrated.contains("fluidHandler.writeToNBT(world.registryAccess(), readyWrite)"),
            migrated
        )
        assertTrue(
            migrated.contains("fluidHandler.readFromNBT(world.registryAccess(), readyRead)"),
            migrated
        )
        assertFalse(migrated.contains("world.registryAccess(), world.registryAccess()"), migrated)
        assertEquals(migrated, replayed)
    }

    @Test
    fun `legacy codec and block state id map buffers keep their wire contracts`() {
        val source = """
            package example;

            import com.mojang.serialization.Codec;
            import java.util.Map;
            import net.minecraft.nbt.NbtOps;
            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.neoforge.registries.GameData;

            class ProjectBuffer {
                <T> T readWithCodec(Object ops, Codec<T> codec) {
                    return null;
                }
            }

            class Packet {
                BlockState state;

                static <T> Map<BlockPos, T> read(FriendlyByteBuf buf, Codec<T> codec) {
                    return buf.readMap(
                        buffer -> buffer.readBlockPos(),
                        b -> b.readWithCodec(NbtOps.INSTANCE, codec));
                }

                static <T> T projectRead(ProjectBuffer buffer, Codec<T> codec) {
                    return buffer.readWithCodec(NbtOps.INSTANCE, codec);
                }

                Packet(RegistryFriendlyByteBuf buffer, Codec<BlockState> codec) {
                    this.state = buffer.readWithCodec(NbtOps.INSTANCE, codec);
                    this.state = buffer.readWithCodec(NbtOps.INSTANCE, codec, null);
                    this.state = buffer.readById(GameData.getBlockStateIDMap());
                }

                void write(RegistryFriendlyByteBuf buffer) {
                    buffer.writeId(GameData.getBlockStateIDMap(), state);
                    String untouched = "buffer.writeId(GameData.getBlockStateIDMap(), state)";
                }
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        val codec = with(pass) { migrateLegacyFriendlyByteBufNbtCodecReads(source) }
        val migrated = with(pass) { migrateLegacyIdMapBufferCalls(codec) }

        assertTrue(
            migrated.contains("buffer.readWithCodecTrusted(NbtOps.INSTANCE, codec)"),
            migrated
        )
        assertTrue(
            migrated.contains("b.readWithCodecTrusted(NbtOps.INSTANCE, codec)"),
            migrated
        )
        assertTrue(
            migrated.contains("buffer -> buffer.readBlockPos()"),
            migrated
        )
        assertTrue(
            migrated.contains("return buffer.readWithCodec(NbtOps.INSTANCE, codec);"),
            migrated
        )
        assertTrue(
            migrated.contains("buffer.readWithCodec(NbtOps.INSTANCE, codec, null)"),
            migrated
        )
        assertTrue(
            migrated.contains("buffer.readById(GameData.getBlockStateIDMap()::byId)"),
            migrated
        )
        assertTrue(
            migrated.contains("if (modporterBlockStateId == -1) throw new IllegalArgumentException("),
            migrated
        )
        assertTrue(
            migrated.contains("return modporterBlockStateId; }, state)"),
            migrated
        )
        assertTrue(
            migrated.contains("\"buffer.writeId(GameData.getBlockStateIDMap(), state)\""),
            migrated
        )
    }

    @Test
    fun `source proven level accessor supplies a widened packet constructor`() {
        val controller = write(
            "src/main/java/example/MinecartController.java",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.world.level.Level;

            class MinecartController {
                void sendData() {
                    if (getWorld().isClientSide)
                        return;
                    new MinecartControllerUpdatePacket(this);
                }

                void sendWithProvider(HolderLookup.Provider registries) {
                    new MinecartControllerUpdatePacket(this);
                }

                private Level getWorld() {
                    return null;
                }
            }
            """.trimIndent()
        )
        write(
            "src/main/java/example/MinecartControllerUpdatePacket.java",
            """
            package example;

            import net.minecraft.core.HolderLookup;
            import net.minecraft.network.RegistryFriendlyByteBuf;

            import java.util.function.Function;

            class MinecartControllerUpdatePacket {
                static final Function<RegistryFriendlyByteBuf, MinecartControllerUpdatePacket> DECODER =
                    MinecartControllerUpdatePacket::new;

                MinecartControllerUpdatePacket(
                    MinecartController controller,
                    HolderLookup.Provider registries
                ) {}

                MinecartControllerUpdatePacket(RegistryFriendlyByteBuf buffer) {}
            }
            """.trimIndent()
        )

        ProviderArityRepair.migrate(StructuralRefactorPass(), tempDir, dryRun = false)
        val migrated = controller.readText()
        val packet = tempDir.resolve("src/main/java/example/MinecartControllerUpdatePacket.java").readText()

        assertTrue(
            migrated.contains(
                "new MinecartControllerUpdatePacket(this, getWorld().registryAccess())"
            ),
            migrated
        )
        assertTrue(
            migrated.contains(
                "new MinecartControllerUpdatePacket(this, registries)"
            ),
            migrated
        )
        assertTrue(packet.contains("MinecartControllerUpdatePacket::new"), packet)
        assertTrue(packet.contains("MinecartControllerUpdatePacket(RegistryFriendlyByteBuf buffer)"), packet)
    }

    @Test
    fun `executable regex exposes original source bytes through masked capture groups`() {
        val source = """
            class Surface {
                void read(CompoundTag nbt) {
                    Tag blocks = nbt.get("Blocks");
                }
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        var matchValue = ""
        var indexedGroups = emptyList<String>()
        var namedCall: MatchGroup? = null
        var namedArgument: MatchGroup? = null
        var destructured = emptyList<String>()
        val maskedOnly = with(pass) {
            replaceExecutableRegex(
                source,
                Regex("""Tag\s+blocks\s*=\s*(?<call>nbt\.get\((?<argument>\s*)\));""")
            ) { match ->
                matchValue = match.value
                indexedGroups = match.groupValues
                namedCall = match.groups["call"]
                namedArgument = match.groups["argument"]
                destructured = match.destructured.toList()
                "Tag blocks = ${match.groupValues[1]};"
            }
        }
        val executable = with(pass) {
            replaceExecutableRegex(source, Regex("""\bTag\s+blocks\b""")) { "Object blocks" }
        }

        assertEquals(source, maskedOnly)
        assertEquals("""Tag blocks = nbt.get("Blocks");""", matchValue)
        assertEquals("""nbt.get("Blocks")""", indexedGroups[1])
        assertEquals(""""Blocks"""", indexedGroups[2])
        assertEquals(indexedGroups.drop(1), destructured)
        assertEquals(indexedGroups[1], namedCall?.value)
        assertEquals(indexedGroups[2], namedArgument?.value)
        assertEquals(
            source.indexOf("""nbt.get("Blocks")""") until source.indexOf("""nbt.get("Blocks")""") + indexedGroups[1].length,
            namedCall?.range
        )
        assertEquals(
            source.indexOf(""""Blocks"""") until source.indexOf(""""Blocks"""") + indexedGroups[2].length,
            namedArgument?.range
        )
        assertTrue(executable.contains("""Object blocks = nbt.get("Blocks");"""), executable)
    }

    @Test
    fun `executable Java regex preserves original bytes for masked only no op matches`() {
        val source = """Tag blocks = nbt.get("DifferentKey");"""
        val pattern = Regex("""Tag\s+blocks\s*=\s*(?<call>nbt\.get\((?<argument>\s*)\));""")
        var call: MatchGroup? = null
        var argument: MatchGroup? = null
        val pass = StructuralRefactorPass()
        val preserved = with(pass) {
            replaceExecutableJavaRegex(
                source,
                pattern
            ) { match ->
                call = match.groups["call"]
                argument = match.groups["argument"]
                match.value
            }
        }
        val explicitlyChanged = with(pass) {
            replaceExecutableJavaRegex(source, pattern) {
                """Tag blocks = nbt.get("Replacement");"""
            }
        }

        assertEquals(source, preserved)
        assertEquals("""nbt.get("DifferentKey")""", call?.value)
        assertEquals(""""DifferentKey"""", argument?.value)
        assertEquals(
            source.indexOf("""nbt.get("DifferentKey")""") until
                source.indexOf("""nbt.get("DifferentKey")""") + call!!.value.length,
            call?.range
        )
        assertEquals(
            source.indexOf(""""DifferentKey"""") until
                source.indexOf(""""DifferentKey"""") + argument!!.value.length,
            argument?.range
        )
        assertEquals("""Tag blocks = nbt.get("Replacement");""", explicitlyChanged)
    }

    @Test
    fun `holder accessor migration preserves NBT keys while migrating real holder access`() {
        val source = """
            package example;

            import java.util.function.Supplier;
            import net.minecraft.core.Holder;
            import net.minecraft.core.HolderGetter;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.Tag;
            import net.minecraft.world.effect.MobEffect;

            class Surface {
                HolderGetter<MobEffect> lookup;

                void read(CompoundTag nbt, Holder<MobEffect> effect) {
                    Tag blocks = nbt.get("Blocks");
                    Tag different = nbt.get("DifferentKey");
                    MobEffect value = effect.get();
                    Supplier<MobEffect> supplier = effect::get;
                    String documentation = "effect.get() and nbt.get(\"Fake\")";
                    // effect.get(); nbt.get("Comment")
                }
            }
        """.trimIndent()
        val expected = """
            package example;

            import java.util.function.Supplier;
            import net.minecraft.core.Holder;
            import net.minecraft.core.HolderGetter;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.nbt.Tag;
            import net.minecraft.world.effect.MobEffect;

            class Surface {
                HolderGetter<MobEffect> lookup;

                void read(CompoundTag nbt, Holder<MobEffect> effect) {
                    Tag blocks = nbt.get("Blocks");
                    Tag different = nbt.get("DifferentKey");
                    MobEffect value = effect.value();
                    Supplier<MobEffect> supplier = effect::value;
                    String documentation = "effect.get() and nbt.get(\"Fake\")";
                    // effect.get(); nbt.get("Comment")
                }
            }
        """.trimIndent()
        val pass = StructuralRefactorPass()
        val migrated = with(pass) { migrateHolderValueAccessors(source) }

        assertEquals(expected, migrated)
        assertEquals(migrated, with(pass) { migrateHolderValueAccessors(migrated) })
    }

    @Test
    fun `packet handlers already using payload context finish direction and sender migration`() {
        write(
            "src/main/java/example/SimplePacketBase.java",
            """
            package example;

            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraftforge.network.NetworkEvent;

            public abstract class SimplePacketBase {
                public abstract void write(FriendlyByteBuf buffer);
                public abstract boolean handle(NetworkEvent.Context context);
            }
            """.trimIndent()
        )
        val packet = write(
            "src/main/java/example/RequestPacket.java",
            """
            package example;

            import net.minecraft.network.FriendlyByteBuf;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraftforge.network.NetworkDirection;
            import net.neoforged.neoforge.network.PacketDistributor;
            import net.neoforged.neoforge.network.handling.IPayloadContext;

            public class RequestPacket extends SimplePacketBase {
                public RequestPacket(FriendlyByteBuf buffer) {}

                @Override
                public void write(FriendlyByteBuf buffer) {}

                @Override
                public boolean handle(IPayloadContext context) {
                    String untouched = "NetworkDirection.PLAY_TO_SERVER context.getSender()";
                    if (context.flow() != NetworkDirection.PLAY_TO_SERVER)
                        return false;
                    Object tracking = PacketDistributor.TRACKING_ENTITY.with(context::getSender);
                    PacketDistributor.sendToPlayersTrackingEntity(
                        context.getSender(), new RequestPacket(null));
                    return true;
                }
            }
            """.trimIndent()
        )

        PacketTablePayloads.migrate(tempDir, "example", dryRun = false)
        val migrated = packet.readText()

        assertTrue(migrated.contains("context.flow() != PacketFlow.SERVERBOUND"), migrated)
        assertTrue(migrated.contains("((ServerPlayer) context.player())"), migrated)
        assertTrue(migrated.contains("PacketDistributor.TRACKING_ENTITY.with(context::player)"), migrated)
        assertTrue(migrated.contains("\"NetworkDirection.PLAY_TO_SERVER context.getSender()\""), migrated)
        assertTrue(migrated.contains("import net.minecraft.network.protocol.PacketFlow;"), migrated)
        assertFalse(migrated.contains("import net.minecraftforge.network.NetworkDirection;"), migrated)
        assertFalse(migrated.contains("context.flow() != NetworkDirection.PLAY_TO_SERVER"), migrated)
        assertFalse(
            com.modporter.core.transforms.util.JavaSourceText.maskCommentsAndLiterals(migrated)
                .contains("context.getSender()"),
            migrated
        )
    }

    private fun write(relative: String, text: String): Path =
        tempDir.resolve(relative).also {
            it.parent.createDirectories()
            it.writeText(text)
        }
}
