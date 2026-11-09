package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.ReturnStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.toList

internal class LegacyParticleSerializationMigration {
    data class MigrationOutcome(
        val changes: List<Change>,
        val ownedFiles: Set<Path>
    )

    private data class SourceUnit(
        val file: Path,
        val source: String,
        val compilationUnit: CompilationUnit
    )

    private data class LegacyRoot(
        val declarationName: String,
        val qualifiedName: String
    )

    fun migrate(projectDir: Path, dryRun: Boolean): MigrationOutcome {
        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        val sourceFiles = Files.walk(projectDir)
            .filter { it.extension == "java" }
            .filter { path ->
                val relative = projectDir.relativize(path).toString().replace('\\', '/')
                !relative.startsWith("build/") &&
                    !relative.contains("/build/") &&
                    !relative.startsWith("src/references/") &&
                    !relative.contains("/src/references/") &&
                    !relative.startsWith("src/main/java/com/modporter/generated/")
            }
            .toList()
            .associateWith { it.readText() }

        fun parseUnit(file: Path): SourceUnit {
            val source = sourceFiles.getValue(file)
            val parsed = parser.parse(source)
            if (!parsed.isSuccessful) {
                throw IllegalStateException(
                    "Cannot parse $file while migrating legacy particle serialization: " +
                        parsed.problems.joinToString("; ") { it.verboseMessage }
                )
            }
            return SourceUnit(file, source, parsed.result.orElseThrow())
        }

        val parsedByFile = linkedMapOf<Path, SourceUnit>()
        sourceFiles.filterValues { source ->
            source.contains("interface") &&
                (source.contains("ParticleOptions.Deserializer") ||
                    source.contains("interface Deserializer") && source.contains("getStreamCodec"))
        }.keys.forEach { file -> parsedByFile[file] = parseUnit(file) }
        val roots = parsedByFile.values.flatMap { unit ->
            unit.compilationUnit.findAll(ClassOrInterfaceDeclaration::class.java)
                .filter { declaration ->
                    isLegacyParticleFactoryRoot(unit.compilationUnit, declaration) ||
                        isClosedParticleFactoryRoot(declaration)
                }
                .map { declaration ->
                    val packageName = unit.compilationUnit.packageDeclaration
                        .map { it.nameAsString }
                        .orElse("")
                    LegacyRoot(
                        declaration.nameAsString,
                        if (packageName.isBlank()) declaration.nameAsString else "$packageName.${declaration.nameAsString}"
                    )
                }
        }
        if (roots.isEmpty()) return MigrationOutcome(emptyList(), emptySet())
        if (roots.map { it.declarationName }.toSet().size != roots.size) {
            throw IllegalStateException("Legacy particle factory roots have ambiguous simple names")
        }

        val knownInterfaceNames = collectParticleInterfaceRoots(parsedByFile.values.toList(), roots)
            .keys
            .toMutableSet()
        var discoveredInterface: Boolean
        do {
            discoveredInterface = false
            sourceFiles.forEach { (file, source) ->
                if (file in parsedByFile || !source.contains("interface")) return@forEach
                if (knownInterfaceNames.none { name -> Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(source) }) {
                    return@forEach
                }
                val unit = parseUnit(file)
                parsedByFile[file] = unit
                unit.compilationUnit.findAll(ClassOrInterfaceDeclaration::class.java)
                    .filter { declaration ->
                        declaration.isInterface && declaration.extendedTypes.any { it.nameAsString in knownInterfaceNames }
                    }
                    .forEach { declaration ->
                        if (knownInterfaceNames.add(declaration.nameAsString)) discoveredInterface = true
                    }
            }
        } while (discoveredInterface)

        sourceFiles.forEach { (file, source) ->
            if (file in parsedByFile || (!source.contains("class") && !source.contains("record"))) return@forEach
            if (knownInterfaceNames.any { name -> Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(source) }) {
                parsedByFile[file] = parseUnit(file)
            }
        }
        val units = parsedByFile.values.toList()
        val interfaceRootByName = collectParticleInterfaceRoots(units, roots)
        val changes = mutableListOf<Change>()
        val ownedFiles = linkedSetOf<Path>()
        units.forEach { unit ->
            val before = unit.compilationUnit.toString()
            var changed = false
            var owned = false
            unit.compilationUnit.findAll(ClassOrInterfaceDeclaration::class.java).forEach { declaration ->
                val root = interfaceRootByName[declaration.nameAsString]
                if (declaration.isInterface && root != null) {
                    owned = true
                    changed = migrateFactoryInterface(unit.compilationUnit, declaration, root) || changed
                } else if (!declaration.isInterface) {
                    val implementationRoot = resolveImplementationRoot(declaration, interfaceRootByName)
                    if (implementationRoot != null) {
                        owned = true
                        changed = migrateParticleImplementation(
                            unit.compilationUnit,
                            declaration,
                            implementationRoot,
                            interfaceRootByName
                        ) || changed
                    }
                }
            }

            if (owned) ownedFiles.add(unit.file.toAbsolutePath().normalize())
            if (changed) {
                val migrated = unit.compilationUnit.toString()
                if (migrated != before) {
                    changes += Change(
                        file = unit.file,
                        line = 1,
                        description = "Migrate legacy particle command/network serializers to MapCodec and StreamCodec contracts",
                        before = "ParticleOptions.Deserializer + Codec + writeToNetwork",
                        after = "typed compatibility deserializer + MapCodec + behavior-preserving StreamCodec",
                        confidence = Confidence.HIGH,
                        ruleId = "struct-legacy-particle-serialization"
                    )
                    if (!dryRun) unit.file.writeText(migrated)
                }
            }
        }

        val remaining = if (dryRun) emptyList() else sourceFiles
            .filter { (_, original) -> original.contains("ParticleOptions.Deserializer") }
            .keys
            .filter { file -> file.readText().contains("ParticleOptions.Deserializer") }
        if (remaining.isNotEmpty()) {
            throw IllegalStateException(
                "Legacy ParticleOptions.Deserializer remains outside the closed project factory graph: " +
                    remaining.joinToString { projectDir.relativize(it).toString() }
            )
        }
        return MigrationOutcome(changes, ownedFiles)
    }

    private fun isClosedParticleFactoryRoot(declaration: ClassOrInterfaceDeclaration): Boolean {
        if (!declaration.isInterface) return false
        val hasNestedDeserializer = declaration.members.any { member ->
            member.isClassOrInterfaceDeclaration &&
                member.asClassOrInterfaceDeclaration().isInterface &&
                member.asClassOrInterfaceDeclaration().nameAsString == "Deserializer"
        }
        return hasNestedDeserializer &&
            declaration.methods.any { it.nameAsString == "getStreamCodec" } &&
            declaration.methods.any { it.nameAsString == "writeToNetwork" } &&
            declaration.methods.any { it.nameAsString == "createType" && it.toString().contains("streamCodec()") }
    }

    private fun isLegacyParticleFactoryRoot(
        compilationUnit: CompilationUnit,
        declaration: ClassOrInterfaceDeclaration
    ): Boolean {
        if (!declaration.isInterface) return false
        val importsLegacyDeserializer = compilationUnit.imports.any {
            it.nameAsString == "net.minecraft.core.particles.ParticleOptions.Deserializer"
        }
        val hasLegacyDeserializerType = declaration.methods.any { method ->
            method.nameAsString == "getDeserializer" &&
                (method.typeAsString.contains("Deserializer") || method.toString().contains("ParticleOptions.Deserializer"))
        }
        val hasCodecFactory = declaration.methods.any { method ->
            method.nameAsString == "getCodec" && method.typeAsString.contains("Codec")
        }
        val hasLegacyParticleTypeFactory = declaration.methods.any { method ->
            method.nameAsString == "createType" && method.toString().contains("new ParticleType<>(false,")
        }
        return (importsLegacyDeserializer || declaration.toString().contains("ParticleOptions.Deserializer")) &&
            hasLegacyDeserializerType && hasCodecFactory && hasLegacyParticleTypeFactory
    }

    private fun collectParticleInterfaceRoots(
        units: List<SourceUnit>,
        roots: List<LegacyRoot>
    ): Map<String, LegacyRoot> {
        val rootByInterface = roots.associateBy { it.declarationName }.toMutableMap()
        var changed: Boolean
        do {
            changed = false
            units.forEach { unit ->
                unit.compilationUnit.findAll(ClassOrInterfaceDeclaration::class.java)
                    .filter { it.isInterface && it.nameAsString !in rootByInterface }
                    .forEach { declaration ->
                        val inheritedRoots = declaration.extendedTypes
                            .mapNotNull { rootByInterface[it.nameAsString] }
                            .distinct()
                        if (inheritedRoots.size > 1) {
                            throw IllegalStateException(
                                "Particle interface ${declaration.nameAsString} extends multiple legacy factory roots"
                            )
                        }
                        inheritedRoots.singleOrNull()?.let { root ->
                            rootByInterface[declaration.nameAsString] = root
                            changed = true
                        }
                    }
            }
        } while (changed)
        return rootByInterface
    }

    private fun resolveImplementationRoot(
        declaration: ClassOrInterfaceDeclaration,
        interfaceRootByName: Map<String, LegacyRoot>
    ): LegacyRoot? {
        val roots = declaration.implementedTypes
            .mapNotNull { interfaceRootByName[it.nameAsString] }
            .distinct()
        if (roots.size > 1) {
            throw IllegalStateException(
                "Particle implementation ${declaration.nameAsString} implements multiple legacy factory roots"
            )
        }
        return roots.singleOrNull()
    }

    private fun migrateFactoryInterface(
        compilationUnit: CompilationUnit,
        declaration: ClassOrInterfaceDeclaration,
        root: LegacyRoot
    ): Boolean {
        val typeParameter = declaration.typeParameters.singleOrNull()?.nameAsString
            ?: throw IllegalStateException(
                "Legacy particle factory interface ${declaration.nameAsString} must have exactly one type parameter"
            )
        var changed = false
        declaration.methods.filter { it.nameAsString == "getDeserializer" }.forEach { method ->
            method.setType("${root.declarationName}.Deserializer<$typeParameter>")
            changed = true
        }
        declaration.methods.filter { it.nameAsString == "getCodec" }.forEach { method ->
            method.setType("MapCodec<$typeParameter>")
            changed = true
        }
        declaration.methods.filter { it.nameAsString == "createType" }.forEach { method ->
            method.setBody(StaticJavaParser.parseBlock(createTypeBody(declaration.nameAsString, typeParameter)))
            changed = true
        }

        if (declaration.nameAsString == root.declarationName) {
            if (declaration.members.none {
                    it.isClassOrInterfaceDeclaration && it.asClassOrInterfaceDeclaration().nameAsString == "Deserializer"
                }) {
                declaration.addMember(StaticJavaParser.parseBodyDeclaration("""
                    interface Deserializer<P extends ParticleOptions> {
                        P fromCommand(ParticleType<P> type, StringReader reader) throws CommandSyntaxException;
                        P fromNetwork(ParticleType<P> type, RegistryFriendlyByteBuf buffer);
                    }
                """.trimIndent()))
                changed = true
            }
            if (declaration.methods.none { it.nameAsString == "getStreamCodec" }) {
                declaration.addMember(StaticJavaParser.parseBodyDeclaration("""
                    @SuppressWarnings("unchecked")
                    default StreamCodec<? super RegistryFriendlyByteBuf, $typeParameter> getStreamCodec(ParticleType<$typeParameter> type) {
                        return StreamCodec.ofMember(
                            (value, buffer) -> ((${root.declarationName}<$typeParameter>) value).writeToNetwork(buffer),
                            buffer -> getDeserializer().fromNetwork(type, buffer)
                        );
                    }
                """.trimIndent()))
                changed = true
            }
            if (declaration.methods.none { it.nameAsString == "writeToNetwork" }) {
                declaration.addMethod("writeToNetwork").apply {
                    setType("void")
                    addParameter("RegistryFriendlyByteBuf", "buffer")
                    removeBody()
                }
                changed = true
            }
            if (declaration.methods.none { it.nameAsString == "writeToString" }) {
                declaration.addMethod("writeToString").apply {
                    setType("String")
                    removeBody()
                }
                changed = true
            }
        }

        if (changed) {
            addParticleSerializationImports(compilationUnit)
            compilationUnit.imports.removeIf {
                it.nameAsString == "net.minecraft.core.particles.ParticleOptions.Deserializer"
            }
        }
        return changed
    }

    private fun createTypeBody(interfaceName: String, typeParameter: String): String = """
        {
            return new ParticleType<>(false) {
                @Override
                public MapCodec<$typeParameter> codec() {
                    return $interfaceName.this.getCodec(this);
                }

                @Override
                public StreamCodec<? super RegistryFriendlyByteBuf, $typeParameter> streamCodec() {
                    return $interfaceName.this.getStreamCodec(this);
                }
            };
        }
    """.trimIndent()

    private fun migrateParticleImplementation(
        compilationUnit: CompilationUnit,
        declaration: ClassOrInterfaceDeclaration,
        root: LegacyRoot,
        interfaceRootByName: Map<String, LegacyRoot>
    ): Boolean {
        val implementedFactory = declaration.implementedTypes.firstOrNull { type ->
            interfaceRootByName[type.nameAsString] == root
        } ?: return false
        val particleType = implementedFactory.typeArguments.orElse(null)
            ?.singleOrNull()
            ?.toString()
            ?: throw IllegalStateException(
                "Legacy particle implementation ${declaration.nameAsString} must close its factory generic"
            )
        if (!particleType.startsWith(declaration.nameAsString)) {
            throw IllegalStateException(
                "Legacy particle implementation ${declaration.nameAsString} uses external data type $particleType; " +
                    "a typed network encoder cannot be derived from its instance writeToNetwork method"
            )
        }
        if (declaration.methods.none { it.nameAsString == "writeToNetwork" }) {
            throw IllegalStateException(
                "Legacy particle implementation ${declaration.nameAsString} has no writeToNetwork encoder"
            )
        }

        var changed = false
        val importedLegacyDeserializer = compilationUnit.imports.any {
            it.nameAsString == "net.minecraft.core.particles.ParticleOptions.Deserializer"
        }
        declaration.findAll(ClassOrInterfaceType::class.java).forEach { type ->
            if (type.nameWithScope == "ParticleOptions.Deserializer" ||
                (importedLegacyDeserializer && type.nameAsString == "Deserializer")) {
                type.removeScope()
                type.setName("Deserializer")
                changed = true
            }
        }
        declaration.methods.filter { it.nameAsString == "getDeserializer" }.forEach { method ->
            method.setType("Deserializer<$particleType>")
            changed = true
        }

        declaration.methods.filter { it.nameAsString == "getCodec" }.forEach { method ->
            method.setType("MapCodec<$particleType>")
            migrateCodecReturns(declaration, method)
            changed = true
        }
        declaration.findAll(MethodDeclaration::class.java)
            .filter { method -> method.nameAsString in setOf("fromNetwork", "writeToNetwork") }
            .forEach { method ->
                method.parameters
                    .filter { parameter -> parameter.typeAsString.endsWith("FriendlyByteBuf") }
                    .forEach { parameter ->
                        parameter.setType("RegistryFriendlyByteBuf")
                        changed = true
                    }
                val bufferNames = method.parameters
                    .filter { it.typeAsString.endsWith("RegistryFriendlyByteBuf") }
                    .map { it.nameAsString }
                    .toSet()
                method.findAll(MethodCallExpr::class.java).toList().forEach { call ->
                    val receiver = call.scope.map { it.toString() }.orElse("")
                    if (receiver !in bufferNames) return@forEach
                    when {
                        call.nameAsString == "readFluidStack" && call.arguments.isEmpty() -> {
                            call.replace(
                                StaticJavaParser.parseExpression<MethodCallExpr>(
                                    "FluidStack.STREAM_CODEC.decode($receiver)"
                                )
                            )
                            changed = true
                        }
                        call.nameAsString == "writeFluidStack" && call.arguments.size == 1 -> {
                            call.replace(
                                StaticJavaParser.parseExpression<MethodCallExpr>(
                                    "FluidStack.STREAM_CODEC.encode($receiver, ${call.arguments[0]})"
                                )
                            )
                            changed = true
                        }
                    }
                }
            }
        if (changed) {
            addParticleSerializationImports(compilationUnit)
            compilationUnit.addImport("${root.qualifiedName}.Deserializer")
            compilationUnit.imports.removeIf {
                it.nameAsString == "net.minecraft.core.particles.ParticleOptions.Deserializer"
            }
        }
        return changed
    }

    private fun migrateCodecReturns(
        declaration: ClassOrInterfaceDeclaration,
        method: MethodDeclaration
    ) {
        method.findAll(ReturnStmt::class.java).forEach { returnStatement ->
            val expression = returnStatement.expression.orElse(null) ?: return@forEach
            when (expression) {
                is MethodCallExpr -> {
                    if (expression.nameAsString != "unit" || expression.scope.map { it.toString() }.orElse("") != "Codec") {
                        throw IllegalStateException(
                            "Cannot derive MapCodec for ${declaration.nameAsString}.${method.nameAsString} return '$expression'"
                        )
                    }
                    expression.setScope(NameExpr("MapCodec"))
                }
                is NameExpr -> migrateCodecField(declaration, expression.nameAsString)
                is FieldAccessExpr -> migrateCodecField(declaration, expression.nameAsString)
                else -> throw IllegalStateException(
                    "Cannot derive MapCodec for ${declaration.nameAsString}.${method.nameAsString} return '$expression'"
                )
            }
        }
    }

    private fun migrateCodecField(declaration: ClassOrInterfaceDeclaration, fieldName: String) {
        val variable = declaration.fields
            .flatMap { it.variables }
            .singleOrNull { it.nameAsString == fieldName }
            ?: throw IllegalStateException(
                "Cannot resolve particle codec field ${declaration.nameAsString}.$fieldName"
            )
        if (variable.typeAsString.startsWith("MapCodec<")) return
        if (!variable.typeAsString.startsWith("Codec<")) {
            throw IllegalStateException(
                "Particle codec field ${declaration.nameAsString}.$fieldName has unsupported type ${variable.typeAsString}"
            )
        }
        val initializer = variable.initializer.orElse(null) as? MethodCallExpr
            ?: throw IllegalStateException(
                "Particle codec field ${declaration.nameAsString}.$fieldName has no structural codec factory"
            )
        when {
            initializer.nameAsString == "create" && initializer.scope.map { it.toString() }.orElse("") == "RecordCodecBuilder" -> {
                initializer.setName("mapCodec")
            }
            initializer.nameAsString == "unit" && initializer.scope.map { it.toString() }.orElse("") == "Codec" -> {
                initializer.setScope(NameExpr("MapCodec"))
            }
            else -> throw IllegalStateException(
                "Particle codec field ${declaration.nameAsString}.$fieldName uses unsupported factory '$initializer'"
            )
        }
        variable.setType("MapCodec<${variable.type.asClassOrInterfaceType().typeArguments.orElseThrow()[0]}>")
    }

    private fun addParticleSerializationImports(compilationUnit: CompilationUnit) {
        compilationUnit.addImport("com.mojang.brigadier.StringReader")
        compilationUnit.addImport("com.mojang.brigadier.exceptions.CommandSyntaxException")
        compilationUnit.addImport("com.mojang.serialization.MapCodec")
        compilationUnit.addImport("net.minecraft.network.RegistryFriendlyByteBuf")
        compilationUnit.addImport("net.minecraft.network.codec.StreamCodec")
    }
}
