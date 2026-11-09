package com.modporter.core.transforms.structural

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LegacyClientSpawnFactoriesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `closed Registrate factory uses the native packet and keeps complex spawn data`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        val accessTransformer = accessTransformer()
        val originalAccessTransformer = accessTransformer.readText()
        val pass = StructuralRefactorPass()

        val dryRun = pass.migrateLegacyClientSpawnFactories(directory, dryRun = true)

        assertEquals(2, dryRun.size, dryRun.toString())
        assertTrue(entity.readText().contains("setCustomClientFactory"))
        assertEquals(originalAccessTransformer, accessTransformer.readText())

        val changes = pass.migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertEquals(2, changes.size, changes.toString())
        val migrated = entity.readText()
        val migratedReadSpawnData = readSpawnDataBlock(migrated)
        assertFalse(
            migrated.contains("net.minecraftforge.network.PlayMessages.SpawnEntity"),
            migrated
        )
        assertFalse(migrated.contains("setCustomClientFactory"), migrated)
        assertFalse(migrated.contains("static ParcelEntity spawn("), migrated)
        assertTrue(
            migrated.contains(
                "import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;"
            ),
            migrated
        )
        assertTrue(migrated.contains("@Override"), migrated)
        assertTrue(
            migrated.contains(
                "public void recreateFromPacket(ClientboundAddEntityPacket packet)"
            ),
            migrated
        )
        assertTrue(
            migrated.contains("setPos(packet.getX(), packet.getY(), packet.getZ());"),
            migrated
        )
        assertTrue(migrated.contains("refreshDimensions();"), migrated)
        assertTrue(migrated.contains("clientPosition = position();"), migrated)
        assertTrue(migrated.contains("super.recreateFromPacket(packet);"), migrated)
        assertTrue(
            migrated.contains("setDeltaMovement(packet.xa, packet.ya, packet.za);"),
            migrated
        )
        assertTrue(
            migrated.contains("private boolean modporterLegacySpawnMotionPending;"),
            migrated
        )
        assertTrue(
            migrated.contains("private double modporterLegacySpawnMotionX;"),
            migrated
        )
        assertTrue(
            migrated.contains(
                "this.modporterLegacySpawnMotionX = packet.getXa();"
            ),
            migrated
        )
        assertTrue(
            migrated.indexOf("clientPosition = position();") <
                migrated.indexOf("super.recreateFromPacket(packet);"),
            migrated
        )
        assertTrue(
            migrated.indexOf("super.recreateFromPacket(packet);") <
                migrated.indexOf("setDeltaMovement(packet.xa, packet.ya, packet.za);"),
            migrated
        )
        assertTrue(
            migrated.indexOf("setDeltaMovement(packet.xa, packet.ya, packet.za);") <
                migrated.indexOf("this.modporterLegacySpawnMotionPending = true;"),
            migrated
        )
        assertTrue(
            migratedReadSpawnData.contains(
                "if (this.modporterLegacySpawnMotionPending)"
            ),
            migratedReadSpawnData
        )
        assertTrue(
            migratedReadSpawnData.contains(
                "lerpMotion(this.modporterLegacySpawnMotionX, " +
                    "this.modporterLegacySpawnMotionY, " +
                    "this.modporterLegacySpawnMotionZ);"
            ),
            migratedReadSpawnData
        )
        assertTrue(
            migratedReadSpawnData.indexOf("modporterLegacySpawnMotionPending = false") <
                migratedReadSpawnData.indexOf("lerpMotion("),
            migratedReadSpawnData
        )
        assertTrue(
            migratedReadSpawnData.indexOf("lerpMotion(") <
                migratedReadSpawnData.indexOf("setBox(buffer.readItem())"),
            migratedReadSpawnData
        )
        assertTrue(
            migratedReadSpawnData.indexOf("setBox(buffer.readItem())") <
                migratedReadSpawnData.indexOf(
                    "setDeltaMovement(buffer.readFloat(), buffer.readFloat(), buffer.readFloat())"
                ),
            migratedReadSpawnData
        )
        assertTrue(migrated.contains("return parcelBuilder"), migrated)
        assertTrue(migrated.contains(".sized(1, 1);"), migrated)
        assertTrue(
            migrated.contains(
                "public ParcelEntity(Level level, double x, double y, double z)"
            ),
            migrated
        )
        assertFalse(migrated.contains("new ClientboundAddEntityPacket"), migrated)
        assertFalse(migrated.contains("getAdditionalData()"), migrated)
        val accessTransformerText = accessTransformer.readText()
        assertTrue(
            accessTransformerText.contains(
                "public net.minecraft.network.protocol.game.ClientboundAddEntityPacket xa"
            ),
            accessTransformerText
        )
        assertTrue(
            accessTransformerText.contains(
                "public net.minecraft.network.protocol.game.ClientboundAddEntityPacket ya"
            ),
            accessTransformerText
        )
        assertTrue(
            accessTransformerText.contains(
                "public net.minecraft.network.protocol.game.ClientboundAddEntityPacket za"
            ),
            accessTransformerText
        )
        assertTrue(
            pass.migrateLegacyClientSpawnFactories(directory, dryRun = false).isEmpty()
        )
    }

    @Test
    fun `already namespace-migrated missing PlayMessages import is admitted`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        entity.writeText(
            entity.readText().replace(
                "net.minecraftforge.network.PlayMessages.SpawnEntity",
                "net.neoforged.neoforge.network.PlayMessages.SpawnEntity"
            )
        )

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertEquals(2, changes.size, changes.toString())
        assertFalse(entity.readText().contains("PlayMessages.SpawnEntity"))
    }

    @Test
    fun `actual Create graph is admitted before legacy AT wiring is migrated`() {
        writeRegistrateGraph()
        writeLegacyAccessTransformerConfiguration()
        val entity = writeEntity()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertEquals(2, changes.size, changes.toString())
        val migrated = entity.readText()
        assertFalse(migrated.contains("PlayMessages.SpawnEntity"), migrated)
        assertFalse(migrated.contains("setCustomClientFactory"), migrated)
        assertTrue(
            migrated.contains(
                "public void recreateFromPacket(ClientboundAddEntityPacket packet)"
            ),
            migrated
        )
        val accessTransformerText = accessTransformer().readText()
        assertTrue(accessTransformerText.contains("ClientboundAddEntityPacket xa"))
        assertTrue(accessTransformerText.contains("ClientboundAddEntityPacket ya"))
        assertTrue(accessTransformerText.contains("ClientboundAddEntityPacket za"))
    }

    @Test
    fun `factory reads of additional spawn bytes reject the complete family`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        entity.writeText(
            entity.readText().replace(
                "parcel.clientPosition = parcel.position();",
                """
                parcel.readSpawnData(packet.getAdditionalData());
                        parcel.clientPosition = parcel.position();
                """.trimIndent()
            )
        )
        val original = entity.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, entity.readText())
    }

    @Test
    fun `a direct factory caller rejects the complete family`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        java(
            "example/SpawnCaller.java",
            """
            package example;
            class SpawnCaller {
                Object call(SpawnEntity packet, Level level) {
                    return ParcelEntity.spawn(packet, level);
                }
            }
            """
        )
        val original = entity.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, entity.readText())
    }

    @Test
    fun `an unparseable supplied source rejects the complete family`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        java(
            "example/BrokenCaller.java",
            """
            package example;
            class BrokenCaller {
                Object call( {
                    return ParcelEntity.spawn(packet, level);
                }
            }
            """
        )
        val originalEntity = entity.readText()
        val originalAccessTransformer = accessTransformer().readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(originalEntity, entity.readText())
        assertEquals(originalAccessTransformer, accessTransformer().readText())
    }

    @Test
    fun `a supplied source join cancellation rejects the complete family`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        java(
            "example/JoinListener.java",
            """
            package example;

            import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

            class JoinListener {
                static void onJoin(EntityJoinLevelEvent event) {
                    event.setCanceled(true);
                }
            }
            """
        )
        val originalEntity = entity.readText()
        val originalAccessTransformer = accessTransformer().readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(originalEntity, entity.readText())
        assertEquals(originalAccessTransformer, accessTransformer().readText())
    }

    @Test
    fun `registration forwarding that drops the EntityFactory rejects the family`() {
        val graph = writeRegistrateGraph()
        graph.writeText(
            graph.readText().replace(
                "super(owner, parent, name, callback, factory, classification);",
                "super(owner, parent, name, callback, (type, level) -> null, classification);"
            )
        )
        val entity = writeEntity()
        val original = entity.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, entity.readText())
    }

    @Test
    fun `position constructor side effects outside position and dimensions reject the family`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        entity.writeText(
            entity.readText().replace(
                "this.refreshDimensions();",
                """
                this.refreshDimensions();
                        auditSpawn();
                """.trimIndent()
            )
        )
        val original = entity.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, entity.readText())
    }

    @Test
    fun `missing authoritative motion read rejects the family`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        entity.writeText(
            entity.readText().replace(
                "setDeltaMovement(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());",
                "setDeltaMovement(0, 0, 0);"
            )
        )
        val original = entity.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, entity.readText())
    }

    @Test
    fun `existing packet recreation override rejects the family`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        entity.writeText(
            entity.readText().replace(
                "public static ParcelEntity spawn(SpawnEntity packet, Level level) {",
                """
                @Override
                    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
                        super.recreateFromPacket(packet);
                    }

                    public static ParcelEntity spawn(SpawnEntity packet, Level level) {
                """.trimIndent()
            )
        )
        val original = entity.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, entity.readText())
    }

    @Test
    fun `a changed legacy lerp formula rejects the family`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        entity.writeText(
            entity.readText().replace(
                "setDeltaMovement(getDeltaMovement().add(x, y, z).scale(.5f));",
                "setDeltaMovement(x, y, z);"
            )
        )
        val original = entity.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, entity.readText())
    }

    @Test
    fun `an existing generated state field rejects the family`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        entity.writeText(
            entity.readText().replace(
                "private ItemStack box;",
                """
                private ItemStack box;
                    private boolean modporterLegacySpawnMotionPending;
                """.trimIndent()
            )
        )
        val original = entity.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, entity.readText())
    }

    @Test
    fun `existing exact packet access transformers are not duplicated`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        val accessTransformer = accessTransformer()
        accessTransformer.writeText(
            """
            public net.minecraft.world.entity.Entity removalReason
            public net.minecraft.network.protocol.game.ClientboundAddEntityPacket xa
            public net.minecraft.network.protocol.game.ClientboundAddEntityPacket ya
            public net.minecraft.network.protocol.game.ClientboundAddEntityPacket za
            """.trimIndent() + "\n"
        )

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertEquals(1, changes.size, changes.toString())
        assertFalse(entity.readText().contains("setCustomClientFactory"))
        val accessTransformerText = accessTransformer.readText()
        assertEquals(
            1,
            Regex("ClientboundAddEntityPacket xa").findAll(accessTransformerText).count()
        )
        assertEquals(
            1,
            Regex("ClientboundAddEntityPacket ya").findAll(accessTransformerText).count()
        )
        assertEquals(
            1,
            Regex("ClientboundAddEntityPacket za").findAll(accessTransformerText).count()
        )
    }

    @Test
    fun `missing access transformer build wiring rejects the family`() {
        writeRegistrateGraph()
        directory.resolve("build.gradle").writeText("plugins { id 'java' }\n")
        val entity = writeEntity()
        val originalEntity = entity.readText()
        val originalAccessTransformer = accessTransformer().readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(originalEntity, entity.readText())
        assertEquals(originalAccessTransformer, accessTransformer().readText())
    }

    @Test
    fun `a conflicting packet access transformer rejects the family`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        val accessTransformer = accessTransformer()
        accessTransformer.writeText(
            "public-f net.minecraft.network.protocol.game.ClientboundAddEntityPacket xa\n"
        )
        val originalEntity = entity.readText()
        val originalAccessTransformer = accessTransformer.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(originalEntity, entity.readText())
        assertEquals(originalAccessTransformer, accessTransformer.readText())
    }

    @Test
    fun `an existing onAddedToLevel override rejects the family`() {
        writeRegistrateGraph()
        val entity = writeEntity()
        entity.writeText(
            entity.readText().replace(
                "public static ParcelEntity spawn(SpawnEntity packet, Level level) {",
                """
                @Override
                    public void onAddedToLevel() {
                        super.onAddedToLevel();
                    }

                    public static ParcelEntity spawn(SpawnEntity packet, Level level) {
                """.trimIndent()
            )
        )
        val original = entity.readText()

        val changes = StructuralRefactorPass()
            .migrateLegacyClientSpawnFactories(directory, dryRun = false)

        assertTrue(changes.isEmpty(), changes.toString())
        assertEquals(original, entity.readText())
    }

    private fun writeEntity(): Path = java(
        "example/ParcelEntity.java",
        """
        package example;

        import net.minecraft.network.RegistryFriendlyByteBuf;
        import net.minecraft.world.entity.EntityType;
        import net.minecraft.world.entity.LivingEntity;
        import net.minecraft.world.level.Level;
        import net.minecraft.world.phys.Vec3;
        import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
        import net.minecraftforge.network.PlayMessages.SpawnEntity;

        public class ParcelEntity extends LivingEntity implements IEntityWithComplexSpawn {
            public Vec3 clientPosition;
            private ItemStack box;

            @SuppressWarnings("unchecked")
            public ParcelEntity(EntityType<?> type, Level level) {
                super((EntityType<? extends LivingEntity>) type, level);
                box = ItemStack.EMPTY;
            }

            public ParcelEntity(Level level, double x, double y, double z) {
                this(AllTypes.PARCEL.get(), level);
                this.setPos(x, y, z);
                this.refreshDimensions();
            }

            public static EntityType.Builder<?> build(EntityType.Builder<?> builder) {
                @SuppressWarnings("unchecked")
                EntityType.Builder<ParcelEntity> parcelBuilder =
                    (EntityType.Builder<ParcelEntity>) builder;
                return parcelBuilder.setCustomClientFactory(ParcelEntity::spawn)
                    .sized(1, 1);
            }

            public static ParcelEntity spawn(SpawnEntity packet, Level level) {
                ParcelEntity parcel =
                    new ParcelEntity(level, packet.getPosX(), packet.getPosY(), packet.getPosZ());
                parcel.setDeltaMovement(packet.getVelX(), packet.getVelY(), packet.getVelZ());
                parcel.clientPosition = parcel.position();
                return parcel;
            }

            public void setBox(ItemStack box) {
                this.box = box.copy();
                refreshDimensions();
            }

            public ItemStack getBox() {
                return box;
            }

            @Override
            public void lerpMotion(double x, double y, double z) {
                setDeltaMovement(getDeltaMovement().add(x, y, z).scale(.5f));
            }

            @Override
            public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
                buffer.writeItem(getBox());
                Vec3 motion = getDeltaMovement();
                buffer.writeFloat((float) motion.x);
                buffer.writeFloat((float) motion.y);
                buffer.writeFloat((float) motion.z);
            }

            @Override
            public void readSpawnData(RegistryFriendlyByteBuf buffer) {
                setBox(buffer.readItem());
                setDeltaMovement(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
            }
        }
        """
    )

    private fun writeRegistrateGraph(): Path {
        writeAccessTransformerConfiguration()
        java(
            "example/AllTypes.java",
            """
            package example;

            import java.util.function.Consumer;
            import java.util.function.Supplier;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.EntityType.EntityFactory;
            import net.minecraft.world.entity.MobCategory;

            class AllTypes {
                static final Entry<ParcelEntity> PARCEL =
                    register("parcel", ParcelEntity::new, () -> new Object(),
                        MobCategory.MISC, 10, 3, true, false, ParcelEntity::build)
                        .register();

                private static <T extends Entity> FancyEntityBuilder<T, ?> register(
                    String name,
                    EntityFactory<T> factory,
                    Supplier<Object> renderer,
                    MobCategory group,
                    int range,
                    int updateFrequency,
                    boolean sendVelocity,
                    boolean immuneToFire,
                    Consumer<EntityType.Builder<T>> propertyBuilder
                ) {
                    return (FancyEntityBuilder<T, ?>) Mod.REGISTRATE
                        .entity(name, factory, group)
                        .properties(b -> b.setTrackingRange(range)
                            .setUpdateInterval(updateFrequency)
                            .setShouldReceiveVelocityUpdates(sendVelocity))
                        .properties(propertyBuilder);
                }
            }
            """
        )
        java(
            "example/FancyRegistrate.java",
            """
            package example;

            import com.tterrag.registrate.AbstractRegistrate;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.MobCategory;

            class FancyRegistrate extends AbstractRegistrate<FancyRegistrate> {
                <T extends Entity> FancyEntityBuilder<T, FancyRegistrate> entity(
                    String name,
                    EntityType.EntityFactory<T> factory,
                    MobCategory category
                ) {
                    return this.entity(self(), name, factory, category);
                }

                <T extends Entity, P> FancyEntityBuilder<T, P> entity(
                    P parent,
                    String name,
                    EntityType.EntityFactory<T> factory,
                    MobCategory category
                ) {
                    return (FancyEntityBuilder<T, P>) this.entry(name, callback -> {
                        return FancyEntityBuilder.create(
                            this, parent, name, callback, factory, category);
                    });
                }
            }
            """
        )
        return java(
            "example/FancyEntityBuilder.java",
            """
            package example;

            import com.tterrag.registrate.AbstractRegistrate;
            import com.tterrag.registrate.builders.BuilderCallback;
            import com.tterrag.registrate.builders.EntityBuilder;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.MobCategory;

            class FancyEntityBuilder<T extends Entity, P> extends EntityBuilder<T, P> {
                static <T extends Entity, P> EntityBuilder<T, P> create(
                    AbstractRegistrate<?> owner,
                    P parent,
                    String name,
                    BuilderCallback callback,
                    EntityType.EntityFactory<T> factory,
                    MobCategory classification
                ) {
                    return (new FancyEntityBuilder<>(
                        owner, parent, name, callback, factory, classification
                    )).defaultLang();
                }

                FancyEntityBuilder(
                    AbstractRegistrate<?> owner,
                    P parent,
                    String name,
                    BuilderCallback callback,
                    EntityType.EntityFactory<T> factory,
                    MobCategory classification
                ) {
                    super(owner, parent, name, callback, factory, classification);
                }
            }
            """
        )
    }

    private fun writeLegacyAccessTransformerConfiguration() {
        directory.resolve("build.gradle").writeText(
            """
            legacyForge {
                validateAccessTransformers = false

                if (file("src/main/resources/META-INF/accesstransformer.cfg").exists()) {
                    accessTransformers.from "src/main/resources/META-INF/accesstransformer.cfg"
                }
            }
            """.trimIndent() + "\n"
        )
    }

    private fun writeAccessTransformerConfiguration() {
        directory.resolve("build.gradle").writeText(
            """
            neoForge {
                validateAccessTransformers = true
                accessTransformers {
                    file('src/main/resources/META-INF/accesstransformer.cfg')
                }
            }
            """.trimIndent() + "\n"
        )
        val accessTransformer = accessTransformer()
        accessTransformer.parent.createDirectories()
        accessTransformer.writeText(
            "public net.minecraft.world.entity.Entity removalReason\n"
        )
    }

    private fun accessTransformer(): Path = directory.resolve(
        "src/main/resources/META-INF/accesstransformer.cfg"
    )

    private fun java(relative: String, source: String): Path {
        val file = directory.resolve("src/main/java").resolve(relative)
        file.parent.createDirectories()
        file.writeText(source.trimIndent() + "\n")
        return file
    }

    private fun readSpawnDataBlock(source: String): String {
        val start = source.indexOf("public void readSpawnData")
        assertTrue(start >= 0, source)
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed readSpawnData method")
    }
}
