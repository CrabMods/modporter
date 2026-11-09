package com.modporter.transforms

import com.modporter.core.transforms.build.BuildSystemPass
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.modporter.core.transforms.build.RegistrateApiMigration
import kotlin.test.assertFailsWith

class BuildSystemTest {

    @TempDir
    lateinit var tempDir: Path

    private val pass = BuildSystemPass()

    @Test
    fun `replaces ForgeGradle plugin with NeoForge ModDev`() {
        val projectDir = tempDir.resolve("p1")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertTrue(content.contains("net.neoforged.moddev"))
        assertFalse(content.contains("net.minecraftforge.gradle"))
    }

    @Test
    fun `old style buildscript migration ignores quoted delimiters`() {
        val projectDir = tempDir.resolve("old-style-quoted-delimiters")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText(listOf(
            "buildscript {",
            "    ext.doubleQuoted = \"}\"",
            "    ext.singleQuoted = '}'",
            "    ext.tripleDouble = \"\"\"",
            "        documentation } repositories { dependencies {",
            "    \"\"\"",
            "    ext.tripleSingle = '''",
            "        more documentation } repositories { dependencies {",
            "    '''",
            "    // } line-comment delimiter noise",
            "    /* } block-comment delimiter noise */",
            "    repositories {",
            "        maven { url 'https://maven.minecraftforge.net/' }",
            "    }",
            "    dependencies {",
            "        classpath 'net.minecraftforge.gradle:ForgeGradle:6.0.+'",
            "    }",
            "}",
            "",
            "apply plugin: 'net.minecraftforge.gradle'",
            "",
            "dependencies {",
            "    implementation \"example:library:1.0\"",
            "}",
            ""
        ).joinToString(System.lineSeparator()))

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-remove-buildscript" })
        assertTrue(content.contains("id 'net.neoforged.moddev' version '2.0.140'"), content)
        assertTrue(content.contains("implementation \"example:library:1.0\""), content)
        assertFalse(content.contains("buildscript"), content)
        assertFalse(content.contains("ForgeGradle"), content)
        assertFalse(content.contains("apply plugin: 'net.minecraftforge.gradle'"), content)
        assertFalse(content.contains("documentation } repositories"), content)
    }

    @Test
    fun `hybrid buildscript with existing plugins block merges ModDev into plugins block`() {
        val projectDir = tempDir.resolve("hybrid-old-style")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            buildscript {
                repositories {
                    maven { url = 'https://maven.minecraftforge.net' }
                    maven { url = 'https://repo.spongepowered.org/repository/maven-public/' }
                    mavenCentral()
                }
                dependencies {
                    classpath group: 'net.minecraftforge.gradle', name: 'ForgeGradle', version: '5.1.+'
                    classpath 'org.parchmentmc:librarian:1.+'
                    classpath 'org.spongepowered:mixingradle:0.7-SNAPSHOT'
                }
            }
            // Only edit below this line, the above code adds and enables the necessary things for Forge to be setup.
            plugins {
                id 'eclipse'
                id 'maven-publish'
            }
            apply plugin: 'net.minecraftforge.gradle'
            apply plugin: 'org.parchmentmc.librarian.forgegradle'
            apply plugin: 'org.spongepowered.mixin'

            version = '1.0.0'
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        val pluginsBlock = content.substring(content.indexOf("plugins {"), content.indexOf("}", content.indexOf("plugins {")) + 1)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-merge-plugins-block" })
        assertTrue(pluginsBlock.contains("id 'net.neoforged.moddev' version '2.0.140'"), content)
        assertTrue(pluginsBlock.contains("id 'java-library'"), content)
        assertEquals(content.indexOf("plugins {"), content.lastIndexOf("plugins {"), content)
        assertFalse(content.contains("buildscript"), content)
        assertFalse(content.contains("apply plugin: 'net.minecraftforge.gradle'"), content)
        assertFalse(content.contains("apply plugin: 'org.parchmentmc.librarian.forgegradle'"), content)
        assertFalse(content.contains("apply plugin: 'org.spongepowered.mixin'"), content)
        assertFalse(Regex("""(?m)^[ \t]*id\s*\(\s*["']net\.neoforged\.moddev["']\s*\)""").containsMatchIn(content), content)
    }

    @Test
    fun `replaces Forge Maven repository URL`() {
        val projectDir = tempDir.resolve("p2")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            repositories {
                maven { url 'https://maven.minecraftforge.net/' }
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertTrue(content.contains("maven.neoforged.net"))
        assertFalse(content.contains("maven.minecraftforge.net"))
    }

    @Test
    fun `adds content filter to Tamaized repository`() {
        val projectDir = tempDir.resolve("p2-tamaized-filter")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            repositories {
                maven {
                    name 'Tama Maven'
                    url "https://maven.tamaized.com/releases"
                }
                maven {
                    name 'Curseforge Maven'
                    url "https://www.cursemaven.com"
                    content {
                        includeGroup "curse.maven"
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-repository-content-filter" })
        assertTrue(content.contains("includeGroup \"tamaized\""))
        assertTrue(content.contains("includeGroup \"curse.maven\""))
    }

    @Test
    fun `replaces Forge dependency with comment`() {
        val projectDir = tempDir.resolve("p3")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
                minecraft "net.neoforged:forge:${'$'}{project.minecraft_version}-${'$'}{project.neo_version}"
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertFalse(content.contains("net.minecraftforge:forge"))
        assertFalse(content.contains("net.neoforged:forge"))
        assertTrue(content.contains("neoForge"))
    }

    @Test
    fun `replaces minecraft block with neoForge block`() {
        val projectDir = tempDir.resolve("p4")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            minecraft {
                mappings channel: 'official', version: '1.20.1'
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        assertFalse(content.contains("minecraft {"))
        assertTrue(content.contains("neoForge {"))
        assertTrue(content.contains("parchment"))
    }

    @Test
    fun `transforms settings gradle`() {
        val projectDir = tempDir.resolve("p5")
        projectDir.createDirectories()
        projectDir.resolve("settings.gradle").writeText("""
            pluginManagement {
                repositories {
                    maven { url = 'https://maven.minecraftforge.net/' }
                }
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("settings.gradle").readText()
        assertTrue(content.contains("maven.neoforged.net"))
        assertFalse(content.contains("maven.minecraftforge.net"))
    }

    @Test
    fun `transforms gradle properties`() {
        val projectDir = tempDir.resolve("p6")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("""
            base_minecraft_version=1.20
            minecraft_version=1.20.1
            forge_version=47.2.0
            mod_id=testmod
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("gradle.properties").readText()
        assertTrue(content.contains("base_minecraft_version=1.21"))
        assertTrue(content.contains("minecraft_version=1.21.1"))
        assertTrue(content.contains("neo_forge_version=21.1.230"))
        assertFalse(Regex("""(?<!\w)forge_version\s*=""").containsMatchIn(content),
            "Should not have standalone forge_version property")
    }

    @Test
    fun `gradle properties mod id detection ignores text block documentation`() {
        val projectDir = tempDir.resolve("p6-text-block-modid")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            forge_version=47.2.0
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText(listOf(
            "package com.example;",
            "",
            "public final class ExampleMod {",
            "    String docs() {",
            "        return \"\"\"",
            "            @Mod(\"examplemod\")",
            "            public static final String MODID = \"examplemod\";",
            "            \"\"\";",
            "    }",
            "}",
            ""
        ).joinToString(System.lineSeparator()))

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("gradle.properties").readText()

        assertFalse(content.contains("mod_id=examplemod"), content)
        assertFalse(result.changes.any { it.ruleId == "build-props-mod-id" }, result.changes.toString())
    }

    @Test
    fun `normalizes neoforge properties without double prefixing`() {
        val projectDir = tempDir.resolve("neoforge-props")
        projectDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            neoforge_version=47.1.0
            mod_version=1.20.1-4.2.0
            hotbath_version=4.2.0
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("gradle.properties").readText()

        assertTrue(content.contains("neo_forge_version=21.1.230"), content)
        assertFalse(content.contains("neoneo_forge_version"), content)
        assertTrue(content.contains("mod_version=1.21.1-4.2.0"), content)
        assertTrue(content.contains("hotbath_version=4.2.0"), content)
    }

    @Test
    fun `normalizes old minecraft version property references in build script`() {
        val projectDir = tempDir.resolve("mc-version-build-props")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            version = "${'$'}{mc_version}-${'$'}{mod_version}-neoforge"
            dependencies {
                implementation "example:dep:${'$'}{project.mc_version}"
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(content.contains("""version = "${'$'}{minecraft_version}-${'$'}{mod_version}-neoforge""""), content)
        assertTrue(content.contains("""implementation "example:dep:${'$'}{project.minecraft_version}""""), content)
        assertFalse(content.contains("mc_version"), content)
    }

    @Test
    fun `updates known dependency version properties from mapping database`() {
        val projectDir = tempDir.resolve("known-dependency-version-props")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            dependencies {
                implementation fg.deobf("com.aetherteam.nitrogen:nitrogen_internals:${'$'}{project.nitrogen_version}")
                implementation fg.deobf("com.aetherteam.cumulus:cumulus_menus:${'$'}{project.cumulus_version}")
                implementation fg.deobf("com.github.glitchfiend:TerraBlender-forge:${'$'}{project.terrablender_version}")
                compileOnly fg.deobf("top.theillusivec4.curios:curios-forge:${'$'}{project.curios_version}:api")
            }
        """.trimIndent())
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            nitrogen_version=1.20.1-1.0.12-neoforge
            cumulus_version=1.20.1-1.0.1-neoforge
            curios_version=5.12.0+1.20.1
            terrablender_version=1.20.1-3.0.1.7
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("gradle.properties").readText()

        assertTrue(result.changes.any { it.ruleId == "build-props-dependency-version" })
        assertTrue(content.contains("nitrogen_version=1.21.1-1.1.25-neoforge"), content)
        assertTrue(content.contains("cumulus_version=1.21.1-2.0.8-neoforge"), content)
        assertTrue(content.contains("curios_version=9.2.3+1.21.1"), content)
        assertTrue(content.contains("terrablender_version=1.21.1-4.1.0.8"), content)
        assertFalse(content.contains("1.20.1-1.0.12-neoforge"), content)
        assertFalse(content.contains("1.20.1-1.0.1-neoforge"), content)
        assertFalse(content.contains("5.12.0+1.20.1"), content)
        assertFalse(content.contains("1.20.1-3.0.1.7"), content)
    }

    @Test
    fun `migrates coremod ASMAPI scripts to NeoForge package`() {
        val projectDir = tempDir.resolve("coremod-asmapi")
        val asmDir = projectDir.resolve("src/main/resources/META-INF/asm")
        asmDir.createDirectories()
        asmDir.resolve("seed.js").writeText("""
            var ASM = Java.type('net.minecraftforge.coremod.api.ASMAPI');
            var Opcodes = Java.type('org.objectweb.asm.Opcodes');
        """.trimIndent())

        val result = pass.apply(projectDir)
        val script = asmDir.resolve("seed.js").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-coremod-asmapi-neoforge" })
        assertTrue(script.contains("net.neoforged.coremod.api.ASMAPI"))
        assertFalse(script.contains("net.minecraftforge.coremod.api.ASMAPI"))
    }

    @Test
    fun `registers animal spawn placement on mod bus`() {
        val projectDir = tempDir.resolve("spawn-placement")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    EntityRegistry.ENTITY_TYPES.register(modEventBus);
                    modEventBus.addListener(EntityRegistry::registerAttributes);
                }
            }
        """.trimIndent())
        srcDir.resolve("EntityRegistry.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.MobCategory;
            import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class EntityRegistry {
                public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
                    DeferredRegister.create(Registries.ENTITY_TYPE, ExampleMod.MODID);

                public static final DeferredHolder<EntityType<?>, EntityType<DeerEntity>> DEER = ENTITY_TYPES.register("deer",
                    () -> EntityType.Builder.of(DeerEntity::new, MobCategory.CREATURE)
                        .sized(0.9F, 0.95F).build("deer"));

                public static void registerAttributes(EntityAttributeCreationEvent event) {
                }
            }
        """.trimIndent())
        srcDir.resolve("DeerEntity.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.animal.Animal;
            import net.minecraft.world.level.Level;

            public class DeerEntity extends Animal {
                public DeerEntity(EntityType<? extends DeerEntity> type, Level level) {
                    super(type, level);
                }
            }
        """.trimIndent())

        pass.apply(projectDir)

        val registry = srcDir.resolve("EntityRegistry.java").readText()
        val mod = srcDir.resolve("ExampleMod.java").readText()
        assertTrue(registry.contains("import net.minecraft.world.entity.SpawnPlacementTypes;"))
        assertTrue(registry.contains("import net.minecraft.world.level.levelgen.Heightmap;"))
        assertTrue(registry.contains("import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;"))
        assertTrue(registry.contains("public static void registerSpawnPlacements(RegisterSpawnPlacementsEvent event)"))
        assertTrue(registry.contains("event.register(DEER.get(), SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Animal::checkAnimalSpawnRules, RegisterSpawnPlacementsEvent.Operation.REPLACE);"))
        assertTrue(mod.contains("modEventBus.addListener(com.example.EntityRegistry::registerSpawnPlacements);"))
    }

    @Test
    fun `registers existing spawn placement method on arbitrary mod bus variable`() {
        val projectDir = tempDir.resolve("spawn-placement-existing-method")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modbus = modContainer.getEventBus();
                    EntityRegistry.ENTITY_TYPES.register(modbus);
                    modbus.addListener(com.example.EntityRegistry::addEntityAttributes);
                }
            }
        """.trimIndent())
        srcDir.resolve("EntityRegistry.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.entity.EntityType;
            import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
            import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class EntityRegistry {
                public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
                    DeferredRegister.create(Registries.ENTITY_TYPE, ExampleMod.MODID);

                public static void addEntityAttributes(EntityAttributeCreationEvent event) {
                }

                public static void registerSpawnPlacements(RegisterSpawnPlacementsEvent event) {
                }
            }
        """.trimIndent())

        pass.apply(projectDir)

        val mod = srcDir.resolve("ExampleMod.java").readText()
        assertTrue(mod.contains("modbus.addListener(com.example.EntityRegistry::addEntityAttributes);"))
        assertTrue(mod.contains("modbus.addListener(com.example.EntityRegistry::registerSpawnPlacements);"))
    }

    @Test
    fun `registers spawn placement listener outside guarded client listener block`() {
        val projectDir = tempDir.resolve("spawn-placement-client-guard")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val clientDir = projectDir.resolve("src/main/java/com/example/client")
        srcDir.createDirectories()
        clientDir.createDirectories()
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.client.ClientRegistry;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    modEventBus.addListener(ClientRegistry::registerClientStuff);
                    EntityRegistry.ENTITY_TYPES.register(modEventBus);
                    modEventBus.addListener(EntityRegistry::registerAttributes);
                }
            }
        """.trimIndent())
        srcDir.resolve("EntityRegistry.java").writeText("""
            package com.example;

            import net.minecraft.core.registries.Registries;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.MobCategory;
            import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class EntityRegistry {
                public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
                    DeferredRegister.create(Registries.ENTITY_TYPE, ExampleMod.MODID);

                public static final DeferredHolder<EntityType<?>, EntityType<DeerEntity>> DEER = ENTITY_TYPES.register("deer",
                    () -> EntityType.Builder.of(DeerEntity::new, MobCategory.CREATURE)
                        .sized(0.9F, 0.95F).build("deer"));

                public static void registerAttributes(EntityAttributeCreationEvent event) {
                }
            }
        """.trimIndent())
        srcDir.resolve("DeerEntity.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.animal.Animal;
            import net.minecraft.world.level.Level;

            public class DeerEntity extends Animal {
                public DeerEntity(EntityType<? extends DeerEntity> type, Level level) {
                    super(type, level);
                }
            }
        """.trimIndent())
        clientDir.resolve("ClientRegistry.java").writeText("""
            package com.example.client;

            import net.minecraft.client.Minecraft;

            public class ClientRegistry {
                public static void registerClientStuff(Object event) {
                    Minecraft.getInstance();
                }
            }
        """.trimIndent())

        pass.apply(projectDir)

        val mod = srcDir.resolve("ExampleMod.java").readText()
        val guardIdx = mod.indexOf("if (net.neoforged.fml.loading.FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT)")
        val spawnIdx = mod.indexOf("modEventBus.addListener(com.example.EntityRegistry::registerSpawnPlacements);")

        assertTrue(guardIdx >= 0, mod)
        assertTrue(spawnIdx >= 0, mod)
        assertTrue(spawnIdx < guardIdx, mod)
    }

    @Test
    fun `migrates structure template pool reflection fields to access transformers`() {
        val projectDir = tempDir.resolve("structure-pool-reflection")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("VillageStructures.java").writeText("""
            package com.example;

            import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
            import net.neoforged.fml.util.ObfuscationReflectionHelper;
            import java.lang.reflect.Field;

            public class VillageStructures {
                void add(StructureTemplatePool pool) throws Exception {
                    Field templatesField = ObfuscationReflectionHelper.findField(
                            StructureTemplatePool.class, "f_210560_"); // templates
                    Field rawTemplatesField = ObfuscationReflectionHelper.findField(
                            StructureTemplatePool.class, "f_210559_"); // rawTemplates
                    Object templates = templatesField.get(pool);
                    Object rawTemplates = rawTemplatesField.get(pool);
                    templatesField.set(pool, templates);
                    rawTemplatesField.set(pool, rawTemplates);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = srcDir.resolve("VillageStructures.java").readText()
        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-structure-pool-at-direct-fields" })
        assertTrue(content.contains("Object templates = pool.templates;"))
        assertTrue(content.contains("Object rawTemplates = pool.rawTemplates;"))
        assertTrue(content.contains("pool.templates = templates;"))
        assertTrue(content.contains("pool.rawTemplates = rawTemplates;"))
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates"))
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates"))
        assertFalse(content.contains("f_210560_"))
        assertFalse(content.contains("f_210559_"))
        assertFalse(content.contains("ObfuscationReflectionHelper"))
        assertFalse(content.contains("getDeclaredField"))
        assertFalse(content.contains("setAccessible"))
    }

    @Test
    fun `structure pool reflection migration ignores comments strings and text blocks`() {
        val projectDir = tempDir.resolve("structure-pool-reflection-docs")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("VillageStructures.java").writeText("""
            package com.example;

            import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
            import net.neoforged.fml.util.ObfuscationReflectionHelper;
            import java.lang.reflect.Field;

            public class VillageStructures {
                String inline = "templatesField.get(pool) rawTemplatesField.set(pool, rawTemplates)";
                String docs = ${"\"\"\""}
                    Field templatesField = ObfuscationReflectionHelper.findField(StructureTemplatePool.class, "f_210560_");
                    Object fake = templatesField.get(pool);
                    rawTemplatesField.set(pool, rawTemplates);
                    templatesField.setAccessible(true);
                    ${"\"\"\""};

                void add(StructureTemplatePool pool) throws Exception {
                    // templatesField.get(pool);
                    Field templatesField = ObfuscationReflectionHelper.findField(
                            StructureTemplatePool.class, "f_210560_"); // templates
                    Field rawTemplatesField = ObfuscationReflectionHelper.findField(
                            StructureTemplatePool.class, "f_210559_"); // rawTemplates
                    Object templates = templatesField.get(pool);
                    Object rawTemplates = rawTemplatesField.get(pool);
                    templatesField.set(pool, templates);
                    rawTemplatesField.set(pool, rawTemplates);
                    // rawTemplatesField.set(pool, rawTemplates);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = srcDir.resolve("VillageStructures.java").readText()
        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-structure-pool-at-direct-fields" })
        assertTrue(content.contains("Object templates = pool.templates;"), content)
        assertTrue(content.contains("Object rawTemplates = pool.rawTemplates;"), content)
        assertTrue(content.contains("pool.templates = templates;"), content)
        assertTrue(content.contains("pool.rawTemplates = rawTemplates;"), content)
        assertTrue(content.contains("String inline = \"templatesField.get(pool) rawTemplatesField.set(pool, rawTemplates)\";"), content)
        assertTrue(content.contains("Object fake = templatesField.get(pool);"), content)
        assertTrue(content.contains("rawTemplatesField.set(pool, rawTemplates);"), content)
        assertTrue(content.contains("templatesField.setAccessible(true);"), content)
        assertTrue(content.contains("// templatesField.get(pool);"), content)
        assertTrue(content.contains("// rawTemplatesField.set(pool, rawTemplates);"), content)
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates"))
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates"))
    }

    @Test
    fun `adds structure pool access transformers only for typed direct field access`() {
        val projectDir = tempDir.resolve("structure-pool-direct-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("PoolAccess.java").writeText("""
            package com.example;

            import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

            public class PoolAccess {
                void patch(StructureTemplatePool pool) {
                    Object templates = pool.templates;
                    pool.rawTemplates = pool.rawTemplates;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates"), at)
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates"), at)
    }

    @Test
    fun `structure pool access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("structure-pool-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("PoolNotes.java").writeText("""
            package com.example;

            import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

            public class PoolNotes {
                String note = "StructureTemplatePool pool.templates pool.rawTemplates";

                void describe() {
                    // StructureTemplatePool pool; pool.templates; pool.rawTemplates;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `adds goal selector and server level access transformers only for typed member access`() {
        val projectDir = tempDir.resolve("goal-lightning-direct-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ServerHooks.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.ai.goal.GoalSelector;

            public class ServerHooks {
                void patch(GoalSelector selector, ServerLevel level, BlockPos origin) {
                    selector.availableGoals.clear();
                    level.findLightningTargetAround(origin);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.world.entity.ai.goal.GoalSelector availableGoals"), at)
        assertTrue(
            at.contains("public net.minecraft.server.level.ServerLevel findLightningTargetAround(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"),
            at
        )
    }

    @Test
    fun `goal selector and server level access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("goal-lightning-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ServerNotes.java").writeText("""
            package com.example;

            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.entity.ai.goal.GoalSelector;

            public class ServerNotes {
                String note = "GoalSelector selector; selector.availableGoals; ServerLevel level; level.findLightningTargetAround(origin);";

                void describe() {
                    // GoalSelector selector; selector.availableGoals.clear();
                    // ServerLevel level; level.findLightningTargetAround(origin);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `adds creative selected tab access transformer only for static field access`() {
        val projectDir = tempDir.resolve("creative-selectedtab-direct-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("CreativeDirectAccess.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
            import net.minecraft.world.item.CreativeModeTab;

            public class CreativeDirectAccess {
                CreativeModeTab selected() {
                    return CreativeModeInventoryScreen.selectedTab;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen selectedTab"), at)
    }

    @Test
    fun `creative selected tab access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("creative-selectedtab-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("CreativeNotes.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;

            public class CreativeNotes {
                String note = "CreativeModeInventoryScreen.selectedTab";

                void describe() {
                    // CreativeModeInventoryScreen.selectedTab
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `adds level renderer access transformers only for typed field access`() {
        val projectDir = tempDir.resolve("level-renderer-direct-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("RendererAccess.java").writeText("""
            package com.example;

            import net.minecraft.client.renderer.LevelRenderer;

            public class RendererAccess {
                void capture(LevelRenderer renderer) {
                    Object sky = renderer.skyBuffer;
                    Object dark = renderer.darkBuffer;
                    int rain = renderer.rainSoundTime;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.client.renderer.LevelRenderer skyBuffer"), at)
        assertTrue(at.contains("public net.minecraft.client.renderer.LevelRenderer darkBuffer"), at)
        assertTrue(at.contains("public net.minecraft.client.renderer.LevelRenderer rainSoundTime"), at)
    }

    @Test
    fun `level renderer access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("level-renderer-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("RendererNotes.java").writeText("""
            package com.example;

            import net.minecraft.client.renderer.LevelRenderer;

            public class RendererNotes {
                String note = "LevelRenderer renderer; renderer.skyBuffer; renderer.darkBuffer; renderer.rainSoundTime;";

                void describe() {
                    // LevelRenderer renderer; renderer.skyBuffer; renderer.darkBuffer; renderer.rainSoundTime;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `migrates pending block entity reflection field to access transformer`() {
        val projectDir = tempDir.resolve("pending-be-reflection")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ChunkCleaner.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.chunk.LevelChunk;
            import java.lang.reflect.Field;
            import java.util.Map;

            public class ChunkCleaner {
                private static Field pendingBlockEntitiesField = null;
                private static boolean pendingBEFieldInitialized = false;

                private static void clearPendingBlockEntities(LevelChunk chunk) {
                    try {
                        if (!pendingBEFieldInitialized) {
                            pendingBEFieldInitialized = true;
                            for (java.lang.reflect.Field field : LevelChunk.class.getDeclaredFields()) {
                                if (java.util.Map.class.isAssignableFrom(field.getType())) {
                                    field.setAccessible(true);
                                    Object testValue = field.get(chunk);
                                    if (testValue instanceof java.util.Map<?, ?> testMap && !testMap.isEmpty()) {
                                        Object firstKey = testMap.keySet().iterator().next();
                                        if (firstKey instanceof BlockPos) {
                                            pendingBlockEntitiesField = field;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        if (pendingBlockEntitiesField != null) {
                            Object value = pendingBlockEntitiesField.get(chunk);
                            if (value instanceof java.util.Map<?, ?> map && !map.isEmpty()) {
                                int count = map.size();
                                map.clear();
                                if (count > 0) {
                                    ExampleMod.LOGGER.debug("Cleared {} pending block entities from chunk", count);
                                }
                            }
                        }
                    } catch (Exception e) {
                        ExampleMod.LOGGER.debug("Could not clear pending BEs via reflection: {}", e.getMessage());
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = srcDir.resolve("ChunkCleaner.java").readText()
        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-levelchunk-pending-blockentities-at" })
        assertTrue(content.contains("Map<BlockPos, CompoundTag> pendingBlockEntities = chunk.pendingBlockEntities;"))
        assertTrue(content.contains("pendingBlockEntities.clear();"))
        assertTrue(at.contains("public net.minecraft.world.level.chunk.ChunkAccess pendingBlockEntities"))
        assertFalse(content.contains("java.lang.reflect"))
        assertFalse(content.contains("Field "))
        assertFalse(content.contains("getDeclaredFields"))
        assertFalse(content.contains("setAccessible"))
    }

    @Test
    fun `pending block entity reflection migration ignores comments strings and text blocks`() {
        val projectDir = tempDir.resolve("pending-be-reflection-docs")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ChunkCleaner.java").writeText("""
            package com.example;

            import net.minecraft.core.BlockPos;
            import net.minecraft.nbt.CompoundTag;
            import net.minecraft.world.level.chunk.LevelChunk;
            import java.lang.reflect.Field;
            import java.util.Map;

            public class ChunkCleaner {
                String note = "LevelChunk.class.getDeclaredFields() pendingBlockEntitiesField clearPendingBlockEntities";
                String docs = ${"\"\"\""}
                    private static Field pendingBlockEntitiesField = null;
                    private static boolean pendingBEFieldInitialized = false;
                    private static void clearPendingBlockEntities(LevelChunk chunk) {
                        LevelChunk.class.getDeclaredFields();
                    }
                    ${"\"\"\""};

                private static Field pendingBlockEntitiesField = null;
                private static boolean pendingBEFieldInitialized = false;

                private static void clearPendingBlockEntities(LevelChunk chunk) {
                    // LevelChunk.class.getDeclaredFields();
                    try {
                        if (!pendingBEFieldInitialized) {
                            pendingBEFieldInitialized = true;
                            for (java.lang.reflect.Field field : LevelChunk.class.getDeclaredFields()) {
                                if (java.util.Map.class.isAssignableFrom(field.getType())) {
                                    field.setAccessible(true);
                                    Object testValue = field.get(chunk);
                                    if (testValue instanceof java.util.Map<?, ?> testMap && !testMap.isEmpty()) {
                                        Object firstKey = testMap.keySet().iterator().next();
                                        if (firstKey instanceof BlockPos) {
                                            pendingBlockEntitiesField = field;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        if (pendingBlockEntitiesField != null) {
                            Object value = pendingBlockEntitiesField.get(chunk);
                            if (value instanceof java.util.Map<?, ?> map && !map.isEmpty()) {
                                int count = map.size();
                                map.clear();
                                if (count > 0) {
                                    ExampleMod.LOGGER.debug("Cleared {} pending block entities from chunk", count);
                                }
                            }
                        }
                    } catch (Exception e) {
                        ExampleMod.LOGGER.debug("Could not clear pending BEs via reflection: {}", e.getMessage());
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = srcDir.resolve("ChunkCleaner.java").readText()
        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-levelchunk-pending-blockentities-at" })
        assertTrue(content.contains("Map<BlockPos, CompoundTag> pendingBlockEntities = chunk.pendingBlockEntities;"), content)
        assertTrue(content.contains("String note = \"LevelChunk.class.getDeclaredFields() pendingBlockEntitiesField clearPendingBlockEntities\";"), content)
        assertTrue(content.contains("private static Field pendingBlockEntitiesField = null;"), content)
        assertTrue(content.contains("LevelChunk.class.getDeclaredFields();"), content)
        assertTrue(at.contains("public net.minecraft.world.level.chunk.ChunkAccess pendingBlockEntities"))
    }

    @Test
    fun `pending block entity reflection migration ignores documentation only`() {
        val projectDir = tempDir.resolve("pending-be-reflection-docs-only")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve("ChunkNotes.java")
        file.writeText("""
            package com.example;

            public class ChunkNotes {
                String note = "LevelChunk.class.getDeclaredFields() pendingBlockEntitiesField clearPendingBlockEntities";
                String docs = ${"\"\"\""}
                    private static Field pendingBlockEntitiesField = null;
                    private static void clearPendingBlockEntities(LevelChunk chunk) {
                        LevelChunk.class.getDeclaredFields();
                    }
                    ${"\"\"\""};
            }
        """.trimIndent())
        val before = file.readText()

        val result = pass.apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-levelchunk-pending-blockentities-at" })
        assertFalse(projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
        assertEquals(before, file.readText())
    }

    @Test
    fun `adds pending block entity access transformer only for typed chunk field access`() {
        val projectDir = tempDir.resolve("pending-be-direct-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ChunkDirectAccess.java").writeText("""
            package com.example;

            import net.minecraft.world.level.chunk.LevelChunk;

            public class ChunkDirectAccess {
                void clear(LevelChunk chunk) {
                    chunk.pendingBlockEntities.clear();
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.world.level.chunk.ChunkAccess pendingBlockEntities"), at)
    }

    @Test
    fun `pending block entity access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("pending-be-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ChunkNotes.java").writeText("""
            package com.example;

            import net.minecraft.world.level.chunk.LevelChunk;

            public class ChunkNotes {
                String note = "LevelChunk chunk; chunk.pendingBlockEntities.clear();";

                void describe() {
                    // LevelChunk chunk; chunk.pendingBlockEntities.clear();
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `migrates entity visibility method reflection to vanilla boss type check`() {
        val projectDir = tempDir.resolve("entity-visibility-reflection")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("BossVisibility.java").writeText("""
            package com.example;

            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.entity.Entity;
            import java.lang.reflect.Method;

            public class BossVisibility {
                private static boolean hasNativePlayerVisibilityHook(Entity entity) {
                    return declaresVisibilityHook(entity.getClass(), "startSeenByPlayer")
                            || declaresVisibilityHook(entity.getClass(), "stopSeenByPlayer");
                }

                private static boolean declaresVisibilityHook(Class<?> entityClass, String methodName) {
                    try {
                        Method method = entityClass.getMethod(methodName, ServerPlayer.class);
                        return method.getDeclaringClass() != Entity.class;
                    } catch (ReflectiveOperationException e) {
                        return true;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = srcDir.resolve("BossVisibility.java").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-entity-visibility-hook-no-reflection" })
        assertTrue(content.contains("import net.minecraft.world.entity.boss.wither.WitherBoss;"), content)
        assertTrue(content.contains("return entity instanceof WitherBoss;"), content)
        assertFalse(content.contains("declaresVisibilityHook"))
        assertFalse(content.contains("getMethod"))
        assertFalse(content.contains("java.lang.reflect"))
        assertFalse(content.contains("ServerPlayer"))
    }

    @Test
    fun `entity visibility reflection migration ignores comments strings and text blocks`() {
        val projectDir = tempDir.resolve("entity-visibility-reflection-docs")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("BossVisibility.java").writeText("""
            package com.example;

            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.entity.Entity;
            import java.lang.reflect.Method;

            public class BossVisibility {
                String note = "declaresVisibilityHook .getMethod( startSeenByPlayer stopSeenByPlayer";
                String docs = ${"\"\"\""}
                    private static boolean hasNativePlayerVisibilityHook(Entity entity) {
                        return declaresVisibilityHook(entity.getClass(), "startSeenByPlayer")
                                || declaresVisibilityHook(entity.getClass(), "stopSeenByPlayer");
                    }

                    private static boolean declaresVisibilityHook(Class<?> entityClass, String methodName) {
                        return entityClass.getMethod(methodName, ServerPlayer.class) != null;
                    }
                    ${"\"\"\""};

                private static boolean hasNativePlayerVisibilityHook(Entity entity) {
                    // declaresVisibilityHook(entity.getClass(), "startSeenByPlayer");
                    return declaresVisibilityHook(entity.getClass(), "startSeenByPlayer")
                            || declaresVisibilityHook(entity.getClass(), "stopSeenByPlayer");
                }

                private static boolean declaresVisibilityHook(Class<?> entityClass, String methodName) {
                    try {
                        Method method = entityClass.getMethod(methodName, ServerPlayer.class);
                        return method.getDeclaringClass() != Entity.class;
                    } catch (ReflectiveOperationException e) {
                        return true;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = srcDir.resolve("BossVisibility.java").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-entity-visibility-hook-no-reflection" })
        assertTrue(content.contains("return entity instanceof WitherBoss;"), content)
        assertTrue(content.contains("String note = \"declaresVisibilityHook .getMethod( startSeenByPlayer stopSeenByPlayer\";"), content)
        assertTrue(content.contains("entityClass.getMethod(methodName, ServerPlayer.class) != null;"), content)
        assertTrue(content.contains("declaresVisibilityHook(entity.getClass(), \"startSeenByPlayer\")"), content)
    }

    @Test
    fun `entity visibility reflection migration ignores documentation only`() {
        val projectDir = tempDir.resolve("entity-visibility-reflection-docs-only")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve("BossNotes.java")
        file.writeText("""
            package com.example;

            public class BossNotes {
                String note = "declaresVisibilityHook .getMethod( startSeenByPlayer stopSeenByPlayer";
                String docs = ${"\"\"\""}
                    private static boolean hasNativePlayerVisibilityHook(Entity entity) {
                        return declaresVisibilityHook(entity.getClass(), "startSeenByPlayer")
                                || declaresVisibilityHook(entity.getClass(), "stopSeenByPlayer");
                    }

                    private static boolean declaresVisibilityHook(Class<?> entityClass, String methodName) {
                        return entityClass.getMethod(methodName, ServerPlayer.class) != null;
                    }
                    ${"\"\"\""};
            }
        """.trimIndent())
        val before = file.readText()

        val result = pass.apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-entity-visibility-hook-no-reflection" })
        assertEquals(before, file.readText())
    }

    @Test
    fun `registers existing mixin configs when migrating away from manifest registration`() {
        val projectDir = tempDir.resolve("existing-mixin-config-registration")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        resourcesDir.createDirectories()
        metaInfDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            tasks.named('jar', Jar).configure {
                manifest {
                    attributes([
                        "MixinConfigs": "example.mixins.json"
                    ])
                }
            }
        """.trimIndent())
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"

            [[mods]]
            modId="examplemod"

            [[dependencies.examplemod]]
            modId="neoforge"
            type="required"

            [[dependencies.examplemod]]
            modId="minecraft"
            type="required"
        """.trimIndent())
        resourcesDir.resolve("example.mixins.json").writeText("""
            {
              "required": true,
              "package": "com.example.mixin",
              "mixins": [
                "BlockAccessor"
              ]
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val buildGradle = projectDir.resolve("build.gradle").readText()
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-register-existing-mixin-config" })
        assertFalse(buildGradle.contains("MixinConfigs"), buildGradle)
        assertTrue(modsToml.contains("[[mixins]]"), modsToml)
        assertTrue(modsToml.contains("config=\"example.mixins.json\""), modsToml)
    }

    @Test
    fun `forge mdk style mixin config names are registered too`() {
        val projectDir = tempDir.resolve("mdk-mixin-config-registration")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        resourcesDir.createDirectories()
        metaInfDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"

            [[mods]]
            modId="showercore"

            [[dependencies.showercore]]
            modId="neoforge"
            type="required"
        """.trimIndent())
        resourcesDir.resolve("mixins.showercore.json").writeText("""
            {
              "required": true,
              "minVersion": "0.8",
              "package": "mod.crabmod.showercore.mixin",
              "mixins": [
                "PoolEntityFluidMixin"
              ],
              "client": [
                "PoolFloorSittingMixin"
              ],
              "refmap": "mixins.showercore.refmap.json"
            }
        """.trimIndent())
        resourcesDir.resolve("mixins.showercore.refmap.json").writeText("""{"mappings": {}}""")
        resourcesDir.resolve("notaconfig.json").writeText("""{"package": "x", "mixins": []}""")

        val result = pass.apply(projectDir)
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(modsToml.contains("[[mixins]]"), modsToml)
        assertTrue(modsToml.contains("config=\"mixins.showercore.json\""), modsToml)
        assertFalse(modsToml.contains("refmap"), modsToml)
        assertFalse(modsToml.contains("notaconfig"), modsToml)
    }

    @Test
    fun `jar jar wrapped and templated dependencies keep their wrappers and move to neoforge artifacts`() {
        val projectDir = tempDir.resolve("wrapped-dependencies")
        projectDir.resolve("src/main/resources").createDirectories()
        projectDir.resolve("gradle").createDirectories()
        projectDir.resolve("gradle/java.gradle").writeText("""
            apply plugin: 'java'
            java.toolchain.languageVersion = JavaLanguageVersion.of(17)
            sourceCompatibility = '17'
            tasks.withType(JavaCompile).configureEach {
                it.options.release = 17
            }
        """.trimIndent())
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            flywheel_minecraft_version=1.20.1
            flywheel_version=1.0.6-beta-266
            vanillin_version=1.0.0
            registrate_version=MC1.20-1.3.3
            ponder_version=1.0.91
        """.trimIndent())
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            apply(from: "./gradle/java.gradle")

            dependencies {
                modImplementation(jarJar("com.tterrag.registrate:Registrate:${'$'}{registrate_version}"))

                modCompileOnly("dev.engine-room.flywheel:flywheel-forge-api-${'$'}{flywheel_minecraft_version}:${'$'}{flywheel_version}")
                modRuntimeOnly(jarJar("dev.engine-room.flywheel:flywheel-forge-${'$'}{flywheel_minecraft_version}:${'$'}{flywheel_version}") {
                    version {
                        strictly "[1.0,2.0)"
                        prefer flywheel_version
                    }
                })
                modRuntimeOnly("dev.engine-room.vanillin:vanillin-forge-${'$'}{flywheel_minecraft_version}:${'$'}{vanillin_version}")

                jarJar(modImplementation("net.createmod.ponder:Ponder-Forge-${'$'}{minecraft_version}:${'$'}{ponder_version}"))
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText().replace("\r\n", "\n")
        val properties = projectDir.resolve("gradle.properties").readText()
        val javaScript = projectDir.resolve("gradle/java.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(content.contains("implementation(jarJar(\"com.tterrag.registrate:Registrate:${'$'}{registrate_version}\"))"), content)
        assertTrue(content.contains("compileOnly(\"dev.engine-room.flywheel:flywheel-neoforge-api-${'$'}{flywheel_minecraft_version}:${'$'}{flywheel_version}\")"), content)
        assertTrue(content.contains("runtimeOnly(jarJar(\"dev.engine-room.flywheel:flywheel-neoforge-${'$'}{flywheel_minecraft_version}:${'$'}{flywheel_version}\") {"), content)
        assertTrue(content.contains("prefer flywheel_version"), content)
        assertTrue(content.contains("runtimeOnly(\"dev.engine-room.vanillin:vanillin-neoforge-${'$'}{flywheel_minecraft_version}:${'$'}{vanillin_version}\")"), content)
        assertTrue(content.contains("jarJar(implementation(\"net.createmod.ponder:ponder-neoforge:${'$'}{ponder_version}\"))"), content)
        assertFalse(content.contains("modImplementation") || content.contains("modCompileOnly") || content.contains("modRuntimeOnly"), content)
        assertFalse(content.contains("-forge-"), content)
        assertTrue(content.contains("https://maven.createmod.net"), content)
        assertTrue(content.contains("https://maven.ithundxr.dev/snapshots"), content)
        assertTrue(properties.contains("flywheel_version=1.0.6"), properties)
        assertTrue(properties.contains("vanillin_version=1.1.3-41"), properties)
        assertTrue(properties.contains("registrate_version=MC1.21-1.3.0+67"), properties)
        assertTrue(properties.contains("ponder_version=1.0.87+mc1.21.1"), properties)
        assertTrue(properties.contains("flywheel_minecraft_version=1.21.1"), properties)
        assertTrue(javaScript.contains("JavaLanguageVersion.of(21)"), javaScript)
        assertTrue(javaScript.contains("it.options.release = 21"), javaScript)
        assertTrue(javaScript.contains("sourceCompatibility = '21'"), javaScript)
        assertFalse(javaScript.contains("17"), javaScript)
        assertTrue(result.changes.any { it.ruleId == "build-dependency-wrapped-notation" })
    }

    @Test
    fun `bridging classes of a removed dependency keep their surface with inert members`() {
        val projectDir = tempDir.resolve("removed-dependency-stub")
        val srcDir = projectDir.resolve("src/main/java/com/example/compat/removed")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly("example.removed:optional-api:1.0.0")
            }
        """.trimIndent())
        srcDir.resolve("RemovedBridge.java").writeText("""
            package com.example.compat.removed;

            import java.util.function.BiConsumer;
            import example.removed.OptionalApi;
            import example.removed.OptionalHandle;
            import example.removed.OptionalShell;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.state.BlockState;

            public class RemovedBridge {
                private BlockPos startCutPos;

                public RemovedBridge(BlockPos startCutPos) {
                    this.startCutPos = startCutPos;
                }

                public static boolean isDynamicBranch(Block block) {
                    return OptionalApi.isPresent(block) || block instanceof OptionalShell;
                }

                public void destroyBlocks(Level world, BiConsumer<BlockPos, Block> drop) {
                    OptionalHandle start = OptionalApi.get(world.getBlockState(startCutPos));
                    if (start == null) return;
                    drop.accept(startCutPos, start);
                }

                private OptionalHandle helper(Level world, BlockState state) {
                    return OptionalApi.get(state);
                }

                public BlockPos start() {
                    return startCutPos;
                }
            }
        """.trimIndent())

        val result = BuildSystemPass(
            mappingsPrefix = "/mappings/dependency-preparation"
        ).apply(projectDir)
        val build = projectDir.resolve("build.gradle").readText()
        val stub = srcDir.resolve("RemovedBridge.java").readText().replace("\r\n", "\n")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(build.contains("optional-api"), build)
        assertTrue(result.changes.any { it.ruleId == "build-remove-dep" })
        assertTrue(result.changes.any { it.ruleId == "build-removed-dependency-stub" }, result.changes.joinToString("\n") { it.ruleId })
        assertFalse(stub.contains("example.removed"), stub)
        assertFalse(stub.contains("OptionalApi"), stub)
        assertFalse(stub.contains("helper"), stub)
        assertTrue(stub.contains("public static boolean isDynamicBranch(Block block) {\n        return false;\n    }"), stub)
        assertTrue(stub.contains("public void destroyBlocks(Level world, BiConsumer<BlockPos, Block> drop) {\n    }"), stub)
        assertTrue(stub.contains("this.startCutPos = startCutPos;"), stub)
        assertTrue(stub.contains("return startCutPos;"), stub)
    }

    @Test
    fun `publication reobf artifacts disappear with their whole closure`() {
        val projectDir = tempDir.resolve("reobf-artifact-closure")
        projectDir.resolve("src/main/resources").createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            project.publishing {
                publications {
                    mavenJava(MavenPublication) {
                        artifactId base.archivesName.get()
                        from components.java

                        artifact(tasks.reobfJar) {
                            classifier = "all"
                        }
                    }
                }

                repositories {
                    if (project.hasProperty("mavendir")) {
                        maven { url mavendir }
                    }
                }
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText().replace("\r\n", "\n")

        assertFalse(content.contains("reobf"), content)
        assertFalse(content.contains("classifier = \"all\""), content)
        assertTrue(content.contains("from components.java\n"), content)
        assertTrue(content.contains("if (project.hasProperty(\"mavendir\")) {\n            maven { url mavendir }\n        }"), content)
        assertEquals(content.count { it == '{' }, content.count { it == '}' }, content)
    }

    @Test
    fun `test fixture resources ride along with the mod in the run configurations`() {
        val projectDir = tempDir.resolve("test-fixture-resources")
        val fixtures = projectDir.resolve("src/testFixtures/resources/data/hotbath/hotbath/custom_fluids")
        fixtures.createDirectories()
        fixtures.resolve("golden_bath.json").writeText("""{"id": "hotbath:golden_bath"}""")
        projectDir.resolve("src/main/resources").createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            minecraft {
                mappings channel: 'official', version: '1.20.1'
                runs {
                    client {
                        mods {
                            showercore {
                                source sourceSets.main
                            }
                        }
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText().replace("\r\n", "\n")

        assertTrue(result.changes.any { it.ruleId == "build-test-fixture-resources" }, result.changes.joinToString("\n") { it.ruleId })
        assertTrue(content.contains("resources.setSrcDirs(['src/testFixtures/resources'])"), content)
        assertTrue(content.contains("sourceSet(sourceSets.testFixtures)"), content)
        assertEquals(1, Regex("sourceSet\\(sourceSets\\.testFixtures\\)").findAll(content).count(), content)

        val again = pass.apply(projectDir)
        assertFalse(again.changes.any { it.ruleId == "build-test-fixture-resources" })
    }

    @Test
    fun `projects without test fixture resources get no fixture source set`() {
        val projectDir = tempDir.resolve("no-test-fixture-resources")
        projectDir.resolve("src/main/resources").createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        assertFalse(result.changes.any { it.ruleId == "build-test-fixture-resources" })
        assertFalse(projectDir.resolve("build.gradle").readText().contains("testFixtures"))
    }

    @Test
    fun `resolved dependency deduplication keeps a runtime line next to its compile only twin`() {
        val compileOnly = com.modporter.core.transforms.build.NeoForgeCoord(config = "compileOnly", coord = "maven.modrinth:mmlib:1.2.15-1.21.1")
        val runtimeOnly = com.modporter.core.transforms.build.NeoForgeCoord(config = "runtimeOnly", coord = "maven.modrinth:mmlib:1.2.15-1.21.1")
        val implementation = com.modporter.core.transforms.build.NeoForgeCoord(config = "implementation", coord = "maven.modrinth:mmlib:1.2.15-1.21.1")

        val afterCompileOnly = setOf(pass.emittedDependencyKey(compileOnly))
        assertTrue(pass.isCoveredByEmitted(afterCompileOnly, compileOnly))
        assertFalse(pass.isCoveredByEmitted(afterCompileOnly, runtimeOnly))

        val afterImplementation = setOf(pass.emittedDependencyKey(implementation))
        assertTrue(pass.isCoveredByEmitted(afterImplementation, compileOnly))
        assertTrue(pass.isCoveredByEmitted(afterImplementation, runtimeOnly))
    }

    @Test
    fun `migrates class for name compat checks to static API checks`() {
        val projectDir = tempDir.resolve("class-for-name-compat")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        srcDir.createDirectories()
        resourcesDir.createDirectories()
        metaInfDir.createDirectories()
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"

            [[mods]]
            modId="examplemod"
        """.trimIndent())
        resourcesDir.resolve("example.mixins.json").writeText("""
            {
              "required": false,
              "package": "com.example",
              "mixins": [
                "FluidMixin"
              ]
            }
        """.trimIndent())
        srcDir.resolve("CompatManager.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.common.NeoForge;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;

            public class CompatManager {
                private static final Logger LOGGER = LoggerFactory.getLogger(CompatManager.class);

                public static void registerEventHandlers(String modId, Class<?>... handlerClasses) {
                    for (Class<?> handlerClass : handlerClasses) {
                        try {
                            Class.forName(handlerClass.getName(), true, handlerClass.getClassLoader());
                            NeoForge.EVENT_BUS.register(handlerClass);
                        } catch (Throwable e) {
                            throw new RuntimeException("Event handler registration failed", e);
                        }
                    }
                }

                public static void verifyApiClasses(String modId, String... classNames) {
                    for (String className : classNames) {
                        try {
                            Class.forName(className);
                        } catch (ClassNotFoundException e) {
                            throw new RuntimeException("Missing API class " + className, e);
                        }
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("FluidMixin.java").writeText("""
            package com.example;

            public class FluidMixin {
                @SuppressWarnings({"rawtypes", "unchecked"})
                private static Object blockingSpaceType() {
                    try {
                        Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");
                        return Enum.valueOf((Class<? extends Enum>) spaceType.asSubclass(Enum.class), "BLOCKING");
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Create FluidFillingBehaviour.SpaceType is unavailable", e);
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val manager = srcDir.resolve("CompatManager.java").readText()
        val mixin = srcDir.resolve("FluidMixin.java").readText()
        val accessor = srcDir.resolve("modporter/mixin/ModPorterSpaceTypeAccessor.java").readText()
        val generatedMixinConfig = resourcesDir.resolve("examplemod.modporter.mixins.json").readText()
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-enum-mixin-invoker" })
        assertTrue(result.changes.any { it.ruleId == "build-generated-mixin-config" })
        assertTrue(manager.contains("NeoForge.EVENT_BUS.register(handlerClass);"), manager)
        assertTrue(manager.contains("ModList.get().isLoaded(modId)"), manager)
        assertTrue(manager.contains("import net.neoforged.fml.ModList;"), manager)
        assertTrue(mixin.contains("import com.example.modporter.mixin.ModPorterSpaceTypeAccessor;"), mixin)
        assertTrue(mixin.contains("return ModPorterSpaceTypeAccessor.modporter${'$'}valueOf(\"BLOCKING\");"), mixin)
        assertTrue(accessor.contains("package com.example.modporter.mixin;"), accessor)
        assertTrue(accessor.contains("@Mixin(targets = \"com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType\", remap = false)"), accessor)
        assertTrue(accessor.contains("@Invoker(value = \"valueOf\", remap = false)"), accessor)
        assertTrue(generatedMixinConfig.contains("\"package\": \"com.example.modporter.mixin\""), generatedMixinConfig)
        assertTrue(generatedMixinConfig.contains("\"ModPorterSpaceTypeAccessor\""), generatedMixinConfig)
        assertTrue(modsToml.contains("config=\"examplemod.modporter.mixins.json\""), modsToml)
        assertFalse(manager.contains("Class.forName"))
        assertFalse(mixin.contains("Class.forName"))
        assertFalse(mixin.contains("Enum.valueOf"))
        assertFalse(mixin.contains("@SuppressWarnings"))
    }

    @Test
    fun `does not rewrite API verifier when class-for-name appears only in comments or strings`() {
        val projectDir = tempDir.resolve("class-for-name-api-verifier-comment-string")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("CompatManager.java").writeText("""
            package com.example;

            public class CompatManager {
                public static void verifyApiClasses(String modId, String... classNames) {
                    String marker = "Class.forName(";
                    // Class.forName(classNames[0]);
                    if (marker.isEmpty()) {
                        throw new IllegalStateException("unreachable");
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("CompatManager.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(source.contains("""String marker = "Class.forName(";"""), source)
        assertTrue(source.contains("// Class.forName(classNames[0]);"), source)
        assertFalse(source.contains("ModList.get().isLoaded(modId)"), source)
        assertFalse(source.contains("import net.neoforged.fml.ModList;"), source)
    }

    @Test
    fun `does not remove commented class-for-name eager load`() {
        val projectDir = tempDir.resolve("commented-class-for-name-eager-load")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("CompatManager.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.common.NeoForge;

            public class CompatManager {
                public static void registerEventHandler(Class<?> handlerClass) throws Exception {
                    /*
                    Class.forName(handlerClass.getName(), true, handlerClass.getClassLoader());
                    */
                    NeoForge.EVENT_BUS.register(handlerClass);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("CompatManager.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(source.contains("Class.forName(handlerClass.getName(), true, handlerClass.getClassLoader());"), source)
        assertTrue(source.contains("NeoForge.EVENT_BUS.register(handlerClass);"), source)
    }

    @Test
    fun `does not generate enum mixin invoker from commented class-for-name flow`() {
        val projectDir = tempDir.resolve("commented-enum-class-for-name")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("FluidMixin.java").writeText("""
            package com.example;

            public class FluidMixin {
                @SuppressWarnings({"rawtypes", "unchecked"})
                private static Object blockingSpaceType() {
                    // try {
                    //     Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");
                    //     return Enum.valueOf((Class<? extends Enum>) spaceType.asSubclass(Enum.class), "BLOCKING");
                    // } catch (ClassNotFoundException e) {
                    //     throw new IllegalStateException("Create FluidFillingBehaviour.SpaceType is unavailable", e);
                    // }
                    return null;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("FluidMixin.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-enum-mixin-invoker" })
        assertTrue(source.contains("@SuppressWarnings({\"rawtypes\", \"unchecked\"})"), source)
        assertTrue(source.contains("""//     Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");"""), source)
        assertFalse(source.contains("ModPorterSpaceTypeAccessor"), source)
        assertFalse(srcDir.resolve("modporter/mixin/ModPorterSpaceTypeAccessor.java").exists())
    }

    @Test
    fun `generated mixin config resolves templated mods toml mod id from gradle properties`() {
        val projectDir = tempDir.resolve("templated-generated-mixin-config")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        srcDir.createDirectories()
        resourcesDir.createDirectories()
        metaInfDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=hotbath\n")
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="${'$'}{loader_version_range}"

            [[mods]]
            modId="${'$'}{mod_id}"
        """.trimIndent())
        srcDir.resolve("FluidMixin.java").writeText("""
            package com.example;

            public class FluidMixin {
                @SuppressWarnings({"rawtypes", "unchecked"})
                private static Object blockingSpaceType() {
                    try {
                        Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");
                        return Enum.valueOf((Class<? extends Enum>) spaceType.asSubclass(Enum.class), "BLOCKING");
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Create FluidFillingBehaviour.SpaceType is unavailable", e);
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(resourcesDir.resolve("hotbath.modporter.mixins.json").exists())
        assertFalse(resourcesDir.resolve("${'$'}{mod_id}.modporter.mixins.json").exists())
        assertTrue(modsToml.contains("config=\"hotbath.modporter.mixins.json\""), modsToml)
        assertFalse(modsToml.contains("config=\"${'$'}{mod_id}.modporter.mixins.json\""), modsToml)
    }

    @Test
    fun `generated mixin config rejects missing mod id instead of placeholder name`() {
        val projectDir = tempDir.resolve("missing-modid-generated-mixin-config")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val resourcesDir = projectDir.resolve("src/main/resources")
        srcDir.createDirectories()
        resourcesDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("FluidMixin.java").writeText("""
            package com.example;

            public class FluidMixin {
                @SuppressWarnings({"rawtypes", "unchecked"})
                private static Object blockingSpaceType() {
                    try {
                        Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");
                        return Enum.valueOf((Class<? extends Enum>) spaceType.asSubclass(Enum.class), "BLOCKING");
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Create FluidFillingBehaviour.SpaceType is unavailable", e);
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("FluidMixin.java").readText()

        assertTrue(
            result.errors.any { it.contains("Cannot derive generated mixin config name") },
            "Expected missing mod id to hard gate generated mixin config naming; got ${result.errors}"
        )
        assertTrue(source.contains("Class.forName("), source)
        assertFalse(resourcesDir.resolve("modporter.modporter.mixins.json").exists())
        assertFalse(srcDir.resolve("modporter/mixin/ModPorterSpaceTypeAccessor.java").exists())
    }

    @Test
    fun `generated mixin config rejects ambiguous metadata mod ids`() {
        val projectDir = tempDir.resolve("ambiguous-modid-generated-mixin-config")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        srcDir.createDirectories()
        metaInfDir.createDirectories()
        metaInfDir.resolve("mods.toml").writeText("""
            modLoader="javafml"

            [[mods]]
            modId="firstmod"

            [[mods]]
            modId="secondmod"
        """.trimIndent())
        srcDir.resolve("FluidMixin.java").writeText("""
            package com.example;

            public class FluidMixin {
                @SuppressWarnings({"rawtypes", "unchecked"})
                private static Object blockingSpaceType() {
                    try {
                        Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");
                        return Enum.valueOf((Class<? extends Enum>) spaceType.asSubclass(Enum.class), "BLOCKING");
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Create FluidFillingBehaviour.SpaceType is unavailable", e);
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("FluidMixin.java").readText()

        assertTrue(
            result.errors.any { it.contains("Cannot derive generated mixin config name") },
            "Expected ambiguous metadata mod ids to hard gate generated mixin config naming; got ${result.errors}"
        )
        assertTrue(source.contains("Class.forName("), source)
        assertFalse(resourcesDir.resolve("firstmod.modporter.mixins.json").exists())
        assertFalse(resourcesDir.resolve("secondmod.modporter.mixins.json").exists())
        assertFalse(resourcesDir.resolve("modporter.modporter.mixins.json").exists())
        assertFalse(srcDir.resolve("modporter/mixin/ModPorterSpaceTypeAccessor.java").exists())
    }

    @Test
    fun `generated mixin invoker joins conditional parent mixin config`() {
        val projectDir = tempDir.resolve("conditional-parent-mixin-config")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        srcDir.createDirectories()
        resourcesDir.createDirectories()
        metaInfDir.createDirectories()
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"

            [[mods]]
            modId="examplemod"
        """.trimIndent())
        resourcesDir.resolve("example.create.mixins.json").writeText("""
            {
              "required": false,
              "minVersion": "0.8",
              "package": "com.example",
              "compatibilityLevel": "JAVA_21",
              "plugin": "com.example.CreateMixinPlugin",
              "mixins": [
              ],
              "injectors": {
                "defaultRequire": 0
              }
            }
        """.trimIndent())
        srcDir.resolve("FluidMixin.java").writeText("""
            package com.example;

            public class FluidMixin {
                @SuppressWarnings({"rawtypes", "unchecked"})
                private static Object blockingSpaceType() {
                    try {
                        Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");
                        return Enum.valueOf((Class<? extends Enum>) spaceType.asSubclass(Enum.class), "BLOCKING");
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Create FluidFillingBehaviour.SpaceType is unavailable", e);
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val conditionalConfig = resourcesDir.resolve("example.create.mixins.json").readText()
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(conditionalConfig.contains("\"modporter.mixin.ModPorterSpaceTypeAccessor\""), conditionalConfig)
        assertFalse(resourcesDir.resolve("examplemod.modporter.mixins.json").exists())
        assertFalse(modsToml.contains("examplemod.modporter.mixins.json"), modsToml)
    }

    @Test
    fun `generated mixin invoker prefers most specific conditional mixin config`() {
        val projectDir = tempDir.resolve("specific-conditional-mixin-config")
        val srcDir = projectDir.resolve("src/main/java/com/example/create")
        val resourcesDir = projectDir.resolve("src/main/resources")
        val metaInfDir = resourcesDir.resolve("META-INF")
        srcDir.createDirectories()
        resourcesDir.createDirectories()
        metaInfDir.createDirectories()
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"

            [[mods]]
            modId="examplemod"

            [[mixins]]
            config="example.mixins.json"

            [[mixins]]
            config="example.create.mixins.json"
        """.trimIndent())
        resourcesDir.resolve("example.mixins.json").writeText("""
            {
              "required": false,
              "package": "com.example",
              "plugin": "com.example.RootMixinPlugin",
              "mixins": [
                "ExistingMixin",
                "create.modporter.mixin.ModPorterSpaceTypeAccessor"
              ]
            }
        """.trimIndent())
        resourcesDir.resolve("example.create.mixins.json").writeText("""
            {
              "required": false,
              "minVersion": "0.8",
              "package": "com.example.create",
              "compatibilityLevel": "JAVA_21",
              "plugin": "com.example.create.CreateMixinPlugin",
              "mixins": [
              ],
              "injectors": {
                "defaultRequire": 0
              }
            }
        """.trimIndent())
        srcDir.resolve("FluidMixin.java").writeText("""
            package com.example.create;

            public class FluidMixin {
                @SuppressWarnings({"rawtypes", "unchecked"})
                private static Object blockingSpaceType() {
                    try {
                        Class<?> spaceType = Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour${'$'}SpaceType");
                        return Enum.valueOf((Class<? extends Enum>) spaceType.asSubclass(Enum.class), "BLOCKING");
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("Create FluidFillingBehaviour.SpaceType is unavailable", e);
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val parentConfig = resourcesDir.resolve("example.mixins.json").readText()
        val createConfig = resourcesDir.resolve("example.create.mixins.json").readText()
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(createConfig.contains("\"modporter.mixin.ModPorterSpaceTypeAccessor\""), createConfig)
        assertFalse(parentConfig.contains("create.modporter.mixin.ModPorterSpaceTypeAccessor"), parentConfig)
        assertFalse(resourcesDir.resolve("examplemod.modporter.mixins.json").exists())
        assertFalse(modsToml.contains("examplemod.modporter.mixins.json"), modsToml)
    }

    @Test
    fun `adds compile only dependency for reflected optional API packages`() {
        val projectDir = tempDir.resolve("reflected-optional-api-dep")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation "com.example:kept:1.0.0"
            }
        """.trimIndent())
        srcDir.resolve("SeasonCompat.java").writeText("""
            package com.example;

            public class SeasonCompat {
                void resolve() throws Exception {
                    Class.forName("sereneseasons.api.season.SeasonHelper");
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-reflected-optional-api-dependencies" })
        assertTrue(content.contains("""compileOnly "curse.maven:serene-seasons-291874:6182596""""))
        assertTrue(content.contains("https://www.cursemaven.com"))
        assertTrue(content.contains("""includeGroup "curse.maven""""))
    }

    @Test
    fun `reflected optional API dependency scan ignores comments`() {
        val projectDir = tempDir.resolve("reflected-optional-api-comment")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            dependencies {
                implementation "com.example:kept:1.0.0"
            }
        """.trimIndent())
        srcDir.resolve("SeasonCompat.java").writeText("""
            package com.example;

            public class SeasonCompat {
                void resolve() {
                    // Class.forName("sereneseasons.api.season.SeasonHelper");
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-reflected-optional-api-dependencies" })
        assertFalse(content.contains("serene-seasons"), content)
        assertFalse(content.contains("cursemaven"), content)
    }

    @Test
    fun `rewrites class for name isInstance checks without member reflection`() {
        val projectDir = tempDir.resolve("class-for-name-isinstance")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExternalEntityCompat.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.Entity;

            public class ExternalEntityCompat {
                private static Class<?> targetClass = null;
                private static boolean targetClassResolved = false;

                public static boolean isExternalEntity(Entity entity) {
                    try {
                        if (!targetClassResolved) {
                            targetClassResolved = true;
                            targetClass = Class.forName("example.optional.ExternalEntity");
                        }
                        return targetClass != null && targetClass.isInstance(entity);
                    } catch (Exception ignored) {
                        targetClassResolved = true;
                        return false;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExternalEntityCompat.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(content.contains("""return modporterRuntimeInstanceOf(entity, "example.optional.ExternalEntity");"""), content)
        assertTrue(content.contains("private static boolean modporterRuntimeInstanceOf(Object value, String binaryClassName)"))
        assertFalse(content.contains("Class.forName"))
        assertFalse(content.contains("isInstance("))
        assertFalse(content.contains("targetClassResolved"))
    }

    @Test
    fun `class for name isInstance migration ignores commented reflection flow`() {
        val projectDir = tempDir.resolve("class-for-name-isinstance-comment")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExternalEntityCompat.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.Entity;

            public class ExternalEntityCompat {
                private static Class<?> targetClass = null;

                // try {
                //     targetClass = Class.forName("example.optional.ExternalEntity");
                //     return targetClass != null && targetClass.isInstance(entity);
                // } catch (Exception ignored) {
                //     return false;
                // }
                public static boolean isExternalEntity(Entity entity) {
                    return false;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExternalEntityCompat.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(content.contains("return false;"), content)
        assertFalse(content.contains("modporterRuntimeInstanceOf"), content)
    }

    @Test
    fun `rewrites season helper reflection to static optional API calls`() {
        val projectDir = tempDir.resolve("season-helper-static-api")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            dependencies {
            }
        """.trimIndent())
        srcDir.resolve("SeasonCompat.java").writeText("""
            package com.example;

            import java.lang.reflect.Method;
            import net.minecraft.world.level.Level;

            public class SeasonCompat {
                public enum WinterSubSeason { NONE, EARLY, MID, LATE }

                private static boolean seasonResolved = false;
                private static Method seasonHelperGetState;
                private static Method seasonStateGetSubSeason;
                private static Method subSeasonName;

                private static void resolveSeasonApi() {
                    if (seasonResolved) return;
                    seasonResolved = true;
                    try {
                        Class<?> seasonHelper = Class.forName("sereneseasons.api.season.SeasonHelper");
                        seasonHelperGetState = seasonHelper.getMethod("getSeasonState", Level.class);
                        Class<?> iSeasonState = Class.forName("sereneseasons.api.season.ISeasonState");
                        seasonStateGetSubSeason = iSeasonState.getMethod("getSubSeason");
                        Class<?> subSeason = Class.forName("sereneseasons.api.season.Season${'$'}SubSeason");
                        subSeasonName = subSeason.getMethod("name");
                    } catch (Exception ignored) {
                        seasonHelperGetState = null;
                    }
                }

                public static WinterSubSeason getWinterSubSeason(Level level) {
                    resolveSeasonApi();
                    if (seasonHelperGetState == null) return WinterSubSeason.NONE;
                    try {
                        Object state = seasonHelperGetState.invoke(null, level);
                        if (state == null) return WinterSubSeason.NONE;
                        Object sub = seasonStateGetSubSeason.invoke(state);
                        if (sub == null) return WinterSubSeason.NONE;
                        String name = (String) subSeasonName.invoke(sub);
                        if ("EARLY_WINTER".equals(name)) return WinterSubSeason.EARLY;
                        return WinterSubSeason.NONE;
                    } catch (Exception ignored) {
                        return WinterSubSeason.NONE;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("SeasonCompat.java").readText()
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(content.contains("import sereneseasons.api.season.ISeasonState;"), content)
        assertTrue(content.contains("import sereneseasons.api.season.Season.SubSeason;"), content)
        assertTrue(content.contains("import sereneseasons.api.season.SeasonHelper;"), content)
        assertTrue(content.contains("ISeasonState state = SeasonHelper.getSeasonState(level);"), content)
        assertTrue(content.contains("SubSeason sub = state.getSubSeason();"), content)
        assertTrue(content.contains("String name = sub.name();"), content)
        assertTrue(content.contains("if (state == null) return WinterSubSeason.NONE;"), content)
        assertTrue(content.contains("if (sub == null) return WinterSubSeason.NONE;"), content)
        assertFalse(content.contains("Season.SubSeason sub"), content)
        assertTrue(build.contains("""compileOnly "curse.maven:serene-seasons-291874:6182596""""))
        assertFalse(content.contains("Class.forName"))
        assertFalse(content.contains(".getMethod("))
        assertFalse(content.contains("java.lang.reflect.Method"))
        assertFalse(content.contains(".invoke("))
    }

    @Test
    fun `season helper reflection migration ignores commented reflection flow`() {
        val projectDir = tempDir.resolve("season-helper-comment")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            dependencies {
            }
        """.trimIndent())
        srcDir.resolve("SeasonCompat.java").writeText("""
            package com.example;

            import net.minecraft.world.level.Level;

            public class SeasonCompat {
                public enum WinterSubSeason { NONE, EARLY, MID, LATE }

                private static void resolveSeasonApi() {
                    // Class<?> seasonHelper = Class.forName("sereneseasons.api.season.SeasonHelper");
                    // seasonHelperGetState = seasonHelper.getMethod("getSeasonState", Level.class);
                    // Class<?> iSeasonState = Class.forName("sereneseasons.api.season.ISeasonState");
                    // seasonStateGetSubSeason = iSeasonState.getMethod("getSubSeason");
                    // Class<?> subSeason = Class.forName("sereneseasons.api.season.Season${'$'}SubSeason");
                    // subSeasonName = subSeason.getMethod("name");
                }

                public static WinterSubSeason getWinterSubSeason(Level level) {
                    return WinterSubSeason.NONE;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("SeasonCompat.java").readText()
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertFalse(result.changes.any { it.ruleId == "build-reflected-optional-api-dependencies" })
        assertFalse(content.contains("SeasonHelper.getSeasonState"), content)
        assertFalse(build.contains("serene-seasons"), build)
    }

    @Test
    fun `season helper reflection migration ignores text block reflection documentation`() {
        val projectDir = tempDir.resolve("season-helper-text-block")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            dependencies {
            }
        """.trimIndent())
        srcDir.resolve("SeasonCompat.java").writeText(listOf(
            "package com.example;",
            "",
            "import net.minecraft.world.level.Level;",
            "",
            "public class SeasonCompat {",
            "    public enum WinterSubSeason { NONE, EARLY, MID, LATE }",
            "",
            "    private static void resolveSeasonApi() {",
            "        String documented = \"\"\"",
            "            Class<?> seasonHelper = Class.forName(\"sereneseasons.api.season.SeasonHelper\");",
            "            seasonHelperGetState = seasonHelper.getMethod(\"getSeasonState\", Level.class);",
            "            Class<?> iSeasonState = Class.forName(\"sereneseasons.api.season.ISeasonState\");",
            "            seasonStateGetSubSeason = iSeasonState.getMethod(\"getSubSeason\");",
            "            Class<?> subSeason = Class.forName(\"sereneseasons.api.season.Season${'$'}SubSeason\");",
            "            subSeasonName = subSeason.getMethod(\"name\");",
            "            \"\"\";",
            "        if (documented.isEmpty()) {",
            "            throw new IllegalStateException(\"unreachable\");",
            "        }",
            "    }",
            "",
            "    public static WinterSubSeason getWinterSubSeason(Level level) {",
            "        return WinterSubSeason.NONE;",
            "    }",
            "}",
            ""
        ).joinToString(System.lineSeparator()))

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("SeasonCompat.java").readText()
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertFalse(result.changes.any { it.ruleId == "build-reflected-optional-api-dependencies" })
        assertTrue(content.contains("String documented = \"\"\""), content)
        assertFalse(content.contains("SeasonHelper.getSeasonState(level)"), content)
        assertFalse(content.contains("import sereneseasons.api.season.SeasonHelper;"), content)
        assertFalse(build.contains("serene-seasons"), build)
    }

    @Test
    fun `rewrites DeferredHolder reflection collectors to explicit registry lists`() {
        val projectDir = tempDir.resolve("deferredholder-reflection-collector")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("BlockItemRegistry.java").writeText("""
            package com.example;

            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.registries.DeferredRegister;

            public class BlockItemRegistry {
                public static final DeferredRegister<Item> ITEMS = null;
                public static final DeferredHolder<Item, Item> FIRST = ITEMS.register("first", Item::new);
                public static final DeferredHolder<Item, Item> SECOND = ITEMS.register("second", Item::new);
                private static final DeferredHolder<Item, Item> INTERNAL = ITEMS.register("internal", Item::new);
            }
        """.trimIndent())
        srcDir.resolve("BlockItemsTest.java").writeText("""
            package com.example;

            import java.lang.reflect.Field;
            import java.lang.reflect.Modifier;
            import java.util.ArrayList;
            import java.util.List;
            import net.minecraft.world.item.Item;
            import net.neoforged.neoforge.registries.DeferredHolder;

            public class BlockItemsTest {
                @SuppressWarnings("unchecked")
                private static List<DeferredHolder<Item, Item>> collect() {
                    List<DeferredHolder<Item, Item>> out = new ArrayList<>();
                    for (Field f : BlockItemRegistry.class.getDeclaredFields()) {
                        int mods = f.getModifiers();
                        if (!(Modifier.isPublic(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods))) continue;
                        if (!DeferredHolder.class.isAssignableFrom(f.getType())) continue;
                        try {
                            out.add((DeferredHolder<Item, Item>) f.get(null));
                        } catch (IllegalAccessException ignored) {}
                    }
                    return out;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("BlockItemsTest.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-deferredholder-reflection-collector" })
        assertTrue(content.contains("out.add(BlockItemRegistry.FIRST);"), content)
        assertTrue(content.contains("out.add(BlockItemRegistry.SECOND);"), content)
        assertFalse(content.contains("INTERNAL"), content)
        assertFalse(content.contains("java.lang.reflect"), content)
        assertFalse(content.contains("getDeclaredFields"), content)
    }

    @Test
    fun `migrates creative selected tab reflection to access transformer`() {
        val projectDir = tempDir.resolve("creative-selectedtab-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            neoForge {
                version = "21.1.219"
            }
        """.trimIndent())
        srcDir.resolve("CreativeFilters.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
            import net.minecraft.world.item.CreativeModeTab;
            import net.minecraft.world.item.CreativeModeTabs;

            public class CreativeFilters {
              private static CreativeModeTab getSelectedTab() {
                try {
                  java.lang.reflect.Field field =
                      CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
                  field.setAccessible(true);
                  return (CreativeModeTab) field.get(null);
                } catch (ReflectiveOperationException | ClassCastException e) {
                  return CreativeModeTabs.getDefaultTab();
                }
              }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("CreativeFilters.java").readText()
        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-creative-selectedtab-at" })
        assertTrue(content.contains("CreativeModeInventoryScreen.selectedTab"), content)
        assertTrue(at.contains("public net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen selectedTab"))
        assertTrue(build.contains("validateAccessTransformers = true"), build)
        assertFalse(content.contains("getDeclaredField"))
        assertFalse(content.contains("setAccessible"))
        assertFalse(content.contains("java.lang.reflect"))
    }

    @Test
    fun `creative selected tab reflection migration ignores comments outside method evidence`() {
        val projectDir = tempDir.resolve("creative-selectedtab-comment-no-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("CreativeFilters.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
            import net.minecraft.world.item.CreativeModeTab;
            import net.minecraft.world.item.CreativeModeTabs;

            public class CreativeFilters {
              // CreativeModeInventoryScreen.class.getDeclaredField("selectedTab");
              // field.setAccessible(true);
              // field.get(null);

              private static CreativeModeTab getSelectedTab() {
                return CreativeModeTabs.getDefaultTab();
              }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("CreativeFilters.java").readText()
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-creative-selectedtab-at" })
        assertTrue(content.contains("return CreativeModeTabs.getDefaultTab();"), content)
        assertFalse(content.contains("CreativeModeInventoryScreen.selectedTab"), content)
        assertFalse(atFile.exists())
    }

    @Test
    fun `rewrites class-for-name presence checks to class resource probes`() {
        val projectDir = tempDir.resolve("class-for-name-presence")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ClientSetup.java").writeText("""
            package com.example;

            public class ClientSetup {
                public static boolean optionalPresent = false;

                public static void setup() {
                    try {
                        Class.forName("net.optional.ExampleConfig");
                        optionalPresent = true;
                    } catch (ClassNotFoundException e) {
                        optionalPresent = false;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientSetup.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertFalse(content.contains("Class.forName"), content)
        assertTrue(content.contains("""optionalPresent = modporterClassResourcePresent("net.optional.ExampleConfig");"""), content)
        assertTrue(content.contains("private static boolean modporterClassResourcePresent(String binaryClassName)"), content)
    }

    @Test
    fun `does not rewrite commented class-for-name presence checks`() {
        val projectDir = tempDir.resolve("commented-class-for-name-presence")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ClientSetup.java").writeText("""
            package com.example;

            public class ClientSetup {
                public static boolean optionalPresent = false;

                public static void setup() {
                    // try {
                    //     Class.forName("net.optional.ExampleConfig");
                    //     optionalPresent = true;
                    // } catch (ClassNotFoundException e) {
                    //     optionalPresent = false;
                    // }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientSetup.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertTrue(content.contains("""//     Class.forName("net.optional.ExampleConfig");"""), content)
        assertFalse(content.contains("modporterClassResourcePresent"), content)
    }

    @Test
    fun `rewrites class-for-name loader presence probes to class resource probes`() {
        val projectDir = tempDir.resolve("class-for-name-loader-presence")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExampleMixinPlugin.java").writeText("""
            package com.example;

            public class ExampleMixinPlugin {
                private boolean optionalA = false;
                private boolean optionalB = false;

                public void onLoad(String mixinPackage) {
                    try {
                        Class.forName("net.optional.First", false, getClass().getClassLoader());
                        optionalA = true;
                    } catch (ClassNotFoundException e) {
                    }
                    try {
                        Class.forName("net.optional.Second", false, Thread.currentThread().getContextClassLoader());
                        optionalB = true;
                    } catch (ClassNotFoundException e) {
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExampleMixinPlugin.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-class-forname-no-reflection" })
        assertFalse(content.contains("Class.forName"), content)
        assertFalse(content.contains("ClassNotFoundException"), content)
        assertTrue(content.contains("""optionalA = modporterClassResourcePresent("net.optional.First");"""), content)
        assertTrue(content.contains("""optionalB = modporterClassResourcePresent("net.optional.Second");"""), content)
        assertTrue(content.contains("private static boolean modporterClassResourcePresent(String binaryClassName)"), content)
    }

    @Test
    fun `removes ForgeGradle JarJar import and task type after ModDev migration`() {
        val projectDir = tempDir.resolve("forgegradle-jarjar-import")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            import net.minecraftforge.gradle.userdev.tasks.JarJar

            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            tasks.named('jarJar', JarJar).configure {
                archiveClassifier = ''
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-remove-forgegradle-import" })
        assertTrue(result.changes.any { it.ruleId == "build-remove-forgegradle-jarjar-type" })
        assertTrue(result.changes.any { it.ruleId == "build-remove-jarjar-archive-classifier" })
        assertFalse(content.contains("net.minecraftforge.gradle.userdev.tasks.JarJar"), content)
        assertFalse(content.contains(", JarJar"), content)
        assertFalse(content.contains("archiveClassifier = ''"), content)
    }

    @Test
    fun `removes ModdingX ForgeGradle companion plugins and recreates sourceJar task`() {
        val projectDir = tempDir.resolve("moddingx-forgegradle-companion")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'java-library'
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
                id 'org.moddingx.modgradle.mapping' version '[4,5)'
                id 'org.moddingx.modgradle.sourcejar' version '[4,5)' apply false
            }

            apply plugin: 'org.moddingx.modgradle.sourcejar'

            publishing {
                publications {
                    mavenJava(MavenPublication) {
                        artifact sourceJar
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-remove-forgegradle-companion-plugin" })
        assertTrue(result.changes.any { it.ruleId == "build-remove-forgegradle-companion-apply" })
        assertTrue(result.changes.any { it.ruleId == "build-sourcejar-task" })
        assertFalse(content.contains("org.moddingx.modgradle.mapping"), content)
        assertFalse(content.contains("org.moddingx.modgradle.sourcejar"), content)
        assertTrue(content.contains("tasks.register('sourceJar', Jar)"), content)
        assertTrue(content.contains("from sourceSets.main.allSource"), content)
        assertTrue(content.contains("artifact sourceJar"), content)
    }

    @Test
    fun `migrates srg access transformer members through the official mapping table`() {
        val projectDir = tempDir.resolve("at-srg-members")
        val metaInf = projectDir.resolve("src/main/resources/META-INF")
        metaInf.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0.24,6.2)'
            }
            minecraft {
                accessTransformer = file('src/main/resources/META-INF/accesstransformer.cfg')
            }
        """.trimIndent())
        metaInf.resolve("accesstransformer.cfg").writeText("""
            public net.minecraft.world.entity.LivingEntity f_20937_ #fallFlyTicks
            public net.minecraft.world.entity.Entity m_20291_(I)Z #getSharedFlag
            public net.minecraft.world.entity.Entity m_20115_(IZ)V #setSharedFlag
            public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool f_210559_ # rawTemplates
        """.trimIndent() + "\n")

        val result = pass.apply(projectDir)
        val content = metaInf.resolve("accesstransformer.cfg").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(content.contains("public net.minecraft.world.entity.LivingEntity fallFlyTicks"), content)
        assertTrue(content.contains("public net.minecraft.world.entity.Entity getSharedFlag(I)Z"), content)
        assertTrue(content.contains("public net.minecraft.world.entity.Entity setSharedFlag(IZ)V"), content)
        assertTrue(content.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates"), content)
        assertFalse(Regex("""[fm]_[0-9]+_""").containsMatchIn(content), content)
    }

    @Test
    fun `access transformers without a 1_21_1 target are dropped and srg ids resolve through both mapping files`() {
        val projectDir = tempDir.resolve("at-missing-targets")
        val metaInf = projectDir.resolve("src/main/resources/META-INF")
        metaInf.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        metaInf.resolve("accesstransformer.cfg").writeText("""
            public net.minecraft.client.gui.screens.TitleScreen panorama # panorama
            public net.minecraft.server.MinecraftServer f_129744_ # storageSource
            public net.minecraft.world.entity.LivingEntity jumping # jumping
            public net.minecraft.world.item.alchemy.PotionBrewing POTION_MIXES # POTION_MIXES
            public net.minecraft.world.item.crafting.RecipeManager recipes # recipes
            public net.minecraft.world.level.chunk.PaletteResize
            public net.minecraft.client.gui.components.CommandSuggestions${'$'}SuggestionsList <init>(Lnet/minecraft/client/gui/components/CommandSuggestions;IIILjava/util/List;Z)V
        """.trimIndent() + "\n")

        val result = pass.apply(projectDir)
        val content = metaInf.resolve("accesstransformer.cfg").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(content.contains("public net.minecraft.server.MinecraftServer storageSource"), content)
        assertTrue(content.contains("public net.minecraft.world.entity.LivingEntity jumping"), content)
        assertTrue(content.contains("public net.minecraft.world.level.chunk.PaletteResize"), content)
        assertTrue(content.contains("CommandSuggestions${'$'}SuggestionsList <init>("), content)
        assertFalse(content.contains("panorama"), content)
        assertFalse(content.contains("POTION_MIXES"), content)
        assertFalse(content.contains("RecipeManager recipes"), content)
        assertFalse(content.contains("f_129744_"), content)
        assertEquals(3, result.changes.count { it.ruleId == "build-access-transformer-missing-target" }, result.changes.joinToString("\n") { it.before })
    }

    @Test
    fun `maps deobf wrapped jarJar range pin blocks to moddev version constraints`() {
        val projectDir = tempDir.resolve("jarjar-deobf-range-pin")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0.24,6.2)'
            }

            dependencies {
                implementation fg.deobf("com.aetherteam.nitrogen:nitrogen_internals:${'$'}{project.nitrogen_version}")

                jarJar fg.deobf("com.aetherteam.nitrogen:nitrogen_internals:${'$'}{project.nitrogen_version}") {
                    jarJar.ranged(it, "[${'$'}{project.nitrogen_version},)")
                    jarJar.pin(it, "${'$'}{project.nitrogen_version}")
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-jarjar-version-constraints" }, result.changes.joinToString("\n") { it.ruleId })
        assertFalse(content.contains("fg.deobf"), content)
        assertFalse(content.contains("jarJar.ranged"), content)
        // Known artifacts resolve like every other declaration; the literal version replaces the Forge-era property.
        assertTrue(content.contains("""jarJar("com.aetherteam.nitrogen:nitrogen_internals:1.21.1-1.1.25-neoforge") {"""), content)
        assertTrue(content.contains("""strictly "[1.21.1-1.1.25-neoforge,)""""), content)
        assertTrue(content.contains("""prefer "1.21.1-1.1.25-neoforge""""), content)
        assertFalse(content.contains("""jarJar "com"""), content)
    }

    @Test
    fun `removes bundled jarJar mixin dependencies and normalizes range pin dsl`() {
        val projectDir = tempDir.resolve("jarjar-range-pin")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            dependencies {
                jarJar("io.github.llamalad7:mixinextras-forge:${'$'}{project.mixinextras_version}") {
                    jarJar.ranged(it, "[${'$'}{project.mixinextras_version},)")
                    jarJar.pin(it, "${'$'}{project.mixinextras_version}")
                }

                jarJar "com.example:library:${'$'}{project.library_version}" {
                    jarJar.ranged(it, "[${'$'}{project.library_version},)")
                    jarJar.pin(it, "${'$'}{project.library_version}")
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-remove-bundled-mixin-dependency" })
        assertTrue(result.changes.any { it.ruleId == "build-jarjar-version-constraints" })
        assertFalse(content.contains("mixinextras"), content)
        assertFalse(content.contains("jarJar.ranged"), content)
        assertFalse(content.contains("jarJar.pin"), content)
        // ForgeGradle range/pin become ModDev rich-version constraints, as in official 1.21.1 ports.
        assertTrue(content.contains("""jarJar("com.example:library:${'$'}{project.library_version}") {"""), content)
        assertTrue(content.contains("""strictly "[${'$'}{project.library_version},)""""), content)
        assertTrue(content.contains("""prefer "${'$'}{project.library_version}""""), content)
        assertTrue(content.contains("version {"), content)
    }

    @Test
    fun `publishing uses archive jar instead of ModDev jarJar task`() {
        val projectDir = tempDir.resolve("jarjar-publication")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }

            publishing {
                publications {
                    mavenJava(MavenPublication) {
                        artifact project.tasks.jarJar
                    }
                }
            }

            curseforge {
                project {
                    mainArtifact(tasks.jarJar)
                }
            }

            modrinth {
                uploadFile = tasks.jarJar
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-jarjar-publication-archive" })
        assertTrue(content.contains("artifact tasks.named('jar')"), content)
        assertTrue(content.contains("mainArtifact(tasks.jar)"), content)
        assertTrue(content.contains("uploadFile = tasks.jar"), content)
        assertFalse(content.contains("tasks.jarJar"), content)
    }

    @Test
    fun `rewrites add-layers renderer reflection to public renderer lookup API`() {
        val projectDir = tempDir.resolve("addlayers-renderers-at")
        val srcDir = projectDir.resolve("src/main/java/com/example/client")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ClientSetup.java").writeText("""
            package com.example.client;

            import net.minecraft.client.model.EntityModel;
            import net.minecraft.client.renderer.entity.EntityRenderer;
            import net.minecraft.client.renderer.entity.LivingEntityRenderer;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.player.Player;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.EntityRenderersEvent;
            import java.lang.reflect.Field;
            import java.util.Map;

            public class ClientSetup {
                private static Field field_EntityRenderersEvent${'$'}AddLayers_renderers;

                @SubscribeEvent
                @SuppressWarnings("unchecked")
                public static void attachRenderLayers(EntityRenderersEvent.AddLayers event) {
                    if (field_EntityRenderersEvent${'$'}AddLayers_renderers == null) {
                        try {
                            field_EntityRenderersEvent${'$'}AddLayers_renderers = EntityRenderersEvent.AddLayers.class.getDeclaredField("renderers");
                            field_EntityRenderersEvent${'$'}AddLayers_renderers.setAccessible(true);
                        } catch (NoSuchFieldException e) {
                            e.printStackTrace();
                        }
                    }
                    if (field_EntityRenderersEvent${'$'}AddLayers_renderers != null) {
                        event.getSkins().forEach(renderer -> {
                            LivingEntityRenderer<Player, EntityModel<Player>> skin = event.getSkin(renderer);
                            attachRenderLayers(skin);
                        });
                        try {
                            ((Map<EntityType<?>, EntityRenderer<?>>) field_EntityRenderersEvent${'$'}AddLayers_renderers.get(event)).values().stream().
                                    filter(LivingEntityRenderer.class::isInstance).map(LivingEntityRenderer.class::cast).forEach(ClientSetup::attachRenderLayers);
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        }
                    }
                }

                private static void attachRenderLayers(LivingEntityRenderer<?, ?> renderer) {
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientSetup.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-entityrenderers-addlayers-api" })
        assertFalse(content.contains("getDeclaredField"), content)
        assertFalse(content.contains("setAccessible"), content)
        assertFalse(content.contains("java.lang.reflect.Field"), content)
        assertFalse(content.contains("field_EntityRenderersEvent"), content)
        assertTrue(content.contains("event.getSkins().forEach(renderer -> {"), content)
        assertTrue(content.contains("attachRenderLayers(skin);"), content)
        assertTrue(content.contains("});"), content)
        assertTrue(content.contains("event.getEntityTypes().stream().map(event::getRenderer)."), content)
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(!atFile.exists() || !atFile.readText().contains("EntityRenderersEvent${'$'}AddLayers renderers"))
    }

    @Test
    fun `add-layers renderer reflection migration ignores comments outside method evidence`() {
        val projectDir = tempDir.resolve("addlayers-renderers-comment-markers")
        val srcDir = projectDir.resolve("src/main/java/com/example/client")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ClientSetup.java").writeText("""
            package com.example.client;

            import net.minecraft.client.model.EntityModel;
            import net.minecraft.client.renderer.entity.EntityRenderer;
            import net.minecraft.client.renderer.entity.LivingEntityRenderer;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.player.Player;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.client.event.EntityRenderersEvent;
            import java.util.Map;

            public class ClientSetup {
                // EntityRenderersEvent.AddLayers.class.getDeclaredField("renderers");
                // field_EntityRenderersEvent${'$'}AddLayers_renderers.setAccessible(true);

                @SubscribeEvent
                @SuppressWarnings("unchecked")
                public static void attachRenderLayers(EntityRenderersEvent.AddLayers event) {
                    event.getSkins().forEach(renderer -> {
                        LivingEntityRenderer<Player, EntityModel<Player>> skin = event.getSkin(renderer);
                        attachRenderLayers(skin);
                    });
                    ((Map<EntityType<?>, EntityRenderer<?>>) renderers.get(event)).values().stream().
                            filter(LivingEntityRenderer.class::isInstance).map(LivingEntityRenderer.class::cast).forEach(ClientSetup::attachRenderLayers);
                }

                private static void attachRenderLayers(LivingEntityRenderer<?, ?> renderer) {
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientSetup.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-entityrenderers-addlayers-api" })
        assertTrue(content.contains("renderers.get(event)"), content)
        assertTrue(content.contains("getDeclaredField(\"renderers\")"), content)
    }

    @Test
    fun `rewrites obfuscation method handles to mixin invoker calls`() {
        val projectDir = tempDir.resolve("methodhandle-mixin")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val metaInfDir = projectDir.resolve("src/main/resources/META-INF")
        srcDir.createDirectories()
        metaInfDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        metaInfDir.resolve("neoforge.mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[1,)"

            [[mods]]
            modId="examplemod"
        """.trimIndent())
        srcDir.resolve("EntityUtil.java").writeText("""
            package com.example;

            import net.minecraft.core.Direction;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.world.entity.LivingEntity;
            import net.minecraft.world.entity.decoration.HangingEntity;
            import net.minecraft.world.entity.decoration.Painting;
            import net.neoforged.fml.util.ObfuscationReflectionHelper;
            import org.jetbrains.annotations.Nullable;
            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;
            import java.lang.reflect.Method;

            public class EntityUtil {
                private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
                private static final Method reflectedDeathSound = ObfuscationReflectionHelper.findMethod(LivingEntity.class, "m_5592_");
                private static final MethodHandle boundDeathSound;
                private static final Method reflectedDirectionSetter = ObfuscationReflectionHelper.findMethod(HangingEntity.class, "m_6022_", Direction.class);
                private static final MethodHandle boundDirectionSetter;

                static {
                    MethodHandle temporaryDeathSound = null;
                    MethodHandle temporaryDirectionSetter = null;
                    try {
                        temporaryDeathSound = LOOKUP.unreflect(reflectedDeathSound);
                        temporaryDirectionSetter = LOOKUP.unreflect(reflectedDirectionSetter);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                    boundDeathSound = temporaryDeathSound;
                    boundDirectionSetter = temporaryDirectionSetter;
                }

                @Nullable
                public static SoundEvent getDeathSound(LivingEntity living) {
                    SoundEvent sound = null;
                    if (boundDeathSound != null) {
                        try {
                            sound = (SoundEvent) boundDeathSound.invokeExact(living);
                        } catch (Throwable e) {
                        }
                    }
                    return sound;
                }

                public static void setPaintingDirection(HangingEntity painting, Direction direction) {
                    try {
                        boundDirectionSetter.invoke(painting, direction);
                    } catch (Throwable throwable) {
                        throwable.printStackTrace();
                    }
                }

                public static void setLocalPaintingDirection(Direction direction) {
                    Painting painting = null;
                    try {
                        boundDirectionSetter.invoke(painting, direction);
                    } catch (Throwable throwable) {
                        throwable.printStackTrace();
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("EntityUtil.java").readText()
        val livingInvoker = srcDir.resolve("modporter/mixin/ModPorterLivingEntityInvoker.java").readText()
        val hangingInvoker = srcDir.resolve("modporter/mixin/ModPorterHangingEntityInvoker.java").readText()
        val mixinConfig = projectDir.resolve("src/main/resources/examplemod.modporter.mixins.json").readText()
        val modsToml = metaInfDir.resolve("neoforge.mods.toml").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-obfuscation-methodhandle-mixin-invoker" })
        assertTrue(result.changes.any { it.ruleId == "build-protected-method-mixin-invoker" })
        assertTrue(result.changes.any { it.ruleId == "build-register-generated-mixin-config" })
        assertFalse(content.contains("ObfuscationReflectionHelper"), content)
        assertFalse(content.contains("MethodHandle"), content)
        assertFalse(content.contains("java.lang.reflect.Method"), content)
        assertTrue(content.contains("import com.example.modporter.mixin.ModPorterLivingEntityInvoker;"), content)
        assertTrue(content.contains("import com.example.modporter.mixin.ModPorterHangingEntityInvoker;"), content)
        assertTrue(content.contains("return ((ModPorterLivingEntityInvoker) living).modporter${'$'}getDeathSound();"), content)
        assertTrue(content.contains("((ModPorterHangingEntityInvoker) painting).modporter${'$'}setDirection(direction);"), content)
        assertTrue(content.contains("Painting painting = null;"), content)
        assertTrue(livingInvoker.contains("package com.example.modporter.mixin;"), livingInvoker)
        assertTrue(livingInvoker.contains("@Mixin(LivingEntity.class)"), livingInvoker)
        assertTrue(livingInvoker.contains("@Invoker(\"getDeathSound\")"), livingInvoker)
        assertTrue(hangingInvoker.contains("package com.example.modporter.mixin;"), hangingInvoker)
        assertTrue(hangingInvoker.contains("@Mixin(HangingEntity.class)"), hangingInvoker)
        assertTrue(hangingInvoker.contains("@Invoker(\"setDirection\")"), hangingInvoker)
        assertTrue(mixinConfig.contains("\"package\": \"com.example.modporter.mixin\""), mixinConfig)
        assertTrue(mixinConfig.contains("\"ModPorterLivingEntityInvoker\""), mixinConfig)
        assertTrue(mixinConfig.contains("\"ModPorterHangingEntityInvoker\""), mixinConfig)
        assertTrue(modsToml.contains("[[mixins]]"), modsToml)
        assertTrue(modsToml.contains("config=\"examplemod.modporter.mixins.json\""), modsToml)
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(!atFile.exists() || !atFile.readText().contains("getDeathSound()"))
        assertTrue(!atFile.exists() || !atFile.readText().contains("setDirection("))
    }

    @Test
    fun `obfuscation method handle migration ignores commented binding evidence`() {
        val projectDir = tempDir.resolve("methodhandle-comment-markers")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("EntityUtil.java").writeText("""
            package com.example;

            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.world.entity.LivingEntity;
            import org.jetbrains.annotations.Nullable;

            public class EntityUtil {
                // private static final Method reflectedDeathSound = ObfuscationReflectionHelper.findMethod(LivingEntity.class, "m_5592_");
                // private static final MethodHandle boundDeathSound;
                // boundDeathSound = LOOKUP.unreflect(reflectedDeathSound);

                @Nullable
                public static SoundEvent getDeathSound(LivingEntity living) {
                    return null;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("EntityUtil.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-obfuscation-methodhandle-mixin-invoker" })
        assertFalse(srcDir.resolve("modporter/mixin/ModPorterLivingEntityInvoker.java").exists())
        assertTrue(content.contains("return null;"), content)
    }

    @Test
    fun `guards existing client only listener method references from dedicated server class loading`() {
        val projectDir = tempDir.resolve("client-listener-dist-guard")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    modEventBus.addListener(ClientEvent::registerRecipeSerializers);
                    modEventBus.addListener(ClientEvent::registerParticleFactories);
                    modEventBus.addListener(this::commonSetup);
                }

                private void commonSetup(FMLCommonSetupEvent event) {
                }
            }
        """.trimIndent())
        srcDir.resolve("ClientEvent.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.registries.RegisterEvent;
            import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

            public class ClientEvent {
                public static void registerRecipeSerializers(RegisterEvent event) {
                }

                public static void registerParticleFactories(RegisterParticleProvidersEvent event) {
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-client-only-listener-dist-guard" })
        assertTrue(content.contains("if (net.neoforged.fml.loading.FMLLoader.getDist() == net.neoforged.api.distmarker.Dist.CLIENT) {"), content)
        assertTrue(Regex("""(?m)^[ \t]*modEventBus\.addListener\(ClientEvent::registerRecipeSerializers\);\s*$""").containsMatchIn(content), content)
        assertFalse(Regex("""(?m)^[ \t]*if\s*\([^\r\n]*FMLLoader\.getDist\(\)[^\r\n]*\)\s*\{\s*\r?\n[ \t]*modEventBus\.addListener\(ClientEvent::registerRecipeSerializers\);""").containsMatchIn(content), content)
        assertTrue(content.contains("modEventBus.addListener(ClientEvent::registerParticleFactories);"), content)
        assertTrue(content.contains("modEventBus.addListener(this::commonSetup);"), content)
        assertFalse(Regex("""if\s*\([^\r\n]*FMLLoader\.getDist\(\)[^\r\n]*\)\s*\{\s*\r?\n[ \t]*modEventBus\.addListener\(this::commonSetup\);""").containsMatchIn(content))
    }

    @Test
    fun `client only listener guard ignores comments and strings`() {
        val projectDir = tempDir.resolve("client-listener-comment-no-guard")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                public ExampleMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    modEventBus.addListener(ClientEvent::commonSetup);
                }
            }
        """.trimIndent())
        srcDir.resolve("ClientEvent.java").writeText("""
            package com.example;

            public class ClientEvent {
                String note = "net.minecraft.client.gui.screens.Screen RegisterParticleProvidersEvent";

                public static void commonSetup(Object event) {
                    // net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent
                    // net.minecraft.client.gui.screens.Screen
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExampleMod.java").readText()

        assertFalse(result.changes.any { it.ruleId == "build-client-only-listener-dist-guard" })
        assertTrue(content.contains("modEventBus.addListener(ClientEvent::commonSetup);"), content)
        assertFalse(content.contains("FMLLoader.getDist()"), content)
    }

    @Test
    fun `marks client lifecycle event bus subscribers as client dist`() {
        val projectDir = tempDir.resolve("client-subscriber-dist")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
            import net.neoforged.neoforge.common.NeoForge;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD)
                public static class ModEvents {
                    @SubscribeEvent
                    public static void onClientSetup(FMLClientSetupEvent event) {
                        NeoForge.EVENT_BUS.register(new ClientScreenEvents());
                    }
                }
            }
        """.trimIndent())
        srcDir.resolve("ClientScreenEvents.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.Screen;

            public class ClientScreenEvents {
                Screen screen;
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-client-eventbus-subscriber-dist" })
        assertTrue(content.contains("@EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = net.neoforged.api.distmarker.Dist.CLIENT)"), content)
    }

    @Test
    fun `client event bus subscriber dist marking ignores comments and strings`() {
        val projectDir = tempDir.resolve("client-subscriber-comment-no-dist")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id("net.neoforged.moddev") version "2.0.140"
            }
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD)
                public static class ModEvents {
                    String note = "FMLClientSetupEvent RegisterShadersEvent net.minecraft.client.gui.screens.Screen";

                    @SubscribeEvent
                    public static void commonSetup(FMLCommonSetupEvent event) {
                        // FMLClientSetupEvent RegisterParticleProvidersEvent
                        // net.neoforged.neoforge.client.event.ModelEvent
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ExampleMod.java").readText()

        assertFalse(result.changes.any { it.ruleId == "build-client-eventbus-subscriber-dist" })
        assertTrue(content.contains("@EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD)"), content)
        assertFalse(content.contains("value = net.neoforged.api.distmarker.Dist.CLIENT"), content)
    }

    @Test
    fun `restores getTag on local TagKey holder types after nbt text migration`() {
        val projectDir = tempDir.resolve("restore-local-gettag")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Filters.java").writeText("""
            package com.example;

            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;

            public class Filters {
                void add(TagFilter filter) {
                    if (filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag() != null) {
                        use(filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag());
                    }
                }

                void use(Object value) {}

                public static class TagFilter {
                    private final TagKey<Item> tag = null;

                    public TagKey<Item> getTag() {
                        return this.tag;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("Filters.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-restore-non-itemstack-gettag" })
        assertTrue(content.contains("if (filter.getTag() != null)"), content)
        assertTrue(content.contains("use(filter.getTag());"), content)
        assertFalse(content.contains("filter.getOrDefault"))
    }

    @Test
    fun `restore getTag migration ignores comments strings and text blocks`() {
        val projectDir = tempDir.resolve("restore-local-gettag-docs")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Filters.java").writeText("""
            package com.example;

            import net.minecraft.tags.TagKey;
            import net.minecraft.world.item.Item;

            public class Filters {
                String note = "filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()";
                String docs = ${"\"\"\""}
                    public static class TagFilter {
                        public TagKey<Item> getTag() {
                            return null;
                        }
                    }
                    void add(TagFilter filter) {
                        use(filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag());
                    }
                    ${"\"\"\""};

                void add(TagFilter filter) {
                    // filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()
                    use(filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag());
                }

                void use(Object value) {}

                public static class TagFilter {
                    private final TagKey<Item> tag = null;

                    public TagKey<Item> getTag() {
                        return this.tag;
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("Filters.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-restore-non-itemstack-gettag" })
        assertTrue(content.contains("use(filter.getTag());"), content)
        assertTrue(content.contains("String note = \"filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()\";"), content)
        assertTrue(content.contains("use(filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag());"), content)
        assertTrue(content.contains("// filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()"), content)
    }

    @Test
    fun `restore getTag migration ignores documentation only`() {
        val projectDir = tempDir.resolve("restore-local-gettag-docs-only")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve("FilterNotes.java")
        file.writeText("""
            package com.example;

            public class FilterNotes {
                String note = "TagKey< getTag() filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag()";
                String docs = ${"\"\"\""}
                    public static class TagFilter {
                        public TagKey<Item> getTag() {
                            return null;
                        }
                    }
                    void add(TagFilter filter) {
                        use(filter.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag());
                    }
                    ${"\"\"\""};
            }
        """.trimIndent())
        val before = file.readText()

        val result = pass.apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-restore-non-itemstack-gettag" })
        assertEquals(before, file.readText())
    }

    @Test
    fun `passes model resource location id from modify baking result lambda`() {
        val projectDir = tempDir.resolve("modelresource-id")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ClientModels.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.client.event.ModelEvent;

            public class ClientModels {
                public static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
                    event.getModels().replaceAll((location, model) ->
                        ModelHooks.shouldWrap(location) ? new Wrapped(model) : model);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientModels.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-modelresource-location-id" })
        assertTrue(content.contains("ModelHooks.shouldWrap(location.id())"), content)
        assertFalse(content.contains("shouldWrap(location)"))
    }

    @Test
    fun `model resource location id migration ignores comments strings and text blocks`() {
        val projectDir = tempDir.resolve("modelresource-id-docs")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ClientModels.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.client.event.ModelEvent;

            public class ClientModels {
                String note = "ModelHooks.shouldWrap(location)";
                String docs = ${"\"\"\""}
                    public static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
                        event.getModels().replaceAll((location, model) ->
                            ModelHooks.shouldWrap(location) ? model : null);
                    }
                    ${"\"\"\""};

                public static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
                    // ModelHooks.shouldWrap(location)
                    event.getModels().replaceAll((location, model) ->
                        ModelHooks.shouldWrap(location) ? new Wrapped(model) : model);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientModels.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-modelresource-location-id" })
        assertTrue(content.contains("ModelHooks.shouldWrap(location.id()) ? new Wrapped(model) : model);"), content)
        assertTrue(content.contains("String note = \"ModelHooks.shouldWrap(location)\";"), content)
        assertTrue(content.contains("ModelHooks.shouldWrap(location) ? model : null);"), content)
        assertTrue(content.contains("// ModelHooks.shouldWrap(location)"), content)
    }

    @Test
    fun `model resource location id migration ignores documentation only`() {
        val projectDir = tempDir.resolve("modelresource-id-docs-only")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve("ClientModelNotes.java")
        file.writeText("""
            package com.example;

            public class ClientModelNotes {
                String note = "ModelEvent.ModifyBakingResult event.getModels().replaceAll ModelHooks.shouldWrap(location)";
                String docs = ${"\"\"\""}
                    public static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
                        event.getModels().replaceAll((location, model) ->
                            ModelHooks.shouldWrap(location) ? model : null);
                    }
                    ${"\"\"\""};
            }
        """.trimIndent())
        val before = file.readText()

        val result = pass.apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-modelresource-location-id" })
        assertEquals(before, file.readText())
    }

    @Test
    fun `retargets client color handler mixin imports to event package shape`() {
        val projectDir = tempDir.resolve("client-colorhandler-package")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ColorHandlerMixin.java").writeText("""
            package com.example;

            import org.spongepowered.asm.mixin.Mixin;
            import examplemod.client.ColorHandler;

            @Mixin(ColorHandler.class)
            public class ColorHandlerMixin {
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = srcDir.resolve("ColorHandlerMixin.java").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-client-event-colorhandler-target" })
        assertTrue(content.contains("import examplemod.client.event.ColorHandler;"), content)
        assertFalse(content.contains("import examplemod.client.ColorHandler;"))
    }

    @Test
    fun `client color handler mixin retarget ignores comments strings and text blocks`() {
        val projectDir = tempDir.resolve("client-colorhandler-package-docs")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ColorHandlerMixin.java").writeText("""
            package com.example;

            import org.spongepowered.asm.mixin.Mixin;
            import examplemod.client.ColorHandler;

            @Mixin(ColorHandler.class)
            public class ColorHandlerMixin {
                String note = "@Mixin(examplemod.client.ColorHandler.class)";
                String docs = ${"\"\"\""}
                    import docs.client.ColorHandler;
                    @Mixin(docs.client.ColorHandler.class)
                    ${"\"\"\""};
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ColorHandlerMixin.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-client-event-colorhandler-target" })
        assertTrue(content.contains("import examplemod.client.event.ColorHandler;"), content)
        assertTrue(content.contains("String note = \"@Mixin(examplemod.client.ColorHandler.class)\";"), content)
        assertTrue(content.contains("import docs.client.ColorHandler;"), content)
        assertFalse(content.contains("import docs.client.event.ColorHandler;"), content)
    }

    @Test
    fun `client color handler mixin retarget ignores documentation only`() {
        val projectDir = tempDir.resolve("client-colorhandler-package-docs-only")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val file = srcDir.resolve("ColorHandlerNotes.java")
        file.writeText("""
            package com.example;

            public class ColorHandlerNotes {
                String note = "@Mixin(examplemod.client.ColorHandler.class)";
                String docs = ${"\"\"\""}
                    import examplemod.client.ColorHandler;
                    @Mixin(examplemod.client.ColorHandler.class)
                    ${"\"\"\""};
            }
        """.trimIndent())
        val before = file.readText()

        val result = pass.apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-client-event-colorhandler-target" })
        assertEquals(before, file.readText())
    }

    @Test
    fun `migrates stale access transformers to explicit valid 121 targets`() {
        val projectDir = tempDir.resolve("legacy-at")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        atDir.createDirectories()
        atDir.resolve("accesstransformer.cfg").writeText("""
            # No API yet for Trunk Placers yet
            public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType <init>(Lcom/mojang/serialization/Codec;)V # ctor
            public net.minecraft.client.resources.model.ModelBakery f_119234_ # UNREFERENCED_TEXTURES
            public net.minecraft.world.level.chunk.ChunkGenerator m_223138_(Lnet/minecraft/core/Holder;Lnet/minecraft/world/level/levelgen/RandomState;)Ljava/util/List; # getPlacementsForStructure
            public net.minecraft.world.entity.ai.goal.GoalSelector f_25345_ # availableGoals
            protected net.minecraft.world.level.levelgen.structure.StructurePiece f_73379_ # rotation
            public net.minecraft.world.entity.LivingEntity m_21275_(Lnet/minecraft/world/damagesource/DamageSource;)Z # isDamageSourceBlocked
            public net.minecraft.client.model.HumanoidModel m_102875_(Lnet/minecraft/world/entity/LivingEntity;)V # poseRightArm
            public net.minecraft.world.level.block.FireBlock m_53444_(Lnet/minecraft/world/level/block/Block;II)V # setFlammable
            public net.minecraft.world.level.saveddata.maps.MapItemSavedData f_77894_ # decorations
            public net.minecraft.client.multiplayer.ClientAdvancements f_104390_ # advancementToProgress
            public net.minecraft.world.entity.Entity f_19815_ # dimensions
            public net.minecraft.world.entity.Mob m_21424_(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V # maybeDisableShield
            public net.minecraft.client.gui.Gui f_92980_ # vignetteBrightness
            public net.minecraft.world.entity.ai.control.MoveControl f_24981_ # operation
            public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer f_70263_ # baseHeight
            public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer f_70264_ # heightRandA
            public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer f_70265_ # heightRandB
            public net.minecraft.world.entity.animal.Parrot f_29358_ # MOB_SOUND_MAP
            public net.minecraft.entity.ai.brain.task.GiveHeroGiftsTask field_220403_a # GIFTS
            public net.minecraft.village.PointOfInterestType func_221052_a(Lnet/minecraft/village/PointOfInterestType;)Lnet/minecraft/village/PointOfInterestType; # registerBlockStates
            public net.minecraft.world.level.block.ComposterBlock m_51920_(FLnet/minecraft/world/level/ItemLike;)V # add
            public-f net.minecraft.world.item.AxeItem f_150683_ # STRIPPABLES
            public-f net.minecraft.world.entity.player.Inventory f_35978_ # player
            public net.minecraft.world.level.BaseSpawner f_45451_ # maxNearbyEntities
            public net.minecraft.world.level.BaseSpawner f_45449_ # spawnCount
            public net.minecraft.world.level.BaseSpawner f_45453_ # spawnRange
            public net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration f_159157_ #floorLevel
            public net.minecraft.world.level.block.FlowerPotBlock m_153267_()Z # isEmpty
            public net.minecraft.server.level.ChunkMap m_183719_()Lnet/minecraft/world/level/chunk/ChunkGenerator; # generator
            public net.minecraft.world.level.biome.Biome f_47435_ # TEMPERATURE_NOISE
            public-f net.minecraft.world.level.biome.BiomeSource f_47891_ # possibleBiomes
            public net.minecraft.world.entity.decoration.Painting m_218891_(Lnet/minecraft/core/Holder;)V # setVariant
            public net.minecraft.world.level.block.DispenserBlock f_52661_ # DISPENSER_REGISTRY
            public net.minecraft.client.gui.Gui m_93024_(Lnet/minecraft/world/phys/HitResult;)Z # canRenderCrosshairForSpectator
            public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData f_256718_ # centerX
            public net.minecraft.world.level.levelgen.feature.TreeFeature m_225251_(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Ljava/util/Set;Ljava/util/Set;Ljava/util/Set;)Lnet/minecraft/world/phys/shapes/DiscreteVoxelShape; # updateLeaves
            public net.minecraft.world.level.levelgen.carver.WorldCarver m_159418_(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/Aquifer;)Lnet/minecraft/world/level/block/state/BlockState; # getCarveState
            public net.minecraft.world.level.levelgen.synth.BlendedNoise f_192799_ # xzScale
            public net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator${'$'}Context f_226046_ # decorationSetter
            public net.minecraft.world.level.chunk.ChunkGenerator m_223104_(Lnet/minecraft/world/level/levelgen/structure/StructureSet${'$'}StructureSelectionEntry;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/core/SectionPos;)Z # tryGenerateStructure
            public net.minecraft.world.level.storage.loot.LootTable m_230924_(Lit/unimi/dsi/fastutil/objects/ObjectArrayList;ILnet/minecraft/util/RandomSource;)V # shuffleAndSplitItems
            public net.minecraft.world.level.block.state.BlockBehaviour f_60442_ # material
        """.trimIndent())

        pass.apply(projectDir)

        val at = atDir.resolve("accesstransformer.cfg").readText()
        assertTrue(at.contains("public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType <init>(Lcom/mojang/serialization/MapCodec;)V # ctor"))
        assertTrue(at.contains("public net.minecraft.world.entity.ai.goal.GoalSelector availableGoals # availableGoals"))
        assertTrue(at.contains("protected net.minecraft.world.level.levelgen.structure.StructurePiece rotation # rotation"))
        assertTrue(at.contains("public net.minecraft.world.entity.LivingEntity isDamageSourceBlocked(Lnet/minecraft/world/damagesource/DamageSource;)Z # isDamageSourceBlocked"))
        assertTrue(at.contains("public net.minecraft.client.model.HumanoidModel poseRightArm(Lnet/minecraft/world/entity/LivingEntity;)V # poseRightArm"))
        assertTrue(at.contains("public net.minecraft.world.level.block.FireBlock setFlammable(Lnet/minecraft/world/level/block/Block;II)V # setFlammable"))
        assertTrue(at.contains("public net.minecraft.world.level.saveddata.maps.MapItemSavedData decorations # decorations"))
        assertTrue(at.contains("public net.minecraft.client.multiplayer.ClientAdvancements progress # advancementToProgress"))
        assertTrue(at.contains("public net.minecraft.world.entity.Entity dimensions # dimensions"))
        assertTrue(at.contains("public net.minecraft.client.gui.Gui vignetteBrightness # vignetteBrightness"))
        assertTrue(at.contains("public net.minecraft.world.entity.ai.control.MoveControl operation # operation"))
        assertTrue(at.contains("public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer baseHeight # baseHeight"))
        assertTrue(at.contains("public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer heightRandA # heightRandA"))
        assertTrue(at.contains("public net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer heightRandB # heightRandB"))
        assertTrue(at.contains("public net.minecraft.world.entity.animal.Parrot MOB_SOUND_MAP # MOB_SOUND_MAP"))
        assertTrue(at.contains("public net.minecraft.world.entity.ai.behavior.GiveGiftToHero GIFTS # GIFTS"))
        assertTrue(at.contains("public net.minecraft.world.level.block.ComposterBlock add(FLnet/minecraft/world/level/ItemLike;)V # add"))
        assertTrue(at.contains("public-f net.minecraft.world.item.AxeItem STRIPPABLES # STRIPPABLES"))
        assertTrue(at.contains("public-f net.minecraft.world.entity.player.Inventory player # player"))
        assertTrue(at.contains("public net.minecraft.world.level.BaseSpawner maxNearbyEntities # maxNearbyEntities"))
        assertTrue(at.contains("public net.minecraft.world.level.BaseSpawner spawnCount # spawnCount"))
        assertTrue(at.contains("public net.minecraft.world.level.BaseSpawner spawnRange # spawnRange"))
        assertTrue(at.contains("public net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration floorLevel # floorLevel"))
        assertTrue(at.contains("public net.minecraft.world.level.block.FlowerPotBlock isEmpty()Z # isEmpty"))
        assertTrue(at.contains("public net.minecraft.server.level.ChunkMap generator()Lnet/minecraft/world/level/chunk/ChunkGenerator; # generator"))
        assertTrue(at.contains("public net.minecraft.world.level.biome.Biome TEMPERATURE_NOISE # TEMPERATURE_NOISE"))
        assertTrue(at.contains("public-f net.minecraft.world.level.biome.BiomeSource possibleBiomes # possibleBiomes"))
        assertTrue(at.contains("public net.minecraft.world.entity.decoration.Painting setVariant(Lnet/minecraft/core/Holder;)V # setVariant"))
        assertTrue(at.contains("public net.minecraft.world.level.block.DispenserBlock DISPENSER_REGISTRY # DISPENSER_REGISTRY"))
        assertTrue(at.contains("public net.minecraft.client.gui.Gui canRenderCrosshairForSpectator(Lnet/minecraft/world/phys/HitResult;)Z # canRenderCrosshairForSpectator"))
        assertTrue(at.contains("public-f net.minecraft.world.level.saveddata.maps.MapItemSavedData centerX # centerX"))
        assertTrue(at.contains("public net.minecraft.world.level.levelgen.feature.TreeFeature updateLeaves(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Ljava/util/Set;Ljava/util/Set;Ljava/util/Set;)Lnet/minecraft/world/phys/shapes/DiscreteVoxelShape; # updateLeaves"))
        assertTrue(at.contains("public net.minecraft.world.level.levelgen.carver.WorldCarver getCarveState(Lnet/minecraft/world/level/levelgen/carver/CarvingContext;Lnet/minecraft/world/level/levelgen/carver/CarverConfiguration;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/Aquifer;)Lnet/minecraft/world/level/block/state/BlockState; # getCarveState"))
        assertTrue(at.contains("public net.minecraft.world.level.levelgen.synth.BlendedNoise xzScale # xzScale"))
        assertTrue(at.contains("public net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator${'$'}Context decorationSetter # decorationSetter"))
        assertTrue(at.contains("public net.minecraft.world.level.chunk.ChunkGenerator tryGenerateStructure(Lnet/minecraft/world/level/levelgen/structure/StructureSet${'$'}StructureSelectionEntry;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/core/SectionPos;)Z # tryGenerateStructure"))
        assertTrue(at.contains("public net.minecraft.world.level.storage.loot.LootTable shuffleAndSplitItems(Lit/unimi/dsi/fastutil/objects/ObjectArrayList;ILnet/minecraft/util/RandomSource;)V # shuffleAndSplitItems"))
        assertFalse(at.contains("Lcom/mojang/serialization/Codec;)V"))
        assertFalse(at.contains("UNREFERENCED_TEXTURES"))
        assertFalse(at.contains("getPlacementsForStructure"))
        assertFalse(at.contains("f_119234_"))
        assertFalse(at.contains("f_47891_"))
        assertFalse(at.contains("m_223138_"))
        assertFalse(at.contains("f_60442_"))
        assertFalse(at.contains("func_221052_a"))
        assertFalse(at.contains("registerBlockStates"))
        assertFalse(at.contains("BlockBehaviour material"))
        assertFalse(at.contains("m_21424_"))
        assertFalse(at.contains("maybeDisableShield"))
    }

    @Test
    fun `unknown access transformer comments do not become member names`() {
        val projectDir = tempDir.resolve("unknown-comment-at")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        atDir.createDirectories()
        atDir.resolve("accesstransformer.cfg").writeText(
            listOf(
                "public net.minecraft.world.entity.Entity f_123456_ # bogusMember",
                "public net.minecraft.world.entity.Mob m_999999_()V # fakeMethod"
            ).joinToString(System.lineSeparator(), postfix = System.lineSeparator())
        )

        val result = pass.apply(projectDir)

        val at = atDir.resolve("accesstransformer.cfg").readText()
        assertTrue(result.changes.none { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.world.entity.Entity f_123456_ # bogusMember"), at)
        assertTrue(at.contains("public net.minecraft.world.entity.Mob m_999999_()V # fakeMethod"), at)
        assertFalse(at.contains("public net.minecraft.world.entity.Entity bogusMember"), at)
        assertFalse(at.contains("public net.minecraft.world.entity.Mob fakeMethod()V"), at)
    }

    @Test
    fun `migrates common projectile menu worldgen archaeology and tnt access transformers`() {
        val projectDir = tempDir.resolve("common-at-members")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        atDir.createDirectories()
        atDir.resolve("accesstransformer.cfg").writeText("""
            public net.minecraft.world.entity.projectile.FishingHook f_37089_ # nibble
            public net.minecraft.world.entity.projectile.FishingHook f_37094_ # hookedIn
            public net.minecraft.world.inventory.ItemCombinerMenu f_39769_ # inputSlots
            public net.minecraft.world.inventory.ItemCombinerMenu f_39770_ # access
            protected-f net.minecraft.world.entity.projectile.FishingHook f_37096_ # luck
            protected-f net.minecraft.world.entity.projectile.FishingHook f_37097_ # lureSpeed
            public net.minecraft.world.level.block.state.properties.IntegerProperty f_223001_ # max
            public net.minecraft.world.entity.vehicle.Boat${'$'}Type <init>(Ljava/lang/String;ILnet/minecraft/world/level/block/Block;Ljava/lang/String;)V # <init>
            public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool f_210559_ # rawTemplates
            public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool f_210560_ # templates
            public net.minecraft.world.level.block.entity.BrushableBlockEntity f_276563_ # item
            public net.minecraft.world.entity.item.PrimedTnt f_32072_ # owner
            public net.minecraft.world.level.block.SaplingBlock f_55975_ # treeGrower
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = atDir.resolve("accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.world.entity.projectile.FishingHook nibble # nibble"), at)
        assertTrue(at.contains("public net.minecraft.world.entity.projectile.FishingHook hookedIn # hookedIn"), at)
        assertTrue(at.contains("public net.minecraft.world.inventory.ItemCombinerMenu inputSlots # inputSlots"), at)
        assertTrue(at.contains("public net.minecraft.world.inventory.ItemCombinerMenu access # access"), at)
        assertTrue(at.contains("protected-f net.minecraft.world.entity.projectile.FishingHook luck # luck"), at)
        assertTrue(at.contains("protected-f net.minecraft.world.entity.projectile.FishingHook lureSpeed # lureSpeed"), at)
        assertTrue(at.contains("public net.minecraft.world.level.block.state.properties.IntegerProperty max # max"), at)
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool rawTemplates # rawTemplates"), at)
        assertTrue(at.contains("public-f net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool templates # templates"), at)
        assertTrue(at.contains("public net.minecraft.world.level.block.entity.BrushableBlockEntity item # item"), at)
        assertTrue(at.contains("public net.minecraft.world.entity.item.PrimedTnt owner # owner"), at)
        assertTrue(at.contains("public net.minecraft.world.level.block.SaplingBlock treeGrower # treeGrower"), at)
        // NeoForge 1.21.1 still declares Boat.Type(String, int, Block, String); the class table keeps the entry
        assertTrue(at.contains("Boat${'$'}Type <init>"), at)
        assertFalse(at.contains("f_37089_"), at)
        assertFalse(at.contains("f_39769_"), at)
        assertFalse(at.contains("f_223001_"), at)
        assertFalse(at.contains("f_210559_"), at)
        assertFalse(at.contains("f_276563_"), at)
        assertFalse(at.contains("f_32072_"), at)
        assertFalse(at.contains("f_55975_"), at)
    }

    @Test
    fun `keeps wildcard access transformer entries`() {
        val projectDir = tempDir.resolve("wildcard-at")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        atDir.createDirectories()
        atDir.resolve("accesstransformer.cfg").writeText("""
            protected net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity *
            public net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity *()
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = atDir.resolve("accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(at.contains("protected net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity *"), at)
        assertTrue(at.contains("public net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity *()"), at)
    }

    @Test
    fun `drops access transformers for removed 121 inner classes`() {
        val projectDir = tempDir.resolve("removed-inner-class-at")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        atDir.createDirectories()
        atDir.resolve("accesstransformer.cfg").writeText("""
            public net.minecraft.client.gui.Gui${'$'}HeartType
            public net.minecraft.world.item.crafting.SimpleCookingSerializer${'$'}CookieBaker
            public net.minecraft.client.gui.screens.TitleScreen${'$'}WarningLabel
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = atDir.resolve("accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.client.gui.Gui${'$'}HeartType"))
        assertFalse(at.contains("SimpleCookingSerializer${'$'}CookieBaker"))
        assertFalse(at.contains("TitleScreen${'$'}WarningLabel"))
    }

    @Test
    fun `migrates client camera and renderer access transformers to 121 named members`() {
        val projectDir = tempDir.resolve("client-renderer-at")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        atDir.createDirectories()
        atDir.resolve("accesstransformer.cfg").writeText("""
            public net.minecraft.client.Camera m_90568_(DDD)V # move
            public net.minecraft.client.Camera m_90572_(FF)V # setRotation
            public net.minecraft.client.Camera m_90566_(D)D # getMaxZoom
            public net.minecraft.client.renderer.entity.EntityRenderDispatcher f_114362_ # renderers
            public net.minecraft.client.renderer.entity.EntityRenderDispatcher f_114363_ # playerRenderers
            public net.minecraft.client.renderer.entity.player.PlayerRenderer m_117775_(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V # renderHand
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = atDir.resolve("accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.client.Camera move(FFF)V # move"), at)
        assertTrue(at.contains("public net.minecraft.client.Camera setRotation(FF)V # setRotation"), at)
        assertTrue(at.contains("public net.minecraft.client.Camera getMaxZoom(F)F # getMaxZoom"), at)
        assertTrue(at.contains("public net.minecraft.client.renderer.entity.EntityRenderDispatcher renderers # renderers"), at)
        assertTrue(at.contains("public net.minecraft.client.renderer.entity.EntityRenderDispatcher playerRenderers # playerRenderers"), at)
        assertTrue(at.contains("public net.minecraft.client.renderer.entity.player.PlayerRenderer renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V # renderHand"), at)
        assertFalse(at.contains("m_90568_"), at)
        assertFalse(at.contains("m_90572_"), at)
        assertFalse(at.contains("m_90566_"), at)
        assertFalse(at.contains("f_114362_"), at)
        assertFalse(at.contains("f_114363_"), at)
        assertFalse(at.contains("m_117775_"), at)
    }

    @Test
    fun `adds entity render dispatcher renderers access transformer when source uses renderer map`() {
        val projectDir = tempDir.resolve("entity-renderer-dispatcher-renderers-at")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.neoforged.moddev' version '2.0.107'
            }
        """.trimIndent())
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("RendererLookup.java").writeText("""
            package com.example;

            import net.minecraft.client.Minecraft;
            import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
            import net.minecraft.world.entity.EntityType;

            public class RendererLookup {
                public Object direct(EntityType<?> type) {
                    return Minecraft.getInstance().getEntityRenderDispatcher().renderers.get(type);
                }

                public Object typed(EntityRenderDispatcher dispatcher, EntityType<?> type) {
                    return dispatcher.renderers.get(type);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        val build = projectDir.resolve("build.gradle").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.client.renderer.entity.EntityRenderDispatcher renderers"), at)
        assertTrue(build.contains("accessTransformers"), build)
    }

    @Test
    fun `adds default attributes suppliers access transformer when generated helper uses supplier map`() {
        val projectDir = tempDir.resolve("default-attributes-suppliers-at")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.neoforged.moddev' version '2.0.107'
            }
        """.trimIndent())
        val srcDir = projectDir.resolve("src/main/java/com/modporter/generated/example/compat")
        srcDir.createDirectories()
        srcDir.resolve("DefaultAttributesCompat.java").writeText("""
            package com.modporter.generated.example.compat;

            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

            public final class DefaultAttributesCompat {
                public static Object get(EntityType<?> type) {
                    return DefaultAttributes.SUPPLIERS.get(type);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        val build = projectDir.resolve("build.gradle").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.world.entity.ai.attributes.DefaultAttributes SUPPLIERS"), at)
        assertTrue(build.contains("accessTransformers"), build)
    }

    @Test
    fun `adds server player init menu access transformer when source calls protected menu initializer`() {
        val projectDir = tempDir.resolve("server-player-init-menu-at")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.neoforged.moddev' version '2.0.107'
            }
        """.trimIndent())
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ServerMenuAccess.java").writeText("""
            package com.example;

            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.server.network.ServerGamePacketListenerImpl;

            public class ServerMenuAccess {
                public void direct(ServerPlayer player) {
                    player.initMenu(player.inventoryMenu);
                }

                public void listener(ServerGamePacketListenerImpl listener) {
                    listener.player.initMenu(listener.player.inventoryMenu);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        val build = projectDir.resolve("build.gradle").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.server.level.ServerPlayer initMenu(Lnet/minecraft/world/inventory/AbstractContainerMenu;)V"), at)
        assertTrue(build.contains("accessTransformers"), build)
    }

    @Test
    fun `adds fired weapon arrow access transformer when migrated projectile source needs it`() {
        val projectDir = tempDir.resolve("projectile-fired-weapon-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.neoforged.moddev' version '2.0.107'
            }
        """.trimIndent())
        srcDir.resolve("ExampleProjectileHooks.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.projectile.AbstractArrow;

            public class ExampleProjectileHooks {
                public static void rememberWeapon(AbstractArrow arrow) {
                    arrow.firedFromWeapon = null;
                    arrow.setPierceLevel((byte) 1);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        val build = projectDir.resolve("build.gradle").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.world.entity.projectile.AbstractArrow firedFromWeapon"), at)
        assertTrue(at.contains("public net.minecraft.world.entity.projectile.AbstractArrow setPierceLevel(B)V"), at)
        assertTrue(build.contains("accessTransformers"), build)
    }

    @Test
    fun `abstract arrow access transformer collection ignores comments and strings`() {
        val projectDir = tempDir.resolve("projectile-comment-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ProjectileNotes.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.projectile.AbstractArrow;

            public class ProjectileNotes {
                String note = "AbstractArrow arrow; arrow.firedFromWeapon = null; arrow.setPierceLevel((byte) 1);";

                void describe() {
                    // AbstractArrow arrow; arrow.firedFromWeapon = null; arrow.setPierceLevel((byte) 1);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertFalse(atFile.exists())
    }

    @Test
    fun `adds arrow pierce access transformer for subclass self calls`() {
        val projectDir = tempDir.resolve("projectile-subclass-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.neoforged.moddev' version '2.0.107'
            }
        """.trimIndent())
        srcDir.resolve("PiercingArrow.java").writeText("""
            package com.example;

            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.projectile.AbstractArrow;
            import net.minecraft.world.level.Level;

            public class PiercingArrow extends AbstractArrow {
                public PiercingArrow(EntityType<? extends PiercingArrow> type, Level level) {
                    super(type, level);
                    this.setPierceLevel((byte) 1);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-access-transformer-entries-121" })
        assertTrue(at.contains("public net.minecraft.world.entity.projectile.AbstractArrow setPierceLevel(B)V"), at)
    }

    @Test
    fun `removes TitleScreen accessors for fields and inner classes removed in 121`() {
        val projectDir = tempDir.resolve("removed-title-screen-accessors")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("TitleScreenAccessor.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.components.SplashRenderer;
            import net.minecraft.client.gui.screens.TitleScreen;
            import net.minecraft.client.renderer.PanoramaRenderer;
            import net.neoforged.neoforge.client.gui.TitleScreenModUpdateIndicator;
            import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.Mutable;
            import org.spongepowered.asm.mixin.gen.Accessor;

            @Mixin(TitleScreen.class)
            public interface TitleScreenAccessor {
                @Accessor("splash")
                SplashRenderer example${'$'}getSplash();

                @Mutable
                @Accessor("panorama")
                void example${'$'}setPanorama(PanoramaRenderer panorama);

                @Accessor(value = "modUpdateNotification", remap = false)
                TitleScreenModUpdateIndicator example${'$'}getModUpdateNotification();

                @Accessor(value = "modUpdateNotification", remap = false)
                void example${'$'}setModUpdateNotification(TitleScreenModUpdateIndicator widget);

                @Accessor
                TitleScreen.WarningLabel example${'$'}getWarningLabel();
            }
        """.trimIndent())
        srcDir.resolve("ExampleTitleScreen.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.TitleScreen;
            import net.minecraft.client.renderer.PanoramaRenderer;

            public class ExampleTitleScreen extends TitleScreen {
                public ExampleTitleScreen() {
                    TitleScreenAccessor accessor = (TitleScreenAccessor) this;
                    accessor.example${'$'}setPanorama(new PanoramaRenderer(CUBE_MAP));
                    accessor.example${'$'}setModUpdateNotification(new ExampleModUpdateIndicator(this));
                    accessor.example${'$'}getModUpdateNotification().init();
                }
            }
        """.trimIndent())
        srcDir.resolve("ExampleModUpdateIndicator.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.TitleScreen;
            import net.neoforged.neoforge.client.gui.TitleScreenModUpdateIndicator;

            public class ExampleModUpdateIndicator extends TitleScreenModUpdateIndicator {
                public ExampleModUpdateIndicator(TitleScreen screen) {
                    super(screen);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val accessor = srcDir.resolve("TitleScreenAccessor.java").readText()
        val screen = srcDir.resolve("ExampleTitleScreen.java").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-title-screen-removed-accessors" })
        assertTrue(result.changes.any { it.ruleId == "build-title-screen-removed-accessor-calls" })
        assertTrue(
            result.changes.any { it.ruleId == "build-title-screen-update-indicator-class" },
            result.changes.joinToString("\n") { "${it.ruleId}: ${it.file.fileName}" } + "\n\n" + screen
        )
        assertTrue(accessor.contains("SplashRenderer example${'$'}getSplash()"))
        assertFalse(accessor.contains("PanoramaRenderer"))
        assertFalse(accessor.contains("TitleScreenModUpdateIndicator"))
        assertFalse(accessor.contains("WarningLabel"))
        assertFalse(screen.contains("example${'$'}setPanorama"))
        assertFalse(screen.contains("example${'$'}setModUpdateNotification"))
        assertFalse(screen.contains("example${'$'}getModUpdateNotification"))
        assertFalse(screen.contains("PanoramaRenderer"))
        assertFalse(srcDir.resolve("ExampleModUpdateIndicator.java").exists())
    }

    @Test
    fun `title screen update indicator cleanup ignores comments and strings`() {
        val projectDir = tempDir.resolve("title-screen-update-indicator-comment")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val notesFile = srcDir.resolve("TitleScreenNotes.java")
        notesFile.writeText("""
            package com.example;

            public class TitleScreenNotes {
                String note = "class Removed extends TitleScreenModUpdateIndicator";

                void describe() {
                    // class Removed extends TitleScreenModUpdateIndicator
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val notes = notesFile.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-title-screen-update-indicator-class" })
        assertTrue(notesFile.exists())
        assertTrue(notes.contains("TitleScreenModUpdateIndicator"), notes)
    }

    @Test
    fun `migrates removed noParticlesOnBreak block property to AT backed helper`() {
        val projectDir = tempDir.resolve("no-particles-block-properties")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.neoforged.moddev' version '2.0.139'
            }

            neoForge {
                version = "21.1.203"
            }
        """.trimIndent())
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Blocks.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.SoundType;
            import net.minecraft.world.level.block.state.BlockBehaviour;

            public class Blocks {
                public static final Block JAR = new Block(BlockBehaviour.Properties.of().noOcclusion().noParticlesOnBreak().sound(SoundType.BONE_BLOCK));
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val blockSource = srcDir.resolve("Blocks.java").readText()
        val helper = projectDir.resolve("src/main/java/com/modporter/compat/ModPorterBlockProperties.java").readText()
        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-block-properties-no-particles" })
        assertTrue(blockSource.contains("com.modporter.compat.ModPorterBlockProperties.noParticlesOnBreak(BlockBehaviour.Properties.of().noOcclusion()).sound(SoundType.BONE_BLOCK)"))
        assertFalse(blockSource.contains("noParticlesOnBreak()"))
        assertTrue(helper.contains("properties.spawnTerrainParticles = false;"))
        assertTrue(at.contains("public net.minecraft.world.level.block.state.BlockBehaviour\$Properties spawnTerrainParticles"))
        assertTrue(build.contains("accessTransformers"))
    }

    @Test
    fun `noParticlesOnBreak migration ignores comments strings and text blocks`() {
        val projectDir = tempDir.resolve("no-particles-block-properties-docs")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.neoforged.moddev' version '2.0.139'
            }

            neoForge {
                version = "21.1.203"
            }
        """.trimIndent())
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Blocks.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.SoundType;
            import net.minecraft.world.level.block.state.BlockBehaviour;

            public class Blocks {
                String note = "BlockBehaviour.Properties.of().noParticlesOnBreak()";
                String docs = ${"\"\"\""}
                    public static final Block DOC = new Block(BlockBehaviour.Properties.of().noParticlesOnBreak());
                    ${"\"\"\""};

                // BlockBehaviour.Properties.of().noParticlesOnBreak()
                public static final Block JAR = new Block(BlockBehaviour.Properties.of().noOcclusion().noParticlesOnBreak().sound(SoundType.BONE_BLOCK));
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val blockSource = srcDir.resolve("Blocks.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-block-properties-no-particles" })
        assertTrue(blockSource.contains("com.modporter.compat.ModPorterBlockProperties.noParticlesOnBreak(BlockBehaviour.Properties.of().noOcclusion()).sound(SoundType.BONE_BLOCK)"), blockSource)
        assertTrue(blockSource.contains("String note = \"BlockBehaviour.Properties.of().noParticlesOnBreak()\";"), blockSource)
        assertTrue(blockSource.contains("public static final Block DOC = new Block(BlockBehaviour.Properties.of().noParticlesOnBreak());"), blockSource)
        assertTrue(blockSource.contains("// BlockBehaviour.Properties.of().noParticlesOnBreak()"), blockSource)
    }

    @Test
    fun `noParticlesOnBreak migration ignores documentation only`() {
        val projectDir = tempDir.resolve("no-particles-block-properties-docs-only")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val javaFile = srcDir.resolve("BlockNotes.java")
        javaFile.writeText("""
            package com.example;

            public class BlockNotes {
                String note = "BlockBehaviour.Properties.of().noParticlesOnBreak()";
                String docs = ${"\"\"\""}
                    public static final Block DOC = new Block(BlockBehaviour.Properties.of().noParticlesOnBreak());
                    ${"\"\"\""};
            }
        """.trimIndent())
        val before = javaFile.readText()

        val result = pass.apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-block-properties-no-particles" })
        assertEquals(before, javaFile.readText())
        assertFalse(projectDir.resolve("src/main/java/com/modporter/compat/ModPorterBlockProperties.java").exists())
        assertFalse(projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }

    @Test
    fun `split tick cleanup ignores documentation only`() {
        val projectDir = tempDir.resolve("split-tick-phase-docs-only")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val javaFile = srcDir.resolve("TickNotes.java")
        javaFile.writeText("""
            package com.example;

            public class TickNotes {
                String note = "RenderFrameEvent.Post event.phase == TickEvent.Phase.START";
                String docs = ${"\"\"\""}
                    public static void renderTick(RenderFrameEvent.Post event) {
                        if (event.phase == TickEvent.Phase.START) {
                            render();
                        }
                    }
                    ${"\"\"\""};
            }
        """.trimIndent())
        val before = javaFile.readText()

        val result = pass.apply(projectDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-cleanup-split-tick-phase" })
        assertEquals(before, javaFile.readText())
    }

    @Test
    fun `split tick cleanup does not rewrite unrelated java files`() {
        val projectDir = tempDir.resolve("split-tick-noop")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val javaFile = srcDir.resolve("Plain.java")
        javaFile.writeText("""
            package com.example;

            public class Plain {
                public void run() {
                    System.out.println("ok");
                }
            }
        """.trimIndent())

        val before = javaFile.readText()
        val result = pass.apply(projectDir)

        assertFalse(result.changes.any { it.ruleId == "build-cleanup-split-tick-phase" })
        assertEquals(before, javaFile.readText())
    }

    @Test
    fun `split tick cleanup scans large unrelated java files linearly`() {
        val projectDir = tempDir.resolve("split-tick-large-noop")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val javaFile = srcDir.resolve("LargeTickReferences.java")
        javaFile.writeText(buildString {
            appendLine("package com.example;")
            appendLine("public class LargeTickReferences {")
            repeat(25_000) { index ->
                appendLine("    String note$index = \"TickEvent reference $index without executable phase\";")
            }
            appendLine("}")
        })
        val before = javaFile.readText()

        val result = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            pass.apply(projectDir)
        }

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-cleanup-split-tick-phase" })
        assertEquals(before, javaFile.readText())
    }

    @Test
    fun `srg reflection ids become the mojang member names that survive on 1_21_1`() {
        val projectDir = tempDir.resolve("srg-reflection-names")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ClickDelay.java").writeText("""
            package com.example;

            import net.minecraft.client.Minecraft;
            import net.neoforged.fml.util.ObfuscationReflectionHelper;

            public class ClickDelay {
                void reset(Minecraft mc) {
                    ObfuscationReflectionHelper.setPrivateValue(Minecraft.class, mc, 10, "f_91078_");
                    Object value = ObfuscationReflectionHelper.getPrivateValue(Minecraft.class, mc, "f_91078_");
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClickDelay.java").readText()
        val accessTransformer = projectDir.resolve(
            "src/main/resources/META-INF/accesstransformer.cfg"
        ).readText()

        assertTrue(result.errors.none { it.contains("Forbidden reflection") }, result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-obfuscation-reflection-at-direct-field" }, result.changes.joinToString("\n") { it.ruleId })
        assertTrue(result.changes.any { it.ruleId == "build-obfuscation-reflection-at-entries" }, result.changes.joinToString("\n") { it.ruleId })
        assertTrue(content.contains("(mc).missTime = 10;"), content)
        assertTrue(content.contains("Object value = (mc).missTime;"), content)
        assertFalse(content.contains("f_91078_"), content)
        assertFalse(content.contains("ObfuscationReflectionHelper"), content)
        assertTrue(accessTransformer.contains("public-f net.minecraft.client.Minecraft missTime"), accessTransformer)
    }

    @Test
    fun `hard gates forbidden reflection after migration`() {
        val projectDir = tempDir.resolve("forbidden-reflection")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("UnsafeReflection.java").writeText("""
            package com.example;

            import net.minecraft.server.MinecraftServer;
            import net.minecraft.world.entity.LivingEntity;

            public class UnsafeReflection {
                void read() throws Exception {
                    MinecraftServer.class.getDeclaredField("f_999999_").setAccessible(true);
                    LivingEntity.class.getDeclaredField("noSuchFieldOnTarget");
                    LivingEntity.class.getDeclaredMethod("noSuchMethodOnTarget");
                    Class.forName("net.minecraftforge.common.util.FakePlayer");
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        assertTrue(result.errors.any { it.contains("Forbidden reflection") && it.contains("f_999999_") }, result.errors.joinToString("\n"))
        assertTrue(result.errors.any { it.contains("Forbidden reflection") && it.contains("LivingEntity#noSuchFieldOnTarget") }, result.errors.joinToString("\n"))
        assertTrue(result.errors.any { it.contains("Forbidden reflection") && it.contains("LivingEntity#noSuchMethodOnTarget") }, result.errors.joinToString("\n"))
        assertTrue(result.errors.any { it.contains("Forbidden reflection") && it.contains("net.minecraftforge.common.util.FakePlayer") }, result.errors.joinToString("\n"))
    }

    @Test
    fun `keeps reflection on members that exist on the 1_21_1 target`() {
        val projectDir = tempDir.resolve("target-member-reflection")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("StorageAccess.java").writeText("""
            package com.example;

            import java.lang.reflect.Field;
            import net.minecraft.server.MinecraftServer;
            import net.minecraft.world.level.storage.LevelStorageSource;

            public class StorageAccess {
                // The user's own 1.21.1 port keeps exactly this: storageSource is the Mojang name
                // of a protected field that still exists on MinecraftServer.
                static LevelStorageSource.LevelStorageAccess storage(MinecraftServer server) {
                    try {
                        Field field = MinecraftServer.class.getDeclaredField("storageSource");
                        field.setAccessible(true);
                        return (LevelStorageSource.LevelStorageAccess) field.get(server);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }

                static Object own() throws Exception {
                    Field field = StorageAccess.class.getDeclaredField("CACHE");
                    field.setAccessible(true);
                    return field.get(null);
                }

                private static final Object CACHE = new Object();
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        assertTrue(result.errors.none { it.contains("Forbidden reflection") }, result.errors.joinToString("\n"))
    }

    @Test
    fun `declares run scoped mod sources through moddev mods and loaded mods`() {
        val projectDir = tempDir.resolve("run-mod-sources")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0.24,6.2)'
            }

            minecraft {
                mappings channel: 'official', version: '1.20.1'
                runs {
                    gameTestServer {
                        property 'forge.enabledGameTestNamespaces', 'hotbath'
                    }
                }
            }

            sourceSets {
                bucketTest {
                    java.srcDir 'src/bucketTest/java'
                }
            }
            minecraft.runs.gameTestServer {
                mods { hotbath_bucket_tests { source sourceSets.bucketTest } }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-run-mod-sources" })
        assertFalse(content.contains("minecraft.runs"), content)
        assertTrue(content.contains("hotbath_bucket_tests {"), content)
        assertTrue(content.contains("sourceSet(sourceSets.bucketTest)"), content)
        assertTrue(content.contains("if (run.name != 'gameTestServer') run.loadedMods.set(neoForge.mods.matching { it.name != 'hotbath_bucket_tests' })"), content)
    }

    @Test
    fun `migrates same project static field reflection to explicit source accessor`() {
        val projectDir = tempDir.resolve("same-project-field-reflection")
        val ownerDir = projectDir.resolve("src/main/java/com/example/service")
        val callerDir = projectDir.resolve("src/main/java/com/example/tests")
        ownerDir.createDirectories()
        callerDir.createDirectories()
        ownerDir.resolve("OwnerService.java").writeText("""
            package com.example.service;

            import java.util.Map;
            import java.util.concurrent.ConcurrentHashMap;

            public class OwnerService {
                private static final Map<Integer, Task> tasks = new ConcurrentHashMap<>();

                public static class Task {
                    public int size() {
                        return 1;
                    }
                }
            }
        """.trimIndent())
        callerDir.resolve("OwnerServiceTests.java").writeText("""
            package com.example.tests;

            import com.example.service.OwnerService;
            import java.lang.reflect.Field;
            import java.util.Map;

            public class OwnerServiceTests {
                @SuppressWarnings("unchecked")
                private static OwnerService.Task task(int index) {
                    try {
                        Field field = OwnerService.class.getDeclaredField("tasks");
                        field.setAccessible(true);
                        Map<Integer, OwnerService.Task> tasks =
                                (Map<Integer, OwnerService.Task>) field.get(null);
                        OwnerService.Task task = tasks.get(index);
                        if (task == null) {
                            throw new IllegalStateException("missing task");
                        }
                        return task;
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("cannot inspect task", e);
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val owner = ownerDir.resolve("OwnerService.java").readText()
        val caller = callerDir.resolve("OwnerServiceTests.java").readText()
        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-same-project-static-field-accessor" })
        assertTrue(result.changes.any { it.ruleId == "build-same-project-static-field-reflection" })
        assertTrue(owner.contains("public static Map<Integer, Task> modporterAccessTasks()"), owner)
        assertTrue(owner.contains("return tasks;"), owner)
        assertTrue(caller.contains("Map<Integer, OwnerService.Task> tasks = OwnerService.modporterAccessTasks();"), caller)
        assertFalse(caller.contains("java.lang.reflect"), caller)
        assertFalse(caller.contains("getDeclaredField"), caller)
        assertFalse(caller.contains("setAccessible"), caller)
        assertFalse(caller.contains("ReflectiveOperationException"), caller)
    }

    @Test
    fun `removes standalone mixin dependencies bundled by NeoForge`() {
        val projectDir = tempDir.resolve("bundled-mixin-deps")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'java'
                id("net.neoforged.moddev") version "2.0.140"
            }

            dependencies {
                implementation "com.example:kept:1.0.0"
                annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'
                implementation "io.github.llamalad7:mixinextras-forge:0.3.5"
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val content = projectDir.resolve("build.gradle").readText()
        assertTrue(result.changes.any { it.ruleId == "build-remove-bundled-mixin-dependency" })
        assertTrue(content.contains("com.example:kept:1.0.0"), content)
        assertFalse(content.contains("org.spongepowered:mixin"), content)
        assertFalse(content.contains("io.github.llamalad7:mixinextras"), content)
        assertFalse(content.contains("annotationProcessor 'org.spongepowered:mixin"), content)
    }

    @Test
    fun `analyze mode does not modify files`() {
        val projectDir = tempDir.resolve("p7")
        projectDir.createDirectories()
        val originalContent = """
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent()
        projectDir.resolve("build.gradle").writeText(originalContent)

        val result = pass.analyze(projectDir)
        assertTrue(result.changeCount > 0)

        // File should not be modified
        assertEquals(originalContent, projectDir.resolve("build.gradle").readText())
    }

    @Test
    fun `handles kotlin DSL build gradle kts`() {
        val projectDir = tempDir.resolve("p8")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle.kts").writeText("""
            plugins {
                id("net.minecraftforge.gradle") version "[6.0,6.2)"
            }
            dependencies {
                minecraft("net.minecraftforge:forge:1.20.1-47.2.0")
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle.kts").readText()
        assertTrue(content.contains("net.neoforged.moddev"))
        assertFalse(content.contains("net.minecraftforge"))
    }

    @Test
    fun `handles project with no build files`() {
        val projectDir = tempDir.resolve("p9")
        projectDir.createDirectories()

        val result = pass.apply(projectDir)
        assertEquals(0, result.changeCount)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `updates Gradle wrapper below ModDev minimum`() {
        val projectDir = tempDir.resolve("p9-wrapper")
        val wrapperProps = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties")
        wrapperProps.parent.createDirectories()
        wrapperProps.writeText("""
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-all.zip
            networkTimeout=10000
            validateDistributionUrl=true
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = wrapperProps.readText()

        assertTrue(result.changes.any { it.ruleId == "build-gradle-wrapper" })
        assertTrue(content.contains("gradle-8.14.4-bin.zip"))
        assertFalse(content.contains("gradle-8.5-all.zip"))
    }

    @Test
    fun `all changes are HIGH confidence`() {
        val projectDir = tempDir.resolve("p10")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
            repositories {
                maven { url 'https://maven.minecraftforge.net/' }
            }
            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.2.0'
            }
        """.trimIndent())

        val result = pass.analyze(projectDir)
        assertTrue(result.changeCount > 0)
        assertTrue(result.changes.all {
            it.confidence == com.modporter.core.pipeline.Confidence.HIGH
        }, "All build system changes should be HIGH confidence")
    }

    @Test
    fun `guards optional prepareGameTestServerRun hook`() {
        val projectDir = tempDir.resolve("p11")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            tasks.named('prepareGameTestServerRun').configure {
                doLast {
                    println 'copying generated gametest assets'
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-guard-optional-run-task" })
        assertTrue(content.contains("tasks.matching { it.name == 'prepareGameTestServerRun' }.configureEach {"))
        assertFalse(content.contains("tasks.named('prepareGameTestServerRun').configure"))
    }

    @Test
    fun `creates missing empty gametest structure templates`() {
        val projectDir = tempDir.resolve("p11b")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("ExampleGameTests.java").writeText("""
            package com.example;

            import net.minecraft.gametest.framework.GameTest;

            public final class ExampleGameTests {
                private static final String TEMPLATE = "mirror_lifecycle_empty";

                @GameTest(template = TEMPLATE, timeoutTicks = 40)
                public static void constantTemplate() {
                }

                @GameTest(template = "empty_1x1", timeoutTicks = 40)
                public static void literalTemplate() {
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        assertEquals(2, result.changes.count { it.ruleId == "build-gametest-empty-structure" })
        assertTrue(projectDir.resolve("src/main/resources/gameteststructures/mirror_lifecycle_empty.snbt").exists())
        assertTrue(projectDir.resolve("src/main/resources/gameteststructures/empty_1x1.snbt").exists())
    }

    @Test
    fun `adds gametest flat preset datapack for custom dimensions without shipping production preset`() {
        val projectDir = tempDir.resolve("p11c")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val dimensionDir = projectDir.resolve("src/main/resources/data/examplemod/dimension")
        srcDir.createDirectories()
        dimensionDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("ExampleGameTests.java").writeText("""
            package com.example;

            import net.minecraft.gametest.framework.GameTest;

            public final class ExampleGameTests {
                @GameTest(template = "empty_1x1")
                public static void customDimensionLoads() {
                }
            }
        """.trimIndent())
        dimensionDir.resolve("mirror_world_0.json").writeText("""
            {
              "type": "examplemod:mirror_world",
              "generator": {
                "type": "examplemod:mirror_chunk_generator",
                "biome": "minecraft:plains"
              }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-gametest-dimension-flat-preset" })
        assertTrue(content.contains("run/world/datapacks/modporter_gametest_dimensions"), content)
        assertTrue(content.contains("data/minecraft/worldgen/world_preset/flat.json"), content)
        assertTrue(content.contains("src/main/resources/data"), content)
        assertTrue(content.contains("src/generated/resources/data"), content)
        assertFalse(projectDir.resolve("src/main/resources/data/minecraft/worldgen/world_preset/flat.json").exists())
    }

    @Test
    fun `normalizes ForgeGradle mod dependency configurations`() {
        val projectDir = tempDir.resolve("p12")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                modCompileOnly("cn.mcmod_mmf.mysterious_mountain_lib:MMLib:1.5.18-1.20.1")
                modRuntimeOnly("cn.mcmod_mmf.mysterious_mountain_lib:MMLib:1.5.18-1.20.1")
                jarJar(modImplementation("com.example:nested-mod-dependency:1.0"))
            }

            // modImplementation("commented:dependency:1.0")
            ext.dependencyDocumentation = "modImplementation( must remain text"
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-mod-dependency-configuration" })
        assertFalse(content.contains("modCompileOnly"))
        assertFalse(content.contains("modRuntimeOnly"))
        assertTrue(content.contains("compileOnly"))
        assertTrue(content.contains("runtimeOnly"))
        assertTrue(content.contains("jarJar(implementation(\"com.example:nested-mod-dependency:1.0\"))"))
        assertTrue(content.contains("// modImplementation(\"commented:dependency:1.0\")"))
        assertTrue(content.contains("\"modImplementation( must remain text\""))
    }

    @Test
    fun `adds legacy capability shims and removes super only hooks`() {
        val projectDir = tempDir.resolve("p13")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("MachineBlockEntity.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.capabilities.Capability;
            import net.neoforged.neoforge.common.util.LazyOptional;

            @Mod("examplemod")
            public class MachineBlockEntity extends BaseBlockEntity {
                private LazyOptional<Object> handler = LazyOptional.of(Object::new);

                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> cap, Object side) {
                    return super.getCapability(cap, side);
                }

                @Override
                public void invalidateCaps() {
                    super.invalidateCaps();
                    handler.invalidate();
                }
            }

            class BaseBlockEntity {
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("MachineBlockEntity.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-add-lazyoptional-shim" })
        assertTrue(result.changes.any { it.ruleId == "build-relocate-lazyoptional-import" })
        assertFalse(result.changes.any { it.ruleId == "build-add-capability-shim" })
        assertFalse(result.changes.any { it.ruleId == "build-relocate-capability-import" })
        assertTrue(projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/LazyOptional.java").exists())
        assertFalse(projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/Capability.java").exists())
        assertFalse(projectDir.resolve("src/main/java/com/modporter/compat/LazyOptional.java").exists())
        assertFalse(projectDir.resolve("src/main/java/com/modporter/compat/Capability.java").exists())
        assertTrue(source.contains("import com.modporter.generated.examplemod.compat.LazyOptional;"))
        assertFalse(source.contains("net.neoforged.neoforge.common.util.LazyOptional"))
        assertFalse(source.contains("net.neoforged.neoforge.capabilities.Capability"))
        assertFalse(source.contains("public <T> LazyOptional<T> getCapability"))
        assertFalse(source.contains("LazyOptional.empty();"))
        assertFalse(source.contains("[forge2neo]"))
        assertTrue(source.contains("handler.invalidate();"))
        assertFalse(source.contains("import com.modporter.generated.examplemod.compat.Capability;"))
        assertFalse(source.contains("super.getCapability"))
        assertFalse(source.contains("super.invalidateCaps();"))
    }

    @Test
    fun `adds conditional recipe adapter backed by RecipeOutput conditions`() {
        val projectDir = tempDir.resolve("p13-conditional-recipe")
        val srcDir = projectDir.resolve("src/main/java/com/example/data")
        srcDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=examplemod\n")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("Recipes.java").writeText("""
            package com.example.data;

            import net.minecraft.data.recipes.RecipeOutput;
            import net.neoforged.neoforge.common.conditions.ModLoadedCondition;
            import net.neoforged.neoforge.common.crafting.ConditionalRecipe;

            public class Recipes {
                public ConditionalRecipe.Builder whenModLoaded(String modid) {
                    return ConditionalRecipe.builder().addCondition(new ModLoadedCondition(modid));
                }

                public void build(RecipeOutput output) {
                    whenModLoaded("farmersdelight").build(output, "examplemod", "rice");
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("Recipes.java").readText()
        val shim = projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/ConditionalRecipe.java")

        assertTrue(result.changes.any { it.ruleId == "build-relocate-conditionalrecipe-import" })
        assertTrue(result.changes.any { it.ruleId == "build-add-conditionalrecipe-shim" })
        assertTrue(source.contains("import com.modporter.generated.examplemod.compat.ConditionalRecipe;"))
        assertFalse(source.contains("net.neoforged.neoforge.common.crafting.ConditionalRecipe"))
        assertTrue(shim.exists())
        val shimContent = shim.readText()
        assertTrue(shimContent.contains("output.withConditions(conditions.toArray(ICondition[]::new))"))
        assertTrue(shimContent.contains("public void accept(ResourceLocation generatedId, Recipe<?> recipe, AdvancementHolder advancement, ICondition... extraConditions)"))
        assertTrue(shimContent.contains("target.accept(id, recipe, forwardedAdvancement);"))
        assertTrue(shimContent.contains("new AdvancementHolder(advancementId, advancement.value())"))
        assertTrue(shimContent.contains("recipe.accept(namedOutput);"))
    }

    @Test
    fun `relocates legacy mmlib render utils to generated fluid renderer`() {
        val projectDir = tempDir.resolve("p13-renderutils")
        val srcDir = projectDir.resolve("src/main/java/com/example/client")
        srcDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=examplemod\n")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("ExampleScreen.java").writeText("""
            package com.example.client;

            import cn.mcmod_mmf.mmlib.client.RenderUtils;
            import net.minecraft.client.gui.GuiGraphics;
            import net.neoforged.neoforge.fluids.FluidStack;

            public class ExampleScreen {
                public void render(GuiGraphics ms, FluidStack stack) {
                    RenderUtils.renderFluidStack(9, 10, 16, 16, 0.0F, stack);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("ExampleScreen.java").readText()
        val shim = projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/RenderUtils.java")

        assertTrue(result.changes.any { it.ruleId == "build-relocate-renderutils-fluid" })
        assertTrue(result.changes.any { it.ruleId == "build-add-renderutils-fluid-shim" })
        assertTrue(source.contains("import com.modporter.generated.examplemod.compat.RenderUtils;"))
        assertTrue(source.contains("RenderUtils.renderFluidStack(ms, 9, 10, 16, 16, 0.0F, stack);"))
        assertFalse(source.contains("cn.mcmod_mmf.mmlib.client.RenderUtils"))
        assertTrue(shim.exists())
        val shimContent = shim.readText()
        assertTrue(shimContent.contains("IClientFluidTypeExtensions.of(fluidStack.getFluid())"))
        assertTrue(shimContent.contains("TextureAtlas.LOCATION_BLOCKS"))
        assertTrue(shimContent.contains("guiGraphics.blit("))
        assertFalse(shimContent.contains("TODO"))
    }

    @Test
    fun `removes stale LazyOptional imports without generating compatibility shim`() {
        val projectDir = tempDir.resolve("p13-stale-lazyoptional")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=examplemod\n")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("OnlyImport.java").writeText("""
            package com.example;

            import com.modporter.generated.examplemod.compat.LazyOptional;

            public class OnlyImport {
                public int value() {
                    return 1;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("OnlyImport.java").readText()
        val shim = projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/LazyOptional.java")

        assertTrue(result.changes.any { it.ruleId == "build-remove-stale-lazyoptional-import" })
        assertFalse(source.contains("LazyOptional"), source)
        assertFalse(shim.exists())
    }

    @Test
    fun `legacy compatibility shims hard gate missing project mod id`() {
        val projectDir = tempDir.resolve("p13-missing-modid")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("Machine.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.common.util.LazyOptional;

            public class Machine {
                private final LazyOptional<Object> handler = LazyOptional.of(Object::new);
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("Machine.java").readText()

        assertTrue(result.errors.any { it.contains("Cannot derive generated compat shim package for legacy source compatibility shims") })
        assertTrue(source.contains("import net.neoforged.neoforge.common.util.LazyOptional;"))
        assertFalse(projectDir.resolve("src/main/java/com/modporter/generated/shared/compat/LazyOptional.java").exists())
        assertFalse(result.changes.any { it.ruleId == "build-add-lazyoptional-shim" })
    }

    @Test
    fun `rewrites legacy abstract tree grower call sites`() {
        val projectDir = tempDir.resolve("p14")
        val treeDir = projectDir.resolve("src/main/java/com/example/tree")
        val blockDir = projectDir.resolve("src/main/java/com/example/block")
        treeDir.createDirectories()
        blockDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=examplemod\n")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        treeDir.resolve("CherryTreeGrower.java").writeText("""
            package com.example.tree;

            import net.minecraft.resources.ResourceKey;
            import net.minecraft.util.RandomSource;
            import net.minecraft.world.level.block.grower.AbstractTreeGrower;
            import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

            public class CherryTreeGrower extends AbstractTreeGrower {
                @Override
                protected ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(RandomSource random, boolean flowers) {
                    return TreeFeatures.CHERRY_KEY;
                }
            }
        """.trimIndent())
        blockDir.resolve("BlockRegistry.java").writeText("""
            package com.example.block;

            import com.example.tree.CherryTreeGrower;
            import net.minecraft.world.level.block.SaplingBlock;
            import net.minecraft.world.level.block.grower.AbstractTreeGrower;

            public class BlockRegistry {
                private static SaplingBlock sapling(AbstractTreeGrower grower) {
                    return new SaplingBlock(grower, null);
                }

                static SaplingBlock cherry() {
                    return sapling(new CherryTreeGrower());
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val registry = blockDir.resolve("BlockRegistry.java").readText()
        val grower = treeDir.resolve("CherryTreeGrower.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-tree-grower-compat" })
        assertTrue(result.changes.any { it.ruleId == "build-tree-grower-helper" })
        assertTrue(result.changes.any { it.ruleId == "build-tree-grower-compat-base" })
        assertTrue(result.changes.any { it.ruleId == "build-tree-grower-unfinal-at" })
        assertTrue(registry.contains("import net.minecraft.world.level.block.grower.TreeGrower;"))
        assertFalse(registry.contains(";import"))
        assertTrue(registry.contains("private static SaplingBlock sapling(TreeGrower grower)"))
        assertTrue(registry.contains("new CherryTreeGrower()"))
        assertTrue(grower.contains("import com.modporter.generated.examplemod.compat.ModPorterAbstractTreeGrower;"))
        assertTrue(grower.contains("public class CherryTreeGrower extends ModPorterAbstractTreeGrower"))
        assertTrue(grower.contains("protected ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature"))
        assertFalse(grower.contains("extends AbstractTreeGrower"))
        val compatBase = projectDir.resolve("src/main/java/com/modporter/generated/examplemod/compat/ModPorterAbstractTreeGrower.java").readText()
        assertTrue(compatBase.contains("extends TreeGrower"))
        assertTrue(compatBase.contains("protected abstract ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature"))
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertTrue(atFile.contains("public-f net.minecraft.world.level.block.grower.TreeGrower"))
        assertFalse(projectDir.resolve("src/main/java/net/minecraft/world/level/block/grower/AbstractTreeGrower.java").exists())
    }

    @Test
    fun `legacy abstract tree grower compat hard gates missing project mod id`() {
        val projectDir = tempDir.resolve("p14-missing-modid")
        val treeDir = projectDir.resolve("src/main/java/com/example/tree")
        treeDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        treeDir.resolve("CherryTreeGrower.java").writeText("""
            package com.example.tree;

            import net.minecraft.resources.ResourceKey;
            import net.minecraft.util.RandomSource;
            import net.minecraft.world.level.block.grower.AbstractTreeGrower;
            import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;

            public class CherryTreeGrower extends AbstractTreeGrower {
                @Override
                protected ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(RandomSource random, boolean flowers) {
                    return TreeFeatures.CHERRY_KEY;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val grower = treeDir.resolve("CherryTreeGrower.java").readText()

        assertTrue(result.errors.any { it.contains("Cannot derive generated compat shim package for legacy tree grower compatibility base") })
        assertTrue(grower.contains("extends AbstractTreeGrower"))
        assertFalse(projectDir.resolve("src/main/java/com/modporter/generated/shared/compat/ModPorterAbstractTreeGrower.java").exists())
    }

    @Test
    fun `rewrites legacy armor material enums and consumers`() {
        val projectDir = tempDir.resolve("p15")
        val itemDir = projectDir.resolve("src/main/java/com/example/item")
        val modDir = projectDir.resolve("src/main/java/com/example")
        itemDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        modDir.resolve("TestMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(TestMod.MODID)
            public class TestMod {
                public static final String MODID = "example";

                public TestMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    modEventBus.addListener(this::setup);
                }
            }
        """.trimIndent())
        itemDir.resolve("ExampleArmorMaterials.java").writeText("""
            package com.example.item;

            import com.example.TestMod;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Ingredient;
            import java.util.function.Supplier;

            public enum ExampleArmorMaterials implements ArmorMaterial {
                STRAW("straw", "strawhat", 6, new int[]{0, 0, 0, 1}, 30,
                        SoundEvents.ARMOR_EQUIP_LEATHER, 0.0F, 0.0F, () -> Ingredient.EMPTY),
                KIMONO("kimono", "kimono_base", 1, new int[]{0, 0, 0, 0}, 0,
                        SoundEvents.WOOL_PLACE, 0.0F, 0.0F, () -> Ingredient.of(Items.PAPER));

                private final String name;
                private final String textureName;
                private final SoundEvent sound;
                private final Supplier<Ingredient> repairIngredient;

                ExampleArmorMaterials(String name, String textureName, int durabilityMult, int[] protections, int enchant,
                                       SoundEvent sound, float tough, float kb, Supplier<Ingredient> repair) {
                    this.name = name;
                    this.textureName = textureName;
                    this.sound = sound;
                    this.repairIngredient = repair;
                }

                @Override
                public String getName() {
                    return TestMod.MODID + ":" + this.name;
                }

                public String getTextureName() {
                    return this.textureName;
                }
            }
        """.trimIndent())
        itemDir.resolve("SamuraiArmorItem.java").writeText("""
            package com.example.item;

            import com.example.TestMod;
            import net.minecraft.world.entity.Entity;
            import net.minecraft.world.entity.EquipmentSlot;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ItemStack;
            import javax.annotation.Nullable;

            public class SamuraiArmorItem extends ArmorItem {
                public SamuraiArmorItem(ExampleArmorMaterials material, Type type, Properties properties) {
                    super(material, type, properties);
                }

                @Override
                public @Nullable String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
                    String materialName = ((ExampleArmorMaterials) getMaterial()).getTextureName();
                    return TestMod.MODID + ":textures/models/armor/" + materialName + ".png";
                }
            }
        """.trimIndent())
        itemDir.resolve("KimonoItem.java").writeText("""
            package com.example.item;

            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ArmorMaterial;

            public class KimonoItem extends ArmorItem {
                public KimonoItem(ArmorMaterial material, Type type, Properties properties) {
                    super(material, type, properties);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val material = itemDir.resolve("ExampleArmorMaterials.java").readText()
        val samurai = itemDir.resolve("SamuraiArmorItem.java").readText()
        val kimono = itemDir.resolve("KimonoItem.java").readText()
        val mod = modDir.resolve("TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-legacy-armor-material-registry" })
        assertTrue(result.changes.any { it.ruleId == "build-legacy-armor-material-consumer" })
        assertTrue(result.changes.any { it.ruleId == "build-register-armor-materials" })
        assertFalse(material.contains("enum ExampleArmorMaterials implements ArmorMaterial"))
        assertTrue(material.contains("DeferredRegister.create(Registries.ARMOR_MATERIAL, TestMod.MODID)"))
        assertTrue(material.contains("Holder.direct(SoundEvents.WOOL_PLACE)"))
        assertTrue(material.contains("() -> Ingredient.EMPTY"), material)
        assertTrue(material.contains("() -> Ingredient.of(Items.PAPER)"), material)
        assertFalse(material.contains("() -> () ->"), material)
        assertTrue(samurai.contains("Holder<ArmorMaterial> material"))
        assertTrue(samurai.contains("ExampleArmorMaterials.getTextureName(this.material)"))
        assertTrue(samurai.contains("ResourceLocation.fromNamespaceAndPath(TestMod.MODID"))
        assertTrue(kimono.contains("Holder<ArmorMaterial> material"))
        assertTrue(mod.contains("import com.example.item.ExampleArmorMaterials;"))
        assertTrue(mod.contains("ExampleArmorMaterials.ARMOR_MATERIALS.register(modEventBus);"))
    }

    @Test
    fun `migrates legacy enum map armor material with knockback argument`() {
        val projectDir = tempDir.resolve("p15-armor-enummap-knockback")
        val itemDir = projectDir.resolve("src/main/java/com/example/item")
        val modDir = projectDir.resolve("src/main/java/com/example")
        itemDir.createDirectories()
        modDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        modDir.resolve("TestMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(TestMod.MODID)
            public class TestMod {
                public static final String MODID = "example";

                public TestMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                }
            }
        """.trimIndent())
        itemDir.resolve("ArmorTier.java").writeText("""
            package com.example.item;

            import com.example.TestMod;
            import net.minecraft.Util;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.crafting.Ingredient;
            import java.util.EnumMap;

            public enum ArmorTier implements ArmorMaterial {
                CRYSTAL("crystal", 30, Util.make(new EnumMap<>(ArmorItem.Type.class), armor -> {
                    armor.put(ArmorItem.Type.BOOTS, 3);
                    armor.put(ArmorItem.Type.LEGGINGS, 6);
                    armor.put(ArmorItem.Type.CHESTPLATE, 8);
                    armor.put(ArmorItem.Type.HELMET, 3);
                }), 18, SoundEvents.ARMOR_EQUIP_IRON, 2.0F, 0.25F, Ingredient.EMPTY);

                private final String name;
                private final int durabilityMultiplier;
                private final EnumMap<ArmorItem.Type, Integer> protectionFunctionForType;
                private final int enchantmentValue;
                private final SoundEvent sound;
                private final float toughness;
                private final float knockbackResistance;
                private final Ingredient repairIngredient;

                ArmorTier(String name, int durability, EnumMap<ArmorItem.Type, Integer> protection, int enchantmentValue, SoundEvent sound, float toughness, float knockbackResistance, Ingredient repairIngredient) {
                    this.name = name;
                    this.durabilityMultiplier = durability;
                    this.protectionFunctionForType = protection;
                    this.enchantmentValue = enchantmentValue;
                    this.sound = sound;
                    this.toughness = toughness;
                    this.knockbackResistance = knockbackResistance;
                    this.repairIngredient = repairIngredient;
                }

                @Override
                public String getName() {
                    return TestMod.MODID + ":" + this.name;
                }
            }
        """.trimIndent())
        itemDir.resolve("BaseArmorItem.java").writeText("""
            package com.example.item;

            import net.minecraft.core.Holder;
            import net.minecraft.world.item.ArmorItem;
            import net.minecraft.world.item.ArmorMaterial;

            public class BaseArmorItem extends ArmorItem {
                public BaseArmorItem(Holder<ArmorMaterial> material, Type type, Properties properties) {
                    super(material, type, properties);
                }
            }
        """.trimIndent())
        itemDir.resolve("CrystalArmorItem.java").writeText("""
            package com.example.item;

            import net.minecraft.world.item.ArmorMaterial;

            public class CrystalArmorItem extends BaseArmorItem {
                public CrystalArmorItem(ArmorMaterial material, Type type, Properties properties) {
                    super(material, type, properties);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val material = itemDir.resolve("ArmorTier.java").readText()
        val armorItem = itemDir.resolve("CrystalArmorItem.java").readText()
        val mod = modDir.resolve("TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-legacy-armor-material-registry" })
        assertTrue(material.contains("registerWithTexture(\"crystal\", \"crystal\""))
        assertTrue(material.contains("() -> Ingredient.EMPTY"), material)
        assertTrue(material.contains("0.25F"))
        assertTrue(armorItem.contains("Holder<ArmorMaterial> material"))
        assertTrue(armorItem.contains("extends BaseArmorItem"))
        assertTrue(mod.contains("ArmorTier.ARMOR_MATERIALS.register(modEventBus);"))
    }

    @Test
    fun `registers generated armor materials outside existing deferred register loops`() {
        val projectDir = tempDir.resolve("p15-armor-loop")
        val itemDir = projectDir.resolve("src/main/java/com/example/item")
        val modDir = projectDir.resolve("src/main/java/com/example")
        itemDir.createDirectories()
        modDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        modDir.resolve("TestMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.neoforge.registries.DeferredRegister;

            @Mod(TestMod.MODID)
            public class TestMod {
                public static final String MODID = "example";

                public TestMod(ModContainer modContainer) {
                    IEventBus modEventBus = modContainer.getEventBus();
                    DeferredRegister<?>[] registers = { ExampleRegistry.BLOCKS, ExampleRegistry.ITEMS };
                    for (DeferredRegister<?> register : registers) {
                        register.register(modEventBus);
                    }
                }
            }
        """.trimIndent())
        itemDir.resolve("ExampleArmorMaterials.java").writeText("""
            package com.example.item;

            import com.example.TestMod;
            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Ingredient;
            import java.util.function.Supplier;

            public enum ExampleArmorMaterials implements ArmorMaterial {
                STRAW("straw", "strawhat", 6, new int[]{0, 0, 0, 1}, 30,
                        SoundEvents.ARMOR_EQUIP_LEATHER, 0.0F, 0.0F, () -> Ingredient.of(Items.WHEAT));

                private final String name;
                private final String textureName;
                private final SoundEvent sound;
                private final Supplier<Ingredient> repairIngredient;

                ExampleArmorMaterials(String name, String textureName, int durabilityMult, int[] protections, int enchant,
                                       SoundEvent sound, float tough, float kb, Supplier<Ingredient> repair) {
                    this.name = name;
                    this.textureName = textureName;
                    this.sound = sound;
                    this.repairIngredient = repair;
                }

                @Override
                public String getName() {
                    return TestMod.MODID + ":" + this.name;
                }
            }
        """.trimIndent())

        pass.apply(projectDir)

        val mod = modDir.resolve("TestMod.java").readText()
        val armorRegistration = "ExampleArmorMaterials.ARMOR_MATERIALS.register(modEventBus);"
        val armorIdx = mod.indexOf(armorRegistration)
        val arrayIdx = mod.indexOf("DeferredRegister<?>[] registers")
        val loopIdx = mod.indexOf("for (DeferredRegister<?> register : registers)")
        val loopRegisterIdx = mod.indexOf("register.register(modEventBus);")

        assertTrue(armorIdx >= 0, mod)
        assertTrue(armorIdx < arrayIdx, mod)
        assertTrue(loopIdx >= 0, mod)
        assertTrue(loopRegisterIdx > loopIdx, mod)
        assertFalse(mod.substring(loopIdx, loopRegisterIdx).contains(armorRegistration), mod)
    }

    @Test
    fun `legacy armor material migration rejects package derived mod ids`() {
        val projectDir = tempDir.resolve("p15-armor-no-modid")
        val itemDir = projectDir.resolve("src/main/java/com/example/item")
        itemDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        val materialFile = itemDir.resolve("ExampleArmorMaterials.java")
        materialFile.writeText("""
            package com.example.item;

            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Ingredient;
            import java.util.function.Supplier;

            public enum ExampleArmorMaterials implements ArmorMaterial {
                STRAW("straw", "strawhat", 6, new int[]{0, 0, 0, 1}, 30,
                        SoundEvents.ARMOR_EQUIP_LEATHER, 0.0F, 0.0F, () -> Ingredient.of(Items.WHEAT));

                private final String name;
                private final String textureName;
                private final SoundEvent sound;
                private final Supplier<Ingredient> repairIngredient;

                ExampleArmorMaterials(String name, String textureName, int durabilityMult, int[] protections, int enchant,
                                       SoundEvent sound, float tough, float kb, Supplier<Ingredient> repair) {
                    this.name = name;
                    this.textureName = textureName;
                    this.sound = sound;
                    this.repairIngredient = repair;
                }

                @Override
                public String getName() {
                    return this.name;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val material = materialFile.readText()

        assertTrue(
            result.errors.any {
                it.contains("Cannot derive mod id expression for legacy ArmorMaterial enum ExampleArmorMaterials")
            },
            result.errors.joinToString("\n")
        )
        assertFalse(result.changes.any { it.ruleId == "build-legacy-armor-material-registry" })
        assertTrue(material.contains("enum ExampleArmorMaterials implements ArmorMaterial"))
        assertFalse(material.contains("DeferredRegister.create(Registries.ARMOR_MATERIAL, \"item\")"))
    }

    @Test
    fun `legacy armor material migration uses unique project mod id evidence for plain material names`() {
        val projectDir = tempDir.resolve("p15-armor-project-modid")
        val itemDir = projectDir.resolve("src/main/java/com/example/item")
        val modDir = projectDir.resolve("src/main/java/com/example")
        itemDir.createDirectories()
        modDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        modDir.resolve("TestMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;

            @Mod(TestMod.ID)
            public class TestMod {
                public static final String ID = "example";

                public TestMod(ModContainer container) {
                    IEventBus modEventBus = container.getEventBus();
                }
            }
        """.trimIndent())
        itemDir.resolve("ExampleArmorMaterials.java").writeText("""
            package com.example.item;

            import net.minecraft.sounds.SoundEvent;
            import net.minecraft.sounds.SoundEvents;
            import net.minecraft.world.item.ArmorMaterial;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.Ingredient;
            import java.util.function.Supplier;

            public enum ExampleArmorMaterials implements ArmorMaterial {
                STRAW("straw", "strawhat", 6, new int[]{0, 0, 0, 1}, 30,
                        SoundEvents.ARMOR_EQUIP_LEATHER, 0.0F, 0.0F, () -> Ingredient.of(Items.WHEAT));

                private final String name;
                private final String textureName;
                private final SoundEvent sound;
                private final Supplier<Ingredient> repairIngredient;

                ExampleArmorMaterials(String name, String textureName, int durabilityMult, int[] protections, int enchant,
                                       SoundEvent sound, float tough, float kb, Supplier<Ingredient> repair) {
                    this.name = name;
                    this.textureName = textureName;
                    this.sound = sound;
                    this.repairIngredient = repair;
                }

                @Override
                public String getName() {
                    return this.name;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val material = itemDir.resolve("ExampleArmorMaterials.java").readText()
        val mod = modDir.resolve("TestMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-legacy-armor-material-registry" })
        assertTrue(material.contains("DeferredRegister.create(Registries.ARMOR_MATERIAL, com.example.TestMod.ID)"))
        assertTrue(material.contains("ResourceLocation.fromNamespaceAndPath(com.example.TestMod.ID, textureName)"))
        assertTrue(mod.contains("ExampleArmorMaterials.ARMOR_MATERIALS.register(modEventBus);"))
        assertTrue(result.errors.none { it.contains("Cannot derive mod id expression for legacy ArmorMaterial enum") })
    }

    @Test
    fun `relocates removed mmlib recipe helpers into project local base package`() {
        val projectDir = tempDir.resolve("p15-mmlib")
        val recipeDir = projectDir.resolve("src/main/java/com/example/content/cooking")
        recipeDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        recipeDir.resolve("ExampleRecipe.java").writeText("""
            package com.example.content.cooking;

            import cn.mcmod_mmf.mmlib.fluid.FluidIngredient;
            import cn.mcmod_mmf.mmlib.recipe.AbstractRecipe;
            import cn.mcmod_mmf.mmlib.recipe.AbstractRecipeSerializer;
            import cn.mcmod_mmf.mmlib.recipe.ChanceResult;

            public class ExampleRecipe extends AbstractRecipe {
                FluidIngredient fluid;
                ChanceResult result;
                AbstractRecipeSerializer<ExampleRecipe> serializer;
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = recipeDir.resolve("ExampleRecipe.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-relocate-mmlib-recipe-base" })
        assertTrue(result.changes.any { it.ruleId == "build-add-local-mmlib-recipe-base" })
        assertTrue(recipe.contains("import com.example.content.cooking.base.FluidIngredient;"))
        assertTrue(recipe.contains("import com.example.content.cooking.base.AbstractRecipe;"))
        assertTrue(recipe.contains("import com.example.content.cooking.base.AbstractRecipeSerializer;"))
        assertTrue(recipe.contains("import com.example.content.cooking.base.ChanceResult;"))
        assertTrue(projectDir.resolve("src/main/java/com/example/content/cooking/base/FluidIngredient.java").exists())
        val abstractRecipe = projectDir.resolve("src/main/java/com/example/content/cooking/base/AbstractRecipe.java")
        assertTrue(abstractRecipe.exists())
        val abstractRecipeSource = abstractRecipe.readText()
        assertTrue(abstractRecipeSource.contains("public abstract ItemStack getResultItem(HolderLookup.Provider registries);"))
        assertFalse(abstractRecipeSource.contains("return ItemStack.EMPTY;"))
        assertFalse(abstractRecipeSource.contains("Temporary source-compatibility shim"))
        assertTrue(projectDir.resolve("src/main/java/com/example/content/cooking/base/ChanceResult.java").exists())
        val serializerSource = projectDir.resolve("src/main/java/com/example/content/cooking/base/AbstractRecipeSerializer.java").readText()
        assertTrue(serializerSource.contains("private static JsonElement normalizeLegacyItemStackJson(JsonElement json)"))
        assertTrue(serializerSource.contains("normalized.add(\"id\", obj.get(\"item\"));"))
        assertTrue(serializerSource.contains("components.add(\"minecraft:custom_data\", obj.get(\"nbt\"));"))
        assertTrue(serializerSource.contains("ItemStack stack = decodeItemStack(chanceResultStackJson(obj), \"ChanceResult.item\");"))
        assertTrue(serializerSource.contains("orElseThrow(() -> new JsonParseException("))
        assertFalse(serializerSource.contains(".orElse(ItemStack.EMPTY);"))
        assertTrue(serializerSource.contains("private static JsonElement normalizeLegacyFluidStackJson(JsonElement json)"))
        assertTrue(serializerSource.contains("normalized.add(\"id\", obj.get(\"fluid\"));"))
        assertTrue(serializerSource.contains("\"minecraft:empty\".equals(obj.get(\"fluid\").getAsString())"))
        val fluidIngredientSource = projectDir.resolve("src/main/java/com/example/content/cooking/base/FluidIngredient.java").readText()
        assertTrue(fluidIngredientSource.contains("obj.has(\"fluidTag\")"))
        assertTrue(fluidIngredientSource.contains("obj.has(\"null_fluid\")"))
        assertTrue(fluidIngredientSource.contains("obj.has(\"tag\") ? obj.get(\"tag\").getAsString() : obj.get(\"fluidTag\").getAsString()"))
        assertFalse(projectDir.resolve("src/main/java/cn/mcmod_mmf/mmlib/fluid/FluidIngredient.java").exists())
        assertFalse(projectDir.resolve("src/main/java/cn/mcmod_mmf/mmlib/recipe/AbstractRecipe.java").exists())
        assertFalse(projectDir.resolve("src/main/java/cn/mcmod_mmf/mmlib/recipe/ChanceResult.java").exists())
        assertFalse(projectDir.resolve("src/main/java/cn/mcmod_mmf/mmlib/recipe/AbstractRecipeSerializer.java").exists())
    }

    @Test
    fun `mmlib recipe base relocation requires executable removed type evidence`() {
        val projectDir = tempDir.resolve("p15-mmlib-no-package-guess")
        val recipeDir = projectDir.resolve("src/main/java/com/example/recipes")
        recipeDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        recipeDir.resolve("Notes.java").writeText("""
            package com.example.recipes;

            public class Notes {
                // import cn.mcmod_mmf.mmlib.recipe.AbstractRecipe;
                private static final String DOC = "cn.mcmod_mmf.mmlib.recipe.AbstractRecipe";
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = recipeDir.resolve("Notes.java").readText()

        assertFalse(result.changes.any { it.ruleId == "build-relocate-mmlib-recipe-base" })
        assertFalse(result.changes.any { it.ruleId == "build-add-local-mmlib-recipe-base" })
        assertFalse(projectDir.resolve("src/main/java/com/example/recipes/base/AbstractRecipe.java").exists())
        assertTrue(source.contains("cn.mcmod_mmf.mmlib.recipe.AbstractRecipe"))
    }

    @Test
    fun `does not exclude legacy datagen sources from strict migration`() {
        val projectDir = tempDir.resolve("p16")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        val builderDir = dataDir.resolve("builder")
        dataDir.createDirectories()
        builderDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            public class ExampleRecipeProvider extends AbstractRecipeProvider {
            }
        """.trimIndent())
        builderDir.resolve("ExampleRecipeBuilder.java").writeText("""
            package com.example.data.builder;

            public class ExampleRecipeBuilder {
                public static class Result implements RecipeOutput {
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(result.changes.any { it.ruleId == "build-exclude-integrations" })
        assertFalse(content.contains("exclude 'com/example/data/ExampleRecipeProvider.java'"))
        assertFalse(content.contains("exclude 'com/example/data/builder/ExampleRecipeBuilder.java'"))
    }

    @Test
    fun `does not exclude main-source gametest fixtures from strict migration`() {
        val projectDir = tempDir.resolve("p17")
        val testDir = projectDir.resolve("src/main/java/com/example/test/util")
        testDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        testDir.resolve("SakuraTestBase.java").writeText("""
            package com.example.test.util;

            public final class SakuraTestBase {
                RegistryObject<?> oldForgeTestOnlyField;
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(result.changes.any { it.ruleId == "build-exclude-integrations" })
        assertFalse(content.contains("exclude 'com/example/test/util/SakuraTestBase.java'"))
    }

    @Test
    fun `removes existing build excludes that hide main source and resource content`() {
        val projectDir = tempDir.resolve("p17-existing-excludes")
        val gameTestDir = projectDir.resolve("src/main/java/com/example/gametest")
        val structureDir = projectDir.resolve("src/main/resources/gameteststructures")
        gameTestDir.createDirectories()
        structureDir.createDirectories()
        gameTestDir.resolve("ExampleGameTests.java").writeText("""
            package com.example.gametest;

            public class ExampleGameTests {
            }
        """.trimIndent())
        structureDir.resolve("empty.snbt").writeText("{}")
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            tasks.named('jar', Jar).configure {
                exclude 'com/example/gametest/**'
                exclude 'gameteststructures/**'
                exclude 'META-INF/private-notes.txt'
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-remove-main-source-exclude" }, result.changes.joinToString("\n"))
        assertFalse(content.contains("exclude 'com/example/gametest/**'"), content)
        assertFalse(content.contains("exclude 'gameteststructures/**'"), content)
        assertTrue(content.contains("exclude 'META-INF/private-notes.txt'"), content)
    }

    @Test
    fun `does not create source exclusions for removed api references`() {
        val projectDir = tempDir.resolve("p18")
        val blockDir = projectDir.resolve("src/main/java/com/example/block")
        blockDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        blockDir.resolve("ProviderRegistry.java").writeText("""
            package com.example.block;

            public final class ProviderRegistry {
                public static final Object PROVIDER = Capabilities.ITEM_HANDLER;
            }
        """.trimIndent())
        blockDir.resolve("FoodBlock.java").writeText("""
            package com.example.block;

            public class FoodBlock extends Block {
                public FoodBlock() {
                    super(ProviderRegistry.PROVIDER);
                }

                public int unrelated01() { return 1; }
                public int unrelated02() { return 2; }
                public int unrelated03() { return 3; }
                public int unrelated04() { return 4; }
                public int unrelated05() { return 5; }
                public int unrelated06() { return 6; }
                public int unrelated07() { return 7; }
                public int unrelated08() { return 8; }
                public int unrelated09() { return 9; }
                public int unrelated10() { return 10; }
                public int unrelated11() { return 11; }
                public int unrelated12() { return 12; }
                public int unrelated13() { return 13; }
                public int unrelated14() { return 14; }
                public int unrelated15() { return 15; }
                public int unrelated16() { return 16; }
                public int unrelated17() { return 17; }
                public int unrelated18() { return 18; }
                public int unrelated19() { return 19; }
                public int unrelated20() { return 20; }
                public int unrelated21() { return 21; }
                public int unrelated22() { return 22; }
                public int unrelated23() { return 23; }
                public int unrelated24() { return 24; }
                public int unrelated25() { return 25; }
                public int unrelated26() { return 26; }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(result.changes.any { it.ruleId == "build-exclude-integrations" })
        assertFalse(content.contains("exclude 'com/example/block/ProviderRegistry.java'"))
        assertFalse(content.contains("exclude 'com/example/block/FoodBlock.java'"))
    }

    @Test
    fun `keeps curse maven compileOnly integrations in source set without excluding core`() {
        val projectDir = tempDir.resolve("p19")
        val compatDir = projectDir.resolve("src/main/java/com/example/compat")
        val coreDir = projectDir.resolve("src/main/java/com/example/core")
        compatDir.createDirectories()
        coreDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly fg.deobf("curse.maven:alexs-mobs-426558:4610263")
            }
        """.trimIndent())
        compatDir.resolve("AlexsMobsEventHandler.java").writeText("""
            package com.example.compat;

            import com.github.alexthe666.alexsmobs.entity.EntityFly;

            public final class AlexsMobsEventHandler {
                public static boolean hasFlies(Object level) {
                    return EntityFly.class != null && level != null;
                }
            }
        """.trimIndent())
        compatDir.resolve("CompatBridge.java").writeText("""
            package com.example.compat;

            public final class CompatBridge {
                public boolean usesAlexsMobs(Object level) {
                    if (level == null) {
                        return false;
                    }
                    return AlexsMobsEventHandler.hasFlies(level);
                }

                public int unrelated01() { return 1; }
                public int unrelated02() { return 2; }
                public int unrelated03() { return 3; }
                public int unrelated04() { return 4; }
                public int unrelated05() { return 5; }
                public int unrelated06() { return 6; }
                public int unrelated07() { return 7; }
                public int unrelated08() { return 8; }
                public int unrelated09() { return 9; }
                public int unrelated10() { return 10; }
                public int unrelated11() { return 11; }
                public int unrelated12() { return 12; }
                public int unrelated13() { return 13; }
                public int unrelated14() { return 14; }
                public int unrelated15() { return 15; }
                public int unrelated16() { return 16; }
                public int unrelated17() { return 17; }
                public int unrelated18() { return 18; }
                public int unrelated19() { return 19; }
                public int unrelated20() { return 20; }
                public int unrelated21() { return 21; }
                public int unrelated22() { return 22; }
                public int unrelated23() { return 23; }
                public int unrelated24() { return 24; }
                public int unrelated25() { return 25; }
                public int unrelated26() { return 26; }
            }
        """.trimIndent())
        coreDir.resolve("CoreRegistry.java").writeText("""
            package com.example.core;

            import com.example.compat.AlexsMobsEventHandler;

            public final class CoreRegistry {
                public static final String OPTIONAL_HANDLER =
                    AlexsMobsEventHandler.class.getName();

                public int unrelated01() { return 1; }
                public int unrelated02() { return 2; }
                public int unrelated03() { return 3; }
                public int unrelated04() { return 4; }
                public int unrelated05() { return 5; }
                public int unrelated06() { return 6; }
                public int unrelated07() { return 7; }
                public int unrelated08() { return 8; }
                public int unrelated09() { return 9; }
                public int unrelated10() { return 10; }
                public int unrelated11() { return 11; }
                public int unrelated12() { return 12; }
                public int unrelated13() { return 13; }
                public int unrelated14() { return 14; }
                public int unrelated15() { return 15; }
                public int unrelated16() { return 16; }
                public int unrelated17() { return 17; }
                public int unrelated18() { return 18; }
                public int unrelated19() { return 19; }
                public int unrelated20() { return 20; }
                public int unrelated21() { return 21; }
                public int unrelated22() { return 22; }
                public int unrelated23() { return 23; }
                public int unrelated24() { return 24; }
                public int unrelated25() { return 25; }
                public int unrelated26() { return 26; }
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(content.contains("""compileOnly "curse.maven:alexs-mobs-1-21-1-port-1415721:7391745""""))
        assertFalse(content.contains("4610263"))
        assertFalse(content.contains("E:/MinecraftDev"))
        assertFalse(content.contains("exclude 'com/example/compat/AlexsMobsEventHandler.java'"))
        assertFalse(content.contains("exclude 'com/example/compat/CompatBridge.java'"))
        assertFalse(content.contains("exclude 'com/example/core/CoreRegistry.java'"))
    }

    @Test
    fun `resolves Create CurseMaven dependency to NeoForge compatible file`() {
        val projectDir = tempDir.resolve("p19-create")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly fg.deobf("curse.maven:create-328085:7178761")
                runtimeOnly "curse.maven:create-328085:7178761"
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""compileOnly "curse.maven:create-328085:7408951""""))
        assertFalse(content.contains("7178761"))
        assertFalse(content.contains("net.minecraftforge.fluids"))
    }

    @Test
    fun `resolves JEI to compile common api and full NeoForge runtime jars`() {
        val projectDir = tempDir.resolve("p19-jei")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly fg.deobf("mezz.jei:jei-1.20.1-forge-api:15.2.0.27")
                runtimeOnly fg.deobf("mezz.jei:jei-1.20.1-forge:15.2.0.27")
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""compileOnly "mezz.jei:jei-1.21.1-common-api:19.21.2.313""""))
        assertTrue(content.contains("""compileOnly "mezz.jei:jei-1.21.1-neoforge:19.21.2.313""""))
        assertTrue(content.contains("""runtimeOnly "mezz.jei:jei-1.21.1-neoforge:19.21.2.313""""))
        assertFalse(content.contains("jei-1.20.1-forge"), content)
        assertFalse(content.contains("fg.deobf"), content)
    }

    @Test
    fun `resolves curse maven runtime integrations to clean target runtime coordinates`() {
        val projectDir = tempDir.resolve("p19-curse-runtime-integrations")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                runtimeOnly fg.deobf("curse.maven:jei-238222:6600311")
                runtimeOnly fg.deobf("curse.maven:farmers-delight-398521:6917255")
                runtimeOnly "curse.maven:construction-wand-399558:4684054"
                runtimeOnly "curse.maven:better-combat-by-daedelus-639842:7287334"
                runtimeOnly "curse.maven:cloth-config-348521:5729105"
                runtimeOnly "curse.maven:playeranimator-658587:4587214"
                runtimeOnly "curse.maven:worldedit-225608:4586218"
                runtimeOnly "curse.maven:world-preview-891429:5686690"
                implementation "curse.maven:caelus-308989:5281700"
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(result.changes.any { it.ruleId == "build-remove-dep" })
        assertTrue(content.contains("""compileOnly "mezz.jei:jei-1.21.1-common-api:19.21.2.313""""))
        assertTrue(content.contains("""compileOnly "mezz.jei:jei-1.21.1-neoforge:19.21.2.313""""))
        assertTrue(content.contains("""runtimeOnly "mezz.jei:jei-1.21.1-neoforge:19.21.2.313""""))
        assertTrue(content.contains("""compileOnly "curse.maven:farmers-delight-398521:8083481""""))
        assertTrue(content.contains("""runtimeOnly "curse.maven:farmers-delight-398521:8083481""""))
        assertTrue(content.contains("""runtimeOnly "maven.modrinth:better-combat:2.3.2+1.21.1-neoforge""""))
        assertTrue(content.contains("""runtimeOnly "maven.modrinth:cloth-config:15.0.140+neoforge""""))
        assertTrue(content.contains("""runtimeOnly "maven.modrinth:playeranimator:2.0.4+1.21.1-forge""""))
        assertTrue(content.contains("""runtimeOnly "maven.modrinth:worldedit:7.3.8""""))
        assertTrue(content.contains("""compileOnly "curse.maven:caelus-308989:5694215""""))
        assertTrue(content.contains("""runtimeOnly "curse.maven:caelus-308989:5694215""""))
        assertTrue(content.contains("https://maven.blamejared.com"), content)
        assertTrue(content.contains("https://api.modrinth.com/maven"), content)
        assertTrue(content.contains("https://www.cursemaven.com"), content)
        assertFalse(content.contains("curse.maven:construction-wand-399558"), content)
        assertFalse(content.contains("curse.maven:world-preview-891429"), content)
        assertFalse(content.contains("6600311"), content)
        assertFalse(content.contains("6917255"), content)
        assertFalse(content.contains("7287334"), content)
        assertFalse(content.contains("5729105"), content)
        assertFalse(content.contains("4587214"), content)
        assertFalse(content.contains("4586218"), content)
        assertFalse(content.contains("5281700"), content)
        assertFalse(content.contains("fg.deobf"), content)
    }

    @Test
    fun `unresolved fg deobf wrappers are removed without dependency prefix allowlist`() {
        val projectDir = tempDir.resolve("p19-generic-fg-deobf")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly fg.deobf "com.example.unlisted:api:1.2.3"
                runtimeOnly fg.deobf("com.example.unlisted:runtime:4.5.6")
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(content.contains("""compileOnly "com.example.unlisted:api:1.2.3""""))
        assertTrue(content.contains("""runtimeOnly "com.example.unlisted:runtime:4.5.6""""))
        assertFalse(content.contains("fg.deobf"), content)
    }

    @Test
    fun `resolves versioned public library dependencies to target NeoForge coordinates`() {
        val projectDir = tempDir.resolve("p19-versioned-public-libraries")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                implementation fg.deobf("com.aetherteam.nitrogen:nitrogen_internals:${'$'}{project.nitrogen_version}")
                implementation fg.deobf("com.aetherteam.cumulus:cumulus_menus:${'$'}{project.cumulus_version}")
            }
        """.trimIndent())
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            nitrogen_version=1.20.1-1.0.12-neoforge
            cumulus_version=1.20.1-1.0.1-neoforge
        """.trimIndent())

        val result = pass.apply(projectDir)
        val build = projectDir.resolve("build.gradle").readText()
        val props = projectDir.resolve("gradle.properties").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(build.contains("""implementation "com.aetherteam.nitrogen:nitrogen_internals:1.21.1-1.1.25-neoforge""""))
        assertTrue(build.contains("""implementation "com.aetherteam.cumulus:cumulus_menus:1.21.1-2.0.8-neoforge""""))
        assertTrue(build.contains("https://packages.aether-mod.net/Nitrogen"))
        assertTrue(build.contains("https://packages.aether-mod.net/Cumulus"))
        assertTrue(props.contains("nitrogen_version=1.21.1-1.1.25-neoforge"), props)
        assertTrue(props.contains("cumulus_version=1.21.1-2.0.8-neoforge"), props)
        assertFalse(build.contains("fg.deobf"), build)
        assertFalse(props.contains("1.20.1-1.0"), props)
    }

    @Test
    fun `dependency version properties update only when matching dependency is declared`() {
        val projectDir = tempDir.resolve("p19-version-property-scope")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                implementation "com.example:unrelated:1.0.0"
            }
        """.trimIndent())
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            jade_version=4681833
        """.trimIndent())

        pass.apply(projectDir)
        val props = projectDir.resolve("gradle.properties").readText()

        assertTrue(props.contains("jade_version=4681833"), props)
        assertFalse(props.contains("jade_version=5813144"), props)
    }

    @Test
    fun `dependency resolver ignores known prefixes outside parsed coordinates`() {
        val projectDir = tempDir.resolve("p19-structured-dependency-no-comment-match")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                implementation "com.example:library:1.0.0" // curse.maven:create-328085:7178761 documents an optional manual test
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(result.changes.any { it.ruleId == "build-resolve-dep" }, result.changes.joinToString("\n"))
        assertTrue(content.contains("""implementation "com.example:library:1.0.0""""))
        assertFalse(content.contains("curse.maven:create-328085:7408951"), content)
    }

    @Test
    fun `dependency resolver handles Gradle map notation as structured coordinates`() {
        val projectDir = tempDir.resolve("p19-structured-map-notation")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly group: 'curse.maven', name: 'create-328085', version: '7178761'
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""compileOnly "curse.maven:create-328085:7408951""""))
        assertFalse(content.contains("7178761"), content)
    }

    @Test
    fun `resolves TerraBlender and Curios Forge coordinates to target NeoForge artifacts`() {
        val projectDir = tempDir.resolve("p19-btv-public-libraries")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                implementation fg.deobf('com.github.glitchfiend:TerraBlender-forge:1.20.1-3.0.1.7')
                compileOnly(fg.deobf("top.theillusivec4.curios:curios-forge:${'$'}{curios_version}:api"))
                runtimeOnly(fg.deobf("top.theillusivec4.curios:curios-forge:${'$'}{curios_version}"))
            }
        """.trimIndent())
        projectDir.resolve("gradle.properties").writeText("""
            minecraft_version=1.20.1
            curios_version=5.12.0+1.20.1
        """.trimIndent())

        val result = pass.apply(projectDir)
        val build = projectDir.resolve("build.gradle").readText()
        val props = projectDir.resolve("gradle.properties").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(build.contains("""implementation "com.github.glitchfiend:TerraBlender-neoforge:1.21.1-4.1.0.8""""))
        assertTrue(build.contains("""compileOnly "top.theillusivec4.curios:curios-neoforge:9.2.3+1.21.1:api""""))
        assertTrue(build.contains("""compileOnly "top.theillusivec4.curios:curios-neoforge:9.2.3+1.21.1""""))
        assertTrue(build.contains("""compileOnly "net.fabricmc:fabric-loader:0.16.14""""))
        assertTrue(build.contains("""runtimeOnly "top.theillusivec4.curios:curios-neoforge:9.2.3+1.21.1""""))
        assertTrue(build.contains("https://maven.minecraftforge.net/releases"), build)
        assertTrue(build.contains("https://maven.theillusivec4.top/"), build)
        assertTrue(build.contains("https://maven.fabricmc.net/"), build)
        assertTrue(props.contains("curios_version=9.2.3+1.21.1"), props)
        assertFalse(build.contains("TerraBlender-forge"), build)
        assertFalse(build.contains("curios-forge"), build)
        assertFalse(build.contains("fg.deobf"), build)
    }

    @Test
    fun `resolves Curios CurseMaven coordinate to target NeoForge artifacts`() {
        val projectDir = tempDir.resolve("p19-curios-cursemaven")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            repositories {
                maven {
                    url "https://www.cursemaven.com"
                    content { includeGroup "curse.maven" }
                }
            }

            dependencies {
                implementation "curse.maven:curios-309927:5843594"
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(build.contains("""compileOnly "top.theillusivec4.curios:curios-neoforge:9.2.3+1.21.1:api""""))
        assertTrue(build.contains("""compileOnly "top.theillusivec4.curios:curios-neoforge:9.2.3+1.21.1""""))
        assertTrue(build.contains("""runtimeOnly "top.theillusivec4.curios:curios-neoforge:9.2.3+1.21.1""""))
        assertTrue(build.contains("https://maven.theillusivec4.top/"), build)
        assertFalse(build.contains("curse.maven:curios-309927"), build)
    }

    @Test
    fun `resolves replaced runtime-only optional mod dependencies`() {
        val projectDir = tempDir.resolve("p19-runtime-replacement")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            repositories {
                maven {
                    name 'Tama Maven'
                    url "https://maven.tamaized.com/releases"
                }
            }

            dependencies {
                runtimeOnly fg.deobf("team-twilight:crossdimcommands:${'$'}{project.base_minecraft_version}-1.0")
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""runtimeOnly "com.telepathicgrunt:CommandStructures-Neoforge:4.3.2+1.20.6""""))
        assertTrue(content.contains("https://nexus.resourcefulbees.com/repository/maven-public/"))
        assertFalse(content.contains("team-twilight:crossdimcommands"))
    }

    @Test
    fun `resolves and removes stale curse maven runtime dependencies by explicit mapping`() {
        val projectDir = tempDir.resolve("p19-runtime-stale-removal")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                implementation fg.deobf("curse.maven:jade-324717:4681833")
                compileOnly "curse.maven:lootr-361276:${'$'}{project.lootr_version}"
                runtimeOnly "curse.maven:jeed-532286:4599236"
                runtimeOnly fg.deobf("curse.maven:museum-curator-859070:4629894")
            }
        """.trimIndent())
        projectDir.resolve("gradle.properties").writeText("""
            lootr_version=5636598
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()
        val props = projectDir.resolve("gradle.properties").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(result.changes.any { it.ruleId == "build-remove-dep" })
        assertTrue(content.contains("""compileOnly "curse.maven:jade-324717:5813144""""))
        assertTrue(content.contains("""compileOnly "curse.maven:lootr-361276:5832064""""))
        assertTrue(content.contains("""runtimeOnly "curse.maven:jeed-532286:6550600""""))
        assertFalse(content.contains("4681833"))
        assertFalse(content.contains("5636598"))
        assertFalse(content.contains("4599236"))
        assertFalse(content.contains("curse.maven:museum-curator-859070"))
        assertFalse(content.contains("4629894"))
        assertTrue(props.contains("lootr_version=5832064"), props)
    }

    @Test
    fun `runtime absent dependency mapping does not remove compile api declarations`() {
        val projectDir = tempDir.resolve("p19-runtime-absent-scope")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly "curse.maven:museum-curator-859070:4629894"
                runtimeOnly fg.deobf("curse.maven:museum-curator-859070:4629894")
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-remove-dep" })
        assertTrue(content.contains("""compileOnly "curse.maven:museum-curator-859070:4629894""""))
        assertFalse(content.contains("""runtimeOnly "curse.maven:museum-curator-859070:4629894""""))
    }

    @Test
    fun `renders resolved optional public curse maven dependencies as compileOnly coordinates`() {
        val projectDir = tempDir.resolve("p19-public-curse-maven")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly fg.deobf("curse.maven:alexs-mobs-426558:5698791")
                runtimeOnly "curse.maven:alexs-mobs-426558:5698791"
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""compileOnly "curse.maven:alexs-mobs-1-21-1-port-1415721:7391745""""))
        assertFalse(content.contains("""runtimeOnly "curse.maven:alexs-mobs-1-21-1-port-1415721:7391745""""))
        assertFalse(content.contains("5698791"))
        assertFalse(content.contains("E:/MinecraftDev"))
        assertFalse(content.contains("""compileOnly "files("""))
    }

    @Test
    fun `resolves Botania map style dependency to NeoForge compileOnly snapshot`() {
        val projectDir = tempDir.resolve("p19-botania")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                compileOnly [
                    group: "vazkii.botania",
                    name: "Botania",
                    version: "${'$'}{project.botania}",
                    classifier: "api"
                ]
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""compileOnly "vazkii.botania:botania-neoforge-1.21.1:452-SNAPSHOT""""))
        assertTrue(content.contains("https://maven.blamejared.com"))
        assertFalse(content.contains("TO" + "DO: Update for NeoForge"))
        assertFalse(content.contains("name: \"Botania\""))
    }

    @Test
    fun `resolves CraftTweaker to official NeoForge maven artifact`() {
        val projectDir = tempDir.resolve("p19-crafttweaker")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }

            dependencies {
                implementation fg.deobf("com.blamejared.crafttweaker:CraftTweaker-forge-1.20.1:14.0.14")
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertTrue(result.changes.any { it.ruleId == "build-resolve-dep" })
        assertTrue(content.contains("""implementation "com.blamejared.crafttweaker:CraftTweaker-neoforge-1.21.1:21.0.38""""))
        assertTrue(content.contains("https://maven.blamejared.com"))
        assertFalse(content.contains("CraftTweaker-forge-1.20.1"))
        assertFalse(content.contains("maven.modrinth:crafttweaker"))
    }

    @Test
    fun `migrates RecipeProvider lookup constructor and special recipe factory`() {
        val projectDir = tempDir.resolve("p19-datagen")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        dataDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("RecipeGenerator.java").writeText("""
            package com.example.data;

            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;
            import net.minecraft.data.recipes.RecipeProvider;
            import net.minecraft.data.recipes.SpecialRecipeBuilder;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.CraftingBookCategory;
            import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
            import net.minecraft.core.registries.BuiltInRegistries;

            public class RecipeGenerator extends RecipeProvider {
                public RecipeGenerator(PackOutput packOutput) {
                    super(packOutput);
                }

                protected void buildRecipes(RecipeOutput consumer) {
                    specialRecipe(consumer, RecipeWandUpgrade.SERIALIZER);
                }

                private void specialRecipe(RecipeOutput consumer, SimpleCraftingRecipeSerializer<RecipeWandUpgrade> serializer) {
                    ResourceLocation name = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
                    SpecialRecipeBuilder.special(serializer).save(consumer, ExampleMod.loc("dynamic/" + name.getPath()).toString());
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    generator.addProvider(true, new RecipeGenerator(packOutput));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = dataDir.resolve("RecipeGenerator.java").readText()
        val modData = dataDir.resolve("ModData.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-datagen-api-121" })
        assertTrue(recipe.contains("import java.util.concurrent.CompletableFuture;"))
        assertTrue(recipe.contains("import net.minecraft.core.HolderLookup;"))
        assertTrue(recipe.contains("import net.minecraft.core.registries.BuiltInRegistries;"))
        assertTrue(recipe.contains("public RecipeGenerator(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> lookupProvider)"))
        assertTrue(recipe.contains("super(packOutput, lookupProvider);"))
        assertTrue(recipe.contains("java.util.function.Function<net.minecraft.world.item.crafting.CraftingBookCategory, net.minecraft.world.item.crafting.Recipe<?>> recipeFactory"))
        assertTrue(recipe.contains("specialRecipe(consumer, RecipeWandUpgrade::new, BuiltInRegistries.RECIPE_SERIALIZER.getKey(RecipeWandUpgrade.SERIALIZER));"))
        assertTrue(recipe.contains("SpecialRecipeBuilder.special(recipeFactory).save"))
        assertFalse(recipe.contains("SpecialRecipeBuilder.special(serializer)"))
        assertFalse(recipe.contains("ResourceLocation name ="))
        assertFalse(recipe.contains("Registries.RECIPE_SERIALIZER.getKey(serializer)"))
        assertTrue(modData.contains("new RecipeGenerator(packOutput, event.getLookupProvider())"))
    }

    @Test
    fun `migrates mmlib recipe and loot datagen providers to lookup constructors`() {
        val projectDir = tempDir.resolve("p19-mmlib-datagen")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        dataDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            import com.example.ExampleMod;
            import cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;

            public class ExampleRecipeProvider extends AbstractRecipeProvider {
                public ExampleRecipeProvider(PackOutput packOutput) {
                    super(packOutput);
                }

                protected void buildRecipes(RecipeOutput output) {
                    save(output, ExampleMod.MODID);
                }
            }
        """.trimIndent())
        dataDir.resolve("ExampleLootTableProvider.java").writeText("""
            package com.example.data;

            import net.minecraft.data.PackOutput;
            import net.minecraft.data.loot.LootTableProvider;
            import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
            import java.util.List;
            import java.util.Set;

            public class ExampleLootTableProvider extends LootTableProvider {
                public ExampleLootTableProvider(PackOutput packOutput) {
                    super(packOutput, Set.of(), List.of(new SubProviderEntry(ExampleBlockLoot::new, LootContextParamSets.BLOCK)));
                }
            }
        """.trimIndent())
        dataDir.resolve("ExampleLootModifierProvider.java").writeText("""
            package com.example.data;

            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;

            public class ExampleLootModifierProvider extends GlobalLootModifierProvider {
                public ExampleLootModifierProvider(PackOutput output, String modid) {
                    super(output, modid);
                }

                protected void start() {
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import com.example.ExampleMod;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;
            import java.util.concurrent.CompletableFuture;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    CompletableFuture<HolderLookup.Provider> provider = event.getLookupProvider();
                    generator.addProvider(true, new ExampleRecipeProvider(packOutput));
                    generator.addProvider(true, new ExampleLootTableProvider(packOutput));
                    generator.addProvider(true, new ExampleLootModifierProvider(packOutput, ExampleMod.MODID));
                }
            }
        """.trimIndent())
        projectDir.resolve("src/main/java/com/example/ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "example";
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = dataDir.resolve("ExampleRecipeProvider.java").readText()
        val lootTable = dataDir.resolve("ExampleLootTableProvider.java").readText()
        val lootModifier = dataDir.resolve("ExampleLootModifierProvider.java").readText()
        val modData = dataDir.resolve("ModData.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-datagen-api-121" })
        assertTrue(recipe.contains("public ExampleRecipeProvider(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> lookupProvider)"))
        assertTrue(recipe.contains("super(packOutput, com.example.ExampleMod.MODID, lookupProvider);"))
        assertTrue(lootTable.contains("public ExampleLootTableProvider(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> lookupProvider)"))
        assertTrue(lootTable.contains("lookupProvider"))
        assertTrue(lootModifier.contains("public ExampleLootModifierProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, String modid)"))
        assertTrue(lootModifier.contains("super(output, lookupProvider, modid);"))
        assertTrue(modData.contains("new ExampleRecipeProvider(packOutput, provider)"))
        assertTrue(modData.contains("new ExampleLootTableProvider(packOutput, provider)"))
        assertTrue(modData.contains("new ExampleLootModifierProvider(packOutput, provider, ExampleMod.MODID)"))
    }

    @Test
    fun `global loot modifier provider keeps already migrated lookup constructor calls`() {
        val projectDir = tempDir.resolve("p19-global-loot-modifier-existing-lookup")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        dataDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("ExampleLootModifierProvider.java").writeText("""
            package com.example.data;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.common.data.GlobalLootModifierProvider;

            public class ExampleLootModifierProvider extends GlobalLootModifierProvider {
                public ExampleLootModifierProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
                    super(output, lookupProvider, "example");
                }

                protected void start() {
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    CompletableFuture<HolderLookup.Provider> provider = event.getLookupProvider();
                    generator.addProvider(true, new ExampleLootModifierProvider(packOutput, provider));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val modData = dataDir.resolve("ModData.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(modData.contains("new ExampleLootModifierProvider(packOutput, provider)"), modData)
        assertFalse(modData.contains("provider, provider"), modData)
    }

    @Test
    fun `mmlib recipe provider derives mod id from project metadata`() {
        val projectDir = tempDir.resolve("p19-mmlib-datagen-metadata-modid")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        val metaInfDir = projectDir.resolve("src/main/resources/META-INF")
        dataDir.createDirectories()
        metaInfDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        metaInfDir.resolve("mods.toml").writeText("""
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"

            [[mods]]
            modId="examplemod"
            version="1.0.0"
            displayName="Example Mod"
        """.trimIndent())
        dataDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            import cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;

            public class ExampleRecipeProvider extends AbstractRecipeProvider {
                public ExampleRecipeProvider(PackOutput packOutput) {
                    super(packOutput);
                }

                protected void buildRecipes(RecipeOutput output) {
                    save(output, "example_recipe");
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    generator.addProvider(true, new ExampleRecipeProvider(packOutput));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = dataDir.resolve("ExampleRecipeProvider.java").readText()
        val modData = dataDir.resolve("ModData.java").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected build-system errors: ${result.errors}")
        assertTrue(result.changes.any { it.ruleId == "build-datagen-api-121" })
        assertFalse(recipe.contains("\"minecraft\""))
        assertTrue(recipe.contains("CompletableFuture<HolderLookup.Provider> lookupProvider"))
        assertTrue(recipe.contains("super(packOutput, \"examplemod\", lookupProvider);"))
        assertTrue(modData.contains("new ExampleRecipeProvider(packOutput, event.getLookupProvider())"))
    }

    @Test
    fun `mmlib recipe provider does not derive mod id from unrelated constants`() {
        val projectDir = tempDir.resolve("p19-mmlib-datagen-unrelated-modid")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        dataDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            import cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider;
            import com.dependency.DependencyMod;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;

            public class ExampleRecipeProvider extends AbstractRecipeProvider {
                public ExampleRecipeProvider(PackOutput packOutput) {
                    super(packOutput);
                }

                protected void buildRecipes(RecipeOutput output) {
                    save(output, DependencyMod.MODID);
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    generator.addProvider(true, new ExampleRecipeProvider(packOutput));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = dataDir.resolve("ExampleRecipeProvider.java").readText()

        assertTrue(
            result.errors.any { it.contains("Cannot derive mod id for AbstractRecipeProvider ExampleRecipeProvider") },
            "Expected hard migration error, got: ${result.errors}"
        )
        assertFalse(recipe.contains("DependencyMod.MODID, lookupProvider"))
        assertTrue(recipe.contains("super(packOutput);"))
    }

    @Test
    fun `mmlib recipe provider ignores text block mod id documentation`() {
        val projectDir = tempDir.resolve("p19-mmlib-datagen-text-block-modid")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val dataDir = srcDir.resolve("data")
        dataDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        srcDir.resolve("ExampleMod.java").writeText(listOf(
            "package com.example;",
            "",
            "public final class ExampleMod {",
            "    String docs() {",
            "        return \"\"\"",
            "            @Mod(\"examplemod\")",
            "            public static final String MODID = \"examplemod\";",
            "            \"\"\";",
            "    }",
            "}",
            ""
        ).joinToString(System.lineSeparator()))
        dataDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            import cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;

            public class ExampleRecipeProvider extends AbstractRecipeProvider {
                public ExampleRecipeProvider(PackOutput packOutput) {
                    super(packOutput);
                }

                protected void buildRecipes(RecipeOutput output) {
                    save(output, "example_recipe");
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    generator.addProvider(true, new ExampleRecipeProvider(packOutput));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = dataDir.resolve("ExampleRecipeProvider.java").readText()

        assertTrue(
            result.errors.any { it.contains("Cannot derive mod id for AbstractRecipeProvider ExampleRecipeProvider") },
            "Expected text block mod id documentation to be ignored; got errors: ${result.errors}"
        )
        assertFalse(recipe.contains("\"examplemod\", lookupProvider"), recipe)
        assertTrue(recipe.contains("super(packOutput);"), recipe)
    }

    @Test
    fun `mmlib recipe provider without any mod id source reports hard migration error`() {
        val projectDir = tempDir.resolve("p19-mmlib-datagen-missing-modid")
        val dataDir = projectDir.resolve("src/main/java/com/example/data")
        dataDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        dataDir.resolve("ExampleRecipeProvider.java").writeText("""
            package com.example.data;

            import cn.mcmod_mmf.mmlib.data.AbstractRecipeProvider;
            import net.minecraft.data.PackOutput;
            import net.minecraft.data.recipes.RecipeOutput;

            public class ExampleRecipeProvider extends AbstractRecipeProvider {
                public ExampleRecipeProvider(PackOutput packOutput) {
                    super(packOutput);
                }

                protected void buildRecipes(RecipeOutput output) {
                    save(output, "example_recipe");
                }
            }
        """.trimIndent())
        dataDir.resolve("ModData.java").writeText("""
            package com.example.data;

            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ModData {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput packOutput = generator.getPackOutput();
                    generator.addProvider(true, new ExampleRecipeProvider(packOutput));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val recipe = dataDir.resolve("ExampleRecipeProvider.java").readText()
        val modData = dataDir.resolve("ModData.java").readText()

        assertTrue(
            result.errors.any { it.contains("Cannot derive mod id for AbstractRecipeProvider ExampleRecipeProvider") },
            "Expected hard migration error, got: ${result.errors}"
        )
        assertFalse(recipe.contains("\"minecraft\""))
        assertTrue(recipe.contains("super(packOutput);"))
        assertTrue(modData.contains("new ExampleRecipeProvider(packOutput)"))
    }

    @Test
    fun `migrates custom stat registration to deferred register`() {
        val projectDir = tempDir.resolve("p19-custom-stats")
        val rootDir = projectDir.resolve("src/main/java/com/example")
        val basicsDir = rootDir.resolve("basics")
        basicsDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        rootDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import com.example.basics.ModStats;
            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.ModContainer;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                public ExampleMod(IEventBus modEventBus, ModContainer modContainer) {
                    modEventBus.addListener(this::commonSetup);
                    ModItems.ITEMS.register(modEventBus);
                }

                private void commonSetup(final FMLCommonSetupEvent event) {
                    ModStats.register();
                }
            }
        """.trimIndent())
        basicsDir.resolve("ModStats.java").writeText("""
            package com.example.basics;

            import com.example.ExampleMod;
            import net.minecraft.core.Registry;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.stats.StatFormatter;
            import net.minecraft.stats.Stats;

            public class ModStats
            {
                public static final ResourceLocation USE_WAND = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "use_wand");

                public static void register() {
                    registerStat(USE_WAND);
                }

                private static void registerStat(ResourceLocation registryName) {
                    Registry.register(BuiltInRegistries.CUSTOM_STAT, registryName.getPath(), registryName);
                    Stats.CUSTOM.get(registryName, StatFormatter.DEFAULT);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val stats = basicsDir.resolve("ModStats.java").readText()
        val mod = rootDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-custom-stat-deferred-register" })
        assertTrue(result.changes.any { it.ruleId == "build-custom-stat-mod-event-bus" })
        assertTrue(stats.contains("import net.minecraft.core.registries.Registries;"))
        assertTrue(stats.contains("import net.neoforged.bus.api.IEventBus;"))
        assertTrue(stats.contains("import net.neoforged.neoforge.registries.DeferredRegister;"))
        assertTrue(stats.contains("DeferredRegister.create(Registries.CUSTOM_STAT, ExampleMod.MODID)"))
        assertTrue(stats.contains("""CUSTOM_STATS.register("use_wand", () -> USE_WAND);"""))
        assertTrue(stats.contains("public static void register(IEventBus modEventBus)"))
        assertFalse(stats.contains("Registry.register(BuiltInRegistries.CUSTOM_STAT"))
        assertFalse(stats.contains("Stats.CUSTOM.get"))
        assertTrue(mod.contains("ModStats.register(modEventBus);"))
        assertFalse(mod.contains("ModStats.register();"))
    }

    @Test
    fun `custom stat migration uses declared Java owner not file name`() {
        val projectDir = tempDir.resolve("p19-custom-stats-declared-owner")
        val rootDir = projectDir.resolve("src/main/java/com/example/basics")
        rootDir.createDirectories()
        rootDir.resolve("ExampleMod.java").writeText("""
            package com.example.basics;

            import net.neoforged.bus.api.IEventBus;
            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "examplemod";

                public ExampleMod(IEventBus modEventBus) {
                    ModItems.ITEMS.register(modEventBus);
                    ActualStats.register();
                }
            }
        """.trimIndent())
        rootDir.resolve("LegacyStatsContainer.java").writeText("""
            package com.example.basics;

            import net.minecraft.core.Registry;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.stats.StatFormatter;
            import net.minecraft.stats.Stats;

            class ActualStats {
                public static final ResourceLocation USE_WAND = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "use_wand");

                public static void register() {
                    registerStat(USE_WAND);
                }

                private static void registerStat(ResourceLocation registryName) {
                    Registry.register(BuiltInRegistries.CUSTOM_STAT, registryName.getPath(), registryName);
                    Stats.CUSTOM.get(registryName, StatFormatter.DEFAULT);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val stats = rootDir.resolve("LegacyStatsContainer.java").readText()
        val mod = rootDir.resolve("ExampleMod.java").readText()

        assertTrue(result.errors.isEmpty(), "errors=${result.errors}")
        assertTrue(result.changes.any { it.ruleId == "build-custom-stat-deferred-register" })
        assertTrue(stats.contains("class ActualStats {\n    private static final DeferredRegister<ResourceLocation> CUSTOM_STATS"), stats)
        assertTrue(mod.contains("ActualStats.register(modEventBus);"), mod)
        assertFalse(mod.contains("LegacyStatsContainer.register(modEventBus);"), mod)
    }

    @Test
    fun `custom stat migration rejects mixed namespaces instead of choosing first declaration`() {
        val projectDir = tempDir.resolve("p19-custom-stats-mixed-namespaces")
        val rootDir = projectDir.resolve("src/main/java/com/example")
        rootDir.createDirectories()
        rootDir.resolve("ModStats.java").writeText("""
            package com.example;

            import net.minecraft.core.Registry;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.stats.StatFormatter;
            import net.minecraft.stats.Stats;

            public class ModStats {
                public static final ResourceLocation USE_WAND = ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "use_wand");
                public static final ResourceLocation FOREIGN = ResourceLocation.fromNamespaceAndPath(OtherMod.MODID, "foreign");

                public static void register() {
                    registerStat(USE_WAND);
                    registerStat(FOREIGN);
                }

                private static void registerStat(ResourceLocation registryName) {
                    Registry.register(BuiltInRegistries.CUSTOM_STAT, registryName.getPath(), registryName);
                    Stats.CUSTOM.get(registryName, StatFormatter.DEFAULT);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val stats = rootDir.resolve("ModStats.java").readText()

        assertTrue(
            result.errors.any { it.contains("mixed custom stat namespaces") },
            "Expected mixed namespace hard gate, got: ${result.errors}"
        )
        assertFalse(stats.contains("DeferredRegister.create(Registries.CUSTOM_STAT, ExampleMod.MODID)"), stats)
        assertTrue(stats.contains("Registry.register(BuiltInRegistries.CUSTOM_STAT"), stats)
    }

    @Test
    fun `namespaces register event resource locations with mod id`() {
        val projectDir = tempDir.resolve("p19-registerevent-location")
        val itemDir = projectDir.resolve("src/main/java/com/example/items")
        itemDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        itemDir.resolve("ModItems.java").writeText("""
            package com.example.items;

            import com.example.ExampleMod;
            import net.minecraft.core.registries.Registries;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.Mod.EventBusSubscriber;
            import net.neoforged.neoforge.registries.RegisterEvent;

            @EventBusSubscriber(modid = ExampleMod.MODID)
            public class ModItems {
                @SubscribeEvent
                public static void registerRecipeSerializers(RegisterEvent event) {
                    event.register(Registries.RECIPE_SERIALIZER, registry -> {
                        registry.register("wand_upgrade", RecipeWandUpgrade.SERIALIZER);
                        registry.register("example:qualified_upgrade", QualifiedUpgrade.SERIALIZER);
                    });
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = itemDir.resolve("ModItems.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-registerevent-resource-location-namespace" })
        assertTrue(content.contains("import net.minecraft.resources.ResourceLocation;"))
        assertTrue(content.contains("""registry.register(ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "wand_upgrade"), RecipeWandUpgrade.SERIALIZER);"""))
        assertTrue(content.contains("""registry.register(ResourceLocation.parse("example:qualified_upgrade"), QualifiedUpgrade.SERIALIZER);"""))
        assertFalse(content.contains("""ResourceLocation.parse("wand_upgrade")"""))
        assertFalse(content.contains("""registry.register("wand_upgrade""""))
    }

    @Test
    fun `register event string ids are not namespaced without source mod id evidence`() {
        val projectDir = tempDir.resolve("p19-registerevent-location-no-modid")
        val itemDir = projectDir.resolve("src/main/java/com/example/items")
        itemDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        itemDir.resolve("ModItems.java").writeText("""
            package com.example.items;

            import net.minecraft.core.registries.Registries;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.registries.RegisterEvent;

            public class ModItems {
                @SubscribeEvent
                public static void registerRecipeSerializers(RegisterEvent event) {
                    event.register(Registries.RECIPE_SERIALIZER, registry -> {
                        registry.register("wand_upgrade", RecipeWandUpgrade.SERIALIZER);
                    });
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = itemDir.resolve("ModItems.java").readText()

        assertFalse(result.changes.any { it.ruleId == "build-registerevent-resource-location-namespace" })
        assertTrue(content.contains("""registry.register("wand_upgrade", RecipeWandUpgrade.SERIALIZER);"""))
        assertFalse(content.contains("minecraft:wand_upgrade"))
        assertFalse(content.contains("ResourceLocation.fromNamespaceAndPath"))
    }

    @Test
    fun `migrates world carver register event to deferred register`() {
        val projectDir = tempDir.resolve("p19-world-carvers")
        val initDir = projectDir.resolve("src/main/java/com/example/init")
        val rootDir = projectDir.resolve("src/main/java/com/example")
        initDir.createDirectories()
        rootDir.createDirectories()
        initDir.resolve("ExampleCarvers.java").writeText("""
            package com.example.init;

            import net.minecraft.core.HolderGetter;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.data.worldgen.BootstrapContext;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.tags.BlockTags;
            import net.minecraft.util.valueproviders.ConstantFloat;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.levelgen.VerticalAnchor;
            import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
            import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
            import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.fml.common.Mod;
            import net.neoforged.neoforge.registries.ForgeRegistries;
            import net.neoforged.neoforge.registries.RegisterEvent;
            import com.example.ExampleMod;
            import com.example.world.ExampleCavesCarver;

            import java.util.Objects;

            @Mod.EventBusSubscriber(modid = ExampleMod.ID, bus = Mod.EventBusSubscriber.Bus.MOD)
            public class ExampleCarvers {
                public static final ExampleCavesCarver EXAMPLE_CAVES = new ExampleCavesCarver(CaveCarverConfiguration.CODEC, false);
                public static final ExampleCavesCarver HIGHLAND_CAVES = new ExampleCavesCarver(CaveCarverConfiguration.CODEC, true);

                // configured versions need explicit registration
                @SubscribeEvent
                public static void register(RegisterEvent evt) {
                    if (Objects.equals(evt.getForgeRegistry(), ForgeRegistries.WORLD_CARVERS)) {
                        evt.register(ForgeRegistries.Keys.WORLD_CARVERS, helper -> helper.register(ExampleMod.prefix("example_caves"), EXAMPLE_CAVES));
                        evt.register(ForgeRegistries.Keys.WORLD_CARVERS, helper -> helper.register(ExampleMod.prefix("highland_caves"), HIGHLAND_CAVES));
                    }
                }

                public static final ResourceKey<ConfiguredWorldCarver<?>> EXAMPLE_CAVES_CONFIGURED = registerKey("example_caves");

                private static ResourceKey<ConfiguredWorldCarver<?>> registerKey(String name) {
                    return ResourceKey.create(Registries.CONFIGURED_CARVER, ExampleMod.prefix(name));
                }

                public static void bootstrap(BootstrapContext<ConfiguredWorldCarver<?>> context) {
                    HolderGetter<Block> blocks = context.lookup(Registries.BLOCK);
                    context.register(EXAMPLE_CAVES_CONFIGURED, EXAMPLE_CAVES.configured(new CaveCarverConfiguration(0.1F, UniformHeight.of(VerticalAnchor.aboveBottom(5), VerticalAnchor.absolute(-8)), ConstantFloat.of(0.6F), VerticalAnchor.bottom(), blocks.getOrThrow(BlockTags.OVERWORLD_CARVER_REPLACEABLES), ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), ConstantFloat.of(-0.7F))));
                }
            }
        """.trimIndent())
        rootDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.bus.api.IEventBus;

            public class ExampleMod {
                public static final String ID = "example";

                public ExampleMod(IEventBus modbus) {
                    modbus.addListener(com.example.init.ExampleCarvers::register);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val carvers = initDir.resolve("ExampleCarvers.java").readText()
        val mod = rootDir.resolve("ExampleMod.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-world-carver-deferred-register" })
        assertTrue(result.changes.any { it.ruleId == "build-world-carver-modbus-register" })
        assertTrue(carvers.contains("DeferredRegister<WorldCarver<?>> CARVER_TYPES = DeferredRegister.create(Registries.CARVER, ExampleMod.ID)"))
        assertTrue(carvers.contains("DeferredHolder<WorldCarver<?>, ExampleCavesCarver> EXAMPLE_CAVES = CARVER_TYPES.register(\"example_caves\""))
        assertTrue(carvers.contains("DeferredHolder<WorldCarver<?>, ExampleCavesCarver> HIGHLAND_CAVES = CARVER_TYPES.register(\"highland_caves\""))
        assertTrue(carvers.contains("EXAMPLE_CAVES.value().configured(new CaveCarverConfiguration"))
        assertFalse(carvers.contains("RegisterEvent"))
        assertFalse(carvers.contains("ForgeRegistries.WORLD_CARVERS"))
        assertFalse(carvers.contains("@SubscribeEvent"))
        assertTrue(mod.contains("com.example.init.ExampleCarvers.CARVER_TYPES.register(modbus);"))
        assertFalse(mod.contains("ExampleCarvers::register"))
    }

    @Test
    fun `world carver migration derives mod id from source backed resource location factory`() {
        val projectDir = tempDir.resolve("p19-world-carvers-source-factory")
        val initDir = projectDir.resolve("src/main/java/com/example/init")
        val rootDir = projectDir.resolve("src/main/java/com/example")
        initDir.createDirectories()
        rootDir.createDirectories()
        initDir.resolve("ExampleCarvers.java").writeText("""
            package com.example.init;

            import com.example.ExampleMod;
            import com.example.world.ExampleCavesCarver;
            import net.minecraft.core.HolderGetter;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.data.worldgen.BootstrapContext;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.tags.BlockTags;
            import net.minecraft.util.valueproviders.ConstantFloat;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.levelgen.VerticalAnchor;
            import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
            import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
            import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.registries.ForgeRegistries;
            import net.neoforged.neoforge.registries.RegisterEvent;

            public class ExampleCarvers {
                public static final ExampleCavesCarver EXAMPLE_CAVES = new ExampleCavesCarver(CaveCarverConfiguration.CODEC, false);

                @SubscribeEvent
                public static void register(RegisterEvent evt) {
                    evt.register(ForgeRegistries.Keys.WORLD_CARVERS, helper -> helper.register(ExampleMod.prefix("example_caves"), EXAMPLE_CAVES));
                }

                public static final ResourceKey<ConfiguredWorldCarver<?>> EXAMPLE_CAVES_CONFIGURED = registerKey("example_caves");

                private static ResourceKey<ConfiguredWorldCarver<?>> registerKey(String name) {
                    return ResourceKey.create(Registries.CONFIGURED_CARVER, ExampleMod.prefix(name));
                }

                public static void bootstrap(BootstrapContext<ConfiguredWorldCarver<?>> context) {
                    HolderGetter<Block> blocks = context.lookup(Registries.BLOCK);
                    context.register(EXAMPLE_CAVES_CONFIGURED, EXAMPLE_CAVES.configured(new CaveCarverConfiguration(0.1F, UniformHeight.of(VerticalAnchor.aboveBottom(5), VerticalAnchor.absolute(-8)), ConstantFloat.of(0.6F), VerticalAnchor.bottom(), blocks.getOrThrow(BlockTags.OVERWORLD_CARVER_REPLACEABLES), ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), ConstantFloat.of(-0.7F))));
                }
            }
        """.trimIndent())
        rootDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import java.util.Locale;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.bus.api.IEventBus;

            public class ExampleMod {
                public static final String ID = "example";

                public ExampleMod(IEventBus modbus) {
                    modbus.addListener(com.example.init.ExampleCarvers::register);
                }

                public static ResourceLocation prefix(String path) {
                    return ResourceLocation.fromNamespaceAndPath(ID, path.toLowerCase(Locale.ROOT));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val carvers = initDir.resolve("ExampleCarvers.java").readText()
        val mod = rootDir.resolve("ExampleMod.java").readText()

        assertTrue(
            result.errors.none { it.contains("Cannot derive mod id for world carver registration") },
            result.errors.joinToString("\n")
        )
        assertTrue(result.changes.any { it.ruleId == "build-world-carver-deferred-register" })
        assertTrue(carvers.contains("DeferredRegister<WorldCarver<?>> CARVER_TYPES = DeferredRegister.create(Registries.CARVER, ExampleMod.ID)"))
        assertTrue(carvers.contains("DeferredHolder<WorldCarver<?>, ExampleCavesCarver> EXAMPLE_CAVES = CARVER_TYPES.register(\"example_caves\""))
        assertTrue(carvers.contains("EXAMPLE_CAVES.value().configured(new CaveCarverConfiguration"))
        assertFalse(carvers.contains("RegisterEvent"))
        assertFalse(carvers.contains("ForgeRegistries.WORLD_CARVERS"))
        assertTrue(mod.contains("com.example.init.ExampleCarvers.CARVER_TYPES.register(modbus);"))
    }

    @Test
    fun `world carver migration does not derive mod id from unrelated constants`() {
        val projectDir = tempDir.resolve("p19-world-carvers-unrelated-modid")
        val initDir = projectDir.resolve("src/main/java/com/example/init")
        initDir.createDirectories()
        initDir.resolve("ExampleCarvers.java").writeText("""
            package com.example.init;

            import com.dependency.DependencyMod;
            import com.example.world.ExampleCavesCarver;
            import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.registries.ForgeRegistries;
            import net.neoforged.neoforge.registries.RegisterEvent;

            public class ExampleCarvers {
                public static final ExampleCavesCarver EXAMPLE_CAVES = new ExampleCavesCarver(CaveCarverConfiguration.CODEC, false);

                @SubscribeEvent
                public static void register(RegisterEvent evt) {
                    evt.register(ForgeRegistries.Keys.WORLD_CARVERS, helper -> helper.register(DependencyMod.prefix("example_caves"), EXAMPLE_CAVES));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val carvers = initDir.resolve("ExampleCarvers.java").readText()

        assertTrue(
            result.errors.any { it.contains("Cannot derive mod id for world carver registration") },
            "Expected hard migration error, got: ${result.errors}"
        )
        assertFalse(carvers.contains("DeferredRegister<WorldCarver<?>> CARVER_TYPES"))
        assertTrue(carvers.contains("DependencyMod.prefix(\"example_caves\")"))
    }

    @Test
    fun `world carver migration rejects project mod id fallback without subscriber modid`() {
        val projectDir = tempDir.resolve("p19-world-carvers-no-subscriber-modid")
        val initDir = projectDir.resolve("src/main/java/com/example/init")
        val rootDir = projectDir.resolve("src/main/java/com/example")
        initDir.createDirectories()
        rootDir.createDirectories()
        projectDir.resolve("gradle.properties").writeText("mod_id=metadata_mod\n")
        rootDir.resolve("ExampleMod.java").writeText("""
            package com.example;

            import net.neoforged.fml.common.Mod;

            @Mod(ExampleMod.ID)
            public class ExampleMod {
                public static final String ID = "example";
            }
        """.trimIndent())
        initDir.resolve("ExampleCarvers.java").writeText("""
            package com.example.init;

            import com.example.world.ExampleCavesCarver;
            import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
            import net.neoforged.bus.api.SubscribeEvent;
            import net.neoforged.neoforge.registries.ForgeRegistries;
            import net.neoforged.neoforge.registries.RegisterEvent;

            public class ExampleCarvers {
                public static final ExampleCavesCarver EXAMPLE_CAVES = new ExampleCavesCarver(CaveCarverConfiguration.CODEC, false);

                @SubscribeEvent
                public static void register(RegisterEvent evt) {
                    evt.register(ForgeRegistries.Keys.WORLD_CARVERS, helper -> helper.register(ExampleMod.prefix("example_caves"), EXAMPLE_CAVES));
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val carvers = initDir.resolve("ExampleCarvers.java").readText()

        assertTrue(
            result.errors.any { it.contains("Cannot derive mod id for world carver registration") },
            "Expected missing subscriber modid to hard gate; got ${result.errors}"
        )
        assertFalse(carvers.contains("DeferredRegister<WorldCarver<?>> CARVER_TYPES"))
        assertFalse(carvers.contains("metadata_mod"))
        assertFalse(carvers.contains("ExampleMod.ID"))
    }

    @Test
    fun `does not exclude legacy criterion triggers and payload registrars with missing packet classes`() {
        val projectDir = tempDir.resolve("p20")
        val advancementDir = projectDir.resolve("src/main/java/com/example/advancements")
        val networkDir = projectDir.resolve("src/main/java/com/example/network")
        advancementDir.createDirectories()
        networkDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        advancementDir.resolve("AdvancementTrigger.java").writeText("""
            package com.example.advancements;

            import net.minecraft.advancements.CriterionTrigger;
            import net.minecraft.advancements.CriterionTriggerInstance;
            import net.minecraft.advancements.critereon.SerializationContext;

            public class AdvancementTrigger implements CriterionTrigger<AdvancementTrigger.Instance> {
                public static class Instance implements CriterionTriggerInstance {
                }
            }
        """.trimIndent())
        networkDir.resolve("ModNetwork.java").writeText("""
            package com.example.network;

            import net.neoforged.neoforge.network.registration.PayloadRegistrar;

            public class ModNetwork {
                public static void register(PayloadRegistrar registrar) {
                    registrar.playToClient(MissingPacket.TYPE, MissingPacket.STREAM_CODEC, MissingPacket::handle);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(result.changes.any { it.ruleId == "build-exclude-integrations" })
        assertFalse(content.contains("exclude 'com/example/advancements/AdvancementTrigger.java'"))
        assertFalse(content.contains("exclude 'com/example/network/ModNetwork.java'"))
    }

    @Test
    fun `does not exclude codec based criterion triggers`() {
        val projectDir = tempDir.resolve("p20b")
        val advancementDir = projectDir.resolve("src/main/java/com/example/advancements")
        advancementDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
            }
        """.trimIndent())
        advancementDir.resolve("AdvancementTrigger.java").writeText("""
            package com.example.advancements;

            import com.mojang.serialization.Codec;
            import net.minecraft.advancements.CriterionTrigger;
            import net.minecraft.advancements.CriterionTriggerInstance;
            import net.minecraft.advancements.critereon.CriterionValidator;
            import net.minecraft.server.PlayerAdvancements;

            public class AdvancementTrigger implements CriterionTrigger<AdvancementTrigger.Instance> {
                public void addPlayerListener(PlayerAdvancements playerAdvancements, CriterionTrigger.Listener<Instance> listener) {}
                public void removePlayerListener(PlayerAdvancements playerAdvancements, CriterionTrigger.Listener<Instance> listener) {}
                public void removePlayerListeners(PlayerAdvancements playerAdvancements) {}
                public Codec<Instance> codec() { return Codec.unit(new Instance()); }

                public static class Instance implements CriterionTriggerInstance {
                    public void validate(CriterionValidator validator) {}
                }
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(content.contains("exclude 'com/example/advancements/AdvancementTrigger.java'"))
    }

    @Test
    fun `cleans duplicate override annotations after chained rewrites`() {
        val projectDir = tempDir.resolve("p21")
        val blockDir = projectDir.resolve("src/main/java/com/example/block")
        blockDir.createDirectories()
        blockDir.resolve("TaikoBlock.java").writeText("""
            package com.example.block;

            public class TaikoBlock {
                @Override
                @SuppressWarnings("deprecation")
                @Override
                protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
                    return InteractionResult.SUCCESS;
                }

                @Override
                @Deprecated
                @Override
                protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit, int variant) {
                    return InteractionResult.SUCCESS;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = blockDir.resolve("TaikoBlock.java").readText()

        assertTrue(result.changes.any { it.ruleId == "build-cleanup-duplicate-override" })
        assertFalse(Regex("""@Override\s*\r?\n\s*@SuppressWarnings\("deprecation"\)\s*\r?\n\s*@Override""").containsMatchIn(content))
        assertFalse(Regex("""@Override\s*\r?\n\s*@Deprecated\s*\r?\n\s*@Override""").containsMatchIn(content))
        assertTrue(Regex("""@SuppressWarnings\("deprecation"\)\s*\r?\n\s*@Override""").containsMatchIn(content))
        assertTrue(Regex("""@Deprecated\s*\r?\n\s*@Override""").containsMatchIn(content))
    }

    @Test
    fun `does not repair commented cleanup artifacts with synthetic returns`() {
        val projectDir = tempDir.resolve("p22")
        val rootDir = projectDir.resolve("src/main/java/cn/mcmod/sakura")
        val fluidDir = rootDir.resolve("fluid")
        val noodleDir = rootDir.resolve("block/noodles")
        val decoratorDir = rootDir.resolve("level/tree/decorator")
        val particleDir = rootDir.resolve("particle")
        fluidDir.createDirectories()
        noodleDir.createDirectories()
        decoratorDir.createDirectories()
        particleDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            sourceSets {
                main {
                    java {
                        exclude 'cn/mcmod/sakura/item/FoodRegistry.java'
                    }
                }
            }
        """.trimIndent())
        fluidDir.resolve("BucketItemRegistry.java").writeText("""
            package cn.mcmod.sakura.fluid;

            public class BucketItemRegistry {
                public static final Object ITEMS = new Object();
            }
        """.trimIndent())
        rootDir.resolve("SakuraMod.java").writeText("""
            package cn.mcmod.sakura;

            // [forge2neo] import cn.mcmod.sakura.fluid.BucketItemRegistry; // excluded

            public class SakuraMod {
                public void register(Object modEventBus) {
                    BucketItemRegistry.ITEMS.toString();
                }
            }
        """.trimIndent())
        noodleDir.resolve("BlockPasta.java").writeText("""
            package cn.mcmod.sakura.block.noodles;

            import net.minecraft.world.item.ItemStack;

            public class BlockPasta {
                public ItemStack getNoodle() {
                    // [forge2neo] return new ItemStack(FoodRegistry.FOOD.get()); // excluded: FoodRegistry unavailable
                }
            }
        """.trimIndent())
        decoratorDir.resolve("ChestnutBurrDecorator.java").writeText("""
            package cn.mcmod.sakura.level.tree.decorator;

            public class ChestnutBurrDecorator {
                protected TreeDecoratorType<?> type() {
                    // [forge2neo] return SakuraFeatureRegistry.CHESTNUT_BURR_DECORATOR.get(); // excluded: SakuraFeatureRegistry unavailable
                }
            }
        """.trimIndent())
        particleDir.resolve("ShowerParticle.java").writeText("""
            package cn.mcmod.sakura.particle;

            public class ShowerParticle {
                public ParticleRenderType getRenderType() {
                    // [forge2neo] return ClientConfig.enableTranslucentParticles // excluded: ClientConfig unavailable
                        ? ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT
                        : ParticleRenderType.PARTICLE_SHEET_OPAQUE;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val pastaContent = noodleDir.resolve("BlockPasta.java").readText()
        val decoratorContent = decoratorDir.resolve("ChestnutBurrDecorator.java").readText()
        val particleContent = particleDir.resolve("ShowerParticle.java").readText()

        assertFalse(result.changes.any { it.ruleId == "build-repair-post-cleanup-artifacts" })
        assertFalse(result.changes.any { it.ruleId == "build-cleanup-excluded-refs" })
        assertFalse(pastaContent.contains("return ItemStack.EMPTY;"))
        assertFalse(decoratorContent.contains("return null;"))
        assertFalse(particleContent.contains("return null;"))
    }

    @Test
    fun `hybrid builds drop the applied mixin gradle plugin and its block`() {
        val projectDir = tempDir.resolve("p-hybrid-mixin")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            buildscript {
                repositories {
                    maven { url = 'https://repo.spongepowered.org/maven' }
                    mavenCentral()
                }
                dependencies { classpath 'org.spongepowered:mixingradle:0.7.38' }
            }

            plugins {
                id 'eclipse'
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
                id 'org.parchmentmc.librarian.forgegradle' version '1.+'
            }

            apply plugin: 'org.spongepowered.mixin'
            mixin { add sourceSets.main, 'mixins.showercore.refmap.json' }

            version = '1.0'

            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.1.0'
                annotationProcessor 'org.spongepowered:mixin:0.8.5:processor'
            }
        """.trimIndent())

        pass.apply(projectDir)
        val content = projectDir.resolve("build.gradle").readText()

        assertFalse(content.contains("apply plugin: 'org.spongepowered.mixin'"), content)
        assertFalse(content.contains("mixingradle"), content)
        assertFalse(content.contains("mixin { add sourceSets.main"), content)
        assertTrue(content.contains("net.neoforged.moddev"), content)
    }


    @Test
    fun `living breathe refill compatibility migrates from method handles to direct api`() {
        val projectDir = tempDir.resolve("living-breathe-direct-api")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("DivingHelmet.java").writeText("""
            package com.example;

            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.MethodType;
            import org.jetbrains.annotations.Nullable;
            import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;

            public class DivingHelmet {
                // TODO - 1.21.1 - Remove
                @Nullable
                private static final MethodHandle setCanRefillAirHandle;

                // TODO - 1.21.1 - Remove
                static {
                    MethodHandle handle = null;
                    MethodHandles.Lookup lookup = MethodHandles.lookup();
                    MethodType type = MethodType.methodType(void.class, boolean.class);
                    try {
                        handle = lookup.findVirtual(LivingBreatheEvent.class, "setCanRefillAir", type);
                    } catch (Exception ignored) {
                    }
                    setCanRefillAirHandle = handle;
                }

                static void breathe(LivingBreatheEvent event) {
                    event.setCanBreathe(true);
                    try {
                        if (setCanRefillAirHandle != null)
                            setCanRefillAirHandle.invokeExact(event, true);
                    } catch (Throwable ignored) {
                    }
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("DivingHelmet.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-living-breathe-refill-direct-api" })
        assertTrue(content.contains("event.setRefillAirAmount(event.getEntity().getMaxAirSupply());"), content)
        assertFalse(content.contains("MethodHandle"), content)
        assertFalse(content.contains("MethodHandles"), content)
        assertFalse(content.contains("setCanRefillAir"), content)
        assertFalse(content.contains("TODO - 1.21.1 - Remove"), content)
    }


    @Test
    fun `model builder varhandle reads without a complete public api flow remain hard gated`() {
        val projectDir = tempDir.resolve("varhandle-at-direct")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ModelTextures.java").writeText("""
            package com.example;

            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.VarHandle;
            import java.util.Map;
            import net.neoforged.neoforge.client.model.generators.ModelBuilder;

            public class ModelTextures {
                private static final String DOC = "TEXTURES_HANDLE.get(builder)";
                public static final VarHandle TEXTURES_HANDLE;

                static {
                    try {
                        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ModelBuilder.class, MethodHandles.lookup());
                        TEXTURES_HANDLE = lookup.findVarHandle(ModelBuilder.class, "textures", Map.class);
                    } catch (IllegalAccessException | NoSuchFieldException e) {
                        throw new RuntimeException(e);
                    }
                }

                static Map<String, String> textures(ModelBuilder<?> builder) {
                    // TEXTURES_HANDLE.get(builder)
                    return (Map<String, String>) TEXTURES_HANDLE.get(builder);
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ModelTextures.java").readText()
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")

        assertTrue(result.errors.any { it.contains("Forbidden reflection") }, result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-varhandle-at-direct-field" })
        assertTrue(content.contains("return (Map<String, String>) TEXTURES_HANDLE.get(builder);"), content)
        assertTrue(content.contains("DOC = \"TEXTURES_HANDLE.get(builder)\""), content)
        assertTrue(content.contains("// TEXTURES_HANDLE.get(builder)"), content)
        assertTrue(content.contains("VarHandle"), content)
        assertTrue(content.contains("MethodHandles"), content)
        assertTrue(content.contains("findVarHandle"), content)
        assertFalse(atFile.exists())
    }


    @Test
    fun `model builder texture map varhandles migrate to the public texture api without an at`() {
        val projectDir = tempDir.resolve("varhandle-model-texture-api")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("GeneratedModels.java").writeText("""
            package com.example;

            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.VarHandle;
            import java.util.Map;
            import net.minecraft.resources.ResourceLocation;
            import net.neoforged.neoforge.client.model.generators.ModelBuilder;

            public class GeneratedModels {
                public static final VarHandle TEXTURES_HANDLE;

                static {
                    try {
                        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ModelBuilder.class, MethodHandles.lookup());
                        TEXTURES_HANDLE = lookup.findVarHandle(ModelBuilder.class, "textures", Map.class);
                    } catch (IllegalAccessException | NoSuchFieldException exception) {
                        throw new RuntimeException(exception);
                    }
                }

                static void addLayer(ModelBuilder<?> builder, ResourceLocation layer) {
                    Map<String, String> textures = (Map<String, String>) TEXTURES_HANDLE.get(builder);
                    textures.put("layer1", layer.toString());
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("GeneratedModels.java").readText()
        val atFile = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg")

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${result.errors}")
        assertTrue(content.contains("builder.texture(\"layer1\", layer);"), content)
        assertFalse(content.contains("TEXTURES_HANDLE"), content)
        assertFalse(content.contains("java.lang.invoke"), content)
        assertFalse(content.contains("java.util.Map"), content)
        assertFalse(atFile.exists(), atFile.takeIf { it.exists() }?.readText().orEmpty())
    }


    @Test
    fun `obfuscation reflection fields migrate through explicit mappings and third party field evidence`() {
        val projectDir = tempDir.resolve("obfuscation-reflection-at")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val resourcesDir = projectDir.resolve("src/main/resources")
        srcDir.createDirectories()
        resourcesDir.createDirectories()
        resourcesDir.resolve("example.mixins.json").writeText("""
            {
              "required": true,
              "package": "com.example.mixin",
              "mixins": [],
              "client": []
            }
        """.trimIndent())
        srcDir.resolve("ReflectedFields.java").writeText("""
            package com.example;

            import dev.example.maps.LargeMapScreen;
            import dev.example.maps.RegionPanel;
            import net.minecraft.client.Minecraft;
            import net.minecraft.client.gui.Gui;
            import net.neoforged.fml.util.ObfuscationReflectionHelper;

            public class ReflectedFields {
                private static final String DOC = "ObfuscationReflectionHelper.getPrivateValue(Gui.class, gui, f_92993_)";

                void update(Minecraft minecraft, Gui gui, LargeMapScreen screen) {
                    ObfuscationReflectionHelper.setPrivateValue(Minecraft.class, minecraft, 10, "f_91078_");
                    if (ObfuscationReflectionHelper.getPrivateValue(Gui.class, gui,
                            "f_92993_") instanceof Integer timer && timer > 0) {
                        return;
                    }
                    Object panel = ObfuscationReflectionHelper.getPrivateValue(
                            LargeMapScreen.class, screen, "regionPanel");
                    if (!(panel instanceof RegionPanel regionPanel)) {
                        return;
                    }
                    // ObfuscationReflectionHelper.getPrivateValue(Gui.class, gui, "f_92993_");
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ReflectedFields.java").readText()
        val at = projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        val accessor = srcDir.resolve("mixin/modporter/ModPorterLargeMapScreenAccessor.java")
        val mixinConfig = resourcesDir.resolve("example.mixins.json").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-obfuscation-reflection-at-direct-field" })
        assertTrue(content.contains("(minecraft).missTime = 10;"), content)
        assertTrue(content.contains("if ((gui).toolHighlightTimer > 0)"), content)
        assertTrue(content.contains("Object panel = ((com.example.mixin.modporter.ModPorterLargeMapScreenAccessor) (Object) screen).modporter${'$'}getRegionPanel();"), content)
        assertTrue(content.contains("DOC = \"ObfuscationReflectionHelper.getPrivateValue"), content)
        assertTrue(content.contains("// ObfuscationReflectionHelper.getPrivateValue"), content)
        assertFalse(content.contains("import net.neoforged.fml.util.ObfuscationReflectionHelper;"), content)
        assertTrue(at.contains("public-f net.minecraft.client.Minecraft missTime"), at)
        assertTrue(at.contains("public net.minecraft.client.gui.Gui toolHighlightTimer"), at)
        assertFalse(at.contains("dev.example.maps.LargeMapScreen"), at)
        assertTrue(accessor.exists())
        assertTrue(accessor.readText().contains("@Pseudo"))
        assertTrue(accessor.readText().contains("@Mixin(targets = \"dev.example.maps.LargeMapScreen\", remap = false)"))
        assertTrue(accessor.readText().contains("dev.example.maps.RegionPanel modporter${'$'}getRegionPanel()"))
        assertTrue(mixinConfig.contains("modporter.ModPorterLargeMapScreenAccessor"))
    }


    @Test
    fun `unmapped minecraft reflection remains a hard gate failure`() {
        val projectDir = tempDir.resolve("unmapped-minecraft-reflection")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("RemovedField.java").writeText("""
            package com.example;

            import java.util.function.Supplier;
            import net.minecraft.world.level.block.StairBlock;
            import net.neoforged.fml.util.ObfuscationReflectionHelper;

            public class RemovedField {
                void rewrite(StairBlock block, Supplier<?> state) {
                    ObfuscationReflectionHelper.setPrivateValue(StairBlock.class, block, state, "stateSupplier");
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("RemovedField.java").readText()

        assertTrue(result.errors.any { it.contains("Forbidden reflection") }, result.errors.joinToString("\n"))
        assertTrue(content.contains("ObfuscationReflectionHelper.setPrivateValue"), content)
        assertFalse(projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }


    @Test
    fun `removed stair state supplier reflection migrates into constructor state`() {
        val projectDir = tempDir.resolve("stair-state-supplier")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("StairFactory.java").writeText("""
            package com.example;

            import java.util.function.Supplier;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.StairBlock;
            import net.minecraft.world.level.block.WeatheringCopperStairBlock;
            import net.minecraft.world.level.block.state.BlockState;
            import net.neoforged.fml.util.ObfuscationReflectionHelper;

            public class StairFactory {
                StairBlock create(boolean waxed, Object weatherState, Object properties) {
                    Supplier<BlockState> selectedState = () -> Blocks.COPPER_BLOCK.defaultBlockState();
                    if (waxed)
                        return new StairBlock(selectedState, properties);
                    WeatheringCopperStairBlock block =
                            new WeatheringCopperStairBlock(weatherState, Blocks.AIR.defaultBlockState(), properties);
                    ObfuscationReflectionHelper.setPrivateValue(
                            StairBlock.class, block, selectedState, "stateSupplier");
                    return block;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("StairFactory.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-stair-state-supplier-constructor" })
        assertTrue(content.contains("new StairBlock(selectedState.get(), properties)"), content)
        assertTrue(content.contains("new WeatheringCopperStairBlock(weatherState, selectedState.get(), properties)"), content)
        assertFalse(content.contains("ObfuscationReflectionHelper"), content)
        assertFalse(content.contains("stateSupplier\"") , content)
        assertFalse(projectDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
    }


    @Test
    fun `portal provider callback pipelines migrate to direct portal api through the static call graph`() {
        val projectDir = tempDir.resolve("portal-provider-pipeline")
        val srcDir = projectDir.resolve("src/main/java/com/example/portal")
        srcDir.createDirectories()
        srcDir.resolve("PortalBridge.java").writeText("""
            package com.example.portal;

            import java.util.function.BiFunction;
            import java.util.function.Function;
            import net.minecraft.core.BlockPos;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.level.portal.DimensionTransition;
            import net.neoforged.neoforge.common.util.ITeleporter;

            public class PortalBridge {
                public static Exit resolve(ServerLevel level, BlockFace face,
                                           ResourceKey<Level> firstDimension,
                                           ResourceKey<Level> secondDimension,
                                           BiFunction<ServerLevel, Probe, DimensionTransition> provider) {
                    ServerLevel otherLevel = level.getServer().getLevel(secondDimension);
                    BlockPos portalPos = face.getConnectedPos();
                    BlockState portalState = level.getBlockState(portalPos);
                    Probe probe = new Probe();
                    probe.setPortalEntrancePos();
                    DimensionTransition transition = provider.apply(otherLevel, probe);
                    if (transition == null)
                        return null;
                    BlockPos output = BlockPos.containing(transition.pos);
                    return new Exit(otherLevel, output);
                }

                public static Exit fromTeleporter(ServerLevel level, BlockFace face,
                                                  ResourceKey<Level> firstDimension,
                                                  ResourceKey<Level> secondDimension,
                                                  Function<ServerLevel, ITeleporter> factory) {
                    return PortalApi.resolve(level, face, firstDimension, secondDimension,
                            (otherLevel, probe) -> factory.apply(otherLevel)
                                    .getPortalInfo(probe, otherLevel, probe::findDimensionEntryPoint));
                }

                static Exit optional(ServerLevel level, BlockFace face,
                                     ResourceKey<Level> firstDimension,
                                     ResourceKey<Level> secondDimension) {
                    return PortalApi.fromTeleporter(level, face, firstDimension, secondDimension, target -> {
                        try {
                            return (ITeleporter) Class.forName("dev.example.portal.CustomPortalForcer")
                                    .getDeclaredConstructor(ServerLevel.class)
                                    .newInstance(target);
                        } catch (Exception exception) {
                            return target.getPortalForcer();
                        }
                    });
                }

                static Exit compatibility(ServerLevel level, BlockFace face,
                                          ResourceKey<Level> firstDimension,
                                          ResourceKey<Level> secondDimension) {
                    return resolve(level, face, firstDimension, secondDimension, BetterPortalCompat::find);
                }
            }
        """.trimIndent())
        srcDir.resolve("PortalApi.java").writeText("""
            package com.example.portal;

            import java.util.function.BiFunction;
            import java.util.function.Function;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.portal.DimensionTransition;
            import net.neoforged.neoforge.common.util.ITeleporter;

            public class PortalApi {
                public static Exit resolve(ServerLevel level, BlockFace face,
                                           ResourceKey<Level> firstDimension,
                                           ResourceKey<Level> secondDimension,
                                           BiFunction<ServerLevel, Probe, DimensionTransition> provider) {
                    return PortalBridge.resolve(level, face, firstDimension, secondDimension, provider);
                }

                public static Exit fromTeleporter(ServerLevel level, BlockFace face,
                                                  ResourceKey<Level> firstDimension,
                                                  ResourceKey<Level> secondDimension,
                                                  Function<ServerLevel, ITeleporter> factory) {
                    return PortalBridge.fromTeleporter(level, face, firstDimension, secondDimension, factory);
                }
            }
        """.trimIndent())
        val compatFile = srcDir.resolve("BetterPortalCompat.java")
        compatFile.writeText("""
            package com.example.portal;

            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;

            public class BetterPortalCompat {
                private static final MethodHandle HANDLE;

                static {
                    try {
                        Class<?> state = Class.forName("dev.example.portal.TravelerState");
                        HANDLE = MethodHandles.lookup().findConstructor(state, null);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }

                public static DimensionTransition find(ServerLevel level, Probe probe) {
                    return null;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val bridge = srcDir.resolve("PortalBridge.java").readText()
        val api = srcDir.resolve("PortalApi.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-portal-dimension-transition-pipeline" })
        assertTrue(result.changes.any { it.ruleId == "build-portal-unreferenced-reflection-adapter" })
        assertTrue(bridge.contains("instanceof Portal modporterPortal"), bridge)
        assertTrue(bridge.contains("modporterPortal.getPortalDestination(level, probe, probe.blockPosition())"), bridge)
        assertTrue(bridge.contains("BlockPos.containing(transition.pos())"), bridge)
        assertFalse(bridge.contains("BiFunction"), bridge)
        assertFalse(bridge.contains("ITeleporter"), bridge)
        assertFalse(bridge.contains("Class.forName"), bridge)
        assertFalse(bridge.contains("getDeclaredConstructor"), bridge)
        assertFalse(api.contains("BiFunction"), api)
        assertFalse(api.contains("Function"), api)
        assertFalse(api.contains("ITeleporter"), api)
        assertFalse(compatFile.exists())
    }


    @Test
    fun `reflected copper transition maps migrate to registered neoforge data map provider`() {
        val projectDir = tempDir.resolve("copper-datamap")
        val srcDir = projectDir.resolve("src/main/java/com/example/copper")
        srcDir.createDirectories()
        srcDir.resolve("OxidationHooks.java").writeText("""
            package com.example.copper;

            import java.lang.reflect.Field;
            import java.util.function.Supplier;
            import com.google.common.base.Suppliers;
            import com.google.common.collect.BiMap;
            import com.google.common.collect.HashBiMap;
            import com.google.common.collect.ImmutableBiMap;
            import net.minecraft.world.item.HoneycombItem;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.WeatheringCopper;

            public class OxidationHooks {
                private static final BiMap<Supplier<Block>, Supplier<Block>> WEATHERING = HashBiMap.create();
                private static final BiMap<Supplier<Block>, Supplier<Block>> WAXABLE = HashBiMap.create();
                private static boolean weatheringMemoized;
                private static boolean waxableMemoized;

                public static synchronized void addWeathering(Supplier<Block> original, Supplier<Block> weathered) {
                    if (weatheringMemoized) throw new IllegalStateException();
                    WEATHERING.put(original, weathered);
                }

                public static synchronized void addWaxable(Supplier<Block> original, Supplier<Block> waxed) {
                    if (waxableMemoized) throw new IllegalStateException();
                    WAXABLE.put(original, waxed);
                }

                public static void install() {
                    try {
                        Field delegate = WeatheringCopper.NEXT_BY_BLOCK.getClass().getDeclaredField("delegate");
                        delegate.setAccessible(true);
                        delegate.set(WeatheringCopper.NEXT_BY_BLOCK, Suppliers.memoize(ImmutableBiMap::of));
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                    HoneycombItem.WAXABLES = Suppliers.memoize(ImmutableBiMap::of);
                }
            }
        """.trimIndent())
        srcDir.resolve("CopperBlocks.java").writeText("""
            package com.example.copper;

            public class CopperBlocks {
                static void register() {
                    OxidationHooks.addWeathering(CopperBlocks::first, CopperBlocks::second);
                    OxidationHooks.addWaxable(CopperBlocks::first, CopperBlocks::waxed);
                    OxidationHooks.install();
                }
            }
        """.trimIndent())
        srcDir.resolve("ExampleDatagen.java").writeText("""
            package com.example.copper;

            import java.util.concurrent.CompletableFuture;
            import net.minecraft.core.HolderLookup;
            import net.minecraft.data.DataGenerator;
            import net.minecraft.data.PackOutput;
            import net.neoforged.neoforge.data.event.GatherDataEvent;

            public class ExampleDatagen {
                public static void gatherData(GatherDataEvent event) {
                    DataGenerator generator = event.getGenerator();
                    PackOutput output = generator.getPackOutput();
                    CompletableFuture<HolderLookup.Provider> lookupProvider = event.getLookupProvider();
                    lookupProvider = generatedRegistryProvider();
                    generator.addProvider(event.includeServer(), existingProvider());
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val registry = srcDir.resolve("OxidationHooks.java").readText()
        val usage = srcDir.resolve("CopperBlocks.java").readText()
        val datagen = srcDir.resolve("ExampleDatagen.java").readText()
        val provider = projectDir.resolve(
            "src/main/java/com/example/copper/modporter/datagen/ModPorterOxidationHooksDataMapProvider.java"
        )

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-copper-datamap-registry" })
        assertTrue(result.changes.any { it.ruleId == "build-copper-datamap-provider" })
        assertTrue(result.changes.any { it.ruleId == "build-copper-datamap-register-provider" })
        assertTrue(registry.contains("getWeatheringView()"), registry)
        assertTrue(registry.contains("getWaxableView()"), registry)
        assertFalse(registry.contains("java.lang.reflect"), registry)
        assertFalse(registry.contains("getDeclaredField"), registry)
        assertFalse(registry.contains("HoneycombItem.WAXABLES"), registry)
        assertTrue(usage.contains("OxidationHooks.addWeathering"), usage)
        assertTrue(usage.contains("OxidationHooks.addWaxable"), usage)
        assertFalse(usage.contains("OxidationHooks.install()"), usage)
        assertTrue(provider.exists())
        assertTrue(provider.readText().contains("NeoForgeDataMaps.OXIDIZABLES"))
        assertTrue(provider.readText().contains("NeoForgeDataMaps.WAXABLES"))
        assertTrue(datagen.contains("new ModPorterOxidationHooksDataMapProvider(output, lookupProvider)"), datagen)
    }


    @Test
    fun `normalizes positional client dist subscriber value instead of adding a duplicate`() {
        val projectDir = tempDir.resolve("client-subscriber-positional-dist")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("ClientEvents.java").writeText("""
            package com.example;

            import net.minecraft.client.Minecraft;
            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.fml.common.EventBusSubscriber;
            import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

            @EventBusSubscriber(Dist.CLIENT)
            public class ClientEvents {
                private static final Minecraft MINECRAFT = Minecraft.getInstance();
                private static FMLClientSetupEvent setupEvent;
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val content = srcDir.resolve("ClientEvents.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(result.changes.any { it.ruleId == "build-client-eventbus-subscriber-dist" })
        assertTrue(content.contains("@EventBusSubscriber(value = Dist.CLIENT)"), content)
        assertFalse(content.contains("Dist.CLIENT, value ="), content)
    }


    @Test
    fun `structured access transformer member mappings preserve owners descriptors and access`() {
        val projectDir = tempDir.resolve("structured-at-member-mappings")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        atDir.createDirectories()
        atDir.resolve("accesstransformer.cfg").writeText("""
            public net.minecraft.client.gui.Font m_92863_(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/gui/font/FontSet; # getFontSet
            public net.minecraft.server.network.ServerGamePacketListenerImpl f_9737_ # aboveGroundTickCount
            protected net.minecraft.client.model.AgeableListModel f_170339_ # babyZHeadOffset
            public-f net.minecraft.data.recipes.RecipeProvider m_6055_()Ljava/lang/String; # getName
            public net.minecraft.client.gui.Font m_92863_()V # wrong descriptor must remain unresolved
        """.trimIndent())

        val result = pass.apply(projectDir)
        val at = atDir.resolve("accesstransformer.cfg").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${result.errors}")
        assertTrue(at.contains("public net.minecraft.client.gui.Font getFontSet(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/gui/font/FontSet; # getFontSet"), at)
        assertTrue(at.contains("public net.minecraft.server.network.ServerGamePacketListenerImpl aboveGroundTickCount # aboveGroundTickCount"), at)
        assertTrue(at.contains("protected net.minecraft.client.model.AgeableListModel babyZHeadOffset # babyZHeadOffset"), at)
        assertTrue(at.contains("public-f net.minecraft.data.recipes.RecipeProvider getName()Ljava/lang/String; # getName"), at)
        assertTrue(at.contains("public net.minecraft.client.gui.Font m_92863_()V # wrong descriptor must remain unresolved"), at)
        assertFalse(at.contains("public net.minecraft.client.gui.Font getFontSet()V"), at)
    }


    @Test
    fun `title screen panorama field migrates to screen panorama with its source at`() {
        val projectDir = tempDir.resolve("title-screen-panorama-api")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        srcDir.createDirectories()
        atDir.createDirectories()
        srcDir.resolve("MenuOverlay.java").writeText("""
            package com.example;

            import net.minecraft.client.gui.screens.TitleScreen;
            import net.minecraft.client.renderer.PanoramaRenderer;

            public class MenuOverlay {
                PanoramaRenderer panorama(TitleScreen titleScreen) {
                    return titleScreen.panorama;
                }
            }
        """.trimIndent())
        atDir.resolve("accesstransformer.cfg").writeText(
            "public net.minecraft.client.gui.screens.TitleScreen f_96729_ # panorama\n"
        )

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("MenuOverlay.java").readText()
        val at = atDir.resolve("accesstransformer.cfg").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${result.errors}")
        assertTrue(source.contains("return Screen.PANORAMA;"), source)
        assertTrue(source.contains("import net.minecraft.client.gui.screens.Screen;"), source)
        assertTrue(at.contains("public net.minecraft.client.gui.screens.Screen PANORAMA # panorama"), at)
        assertFalse(at.contains("TitleScreen panorama"), at)
    }


    @Test
    fun `build migration does not erase split tick phase semantics`() {
        val projectDir = tempDir.resolve("split-tick-phase")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("TickHandlers.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.client.event.ClientTickEvent;
            import net.neoforged.neoforge.client.event.RenderFrameEvent;
            import net.neoforged.neoforge.event.TickEvent;

            public class TickHandlers {
                public static void clientTick(ClientTickEvent.Post event) {
                    if (event.phase == TickEvent.Phase.END && ready()) {
                        run();
                    }
                    if (event.phase != TickEvent.Phase.END || blocked()) {
                        return;
                    }
                    run();
                }

                public static void renderTick(RenderFrameEvent.Post event) {
                    if (event.phase == TickEvent.Phase.START) {
                        render();
                    }
                }

                private static boolean ready() { return true; }
                private static boolean blocked() { return false; }
                private static void run() {}
                private static void render() {}
            }
        """.trimIndent())

        val result = pass.apply(projectDir)

        val source = srcDir.resolve("TickHandlers.java").readText()
        assertFalse(result.changes.any { it.ruleId == "build-cleanup-split-tick-phase" })
        assertTrue(source.contains("public static void renderTick(RenderFrameEvent.Post event)"))
        assertTrue(source.contains("if (event.phase == TickEvent.Phase.END && ready())"))
        assertTrue(source.contains("if (event.phase != TickEvent.Phase.END || blocked())"))
        assertTrue(source.contains("if (event.phase == TickEvent.Phase.START)"))
        assertTrue(source.contains("import net.neoforged.neoforge.event.TickEvent;"), source)
    }


    @Test
    fun `build pass leaves split tick source and documentation to structural migration`() {
        val projectDir = tempDir.resolve("split-tick-phase-docs")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("TickHandlers.java").writeText("""
            package com.example;

            import net.neoforged.neoforge.client.event.RenderFrameEvent;
            import net.neoforged.neoforge.event.TickEvent;

            public class TickHandlers {
                String note = "RenderFrameEvent.Post event.phase == TickEvent.Phase.START";
                String docs = ${"\"\"\""}
                    public static void renderTick(RenderFrameEvent.Post event) {
                        if (event.phase == TickEvent.Phase.START) {
                            render();
                        }
                    }
                    ${"\"\"\""};

                public static void renderTick(RenderFrameEvent.Post event) {
                    // if (event.phase == TickEvent.Phase.START) { render(); }
                    if (event.phase == TickEvent.Phase.START) {
                        render();
                    }
                }

                private static void render() {}
            }
        """.trimIndent())

        val before = srcDir.resolve("TickHandlers.java").readText()
        val result = pass.apply(projectDir)
        val source = srcDir.resolve("TickHandlers.java").readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertFalse(result.changes.any { it.ruleId == "build-cleanup-split-tick-phase" })
        assertEquals(before, source)
        assertTrue(source.contains("String note = \"RenderFrameEvent.Post event.phase == TickEvent.Phase.START\";"), source)
        assertTrue(source.contains("public static void renderTick(RenderFrameEvent.Post event) {"), source)
        assertTrue(source.contains("// if (event.phase == TickEvent.Phase.START) { render(); }"), source)
    }


    @Test
    fun `reflective gametest discovery becomes closed typed source registrations`() {
        val projectDir = tempDir.resolve("gametest-static-registration")
        val apiDir = projectDir.resolve("src/main/java/com/example/testing")
        val testsDir = projectDir.resolve("src/main/java/com/example/tests")
        val mixinDir = projectDir.resolve("src/main/java/com/example/mixin")
        apiDir.createDirectories()
        testsDir.createDirectories()
        mixinDir.createDirectories()

        apiDir.resolve("StructuredHelper.java").writeText("""
            package com.example.testing;

            import net.minecraft.gametest.framework.GameTestHelper;

            public class StructuredHelper extends GameTestHelper {
                public static final int TIMEOUT = 240;

                public static StructuredHelper of(GameTestHelper helper) {
                    return (StructuredHelper) helper;
                }
            }
        """.trimIndent())
        apiDir.resolve("TestFamily.java").writeText("""
            package com.example.testing;

            public @interface TestFamily {
                String path();
                String namespace() default ExampleIds.ID;
            }
        """.trimIndent())
        apiDir.resolve("ExampleIds.java").writeText("""
            package com.example.testing;

            public final class ExampleIds {
                public static final String ID = "example";
            }
        """.trimIndent())
        testsDir.resolve("MachineTests.java").writeText("""
            package com.example.tests;

            import static com.example.testing.StructuredHelper.TIMEOUT;

            import com.example.testing.StructuredHelper;
            import com.example.testing.TestFamily;
            import net.minecraft.gametest.framework.GameTest;

            @TestFamily(path = "machines")
            public class MachineTests {
                @GameTest(template = "rotation", timeoutTicks = TIMEOUT, setupTicks = 5L,
                    rotationSteps = 2, required = false, attempts = 4, requiredSuccesses = 3,
                    batch = "mechanical")
                public static void rotation(StructuredHelper helper) {
                }

                @GameTest(template = "default_values")
                public static void defaultValues(StructuredHelper helper) {
                }

                // @GameTest(template = "commented")
                public static void notATest(StructuredHelper helper) {
                }
            }
        """.trimIndent())
        apiDir.resolve("StructuredTestFunction.java").writeText("""
            package com.example.testing;

            import java.lang.reflect.Method;
            import java.util.Collection;
            import java.util.Map;
            import java.util.HashMap;
            import java.util.stream.Stream;
            import net.minecraft.core.BlockPos;
            import net.minecraft.gametest.framework.GameTest;
            import net.minecraft.gametest.framework.GameTestHelper;
            import net.minecraft.gametest.framework.TestFunction;

            public class StructuredTestFunction extends TestFunction {
                public static final Map<String, StructuredTestFunction> NAMES_TO_FUNCTIONS = new HashMap<>();
                public final String fullName;

                public static Collection<TestFunction> getTestsFrom(Class<?>... classes) {
                    return Stream.of(classes)
                        .map(Class::getDeclaredMethods)
                        .map(StructuredTestFunction::of)
                        .toList();
                }

                private static TestFunction of(Method method) {
                    GameTest test = method.getAnnotation(GameTest.class);
                    TestFamily group = method.getDeclaringClass().getAnnotation(TestFamily.class);
                    if (method.getParameterTypes()[0] != StructuredHelper.class) {
                        throw new IllegalArgumentException();
                    }
                    try {
                        method.invoke(null, new Object());
                    } catch (ReflectiveOperationException exception) {
                        throw new RuntimeException(exception);
                    }
                    return null;
                }

                public void run(GameTestHelper helper) {
                    helper.getBlockEntity(BlockPos.ZERO).getPersistentData().putString("TypedTestFunction", fullName);
                    StructuredHelper.of(helper);
                }
            }
        """.trimIndent())
        apiDir.resolve("TestBootstrap.java").writeText("""
            package com.example.testing;

            import com.example.tests.MachineTests;
            import java.util.Collection;
            import net.minecraft.gametest.framework.TestFunction;

            public class TestBootstrap {
                private static final Class<?>[] HOLDERS = {
                    MachineTests.class
                };

                public static Collection<TestFunction> tests() {
                    return StructuredTestFunction.getTestsFrom(HOLDERS);
                }
            }
        """.trimIndent())
        mixinDir.resolve("TestLookupMixin.java").writeText("""
            package com.example.mixin;

            import com.example.testing.StructuredTestFunction;
            import net.minecraft.gametest.framework.TestFunction;

            public class TestLookupMixin {
                public static TestFunction lookup(String name) {
                    StructuredTestFunction function = StructuredTestFunction.NAMES_TO_FUNCTIONS.get(name);
                    if (function == null) {
                        throw new IllegalStateException(name);
                    }
                    return function;
                }
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val generated = apiDir.resolve("StructuredTestFunction.java").readText()
        val mixin = mixinDir.resolve("TestLookupMixin.java").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${result.errors}")
        assertTrue(result.changes.any { it.ruleId == "build-gametest-static-registration" })
        assertTrue(generated.contains("owner == com.example.tests.MachineTests.class"))
        assertTrue(generated.contains("com.example.tests.MachineTests::rotation"))
        assertTrue(generated.contains("com.example.testing.ExampleIds.ID, \"machines\""))
        assertTrue(Regex("""\"mechanical\",\s+2, com\.example\.testing\.StructuredHelper\.TIMEOUT, 5L""").containsMatchIn(generated))
        assertTrue(Regex("""\"defaultBatch\",\s+0, 100, 0L""").containsMatchIn(generated))
        assertTrue(generated.contains("required, false, maxAttempts, requiredSuccesses, true"))
        assertTrue(generated.contains("Comparator.comparing(TestFunction::testName)"))
        assertTrue(generated.contains("putString(\"TypedTestFunction\", fullName)"))
        assertFalse(generated.contains("java.lang.reflect"))
        assertFalse(generated.contains("getDeclaredMethods"))
        assertFalse(generated.contains("method.invoke"))
        assertFalse(generated.contains("commented"))
        assertTrue(mixin.contains("return function.testFunction;"))
    }


    @Test
    fun `forgegradle reobfuscation dsl preserves publication blocks on moddev jar task`() {
        val projectDir = tempDir.resolve("reobfuscation-publication")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '[6.0,6.2)'
                id 'maven-publish'
            }

            final slimJar = tasks.register("slimJar", Jar) {
                archiveClassifier = "slim"
                from(sourceSets.main.output)
            }
            obfuscation.reobfuscate(
                slimJar,
                sourceSets.main
            )

            publishing {
                publications {
                    mavenJava(MavenPublication) {
                        from components.java
                        artifact(tasks.reobfJar) {
                            classifier = "all"
                        }
                    }
                }
            }

            publishMods {
                file = reobfJar.archiveFile
            }

            // reobfJar in documentation must not affect executable migration.
            ext.reobfDocumentation = "reobfJar"
        """.trimIndent())

        val result = pass.apply(projectDir)
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${result.errors}")
        assertTrue(build.contains("artifact(tasks.named(\"jar\")) {"))
        assertTrue(build.contains("classifier = \"all\""))
        assertTrue(build.contains("file = tasks.named(\"jar\").flatMap { it.archiveFile }"))
        assertFalse(build.contains("obfuscation.reobfuscate("))
        assertTrue(build.contains("// reobfJar in documentation"))
        assertTrue(build.contains("ext.reobfDocumentation = \"reobfJar\""))
        assertTrue(result.changes.any { it.ruleId == "build-migrate-reobfuscation-dsl" })
        assertTrue(result.changes.any { it.ruleId == "build-migrate-reobfuscation-publication" })
    }


    @Test
    fun `recipe manager private maps migrate with their holder data flow before at removal`() {
        val projectDir = tempDir.resolve("recipe-manager-public-query")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        srcDir.createDirectories()
        atDir.createDirectories()
        srcDir.resolve("RecipeIndex.java").writeText("""
            package com.example;

            import java.util.Map;
            import java.util.function.Consumer;
            import net.minecraft.resources.ResourceLocation;
            import net.minecraft.world.item.crafting.Recipe;
            import net.minecraft.world.item.crafting.RecipeManager;
            import net.minecraft.world.item.crafting.RecipeType;

            public class RecipeIndex {
                @SuppressWarnings("unchecked")
                static <T extends Recipe<?>> void consume(RecipeManager manager, Consumer<T> consumer, RecipeType<?> type) {
                    Map<ResourceLocation, Recipe<?>> map = manager.recipes.get(type);
                    if (map != null)
                        map.values().forEach(recipe -> consumer.accept((T) recipe));
                }
            }
        """.trimIndent())
        atDir.resolve("accesstransformer.cfg").writeText(
            "public net.minecraft.world.item.crafting.RecipeManager f_44007_ # recipes\n"
        )

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("RecipeIndex.java").readText()
        val at = atDir.resolve("accesstransformer.cfg").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${result.errors}")
        assertTrue(source.contains("manager.getAllRecipesFor((RecipeType) type)"), source)
        assertTrue(source.contains("modporterRecipeHolder -> consumer.accept((T) modporterRecipeHolder.value())"), source)
        assertFalse(source.contains("manager.recipes"), source)
        assertFalse(source.contains("map.values()"), source)
        assertFalse(source.contains("java.util.Map"), source)
        assertFalse(source.contains("net.minecraft.resources.ResourceLocation"), source)
        assertFalse(at.contains("RecipeManager"), at)
        assertTrue(result.changes.any { it.ruleId == "build-recipe-manager-public-query-api" })
        assertTrue(result.changes.any { it.ruleId == "build-recipe-manager-obsolete-at" })
    }


    @Test
    fun `static potion brewing tables become level scoped caches with retargeted ats`() {
        val projectDir = tempDir.resolve("potion-brewing-level-state")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        val atDir = projectDir.resolve("src/main/resources/META-INF")
        srcDir.createDirectories()
        atDir.createDirectories()
        srcDir.resolve("PotionRecipeIndex.java").writeText("""
            package com.example;

            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.alchemy.Potion;
            import net.minecraft.world.item.alchemy.PotionBrewing;
            import net.minecraft.world.level.Level;
            import net.neoforged.neoforge.common.brewing.BrewingRecipeRegistry;

            public class PotionRecipeIndex {
                public static final List<String> ALL = createRecipes();
                public static final Map<Item, List<String>> BY_ITEM = sortRecipesByItem(ALL);

                private static List<String> createRecipes() {
                    List<String> output = new ArrayList<>();
                    ItemStack stack = ItemStack.EMPTY;
                    if (PotionBrewing.ALLOWED_CONTAINER.test(stack)) {
                        output.add("container");
                    }
                    for (PotionBrewing.Mix<Potion> mix : PotionBrewing.POTION_MIXES) {
                        output.add(mix.from.value().toString());
                        output.add(mix.to.value().toString());
                        output.add(mix.ingredient.toString());
                    }
                    for (PotionBrewing.Mix<Item> mix : PotionBrewing.CONTAINER_MIXES) {
                        output.add(mix.from.value().toString());
                    }
                    BrewingRecipeRegistry.getRecipes().forEach(recipe -> output.add(recipe.toString()));
                    return output;
                }

                private static Map<Item, List<String>> sortRecipesByItem(List<String> all) {
                    return new HashMap<>();
                }
            }
        """.trimIndent())
        srcDir.resolve("MachineBlockEntity.java").writeText("""
            package com.example;

            import net.minecraft.world.level.block.entity.BlockEntity;

            public class MachineBlockEntity extends BlockEntity {
                void query(Item item) {
                    PotionRecipeIndex.BY_ITEM.get(item);
                }
            }
        """.trimIndent())
        srcDir.resolve("ClientRecipeView.java").writeText("""
            package com.example;

            import net.minecraft.client.Minecraft;

            public class ClientRecipeView {
                void register() {
                    Minecraft.getInstance().getConnection();
                    consume(PotionRecipeIndex.ALL);
                }

                void consume(Object recipes) {
                }
            }
        """.trimIndent())
        atDir.resolve("accesstransformer.cfg").writeText("""
            public net.minecraft.world.item.alchemy.PotionBrewing f_43494_ # POTION_MIXES
            public net.minecraft.world.item.alchemy.PotionBrewing f_43495_ # CONTAINER_MIXES
            public net.minecraft.world.item.alchemy.PotionBrewing f_43497_ # ALLOWED_CONTAINER
        """.trimIndent())

        val result = pass.apply(projectDir)
        val index = srcDir.resolve("PotionRecipeIndex.java").readText()
        val machine = srcDir.resolve("MachineBlockEntity.java").readText()
        val client = srcDir.resolve("ClientRecipeView.java").readText()
        val at = atDir.resolve("accesstransformer.cfg").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${result.errors}")
        assertTrue(index.contains("private static List<String> ALL;"), index)
        assertTrue(index.contains("public static List<String> createRecipes(Level level)"), index)
        assertTrue(index.contains("private static List<String> createRecipesImpl(Level level)"), index)
        assertTrue(index.contains("PotionBrewing potionBrewing = level.potionBrewing();"), index)
        assertTrue(index.contains("potionBrewing.isContainer(stack)"), index)
        assertTrue(index.contains("potionBrewing.potionMixes"), index)
        assertTrue(index.contains("potionBrewing.containerMixes"), index)
        assertTrue(index.contains("mix.from().value()"), index)
        assertTrue(index.contains("mix.to().value()"), index)
        assertTrue(index.contains("mix.ingredient()"), index)
        assertTrue(index.contains("potionBrewing.getRecipes()"), index)
        assertTrue(machine.contains("PotionRecipeIndex.sortRecipesByItem(level).get(item)"), machine)
        assertTrue(client.contains("PotionRecipeIndex.createRecipes(Minecraft.getInstance().level)"), client)
        assertFalse(index.contains("PotionBrewing.POTION_MIXES"), index)
        assertFalse(index.contains("BrewingRecipeRegistry"), index)
        assertTrue(at.contains("public net.minecraft.world.item.alchemy.PotionBrewing potionMixes"), at)
        assertTrue(at.contains("public net.minecraft.world.item.alchemy.PotionBrewing containerMixes"), at)
        assertTrue(at.contains("public net.minecraft.world.item.alchemy.PotionBrewing isContainer(Lnet/minecraft/world/item/ItemStack;)Z"), at)
        assertFalse(at.contains("f_43494_"), at)
        assertFalse(at.contains("POTION_MIXES"), at)
    }


    @Test
    fun `java 21 migration covers applied and subproject gradle scripts without touching literals`() {
        val projectDir = tempDir.resolve("recursive-gradle-java-target")
        val gradleDir = projectDir.resolve("gradle")
        val moduleDir = projectDir.resolve("module")
        gradleDir.createDirectories()
        moduleDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            apply from: "gradle/java.gradle"
        """.trimIndent())
        gradleDir.resolve("java.gradle").writeText("""
            java.toolchain.languageVersion = JavaLanguageVersion.of(17)
            tasks.withType(JavaCompile).configureEach {
                options.release = 17
            }
            ext.futureSource = sourceCompatibility = 170
            ext.futureTarget = targetCompatibility = 171
            // JavaLanguageVersion.of(17) is documentation.
            ext.example = "options.release = 17"
        """.trimIndent())
        moduleDir.resolve("build.gradle.kts").writeText("""
            java {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.toVersion(17)
            }
            tasks.withType<JavaCompile>().configureEach {
                options.release.set(17)
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val shared = gradleDir.resolve("java.gradle").readText()
        val module = moduleDir.resolve("build.gradle.kts").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${result.errors}")
        assertTrue(shared.contains("JavaLanguageVersion.of(21)"), shared)
        assertTrue(shared.contains("options.release = 21"), shared)
        assertTrue(shared.contains("sourceCompatibility = 170"), shared)
        assertTrue(shared.contains("targetCompatibility = 171"), shared)
        assertTrue(shared.contains("// JavaLanguageVersion.of(17) is documentation."), shared)
        assertTrue(shared.contains("\"options.release = 17\""), shared)
        assertTrue(module.contains("JavaVersion.VERSION_21"), module)
        assertTrue(module.contains("JavaVersion.toVersion(21)"), module)
        assertTrue(module.contains("options.release.set(21)"), module)
    }


    @Test
    fun `nested jarjar dependencies retain packaging semantics when coordinates migrate`() {
        val projectDir = tempDir.resolve("nested-jarjar-dependency")
        projectDir.createDirectories()
        projectDir.resolve("build.gradle").writeText("""
            plugins {
                id 'net.minecraftforge.gradle' version '6.0.24'
            }

            dependencies {
                jarJar(implementation("net.createmod.ponder:Ponder-Forge-1.20.1:1.0.82"))
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val build = projectDir.resolve("build.gradle").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${result.errors}")
        assertTrue(
            build.contains("jarJar(implementation(\"net.createmod.ponder:ponder-neoforge:1.0.87+mc1.21.1\"))"),
            build
        )
        assertFalse(build.contains("Ponder-Forge-1.20.1"), build)
    }


    @Test
    fun `complete legacy food properties codecs use the target standard codec`() {
        val projectDir = tempDir.resolve("food-properties-standard-codec")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Codecs.java").writeText("""
            package com.example;

            import com.mojang.serialization.Codec;
            import com.mojang.serialization.codecs.RecordCodecBuilder;
            import net.minecraft.world.food.FoodProperties;

            public class Codecs {
                public static final Codec<FoodProperties> COMPLETE = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("nutrition").forGetter(FoodProperties::nutrition),
                    Codec.FLOAT.fieldOf("saturation").forGetter(FoodProperties::saturation),
                    Codec.BOOL.fieldOf("always").forGetter(FoodProperties::canAlwaysEat),
                    Codec.STRING.listOf().fieldOf("effects").forGetter(value -> value.effects())
                ).apply(instance, (nutrition, saturation, always, effects) -> {
                    FoodProperties.Builder builder = new FoodProperties.Builder().nutrition(nutrition).saturationModifier(saturation);
                    if (always) builder.alwaysEdible();
                    return builder.build();
                }));

                public static final Codec<FoodProperties> PARTIAL = Codec.unit(new FoodProperties.Builder().build());
            }
        """.trimIndent())

        val result = pass.apply(projectDir)
        val source = srcDir.resolve("Codecs.java").readText()

        assertTrue(result.errors.isEmpty(), "Unexpected migration errors: ${result.errors}")
        assertTrue(source.contains("Codec<FoodProperties> COMPLETE = FoodProperties.DIRECT_CODEC;"), source)
        assertTrue(source.contains("Codec<FoodProperties> PARTIAL = Codec.unit"), source)
        assertFalse(source.contains("if (always) builder.alwaysEdible()"), source)
        assertTrue(result.changes.any { it.ruleId == "build-food-properties-standard-codec" })
    }


    @Test
    fun `registrate generic bases and removed functional interfaces migrate from declared structure`() {
        val projectDir = tempDir.resolve("registrate-api-generics")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        srcDir.resolve("Registrations.java").writeText("""
            package com.example;

            import com.tterrag.registrate.util.entry.RegistryEntry;
            import com.tterrag.registrate.util.entry.ItemProviderEntry;
            import com.tterrag.registrate.util.nullness.NonNullFunction;
            import net.minecraft.core.Registry;
            import net.minecraft.resources.ResourceKey;
            import net.neoforged.neoforge.registries.DeferredHolder;
            import net.neoforged.neoforge.common.util.NonNullConsumer;
            import net.neoforged.neoforge.common.util.NonNullPredicate;
            import net.neoforged.neoforge.common.util.NonNullSupplier;

            class DisplaySource {}
            class ConcreteDisplaySource extends DisplaySource {}

            class RegistrateFacade {
                <T extends DisplaySource> RegistryEntry<T> displaySource(String name, NonNullSupplier<T> factory) {
                    return null;
                }
            }

            class Registrations {
                private static final RegistrateFacade REGISTRATE = new RegistrateFacade();
                static final RegistryEntry<ConcreteDisplaySource> DISPLAY =
                    REGISTRATE.displaySource("display", ConcreteDisplaySource::new);
                static final RegistryEntry<?> ANY = DISPLAY;
                static final RegistryEntry<? extends DisplaySource> DISPLAY_BASE = DISPLAY;
                ItemProviderEntry<?> anyItem;
                ItemProviderEntry<? extends ItemLike> itemLike;
                ItemProviderEntry<Item> item;

                <R, T extends R> RegistryEntry<T> accept(
                    ResourceKey<? extends Registry<R>> registry,
                    RegistryEntry<T> entry,
                    NonNullFunction<DeferredHolder<T, T>, ? extends RegistryEntry<T>> entryFactory
                ) {
                    return entry;
                }

                NonNullSupplier<ConcreteDisplaySource> supplier = ConcreteDisplaySource::new;
                NonNullPredicate<ConcreteDisplaySource> predicate = value -> true;
                NonNullConsumer<ConcreteDisplaySource> consumer = value -> {};

                String documentation = "NonNullSupplier RegistryEntry<T>";
                // NonNullPredicate in documentation must remain unchanged.
            }
        """.trimIndent())

        val changes = RegistrateApiMigration().migrate(projectDir, dryRun = false)
        val source = srcDir.resolve("Registrations.java").readText()

        assertTrue(source.contains("RegistryEntry<DisplaySource, T> displaySource"), source)
        assertTrue(source.contains("RegistryEntry<DisplaySource, ConcreteDisplaySource> DISPLAY"), source)
        assertTrue(source.contains("RegistryEntry<?, ?> ANY"), source)
        assertTrue(source.contains("RegistryEntry<DisplaySource, ? extends DisplaySource> DISPLAY_BASE"), source)
        assertTrue(source.contains("ItemProviderEntry<?, ?> anyItem"), source)
        assertTrue(source.contains("ItemProviderEntry<? extends ItemLike, ? extends ItemLike> itemLike"), source)
        assertTrue(source.contains("ItemProviderEntry<Item, Item> item"), source)
        assertTrue(source.contains("RegistryEntry<R, T> accept"), source)
        assertTrue(source.contains("RegistryEntry<R, T> entry"), source)
        assertTrue(
            source.contains(
                "NonNullFunction<DeferredHolder<R, T>, ? extends RegistryEntry<R, T>> entryFactory"
            ),
            source
        )
        assertTrue(source.contains("import java.util.function.Supplier;"), source)
        assertTrue(source.contains("import java.util.function.Predicate;"), source)
        assertTrue(source.contains("import java.util.function.Consumer;"), source)
        assertTrue(source.contains("Supplier<ConcreteDisplaySource> supplier"), source)
        assertTrue(source.contains("Predicate<ConcreteDisplaySource> predicate"), source)
        assertTrue(source.contains("Consumer<ConcreteDisplaySource> consumer"), source)
        assertTrue(source.contains("\"NonNullSupplier RegistryEntry<T>\""), source)
        assertTrue(source.contains("// NonNullPredicate in documentation"), source)
        assertTrue(changes.any { it.ruleId == "build-registrate-registryentry-generics" })
        assertTrue(changes.any { it.ruleId == "build-registrate-itemproviderentry-generics" })
        assertTrue(changes.any { it.ruleId == "build-registrate-deferredholder-factory-generics" })
        assertTrue(changes.any { it.ruleId == "build-neoforge-functional-interfaces" })
    }


    @Test
    fun `registrate deferred holder factory rejects ambiguous registry keys`() {
        val projectDir = tempDir.resolve("registrate-ambiguous-registry-key")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val source = srcDir.resolve("AmbiguousRegistrate.java")
        source.writeText("""
            package com.example;

            import com.tterrag.registrate.util.entry.RegistryEntry;
            import com.tterrag.registrate.util.nullness.NonNullFunction;
            import net.minecraft.core.Registry;
            import net.minecraft.resources.ResourceKey;
            import net.neoforged.neoforge.registries.DeferredHolder;

            class AmbiguousRegistrate {
                <R, T extends R> RegistryEntry<T> accept(
                    ResourceKey<? extends Registry<R>> first,
                    ResourceKey<? extends Registry<R>> second,
                    NonNullFunction<DeferredHolder<T, T>, ? extends RegistryEntry<T>> entryFactory
                ) {
                    return null;
                }
            }
        """.trimIndent())

        val error = assertFailsWith<IllegalStateException> {
            RegistrateApiMigration().migrate(projectDir, dryRun = false)
        }

        assertTrue(error.message.orEmpty().contains("requires one exact Registry key"), error.message)
        assertTrue(source.readText().contains("DeferredHolder<T, T>"))
    }


    @Test
    fun `registrate deferred holder factory ignores project shadow types`() {
        val projectDir = tempDir.resolve("registrate-shadow-types")
        val srcDir = projectDir.resolve("src/main/java/com/example")
        srcDir.createDirectories()
        val source = srcDir.resolve("ShadowRegistrate.java")
        source.writeText("""
            package com.example;

            import com.tterrag.registrate.util.entry.RegistryEntry;
            import com.tterrag.registrate.util.nullness.NonNullFunction;
            import net.minecraft.core.Registry;
            import net.minecraft.resources.ResourceKey;
            import net.neoforged.neoforge.registries.DeferredHolder;

            class ShadowRegistrate {
                static class DeferredHolder<R, T extends R> {}

                <R, T extends R> RegistryEntry<T> accept(
                    ResourceKey<? extends Registry<R>> registry,
                    NonNullFunction<DeferredHolder<T, T>, ? extends RegistryEntry<T>> entryFactory
                ) {
                    return null;
                }
            }
        """.trimIndent())

        val changes = RegistrateApiMigration().migrate(projectDir, dryRun = false)
        val migrated = source.readText()

        assertTrue(migrated.contains("NonNullFunction<DeferredHolder<T, T>"), migrated)
        assertFalse(changes.any { it.ruleId == "build-registrate-deferredholder-factory-generics" })
    }
}
