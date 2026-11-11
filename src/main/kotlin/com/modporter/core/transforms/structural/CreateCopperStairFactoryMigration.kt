package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.stmt.IfStmt
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.stmt.Statement
import com.github.javaparser.ast.stmt.ThrowStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import com.modporter.core.transforms.util.JavaSourceText
import com.modporter.mapping.TargetMembers
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Replaces Create's Forge-only lazy StairBlock model-state bridge with a target-native family
 * that retains the original supplier.
 *
 * Forge 1.20 redirected every StairBlock base/baseState read through stateSupplier. Target
 * StairBlock retains one behavioral base read, getExplosionResistance(), plus the baseState codec
 * getter. The generated subclasses override the behavioral read through the original supplier
 * and own codecs that encode that supplier as base_state. No supplier is evaluated during
 * registration.
 */
internal object CreateCopperStairFactoryMigration {
    const val RULE_ID = "struct-create-copper-stair-factory"

    private const val COPPER_PACKAGE = "com.simibubi.create.foundation.block"
    private const val COPPER_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/block/CopperBlockSet.java"
    private const val TAG_GEN_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/data/TagGen.java"
    private const val CREATE_REGISTRATE_RELATIVE =
        "src/main/java/com/simibubi/create/foundation/data/CreateRegistrate.java"
    private const val COPPER_CLASS = "CopperBlockSet"
    private const val STAIR_CLASS = "CreateCopperStairBlock"
    private const val WEATHERING_STAIR_CLASS = "CreateWeatheringCopperStairBlock"
    private const val STAIR = "net.minecraft.world.level.block.StairBlock"
    private const val WEATHERING_STAIR =
        "net.minecraft.world.level.block.WeatheringCopperStairBlock"
    private const val BLOCK_BEHAVIOUR =
        "net.minecraft.world.level.block.state.BlockBehaviour"
    private const val BLOCK_STATE =
        "net.minecraft.world.level.block.state.BlockState"
    private const val WEATHER_STATE =
        "net.minecraft.world.level.block.WeatheringCopper\$WeatherState"
    private const val PROPERTIES =
        "Lnet/minecraft/world/level/block/state/BlockBehaviour\$Properties;"
    private const val MAP_CODEC = "()Lcom/mojang/serialization/MapCodec;"
    private const val PROPERTIES_CODEC =
        "()Lcom/mojang/serialization/codecs/RecordCodecBuilder;"
    private const val CODEC = "Lcom/mojang/serialization/Codec;"

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean,
        commitProbe: ((Int, Path) -> Unit)? = null
    ): Result = try {
        migrateChecked(pass, projectDir, dryRun, commitProbe)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Create copper stair admission failed"))
    } catch (failure: Exception) {
        val rollbackFailures = failure.suppressed
            .map { it.message ?: it::class.java.simpleName }
        val rollbackDetail = if (rollbackFailures.isEmpty()) {
            ""
        } else {
            "; rollback failures: ${rollbackFailures.joinToString()}"
        }
        Result(
            emptyList(),
            listOf(
                "Create copper stair migration transaction failed: " +
                    (failure.message ?: failure::class.java.simpleName) +
                    rollbackDetail
            )
        )
    }

    private fun migrateChecked(
        pass: StructuralRefactorPass,
        projectDir: Path,
        dryRun: Boolean,
        commitProbe: ((Int, Path) -> Unit)?
    ): Result {
        val copperFile = projectDir.resolve(COPPER_RELATIVE)
        if (!copperFile.isRegularFile()) return Result.EMPTY
        if (!targetContractIsVerified()) {
            abort("NeoForge 1.21.1 stair constructors, codecs, or resistance field changed")
        }

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val copperSource = copperFile.readText()
        val unit = parse(parser, copperSource, copperFile)
        admitClosedSourceGraph(parser, unit, projectDir)
        val admitted = admitFactory(unit)
        val migratedCopper = when (admitted.mode) {
            FactoryMode.MIGRATED -> copperSource
            FactoryMode.LEGACY_REFLECTION,
            FactoryMode.LEGACY_STRIPPED -> migrateFactory(
                pass,
                parser,
                unit,
                admitted
            )
        }

        val sourceRoot = projectDir.resolve("src/main/java")
        val packageRoot = sourceRoot.resolve(COPPER_PACKAGE.replace('.', '/'))
        val stairFile = packageRoot.resolve("$STAIR_CLASS.java")
        val weatheringFile = packageRoot.resolve("$WEATHERING_STAIR_CLASS.java")
        val stairSource = renderCopperStair()
        val weatheringSource = renderWeatheringCopperStair()
        requireGeneratedOwnership(sourceRoot, stairFile, STAIR_CLASS, stairSource)
        requireGeneratedOwnership(
            sourceRoot,
            weatheringFile,
            WEATHERING_STAIR_CLASS,
            weatheringSource
        )

        val outputs = mutableListOf<PreparedOutput>()
        if (migratedCopper != copperSource) {
            outputs += PreparedOutput(
                copperFile,
                copperSource,
                migratedCopper,
                "Replace Forge stair bridges with supplier-backed target stair factories"
            )
        }
        if (!stairFile.exists()) {
            outputs += PreparedOutput(
                stairFile,
                null,
                stairSource,
                "Generate supplier-backed CreateCopperStairBlock with base-state codec"
            )
        }
        if (!weatheringFile.exists()) {
            outputs += PreparedOutput(
                weatheringFile,
                null,
                weatheringSource,
                "Generate supplier-backed weathering stair with weather/base-state codec"
            )
        }

        if (!dryRun) writeFamily(outputs, commitProbe)
        return Result(
            outputs.map { output ->
                Change(
                    file = output.path,
                    line = 1,
                    description = output.description,
                    before = output.before?.take(160) ?: "(missing file)",
                    after = output.after.take(160),
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            },
            emptyList()
        )
    }

    private fun parse(
        parser: JavaParser,
        source: String,
        path: Path
    ): CompilationUnit {
        val parsed = parser.parse(source)
        if (!parsed.isSuccessful || parsed.result.isEmpty) {
            abort(
                "Cannot parse $path: " +
                    parsed.problems.joinToString("; ") { it.verboseMessage }
                        .ifBlank { "unknown parse failure" }
            )
        }
        return parsed.result.orElseThrow()
    }

    private fun admitClosedSourceGraph(
        parser: JavaParser,
        unit: CompilationUnit,
        projectDir: Path
    ) {
        val owner = copperOwner(unit)
        if (owner.extendedTypes.isNotEmpty() || owner.implementedTypes.isNotEmpty()) {
            abort("CopperBlockSet inheritance is outside the proved closed source")
        }
        val nestedTypes = owner.members.filterIsInstance<ClassOrInterfaceDeclaration>()
            .map { it.nameAsString }
            .toSet()
        if (nestedTypes != setOf("Variant", "BlockVariant", "SlabVariant", "StairVariant")) {
            abort("CopperBlockSet owns a nested type outside the proved graph")
        }
        admitCanonicalImports(unit)
        admitBaseBlocks(owner)
        admitDefaultVariants(owner)
        val blockVariant = nestedClass(owner, "BlockVariant")
        val stairVariant = nestedClass(owner, "StairVariant")
        admitBlockVariant(blockVariant)
        admitStairParent(stairVariant)
        admitConstructionGraph(owner)
        admitCreateEntry(owner)
        admitInternalCallerClosure(owner)
        admitProjectCallerClosure(parser, projectDir)
        admitSupportSemantics(parser, projectDir)
    }

    private fun copperOwner(unit: CompilationUnit): ClassOrInterfaceDeclaration {
        if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != COPPER_PACKAGE) {
            abort("CopperBlockSet source path has an unexpected package")
        }
        val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
            ?: abort("CopperBlockSet must contain one top-level class")
        if (owner.nameAsString != COPPER_CLASS || owner.isInterface) {
            abort("Copper stair factory is not owned by the expected CopperBlockSet class")
        }
        return owner
    }

    private fun nestedClass(
        owner: ClassOrInterfaceDeclaration,
        name: String
    ): ClassOrInterfaceDeclaration =
        owner.members.filterIsInstance<ClassOrInterfaceDeclaration>()
            .singleOrNull { it.nameAsString == name }
            ?: abort("CopperBlockSet must own exactly one $name")

    private fun fieldVariable(
        owner: ClassOrInterfaceDeclaration,
        name: String
    ): VariableDeclarator =
        owner.fields.flatMap { it.variables }
            .singleOrNull { it.nameAsString == name }
            ?: abort("${owner.nameAsString} must own exactly one $name field")

    private fun fieldDeclaration(variable: VariableDeclarator): FieldDeclaration =
        variable.findAncestor(FieldDeclaration::class.java).orElse(null)
            ?: abort("${variable.nameAsString} is not declared as a field")

    private fun admitField(
        owner: ClassOrInterfaceDeclaration,
        name: String,
        type: String,
        initializer: String,
        modifiers: Set<String>
    ): VariableDeclarator {
        val variable = fieldVariable(owner, name)
        val declaration = fieldDeclaration(variable)
        val actualModifiers = declaration.modifiers
            .map { it.keyword.asString() }
            .toSet()
        if (normalizeJava(variable.type.toString()) != normalizeJava(type) ||
            normalizeJava(variable.initializer.map { it.toString() }.orElse("")) !=
            normalizeJava(initializer) ||
            actualModifiers != modifiers
        ) {
            abort("${owner.nameAsString}.$name declaration changed from the proved graph")
        }
        return variable
    }

    private fun admitCanonicalImports(unit: CompilationUnit) {
        val imports = unit.imports
            .filter { !it.isAsterisk && !it.isStatic }
            .map { it.nameAsString }
            .toSet()
        val required = setOf(
            "java.util.EnumMap",
            "java.util.HashMap",
            "java.util.Map",
            "java.util.function.Supplier",
            "org.apache.commons.lang3.ArrayUtils",
            "com.simibubi.create.foundation.data.TagGen",
            "com.tterrag.registrate.AbstractRegistrate",
            "com.tterrag.registrate.builders.BlockBuilder",
            "com.tterrag.registrate.providers.DataGenContext",
            "com.tterrag.registrate.providers.RegistrateRecipeProvider",
            "com.tterrag.registrate.util.entry.BlockEntry",
            "com.tterrag.registrate.util.nullness.NonNullBiConsumer",
            "com.tterrag.registrate.util.nullness.NonNullFunction",
            "net.createmod.catnip.data.Iterate",
            "net.minecraft.tags.BlockTags",
            "net.minecraft.world.level.block.Block",
            "net.minecraft.world.level.block.Blocks",
            "net.minecraft.world.level.block.SlabBlock",
            "net.minecraft.world.level.block.StairBlock",
            "net.minecraft.world.level.block.WeatheringCopper.WeatherState",
            "net.minecraft.world.level.block.WeatheringCopperFullBlock",
            "net.minecraft.world.level.block.state.BlockBehaviour.Properties",
            BLOCK_STATE
        )
        if (!imports.containsAll(required)) {
            abort("CopperBlockSet canonical property, parent, or supplier imports changed")
        }
    }

    private fun admitBaseBlocks(owner: ClassOrInterfaceDeclaration) {
        val baseBlocks = fieldVariable(owner, "BASE_BLOCKS")
        if (normalizeJava(baseBlocks.type.toString()) !=
            "Map<WeatherState,Supplier<Block>>" ||
            normalizeJava(baseBlocks.initializer.map { it.toString() }.orElse("")) !=
            "newEnumMap<>(WeatherState.class)" ||
            fieldDeclaration(baseBlocks).modifiers.map { it.keyword.asString() }.toSet() !=
            setOf("protected", "static", "final")
        ) {
            abort("BASE_BLOCKS declaration is outside the proved vanilla copper map")
        }
        val initializers = owner.members.filterIsInstance<InitializerDeclaration>()
            .filter { it.isStatic }
        if (initializers.size != 1) {
            abort("CopperBlockSet must have one exact static BASE_BLOCKS initializer")
        }
        val expected = listOf(
            "BASE_BLOCKS.put(WeatherState.UNAFFECTED,()->Blocks.COPPER_BLOCK);",
            "BASE_BLOCKS.put(WeatherState.EXPOSED,()->Blocks.EXPOSED_COPPER);",
            "BASE_BLOCKS.put(WeatherState.WEATHERED,()->Blocks.WEATHERED_COPPER);",
            "BASE_BLOCKS.put(WeatherState.OXIDIZED,()->Blocks.OXIDIZED_COPPER);"
        )
        val actual = initializers.single().body.statements
            .map { normalizeJava(it.toString()) }
        if (actual != expected) {
            abort("BASE_BLOCKS entries or order changed from the proved vanilla family")
        }
    }

    private fun admitDefaultVariants(owner: ClassOrInterfaceDeclaration) {
        val variants = fieldVariable(owner, "DEFAULT_VARIANTS")
        if (normalizeJava(variants.type.toString()) != "Variant<?>[]" ||
            normalizeJava(variants.initializer.map { it.toString() }.orElse("")) !=
            "newVariant<?>[]{BlockVariant.INSTANCE,SlabVariant.INSTANCE," +
            "StairVariant.INSTANCE}" ||
            fieldDeclaration(variants).modifiers.map { it.keyword.asString() }.toSet() !=
            setOf("public", "static", "final")
        ) {
            abort("DEFAULT_VARIANTS no longer closes over block, slab, and stair in order")
        }
    }

    private fun admitStairParent(stairVariant: ClassOrInterfaceDeclaration) {
        val instance = fieldVariable(stairVariant, "INSTANCE")
        if (normalizeJava(instance.initializer.map { it.toString() }.orElse("")) !=
            "newStairVariant(BlockVariant.INSTANCE)"
        ) {
            abort("StairVariant.INSTANCE is no longer parented by BlockVariant.INSTANCE")
        }
        val parent = fieldVariable(stairVariant, "parent")
        if (normalizeJava(parent.type.toString()) != "Variant<?>") {
            abort("StairVariant parent type changed")
        }
        val constructor = stairVariant.constructors.singleOrNull()
            ?: abort("StairVariant must have one parent constructor")
        if (constructor.parameters.size != 1 ||
            normalizeJava(constructor.parameters[0].type.toString()) != "Variant<?>" ||
            normalizeJava(constructor.body.toString()) != "{this.parent=parent;}"
        ) {
            abort("StairVariant parent constructor changed")
        }
    }

    private fun admitBlockVariant(blockVariant: ClassOrInterfaceDeclaration) {
        val instance = fieldVariable(blockVariant, "INSTANCE")
        if (normalizeJava(instance.initializer.map { it.toString() }.orElse("")) !=
            "newBlockVariant()"
        ) {
            abort("BlockVariant.INSTANCE construction changed")
        }
        val factory = blockVariant.methods.singleOrNull { it.nameAsString == "getFactory" }
            ?: abort("BlockVariant must own one getFactory")
        if (factory.parameters.map { simpleType(it.type.toString()) } !=
            listOf("CopperBlockSet", "WeatherState", "boolean") ||
            normalizeJava(factory.type.toString()) != "NonNullFunction<Properties,Block>"
        ) {
            abort("BlockVariant.getFactory signature changed")
        }
        val state = factory.parameters[1].nameAsString
        val waxed = factory.parameters[2].nameAsString
        val expected =
            "{if($waxed){returnBlock::new;}else{" +
                "returnp->newWeatheringCopperFullBlock($state,p);}}"
        if (normalizeJava(factory.body.map { it.toString() }.orElse("")) != expected) {
            abort("BlockVariant parent factories changed from Block/WeatheringCopperFullBlock")
        }
    }

    private fun admitConstructionGraph(owner: ClassOrInterfaceDeclaration) {
        val protectedStaticFinal = setOf("protected", "static", "final")
        val protectedFinal = setOf("protected", "final")
        admitField(
            owner,
            "WEATHER_STATES",
            "WeatherState[]",
            "WeatherState.values()",
            protectedStaticFinal
        )
        admitField(
            owner,
            "WEATHER_STATE_COUNT",
            "int",
            "WEATHER_STATES.length",
            protectedStaticFinal
        )
        admitField(owner, "name", "String", "", protectedFinal)
        admitField(owner, "generalDirectory", "String", "", protectedFinal)
        admitField(owner, "variants", "Variant<?>[]", "", protectedFinal)
        admitField(
            owner,
            "entries",
            "Map<Variant<?>, BlockEntry<?>[]>",
            "new HashMap<>()",
            protectedFinal
        )
        admitField(
            owner,
            "mainBlockRecipe",
            "NonNullBiConsumer<DataGenContext<Block, ?>, RegistrateRecipeProvider>",
            "",
            protectedFinal
        )
        admitField(owner, "endTextureName", "String", "", protectedFinal)
        admitField(
            owner,
            "onRegister",
            "NonNullBiConsumer<WeatherState, Block>",
            "",
            protectedFinal
        )

        val registrationTypes = listOf(
            "AbstractRegistrate<?>",
            "String",
            "String",
            "Variant<?>[]",
            "NonNullBiConsumer<DataGenContext<Block,?>,RegistrateRecipeProvider>",
            "NonNullBiConsumer<WeatherState,Block>"
        )
        val registrationConstructor = owner.constructors.singleOrNull { constructor ->
            constructor.parameters.map { normalizeJava(it.type.toString()) } ==
                registrationTypes
        } ?: abort("CopperBlockSet six-argument registration constructor changed")
        val expectedRegistrationBody =
            """{
                this(registrate, name, endTextureName, variants, mainBlockRecipe,
                    "copper/", onRegister);
            }"""
        if (normalizeJava(registrationConstructor.body.toString()) !=
            normalizeJava(expectedRegistrationBody)
        ) {
            abort("CopperBlockSet registrations no longer delegate to the proved constructor")
        }

        val fullTypes = registrationTypes.toMutableList().also {
            it.add(5, "String")
        }
        val fullConstructor = owner.constructors.singleOrNull { constructor ->
            constructor.parameters.map { normalizeJava(it.type.toString()) } == fullTypes
        } ?: abort("CopperBlockSet full constructor changed")
        val expectedFullBody =
            """{
                this.name = name;
                this.generalDirectory = generalDirectory;
                this.endTextureName = endTextureName;
                this.variants = variants;
                this.mainBlockRecipe = mainBlockRecipe;
                this.onRegister = onRegister;

                for (boolean waxed : Iterate.falseAndTrue) {
                    for (Variant<?> variant : this.variants) {
                        BlockEntry<?>[] entries =
                            waxed ? this.entries.get(variant) :
                                new BlockEntry<?>[WEATHER_STATE_COUNT * 2];
                        for (WeatherState state : WEATHER_STATES) {
                            int index = getIndex(state, waxed);
                            BlockEntry<?> entry =
                                createEntry(registrate, variant, state, waxed);
                            entries[index] = entry;

                            if (waxed) {
                                CopperRegistries.addWaxable(
                                    () -> entries[getIndex(state, false)].get(),
                                    () -> entry.get());
                            } else if (state != WeatherState.UNAFFECTED) {
                                CopperRegistries.addWeathering(
                                    () -> entries[getIndex(
                                        WEATHER_STATES[state.ordinal() - 1],
                                        false)].get(),
                                    () -> entry.get());
                            }
                        }
                        if (!waxed)
                            this.entries.put(variant, entries);
                    }
                }
            }"""
        if (normalizeJava(fullConstructor.body.toString()) != normalizeJava(expectedFullBody)) {
            abort("CopperBlockSet constructor no longer creates parent entries before stairs")
        }

        val getIndex = owner.methods.singleOrNull { it.nameAsString == "getIndex" }
            ?: abort("CopperBlockSet must own one getIndex")
        if (getIndex.parameters.map { normalizeJava(it.type.toString()) } !=
            listOf("WeatherState", "boolean") ||
            normalizeJava(getIndex.type.toString()) != "int" ||
            normalizeJava(getIndex.body.map { it.toString() }.orElse("")) !=
            "{returnstate.ordinal()+(waxed?WEATHER_STATE_COUNT:0);}"
        ) {
            abort("CopperBlockSet entry index contract changed")
        }

        val hasVariant = owner.methods.singleOrNull { it.nameAsString == "hasVariant" }
            ?: abort("CopperBlockSet must own one hasVariant")
        if (hasVariant.parameters.map { normalizeJava(it.type.toString()) } !=
            listOf("Variant<?>") ||
            normalizeJava(hasVariant.type.toString()) != "boolean" ||
            normalizeJava(hasVariant.body.map { it.toString() }.orElse("")) !=
            "{returnArrayUtils.contains(variants,variant);}"
        ) {
            abort("CopperBlockSet parent-variant membership contract changed")
        }

        val getVariants = owner.methods.singleOrNull { it.nameAsString == "getVariants" }
            ?: abort("CopperBlockSet must own one getVariants")
        if (getVariants.parameters.isNotEmpty() ||
            normalizeJava(getVariants.type.toString()) != "Variant<?>[]" ||
            normalizeJava(getVariants.body.map { it.toString() }.orElse("")) !=
            "{returnvariants;}"
        ) {
            abort("CopperBlockSet variant exposure contract changed")
        }

        val get = owner.methods.singleOrNull { it.nameAsString == "get" }
            ?: abort("CopperBlockSet must own one entry get")
        val expectedGetBody =
            """{
                BlockEntry<?>[] entries = this.entries.get(variant);
                if (entries != null) {
                    return entries[getIndex(state, waxed)];
                }
                return null;
            }"""
        if (get.parameters.map { normalizeJava(it.type.toString()) } !=
            listOf("Variant<?>", "WeatherState", "boolean") ||
            normalizeJava(get.type.toString()) != "BlockEntry<?>" ||
            normalizeJava(get.body.map { it.toString() }.orElse("")) !=
            normalizeJava(expectedGetBody)
        ) {
            abort("CopperBlockSet parent entry lookup contract changed")
        }
    }

    private fun admitCreateEntry(owner: ClassOrInterfaceDeclaration) {
        val method = owner.methods.singleOrNull { it.nameAsString == "createEntry" }
            ?: abort("CopperBlockSet must own one createEntry")
        if (method.typeParameters.map { normalizeJava(it.toString()) } !=
            listOf("TextendsBlock") ||
            normalizeJava(method.type.toString()) != "BlockEntry<?>" ||
            method.modifiers.map { it.keyword.asString() }.toSet() != setOf("protected") ||
            method.parameters.map { normalizeJava(it.type.toString()) } != listOf(
                "AbstractRegistrate<?>",
                "Variant<T>",
                "WeatherState",
                "boolean"
            )
        ) {
            abort("CopperBlockSet.createEntry signature changed")
        }
        val registrate = method.parameters[0].nameAsString
        val variant = method.parameters[1].nameAsString
        val state = method.parameters[2].nameAsString
        val waxed = method.parameters[3].nameAsString
        val base = method.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
            normalizeJava(variable.type.toString()) == "Supplier<Block>" &&
                normalizeJava(variable.initializer.map { it.toString() }.orElse("")) ==
                "BASE_BLOCKS.get($state)"
        } ?: abort("createEntry must derive one lazy base block from BASE_BLOCKS")
        val builder = method.findAll(VariableDeclarator::class.java).singleOrNull { variable ->
            normalizeJava(variable.type.toString()) == "BlockBuilder<T,?>"
        } ?: abort("createEntry must construct one typed BlockBuilder")
        val expected =
            "${registrate}.block(name,$variant.getFactory(this,$state,$waxed))" +
                ".initialProperties(()->${base.nameAsString}.get())" +
                ".loot((lt,block)->$variant.generateLootTable(lt,block,this,$state,$waxed))" +
                ".blockstate((ctx,prov)->$variant.generateBlockState(ctx,prov,this,$state,$waxed))" +
                ".recipe((c,p)->$variant.generateRecipes(" +
                "entries.get(BlockVariant.INSTANCE)[$state.ordinal()],c,p))" +
                ".transform(TagGen.pickaxeOnly())" +
                ".onRegister(block->onRegister.accept($state,block))" +
                ".tag(BlockTags.NEEDS_STONE_TOOL).simpleItem()"
        if (normalizeJava(builder.initializer.map { it.toString() }.orElse("")) != expected) {
            abort("createEntry property or registration builder chain changed")
        }
        if (method.findAll(MethodCallExpr::class.java).any {
                it.nameAsString == "properties"
            }
        ) {
            abort("createEntry now applies an unproved properties callback")
        }
        val baseUses = method.findAll(NameExpr::class.java)
            .filter { it.nameAsString == base.nameAsString }
        if (baseUses.size != 1) {
            abort("createEntry aliases or reuses its base block supplier")
        }
        val baseGet = baseUses.single().parentNode.orElse(null) as? MethodCallExpr
        if (baseGet == null ||
            baseGet.scope.orElse(null) !== baseUses.single() ||
            baseGet.nameAsString != "get" ||
            baseGet.arguments.isNotEmpty()
        ) {
            abort("createEntry base block supplier is used outside initialProperties")
        }
        val builderUses = method.findAll(NameExpr::class.java)
            .filter { it.nameAsString == builder.nameAsString }
        val scopedCalls = builderUses.map { use ->
            val call = use.parentNode.orElse(null) as? MethodCallExpr
                ?: abort("createEntry aliases or exposes its BlockBuilder")
            if (call.scope.orElse(null) !== use) {
                abort("createEntry passes its BlockBuilder into an unproved operation")
            }
            call
        }
        val expectedCalls = mapOf("recipe" to 2, "tag" to 2, "register" to 1)
        if (scopedCalls.groupingBy { it.nameAsString }.eachCount() != expectedCalls ||
            scopedCalls.filter { it.nameAsString == "register" }
                .single().arguments.isNotEmpty()
        ) {
            abort("createEntry mutates its BlockBuilder outside the proved recipe/tag suffix")
        }
    }

    private fun admitInternalCallerClosure(owner: ClassOrInterfaceDeclaration) {
        val variants = owner.members.filterIsInstance<ClassOrInterfaceDeclaration>()
            .filter { nested ->
                nested.implementedTypes.any {
                    normalizeJava(it.toString()).startsWith("Variant<")
                }
            }
            .associate { nested ->
                nested.nameAsString to nested.implementedTypes
                    .map { normalizeJava(it.toString()) }
            }
        val expectedVariants = mapOf(
            "BlockVariant" to listOf("Variant<Block>"),
            "SlabVariant" to listOf("Variant<SlabBlock>"),
            "StairVariant" to listOf("Variant<StairBlock>")
        )
        if (variants != expectedVariants) {
            abort("CopperBlockSet owns an unproved Variant implementation")
        }
        if (owner.findAll(FieldAccessExpr::class.java).any {
                it.nameAsString == "BASE_BLOCKS"
            }
        ) {
            abort("BASE_BLOCKS is accessed through an unproved qualifier")
        }
        val baseUses = owner.findAll(NameExpr::class.java)
            .filter { it.nameAsString == "BASE_BLOCKS" }
        val baseCalls = baseUses.map { use ->
            val call = use.parentNode.orElse(null) as? MethodCallExpr
                ?: abort("BASE_BLOCKS is aliased or passed outside the proved closure")
            if (call.scope.orElse(null) !== use) {
                abort("BASE_BLOCKS is not the direct receiver of its proved operation")
            }
            call
        }
        val puts = baseCalls.filter { it.nameAsString == "put" }
        val gets = baseCalls.filter { it.nameAsString == "get" }
        if (puts.size != 4 || gets.size != 1 ||
            baseCalls.any { it.nameAsString !in setOf("put", "get") } ||
            puts.any {
                it.findAncestor(InitializerDeclaration::class.java)
                    .map { initializer -> initializer.isStatic }.orElse(false).not()
            } ||
            gets.single().findAncestor(MethodDeclaration::class.java)
                .map { it.nameAsString == "createEntry" }.orElse(false).not()
        ) {
            abort("BASE_BLOCKS has writes or reads outside the proved closure")
        }
        if (owner.findAll(NameExpr::class.java).any {
                it.nameAsString == "DEFAULT_VARIANTS"
            } ||
            owner.findAll(FieldAccessExpr::class.java).any {
                it.nameAsString == "DEFAULT_VARIANTS"
            }
        ) {
            abort("DEFAULT_VARIANTS is read or mutated inside CopperBlockSet")
        }
        val factoryCalls = owner.findAll(MethodCallExpr::class.java)
            .filter { it.nameAsString == "getFactory" }
        val createEntry = owner.methods.single { it.nameAsString == "createEntry" }
        if (factoryCalls.size != 1 ||
            normalizeJava(factoryCalls.single().scope.map { it.toString() }.orElse("")) !=
            "variant" ||
            factoryCalls.single().findAncestor(MethodDeclaration::class.java).orElse(null) !==
            createEntry
        ) {
            abort("Variant.getFactory has callers outside createEntry")
        }
    }

    private fun admitProjectCallerClosure(
        parser: JavaParser,
        projectDir: Path
    ) {
        val sourceRoot = projectDir.resolve("src/main/java")
        if (!Files.isDirectory(sourceRoot)) {
            abort("Create source root is missing")
        }
        val copperPath = projectDir.resolve(COPPER_RELATIVE).normalize()
        val creations = mutableListOf<Triple<Path, CompilationUnit, ObjectCreationExpr>>()
        val defaultVariantRefs =
            mutableListOf<Pair<CompilationUnit, FieldAccessExpr>>()
        Files.walk(sourceRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.name.endsWith(".java") }
                .forEach { path ->
                    if (path.normalize() == copperPath) return@forEach
                    val source = path.readText()
                    val executable = JavaSourceText.maskCommentsAndLiterals(source)
                    if (!Regex(
                            """\b(?:CopperBlockSet|BASE_BLOCKS|DEFAULT_VARIANTS|""" +
                                """StairVariant|getVariants|getFactory)\b"""
                        ).containsMatchIn(executable)
                    ) {
                        return@forEach
                    }
                    val parsed = parse(parser, source, path)
                    if (parsed.findAll(ClassOrInterfaceDeclaration::class.java).any { type ->
                            type.extendedTypes.any {
                                refersToCopperClass(parsed, it.toString())
                            } ||
                                type.implementedTypes.any {
                                    refersToCopperVariant(parsed, it.toString())
                                }
                        }
                    ) {
                        abort("Project source extends or mutates the proved CopperBlockSet graph")
                    }
                    if (parsed.imports.any { import ->
                            import.isStatic &&
                                (
                                    import.nameAsString ==
                                        "$COPPER_PACKAGE.$COPPER_CLASS" ||
                                        import.nameAsString.startsWith(
                                            "$COPPER_PACKAGE.$COPPER_CLASS."
                                        )
                                    )
                        }
                    ) {
                        abort("Project source statically imports the proved copper graph")
                    }
                    if (parsed.findAll(ObjectCreationExpr::class.java).any {
                            refersToStairVariant(parsed, it.type.toString())
                        } ||
                        parsed.findAll(MethodReferenceExpr::class.java).any {
                            it.identifier == "new" &&
                                refersToCopperClass(parsed, it.scope.toString())
                        }
                    ) {
                        abort("Project source constructs an unproved copper variant or factory")
                    }
                    parsed.findAll(FieldAccessExpr::class.java).forEach fieldAccess@{ access ->
                        if (!refersToCopperClass(parsed, access.scope.toString())) {
                            return@fieldAccess
                        }
                        when (access.nameAsString) {
                            "BASE_BLOCKS" ->
                                abort("Project source reads or mutates CopperBlockSet.BASE_BLOCKS")
                            "DEFAULT_VARIANTS" ->
                                defaultVariantRefs += parsed to access
                        }
                    }
                    val externalVariantNames = mutableSetOf<String>()
                    parsed.findAll(VariableDeclarator::class.java)
                        .filter {
                            refersToCopperVariant(parsed, it.type.toString())
                        }
                        .forEach { externalVariantNames += it.nameAsString }
                    parsed.findAll(Parameter::class.java)
                        .filter {
                            refersToCopperVariant(parsed, it.type.toString())
                        }
                        .forEach { externalVariantNames += it.nameAsString }
                    val copperSetNames = mutableSetOf<String>()
                    parsed.findAll(VariableDeclarator::class.java)
                        .filter {
                            refersToCopperClass(parsed, it.type.toString())
                        }
                        .forEach { copperSetNames += it.nameAsString }
                    parsed.findAll(Parameter::class.java)
                        .filter {
                            refersToCopperClass(parsed, it.type.toString())
                        }
                        .forEach { copperSetNames += it.nameAsString }
                    if (parsed.findAll(MethodCallExpr::class.java).any { call ->
                            call.nameAsString == "getFactory" &&
                                call.scope.map { scope ->
                                    (scope as? NameExpr)?.nameAsString in
                                        externalVariantNames ||
                                        normalizeJava(scope.toString())
                                            .contains("CopperBlockSet.")
                                }.orElse(false)
                        }
                    ) {
                        abort("Project source directly calls a copper Variant factory")
                    }
                    parsed.findAll(MethodCallExpr::class.java)
                        .filter { call ->
                            if (call.nameAsString != "getVariants") return@filter false
                            call.scope.map { scope ->
                                (scope as? NameExpr)?.nameAsString in copperSetNames ||
                                    normalizeJava(scope.toString()).let {
                                        it.endsWith(".COPPER_SHINGLES") ||
                                            it.endsWith(".COPPER_TILES")
                                    }
                            }.orElse(false)
                        }
                        .forEach { call ->
                            val loop = call.parentNode.orElse(null) as? ForEachStmt
                            if (loop == null || loop.iterable !== call) {
                                abort(
                                    "Project source aliases or mutates CopperBlockSet variants"
                                )
                            }
                        }
                    parsed.findAll(ObjectCreationExpr::class.java)
                        .filter {
                            refersToCopperClass(parsed, it.type.toString())
                        }
                        .forEach { creations += Triple(path, parsed, it) }
                }
        }
        if (creations.size != 2) {
            abort("Expected exactly two actual CopperBlockSet registrations")
        }
        if (defaultVariantRefs.size != 2) {
            abort("DEFAULT_VARIANTS has reads or mutations outside two registrations")
        }
        val expectedNames = setOf("COPPER_SHINGLES", "COPPER_TILES")
        val actualNames = creations.map { (_, unit, creation) ->
            if (unit.packageDeclaration.map { it.nameAsString }.orElse("") !=
                "com.simibubi.create" ||
                (unit.types.singleOrNull() as? ClassOrInterfaceDeclaration)
                    ?.nameAsString != "AllBlocks"
            ) {
                abort("CopperBlockSet registration moved outside canonical AllBlocks")
            }
            val variable = creation.findAncestor(VariableDeclarator::class.java).orElse(null)
                ?: abort("CopperBlockSet registration must initialize a declared field")
            val declaration = fieldDeclaration(variable)
            if (normalizeJava(variable.type.toString()) != COPPER_CLASS ||
                declaration.variables.size != 1 ||
                declaration.modifiers.map { it.keyword.asString() }.toSet() !=
                setOf("public", "static", "final")
            ) {
                abort("CopperBlockSet registration is not a canonical public static final field")
            }
            val requiredImports = setOf(
                "$COPPER_PACKAGE.$COPPER_CLASS",
                "com.simibubi.create.content.decoration.RoofBlockCTBehaviour"
            )
            val ordinaryImports = unit.imports
                .filter { !it.isStatic && !it.isAsterisk }
                .map { it.nameAsString }
                .toSet()
            val hasConnectedTexturesImport = unit.imports.any {
                it.isStatic &&
                    it.nameAsString ==
                    "com.simibubi.create.foundation.data.CreateRegistrate.connectedTextures"
            }
            val owner = unit.types.single() as ClassOrInterfaceDeclaration
            if (!ordinaryImports.containsAll(requiredImports) ||
                !hasConnectedTexturesImport ||
                owner.methods.any { it.nameAsString == "connectedTextures" }
            ) {
                abort("AllBlocks copper registration helpers no longer resolve canonically")
            }
            val expectedRegistryName = when (variable.nameAsString) {
                "COPPER_SHINGLES" -> "\"copper_shingles\""
                "COPPER_TILES" -> "\"copper_tiles\""
                else -> abort("CopperBlockSet registration field changed")
            }
            if (creation.arguments.size != 6 ||
                normalizeJava(creation.arguments[0].toString()) != "REGISTRATE" ||
                normalizeJava(creation.arguments[1].toString()) != expectedRegistryName ||
                normalizeJava(creation.arguments[2].toString()) !=
                "\"copper_roof_top\"" ||
                normalizeJava(creation.arguments[3].toString()) !=
                "CopperBlockSet.DEFAULT_VARIANTS" ||
                creation.arguments[4] !is LambdaExpr ||
                (creation.arguments[4] as LambdaExpr).parameters.size != 2
            ) {
                abort("CopperBlockSet registration constructor arguments changed")
            }
            val defaultRef = creation.arguments[3] as? FieldAccessExpr
                ?: abort("CopperBlockSet registration must directly read DEFAULT_VARIANTS")
            if (defaultRef.parentNode.orElse(null) !== creation ||
                defaultVariantRefs.none { (refUnit, ref) ->
                    refUnit === unit && ref === defaultRef
                }
            ) {
                abort("CopperBlockSet registration aliases DEFAULT_VARIANTS")
            }
            val expectedConsumer =
                "(ws,block)->connectedTextures(()->newRoofBlockCTBehaviour(" +
                    "AllSpriteShifts.${variable.nameAsString}.get(ws))).accept(block)"
            if (normalizeJava(creation.arguments[5].toString()) != expectedConsumer) {
                abort("CopperBlockSet onRegister consumer is no longer model-only")
            }
            variable.nameAsString
        }.toSet()
        if (actualNames != expectedNames) {
            abort("CopperBlockSet registrations changed from shingles and tiles")
        }
    }

    private fun refersToCopperClass(unit: CompilationUnit, type: String): Boolean {
        val erased = normalizeJava(type).substringBefore('<')
        if (erased == "$COPPER_PACKAGE.$COPPER_CLASS") return true
        if (erased != COPPER_CLASS) return false
        return unit.packageDeclaration.map { it.nameAsString }.orElse("") ==
            COPPER_PACKAGE ||
            unit.imports.any {
                !it.isStatic && !it.isAsterisk &&
                    it.nameAsString == "$COPPER_PACKAGE.$COPPER_CLASS"
            }
    }

    private fun refersToCopperVariant(unit: CompilationUnit, type: String): Boolean {
        val erased = normalizeJava(type).substringBefore('<')
        val nested = setOf("Variant", "BlockVariant", "SlabVariant", "StairVariant")
        val simple = erased.substringAfterLast('.')
        if (simple !in nested) return false
        if (erased == "$COPPER_PACKAGE.$COPPER_CLASS.$simple") return true
        if (erased == "$COPPER_CLASS.$simple") {
            return refersToCopperClass(unit, COPPER_CLASS)
        }
        if (erased != simple) return false
        return unit.imports.any {
            !it.isStatic && !it.isAsterisk &&
                it.nameAsString == "$COPPER_PACKAGE.$COPPER_CLASS.$simple"
        }
    }

    private fun refersToStairVariant(unit: CompilationUnit, type: String): Boolean {
        val erased = normalizeJava(type).substringBefore('<')
        return when (erased) {
            "$COPPER_PACKAGE.$COPPER_CLASS.StairVariant" -> true
            "$COPPER_CLASS.StairVariant" ->
                refersToCopperClass(unit, COPPER_CLASS)
            "StairVariant" ->
                unit.imports.any {
                    !it.isStatic && !it.isAsterisk &&
                        it.nameAsString ==
                        "$COPPER_PACKAGE.$COPPER_CLASS.StairVariant"
                }
            else -> false
        }
    }

    private fun admitSupportSemantics(parser: JavaParser, projectDir: Path) {
        val tagPath = projectDir.resolve(TAG_GEN_RELATIVE)
        val registratePath = projectDir.resolve(CREATE_REGISTRATE_RELATIVE)
        if (!tagPath.isRegularFile() || !registratePath.isRegularFile()) {
            abort("Copper property or connected-texture support source is missing")
        }

        val tagUnit = parse(parser, tagPath.readText(), tagPath)
        val tagOwner = exactOwner(
            tagUnit,
            "com.simibubi.create.foundation.data",
            "TagGen"
        )
        val tagImports = tagUnit.imports
            .filter { !it.isStatic && !it.isAsterisk }
            .map { it.nameAsString }
            .toSet()
        if (!tagImports.containsAll(
                setOf(
                    "com.tterrag.registrate.builders.BlockBuilder",
                    "com.tterrag.registrate.util.nullness.NonNullFunction",
                    "net.minecraft.tags.BlockTags",
                    "net.minecraft.world.level.block.Block"
                )
            )
        ) {
            abort("TagGen.pickaxeOnly type ownership changed")
        }
        val pickaxeOnly = tagOwner.methods.singleOrNull {
            it.nameAsString == "pickaxeOnly"
        } ?: abort("TagGen must own one pickaxeOnly")
        if (pickaxeOnly.modifiers.map { it.keyword.asString() }.toSet() !=
            setOf("public", "static") ||
            pickaxeOnly.typeParameters.map { normalizeJava(it.toString()) } !=
            listOf("TextendsBlock", "P") ||
            pickaxeOnly.parameters.isNotEmpty() ||
            normalizeJava(pickaxeOnly.type.toString()) !=
            "NonNullFunction<BlockBuilder<T,P>,BlockBuilder<T,P>>" ||
            normalizeJava(pickaxeOnly.body.map { it.toString() }.orElse("")) !=
            "{returnb->b.tag(BlockTags.MINEABLE_WITH_PICKAXE);}"
        ) {
            abort("TagGen.pickaxeOnly is no longer tag-only")
        }

        val registrateUnit = parse(parser, registratePath.readText(), registratePath)
        val registrateOwner = exactOwner(
            registrateUnit,
            "com.simibubi.create.foundation.data",
            "CreateRegistrate"
        )
        val registrateImports = registrateUnit.imports
            .filter { !it.isStatic && !it.isAsterisk }
            .map { it.nameAsString }
            .toSet()
        val requiredRegistrateImports = setOf(
            "java.util.function.Supplier",
            "com.simibubi.create.CreateClient",
            "com.simibubi.create.foundation.block.connected.CTModel",
            "com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour",
            "com.tterrag.registrate.util.nullness.NonNullConsumer",
            "net.minecraft.world.level.block.Block"
        )
        val hasKnownDist =
            "net.minecraftforge.api.distmarker.Dist" in registrateImports ||
                "net.neoforged.api.distmarker.Dist" in registrateImports
        if (!registrateImports.containsAll(requiredRegistrateImports) || !hasKnownDist) {
            abort("CreateRegistrate CT helper type ownership changed")
        }
        val sourceRoot = projectDir.resolve("src/main/java")
        val connectedTextures = registrateOwner.methods.singleOrNull {
            it.nameAsString == "connectedTextures"
        } ?: abort("CreateRegistrate must own one connectedTextures")
        if (connectedTextures.modifiers.map { it.keyword.asString() }.toSet() !=
            setOf("public", "static") ||
            connectedTextures.typeParameters.map { normalizeJava(it.toString()) } !=
            listOf("TextendsBlock") ||
            connectedTextures.parameters.map { normalizeJava(it.type.toString()) } !=
            listOf("Supplier<ConnectedTextureBehaviour>") ||
            normalizeJava(connectedTextures.type.toString()) !=
            "NonNullConsumer<?superT>" ||
            normalizeJava(connectedTextures.body.map { it.toString() }.orElse("")) !=
            "{returnentry->onClient(()->()->registerCTBehviour(entry,behavior));}"
        ) {
            abort("CreateRegistrate.connectedTextures no longer schedules only CT registration")
        }

        val onClient = registrateOwner.methods.singleOrNull {
            it.nameAsString == "onClient"
        } ?: abort("CreateRegistrate must own one onClient")
        if (onClient.modifiers.map { it.keyword.asString() }.toSet() !=
            setOf("protected", "static") ||
            onClient.parameters.map { normalizeJava(it.type.toString()) } !=
            listOf("Supplier<Runnable>") ||
            normalizeJava(onClient.type.toString()) != "void"
        ) {
            abort("CreateRegistrate.onClient execution boundary changed")
        }
        val onClientBody = normalizeJava(onClient.body.map { it.toString() }.orElse(""))
        when (onClientBody) {
            "{DistExecutor.unsafeRunWhenOn(Dist.CLIENT,toRun);}" -> {
                val hasKnownDistExecutor =
                    "net.minecraftforge.fml.DistExecutor" in registrateImports ||
                        "net.neoforged.fml.DistExecutor" in registrateImports ||
                        "com.modporter.generated.create.compat.DistExecutor" in registrateImports
                if (!hasKnownDistExecutor) {
                    abort("CreateRegistrate.onClient DistExecutor ownership changed")
                }
            }

            "{com.modporter.generated.create.compat.DistExecution.runWhenOn(" +
                "Dist.CLIENT,toRun);}" -> {
                requireUnshadowedPackagePrefix(
                    registrateUnit,
                    registrateOwner,
                    onClient,
                    "com"
                )
                requireDistExecutionOwnership(parser, sourceRoot)
            }

            else -> abort("CreateRegistrate.onClient execution boundary changed")
        }

        val registerCt = registrateOwner.methods.singleOrNull {
            it.nameAsString == "registerCTBehviour"
        } ?: abort("CreateRegistrate must own one registerCTBehviour")
        if (registerCt.modifiers.map { it.keyword.asString() }.toSet() !=
            setOf("private", "static") ||
            registerCt.parameters.map { normalizeJava(it.type.toString()) } !=
            listOf("Block", "Supplier<ConnectedTextureBehaviour>") ||
            normalizeJava(registerCt.type.toString()) != "void"
        ) {
            abort("CreateRegistrate CT registration is no longer model-only")
        }
        val catnipServicesBody = normalizeJava(
            """{
                ConnectedTextureBehaviour behavior = behaviorSupplier.get();
                CreateClient.MODEL_SWAPPER.getCustomBlockModels().register(
                    CatnipServices.REGISTRIES.getKeyOrThrow(entry),
                    model -> new CTModel(model, behavior));
            }"""
        )
        val registeredObjectsBody = normalizeJava(
            """{
                ConnectedTextureBehaviour behavior = behaviorSupplier.get();
                CreateClient.MODEL_SWAPPER.getCustomBlockModels().register(
                    RegisteredObjectsHelper.getKeyOrThrow(entry),
                    model -> new CTModel(model, behavior));
            }"""
        )
        val qualifiedRegisteredObjectsBody = normalizeJava(
            """{
                ConnectedTextureBehaviour behavior = behaviorSupplier.get();
                CreateClient.MODEL_SWAPPER.getCustomBlockModels().register(
                    net.createmod.catnip.registry.RegisteredObjectsHelper.getKeyOrThrow(entry),
                    model -> new CTModel(model, behavior));
            }"""
        )
        when (normalizeJava(registerCt.body.map { it.toString() }.orElse(""))) {
            catnipServicesBody -> {
                if ("net.createmod.catnip.platform.CatnipServices" !in registrateImports) {
                    abort("CreateRegistrate CT registry ownership changed")
                }
            }

            registeredObjectsBody -> {
                if ("net.createmod.catnip.registry.RegisteredObjectsHelper" !in
                    registrateImports
                ) {
                    abort("CreateRegistrate CT registry ownership changed")
                }
            }

            qualifiedRegisteredObjectsBody -> {
                requireUnshadowedPackagePrefix(
                    registrateUnit,
                    registrateOwner,
                    registerCt,
                    "net"
                )
                requireNoProjectOwner(
                    parser,
                    sourceRoot,
                    "net.createmod.catnip.registry",
                    "RegisteredObjectsHelper"
                )
            }

            else -> abort("CreateRegistrate CT registration is no longer model-only")
        }
    }

    private fun requireUnshadowedPackagePrefix(
        unit: CompilationUnit,
        owner: ClassOrInterfaceDeclaration,
        method: MethodDeclaration,
        prefix: String
    ) {
        val ownerFieldShadow = owner.fields
            .flatMap { it.variables }
            .any { it.nameAsString == prefix }
        val ownerTypeShadow = owner.members
            .filterIsInstance<ClassOrInterfaceDeclaration>()
            .any { it.nameAsString == prefix }
        val methodValueShadow =
            method.findAll(Parameter::class.java).any { it.nameAsString == prefix } ||
                method.findAll(VariableDeclarator::class.java)
                    .any { it.nameAsString == prefix }
        val importedPrefixShadow = unit.imports.any {
            it.isAsterisk || it.nameAsString.substringAfterLast('.') == prefix
        }
        val topLevelTypeShadow = unit.types.any { it.nameAsString == prefix }
        if (ownerFieldShadow || ownerTypeShadow || methodValueShadow ||
            importedPrefixShadow || topLevelTypeShadow
        ) {
            abort("CreateRegistrate package prefix '$prefix' is value- or type-shadowed")
        }
    }

    private fun requireDistExecutionOwnership(parser: JavaParser, sourceRoot: Path) {
        val expectedPath = sourceRoot.resolve(
            "com/modporter/generated/create/compat/DistExecution.java"
        )
        if (!expectedPath.isRegularFile() ||
            !sameJava(expectedPath.readText(), distExecutionSource())
        ) {
            abort("Generated DistExecution owner or execution semantics changed")
        }
        val expectedOwner = expectedPath.toAbsolutePath().normalize()
        val owners = projectOwners(
            parser,
            sourceRoot,
            "com.modporter.generated.create.compat",
            "DistExecution"
        ).map { it.toAbsolutePath().normalize() }
        if (owners != listOf(expectedOwner)) {
            abort(
                "Generated DistExecution has conflicting project owners: " +
                    owners.joinToString()
            )
        }
    }

    private fun requireNoProjectOwner(
        parser: JavaParser,
        sourceRoot: Path,
        packageName: String,
        className: String
    ) {
        val owners = projectOwners(parser, sourceRoot, packageName, className)
        if (owners.isNotEmpty()) {
            abort("Project source shadows $packageName.$className: ${owners.joinToString()}")
        }
    }

    private fun projectOwners(
        parser: JavaParser,
        sourceRoot: Path,
        packageName: String,
        className: String
    ): List<Path> {
        if (!Files.isDirectory(sourceRoot)) return emptyList()
        val packagePattern = Regex(
            """(?m)^\s*package\s+${Regex.escape(packageName)}\s*;"""
        )
        val owners = mutableListOf<Path>()
        Files.walk(sourceRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.name.endsWith(".java") }
                .forEach { path ->
                    val source = path.readText()
                    val executable = JavaSourceText.maskCommentsAndLiterals(source)
                    if (packagePattern.containsMatchIn(executable)) {
                        val unit = parse(parser, source, path)
                        if (unit.packageDeclaration
                                .map { it.nameAsString }
                                .orElse("") == packageName &&
                            unit.types.any { it.nameAsString == className }
                        ) {
                            owners.add(path)
                        }
                    }
                }
        }
        return owners.sortedBy { it.toString() }
    }

    private fun distExecutionSource(): String = """
        package com.modporter.generated.create.compat;

        import java.util.concurrent.Callable;
        import java.util.function.Supplier;
        import net.neoforged.api.distmarker.Dist;
        import net.neoforged.fml.loading.FMLLoader;

        /** Typed replacement for the remaining Forge DistExecutor expression contracts. */
        public final class DistExecution {
            private DistExecution() {
            }

            public static void runWhenOn(
                Dist dist,
                Supplier<? extends Runnable> target
            ) {
                if (FMLLoader.getDist() == dist) {
                    target.get().run();
                }
            }

            public static <T> T callWhenOn(
                Dist dist,
                Supplier<? extends Callable<T>> target
            ) {
                if (FMLLoader.getDist() != dist) {
                    return null;
                }
                try {
                    return target.get().call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        """.trimIndent() + "\n"

    private fun exactOwner(
        unit: CompilationUnit,
        packageName: String,
        className: String
    ): ClassOrInterfaceDeclaration {
        if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != packageName) {
            abort("$className source path has an unexpected package")
        }
        val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
            ?: abort("$className must contain one top-level class")
        if (owner.nameAsString != className || owner.isInterface) {
            abort("$className source path has an unexpected owner")
        }
        return owner
    }

    private fun admitFactory(unit: CompilationUnit): AdmittedFactory {
        val owner = copperOwner(unit)
        val stairVariant = nestedClass(owner, "StairVariant")
        val method = stairVariant.methods.singleOrNull { it.nameAsString == "getFactory" }
            ?: abort("StairVariant must own exactly one getFactory")
        if (method.parameters.map { simpleType(it.type.toString()) } !=
            listOf("CopperBlockSet", "WeatherState", "boolean") ||
            normalizeJava(method.type.toString()) !=
            "NonNullFunction<Properties,StairBlock>"
        ) {
            abort("StairVariant.getFactory has an unexpected signature")
        }
        val blocks = method.parameters[0].nameAsString
        val state = method.parameters[1].nameAsString
        val waxed = method.parameters[2].nameAsString
        val statements = method.body.orElse(null)?.statements
            ?: abort("StairVariant.getFactory must have a body")
        if (statements.size != 3) {
            abort("StairVariant.getFactory contains statements outside the verified factory")
        }
        val guard = statements[0] as? IfStmt
            ?: abort("StairVariant.getFactory is missing its parent-variant guard")
        admitParentGuard(guard, blocks)

        val declaration = (statements[1] as? ExpressionStmt)
            ?.expression as? VariableDeclarationExpr
            ?: abort("Legacy StairVariant factory is missing its lazy state supplier")
        val supplier = declaration.variables.singleOrNull()
            ?: abort("Legacy StairVariant supplier declaration is ambiguous")
        admitSupplier(supplier, blocks, state, waxed)
        val factory = statements[2] as? IfStmt
            ?: abort("Legacy StairVariant factory is missing its waxed branch")
        val migrated = factory.findAll(ObjectCreationExpr::class.java).any {
            simpleType(it.type.toString()) in setOf(STAIR_CLASS, WEATHERING_STAIR_CLASS)
        }
        val mode = if (migrated) {
            admitMigratedFactory(factory, supplier.nameAsString, state, waxed)
            FactoryMode.MIGRATED
        } else {
            admitLegacyFactory(factory, supplier.nameAsString, state, waxed)
        }
        return AdmittedFactory(
            method,
            guard,
            statements[1],
            supplier.nameAsString,
            state,
            waxed,
            mode
        )
    }

    private fun admitParentGuard(guard: IfStmt, blocks: String) {
        if (normalizeJava(guard.condition.toString()) !=
            "!$blocks.hasVariant(parent)" ||
            guard.elseStmt.isPresent
        ) {
            abort("StairVariant parent-variant guard is outside the verified contract")
        }
        val thrown = statementsOf(guard.thenStmt).singleOrNull() as? ThrowStmt
            ?: abort("StairVariant parent-variant guard must throw directly")
        val exception = thrown.expression as? ObjectCreationExpr
            ?: abort("StairVariant parent-variant guard must construct its exception")
        if (simpleType(exception.type.toString()) != "IllegalStateException" ||
            exception.arguments.size != 1
        ) {
            abort("StairVariant parent-variant guard has an unexpected exception")
        }
    }

    private fun admitSupplier(
        supplier: VariableDeclarator,
        blocks: String,
        state: String,
        waxed: String
    ) {
        if (normalizeJava(supplier.type.toString()) != "Supplier<BlockState>") {
            abort("Legacy StairVariant supplier has an unexpected type")
        }
        val initializer = supplier.initializer.orElse(null) as? LambdaExpr
            ?: abort("Legacy StairVariant supplier must be a lambda")
        val expected =
            "()->$blocks.get(parent,$state,$waxed).getDefaultState()"
        if (normalizeJava(initializer.toString()) != expected) {
            abort("Legacy StairVariant supplier is not the deferred parent default state")
        }
    }

    private fun admitLegacyFactory(
        factory: IfStmt,
        supplier: String,
        state: String,
        waxed: String
    ): FactoryMode {
        if (normalizeJava(factory.condition.toString()) != waxed) {
            abort("Legacy StairVariant waxed condition is unexpected")
        }
        val waxedReturn = statementsOf(factory.thenStmt).singleOrNull() as? ReturnStmt
            ?: abort("Waxed StairVariant branch must return directly")
        val waxedLambda = waxedReturn.expression.orElse(null) as? LambdaExpr
            ?: abort("Waxed StairVariant branch must return a constructor lambda")
        val property = waxedLambda.parameters.singleOrNull()?.nameAsString
            ?: abort("Waxed StairVariant lambda must have one properties parameter")
        val waxedCreation = (waxedLambda.body as? ExpressionStmt)
            ?.expression as? ObjectCreationExpr
            ?: abort("Waxed StairVariant lambda must construct StairBlock")
        if (simpleType(waxedCreation.type.toString()) != "StairBlock" ||
            waxedCreation.arguments.map { normalizeJava(it.toString()) } !=
            listOf(supplier, property)
        ) {
            abort("Waxed StairVariant constructor is outside the lazy supplier contract")
        }

        val weatherReturn = factory.elseStmt.orElse(null)
            ?.let(::statementsOf)
            ?.singleOrNull() as? ReturnStmt
            ?: abort("Weathering StairVariant branch must return directly")
        val weatherLambda = weatherReturn.expression.orElse(null) as? LambdaExpr
            ?: abort("Weathering StairVariant branch must return a constructor lambda")
        val weatherProperty = weatherLambda.parameters.singleOrNull()?.nameAsString
            ?: abort("Weathering StairVariant lambda must have one properties parameter")
        val weatherBody = weatherLambda.body as? BlockStmt
            ?: abort("Weathering StairVariant lambda must have a block body")
        if (weatherBody.statements.size !in 2..3) {
            abort("Weathering StairVariant lambda contains unverified statements")
        }
        val weatherDeclaration = (weatherBody.statements[0] as? ExpressionStmt)
            ?.expression as? VariableDeclarationExpr
            ?: abort("Weathering StairVariant lambda is missing its block declaration")
        val weatherBlock = weatherDeclaration.variables.singleOrNull()
            ?: abort("Weathering StairVariant block declaration is ambiguous")
        val weatherCreation = weatherBlock.initializer.orElse(null) as? ObjectCreationExpr
            ?: abort("Weathering StairVariant block declaration has no constructor")
        if (simpleType(weatherBlock.type.toString()) != "WeatheringCopperStairBlock" ||
            simpleType(weatherCreation.type.toString()) != "WeatheringCopperStairBlock" ||
            weatherCreation.arguments.map { normalizeJava(it.toString()) } != listOf(
                state,
                "Blocks.AIR.defaultBlockState()",
                weatherProperty
            )
        ) {
            abort("Weathering StairVariant constructor is outside the verified contract")
        }
        val returned = weatherBody.statements.last() as? ReturnStmt
            ?: abort("Weathering StairVariant lambda must return its constructed block")
        if ((returned.expression.orElse(null) as? NameExpr)?.nameAsString !=
            weatherBlock.nameAsString
        ) {
            abort("Weathering StairVariant lambda returns an unexpected value")
        }
        if (weatherBody.statements.size == 2) return FactoryMode.LEGACY_STRIPPED

        val reflection = (weatherBody.statements[1] as? ExpressionStmt)
            ?.expression as? MethodCallExpr
            ?: abort("Weathering StairVariant bridge is not an exact reflection call")
        if (reflection.nameAsString != "setPrivateValue" ||
            reflection.scope.map {
                simpleType(it.toString()) == "ObfuscationReflectionHelper"
            }.orElse(false).not() ||
            reflection.arguments.map { normalizeJava(it.toString()) } != listOf(
                "StairBlock.class",
                weatherBlock.nameAsString,
                supplier,
                "\"stateSupplier\""
            )
        ) {
            abort("Weathering StairVariant reflection does not install the lazy supplier")
        }
        return FactoryMode.LEGACY_REFLECTION
    }

    private fun admitMigratedFactory(
        factory: IfStmt,
        supplier: String,
        state: String,
        waxed: String
    ) {
        if (normalizeJava(factory.condition.toString()) != waxed) {
            abort("Migrated StairVariant waxed condition is unexpected")
        }
        val waxedReturn = statementsOf(factory.thenStmt).singleOrNull() as? ReturnStmt
            ?: abort("Migrated waxed StairVariant branch must return directly")
        val waxedLambda = waxedReturn.expression.orElse(null) as? LambdaExpr
            ?: abort("Migrated waxed StairVariant branch must return a lambda")
        val waxedProperty = waxedLambda.parameters.singleOrNull()?.nameAsString
            ?: abort("Migrated waxed factory must have one properties parameter")
        val waxedCreation = (waxedLambda.body as? ExpressionStmt)
            ?.expression as? ObjectCreationExpr
            ?: abort("Migrated waxed factory must construct its supplier-backed stair")
        if (simpleType(waxedCreation.type.toString()) != STAIR_CLASS ||
            waxedCreation.arguments.map { normalizeJava(it.toString()) } !=
            listOf(supplier, waxedProperty)
        ) {
            abort("Migrated waxed StairVariant branch has an unexpected factory")
        }
        val weatherReturn = factory.elseStmt.orElse(null)
            ?.let(::statementsOf)
            ?.singleOrNull() as? ReturnStmt
            ?: abort("Migrated weathering StairVariant branch must return directly")
        val weatherLambda = weatherReturn.expression.orElse(null) as? LambdaExpr
            ?: abort("Migrated weathering StairVariant branch must return a lambda")
        val property = weatherLambda.parameters.singleOrNull()?.nameAsString
            ?: abort("Migrated weathering factory must have one properties parameter")
        val creation = (weatherLambda.body as? ExpressionStmt)
            ?.expression as? ObjectCreationExpr
            ?: abort("Migrated weathering factory must construct its official stair")
        if (simpleType(creation.type.toString()) != WEATHERING_STAIR_CLASS ||
            creation.arguments.map { normalizeJava(it.toString()) } !=
            listOf(state, supplier, property)
        ) {
            abort("Migrated weathering StairVariant branch has an unexpected factory")
        }
    }

    private fun migrateFactory(
        pass: StructuralRefactorPass,
        parser: JavaParser,
        unit: CompilationUnit,
        admitted: AdmittedFactory
    ): String {
        val replacement = parser.parseBlock(
            """
            {
                ${admitted.guard}
                ${admitted.supplierStatement}
                if (${admitted.waxed}) {
                    return p -> new $STAIR_CLASS(${admitted.supplier}, p);
                } else {
                    return p -> new $WEATHERING_STAIR_CLASS(
                        ${admitted.state}, ${admitted.supplier}, p);
                }
            }
            """.trimIndent()
        ).result.orElseThrow()
        LexicalPreservingPrinter.setup(unit)
        admitted.method.setBody(replacement)
        var migrated = LexicalPreservingPrinter.print(unit)
        migrated = removeImportWhenUnused(pass, migrated, WEATHERING_STAIR)
        migrated = removeImportWhenUnused(
            pass,
            migrated,
            "net.minecraftforge.fml.util.ObfuscationReflectionHelper"
        )
        migrated = removeImportWhenUnused(
            pass,
            migrated,
            "net.neoforged.fml.util.ObfuscationReflectionHelper"
        )
        return migrated
    }

    private fun removeImportWhenUnused(
        pass: StructuralRefactorPass,
        source: String,
        importName: String
    ): String {
        val without = pass.removeImport(source, importName)
        val simple = importName.substringAfterLast('.')
        return if (Regex("""\b${Regex.escape(simple)}\b""")
                .containsMatchIn(JavaSourceText.maskCommentsAndLiterals(without))
        ) {
            source
        } else {
            without
        }
    }

    private fun requireGeneratedOwnership(
        sourceRoot: Path,
        expectedPath: Path,
        className: String,
        expectedSource: String
    ) {
        if (expectedPath.exists() && !sameJava(expectedPath.readText(), expectedSource)) {
            abort("Generated copper stair path is owned by incompatible source: $expectedPath")
        }
        if (!Files.isDirectory(sourceRoot)) return
        Files.walk(sourceRoot).use { paths ->
            paths.filter {
                it.isRegularFile() &&
                    it.name == "$className.java" &&
                    it != expectedPath
            }.forEach { path ->
                val source = path.readText()
                if (Regex(
                        """(?m)^\s*package\s+${Regex.escape(COPPER_PACKAGE)}\s*;"""
                    ).containsMatchIn(source)
                ) {
                    abort("Duplicate $COPPER_PACKAGE.$className source exists at $path")
                }
            }
        }
    }

    private fun targetContractIsVerified(): Boolean {
        // TargetMembers verifies the callable ABI only. Promotion remains bound to the
        // separately recorded neoforge-21.1.230 source/javap proof that StairBlock has
        // exactly one behavioral base read plus its codec base-state getter.
        val stairConstructor =
            "(Lnet/minecraft/world/level/block/state/BlockState;$PROPERTIES)V"
        val weatheringConstructor =
            "(Lnet/minecraft/world/level/block/WeatheringCopper\$WeatherState;" +
                "Lnet/minecraft/world/level/block/state/BlockState;$PROPERTIES)V"
        return TargetMembers.inheritedMethod(STAIR, "<init>", stairConstructor)
            ?.let { it.owner == STAIR && it.access == "public" } == true &&
            TargetMembers.inheritedMethod(
                WEATHERING_STAIR,
                "<init>",
                weatheringConstructor
            )?.let {
                it.owner == WEATHERING_STAIR && it.access == "public"
            } == true &&
            TargetMembers.inheritedMethod(STAIR, "codec", MAP_CODEC)
                ?.let { it.owner == STAIR && it.access == "public" } == true &&
            TargetMembers.inheritedMethod(WEATHERING_STAIR, "codec", MAP_CODEC)
                ?.let {
                    it.owner == WEATHERING_STAIR && it.access == "public"
                } == true &&
            TargetMembers.inheritedMethod(STAIR, "getExplosionResistance", "()F")
                ?.let { it.owner == STAIR && it.access == "public" } == true &&
            TargetMembers.inheritedMethod(STAIR, "propertiesCodec", PROPERTIES_CODEC)
                ?.let {
                    it.owner == BLOCK_BEHAVIOUR && it.access == "public"
                } == true &&
            TargetMembers.fieldDescriptor(BLOCK_STATE, "CODEC") == CODEC &&
            TargetMembers.fieldDescriptor(WEATHER_STATE, "CODEC") == CODEC
    }

    private fun renderCopperStair(): String = """
package $COPPER_PACKAGE;

import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

public class $STAIR_CLASS extends StairBlock {
    public static final MapCodec<$STAIR_CLASS> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BlockState.CODEC.fieldOf("base_state").forGetter($STAIR_CLASS::getModelState),
            propertiesCodec()
    ).apply(i, (baseState, properties) ->
            new $STAIR_CLASS(() -> baseState, properties)));

    private final Supplier<BlockState> modelStateSupplier;

    public $STAIR_CLASS(
        Supplier<BlockState> modelStateSupplier,
        Properties properties
    ) {
        super(Blocks.AIR.defaultBlockState(), properties);
        this.modelStateSupplier = modelStateSupplier;
    }

    private BlockState getModelState() {
        return modelStateSupplier.get();
    }

    @Override
    public float getExplosionResistance() {
        return getModelState().getBlock().getExplosionResistance();
    }

    @Override
    public @NotNull MapCodec<? extends StairBlock> codec() {
        return CODEC;
    }
}
""".trimIndent() + "\n"

    private fun renderWeatheringCopperStair(): String = """
package $COPPER_PACKAGE;

import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChangeOverTimeBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.WeatheringCopperStairBlock;
import net.minecraft.world.level.block.state.BlockState;

public class $WEATHERING_STAIR_CLASS extends WeatheringCopperStairBlock {
    public static final MapCodec<WeatheringCopperStairBlock> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            WeatheringCopper.WeatherState.CODEC.fieldOf("weathering_state").forGetter(ChangeOverTimeBlock::getAge),
            BlockState.CODEC.fieldOf("base_state").forGetter(
                    (WeatheringCopperStairBlock block) ->
                            (($WEATHERING_STAIR_CLASS) block).getModelState()),
            propertiesCodec()
    ).apply(i, (weatherState, baseState, properties) ->
            new $WEATHERING_STAIR_CLASS(
                    weatherState, () -> baseState, properties)));

    private final Supplier<BlockState> modelStateSupplier;

    public $WEATHERING_STAIR_CLASS(
        WeatherState weatherState,
        Supplier<BlockState> modelStateSupplier,
        Properties properties
    ) {
        super(weatherState, Blocks.AIR.defaultBlockState(), properties);
        this.modelStateSupplier = modelStateSupplier;
    }

    private BlockState getModelState() {
        return modelStateSupplier.get();
    }

    @Override
    public float getExplosionResistance() {
        return getModelState().getBlock().getExplosionResistance();
    }

    @Override
    public @NotNull MapCodec<WeatheringCopperStairBlock> codec() {
        return CODEC;
    }
}
""".trimIndent() + "\n"

    private fun statementsOf(statement: Statement): List<Statement> =
        (statement as? BlockStmt)?.statements?.toList() ?: listOf(statement)

    private fun simpleType(type: String): String =
        type.replace(Regex("""\s+"""), "").substringAfterLast('.')

    private fun normalizeJava(source: String): String {
        val normalized = StringBuilder(source.length)
        var quote: Char? = null
        var escaped = false
        source.forEach { character ->
            val activeQuote = quote
            if (activeQuote != null) {
                normalized.append(character)
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == activeQuote) {
                    quote = null
                }
            } else if (character == '"' || character == '\'') {
                quote = character
                normalized.append(character)
            } else if (!character.isWhitespace()) {
                normalized.append(character)
            }
        }
        return normalized.toString()
    }

    private fun sameJava(first: String, second: String): Boolean =
        normalizeJava(first) == normalizeJava(second)

    private fun writeFamily(
        outputs: List<PreparedOutput>,
        commitProbe: ((Int, Path) -> Unit)?
    ) {
        if (outputs.isEmpty()) return
        val staged = linkedMapOf<PreparedOutput, Path>()
        val committed = mutableListOf<PreparedOutput>()
        try {
            outputs.forEach { output ->
                output.path.parent.createDirectories()
                val temporary = Files.createTempFile(
                    output.path.parent,
                    ".modporter-copper-stair-",
                    ".tmp"
                )
                Files.writeString(temporary, output.after, StandardCharsets.UTF_8)
                staged[output] = temporary
            }
            staged.entries.forEachIndexed { index, (output, temporary) ->
                commitProbe?.invoke(index, output.path)
                Files.move(temporary, output.path, ATOMIC_MOVE, REPLACE_EXISTING)
                committed += output
            }
        } catch (failure: Exception) {
            committed.asReversed().forEach { output ->
                runCatching {
                    if (output.before == null) {
                        Files.deleteIfExists(output.path)
                    } else {
                        val rollback = Files.createTempFile(
                            output.path.parent,
                            ".modporter-copper-stair-rollback-",
                            ".tmp"
                        )
                        Files.writeString(rollback, output.before, StandardCharsets.UTF_8)
                        Files.move(rollback, output.path, ATOMIC_MOVE, REPLACE_EXISTING)
                    }
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            staged.values.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)

    private enum class FactoryMode {
        LEGACY_REFLECTION,
        LEGACY_STRIPPED,
        MIGRATED
    }

    private data class AdmittedFactory(
        val method: MethodDeclaration,
        val guard: IfStmt,
        val supplierStatement: Statement,
        val supplier: String,
        val state: String,
        val waxed: String,
        val mode: FactoryMode
    )

    private data class PreparedOutput(
        val path: Path,
        val before: String?,
        val after: String,
        val description: String
    )
}
