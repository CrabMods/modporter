package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.modporter.core.pipeline.Change
import com.modporter.core.pipeline.Confidence
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Restores Create's native 1.21 registry-aware flap-display component boundary.
 *
 * The generic component migration cannot see a registry source at the original provider-less
 * write call, so it deliberately falls back to ComponentSerialization.CODEC. Later provider
 * propagation gives this exact Create class a HolderLookup.Provider. Native Create uses that
 * provider for JSON serialization. The update boundary additionally preserves the old Gson
 * contract: missing, empty, whitespace-only, and JSON-null Text values clear the component,
 * while malformed non-null JSON still fails instead of being silently accepted.
 */
internal object CreateFlapDisplaySerializationMigration {
    const val RULE_ID = "struct-create-native-flap-display-components"

    private const val RELATIVE =
        "src/main/java/com/simibubi/create/content/trains/display/FlapDisplaySection.java"
    private const val PACKAGE_NAME =
        "com.simibubi.create.content.trains.display"
    private const val CLASS_NAME = "FlapDisplaySection"
    private const val LEGACY_WRITE =
        "net.minecraft.network.chat.ComponentSerialization.CODEC" +
            ".encodeStart(com.mojang.serialization.JsonOps.INSTANCE, component)" +
            ".getOrThrow().toString()"
    private fun targetWrite(write: MethodDeclaration): String =
        "Component.Serializer.toJson(component, ${providerName(write)})"

    /** Seeds the existing closed provider graph before a provider-less JSON fallback hides demand. */
    internal fun providerDemandCall(
        method: MethodDeclaration,
        index: JavaProjectTypeIndex
    ): MethodCallExpr? {
        val owner = method.findAncestor(ClassOrInterfaceDeclaration::class.java).orElse(null)
            ?: return null
        if (owner.fullyQualifiedName.orElse("") != "$PACKAGE_NAME.$CLASS_NAME" ||
            method.nameAsString != "write" || method.parameters.isNotEmpty() ||
            index.declaredType(method.type, method) != "net.minecraft.nbt.CompoundTag" ||
            index.hasSourceTypeDefinition("net.minecraft.nbt.CompoundTag") ||
            index.hasSourceTypeDefinition("net.minecraft.network.chat.Component")
        ) return null
        val component = owner.fields.flatMap { it.variables }
            .singleOrNull { it.nameAsString == "component" } ?: return null
        if (index.declaredType(component.type, component) !=
            "net.minecraft.network.chat.Component"
        ) return null
        val writes = method.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == "putString" && it.arguments.size == 2 &&
                (it.arguments[0] as? StringLiteralExpr)?.value == "Text" &&
                it.findAncestor(MethodDeclaration::class.java).orElse(null) === method
        }
        val tagWrite = writes.singleOrNull() ?: return null
        val tag = tagWrite.scope.orElse(null) ?: return null
        if (!index.isExpressionExactlyExternalType(tag, tagWrite, "net.minecraft.nbt.CompoundTag")) return null
        val value = tagWrite.arguments[1] as? MethodCallExpr ?: return null
        if (normalize(value.toString()) !in setOf(
                normalize(LEGACY_WRITE), "Component.Serializer.toJson(component)"
            )
        ) return null
        val reference = value.findAll(NameExpr::class.java)
            .singleOrNull { it.nameAsString == "component" } ?: return null
        return value.takeIf { index.isExactInstanceFieldReference(reference) }
    }

    data class Result(val changes: List<Change>, val gates: List<String>) {
        companion object {
            val EMPTY = Result(emptyList(), emptyList())
        }
    }

    fun migrate(projectDir: Path, dryRun: Boolean): Result = try {
        migrateChecked(projectDir, dryRun)
    } catch (abort: AdmissionAbort) {
        Result(emptyList(), listOf(abort.message ?: "Create flap-display admission failed"))
    } catch (failure: Exception) {
        Result(
            emptyList(),
            listOf(
                "Create flap-display migration failed before commit: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        )
    }

    private fun migrateChecked(projectDir: Path, dryRun: Boolean): Result {
        val sourceFile = projectDir.resolve(RELATIVE)
        if (!sourceFile.isRegularFile()) return Result.EMPTY

        val parser = JavaParser(
            ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        )
        val before = sourceFile.readText()
        val unit = parse(parser, before, sourceFile)
        val owner = requireOwner(unit)
        val write = requireMethod(owner, "write", "CompoundTag", listOf("HolderLookup.Provider"))
        val load = requireMethod(
            owner,
            "load",
            "FlapDisplaySection",
            listOf("CompoundTag", "HolderLookup.Provider")
        )
        val update = requireMethod(
            owner,
            "update",
            "void",
            listOf("CompoundTag", "HolderLookup.Provider")
        )
        requireNativeLoadBoundary(load)

        val writeMode = classifyWrite(write)
        val updateMode = classifyUpdate(update)
        if (writeMode == Mode.TARGET && updateMode == Mode.TARGET) return Result.EMPTY
        if (writeMode != Mode.LEGACY || updateMode != Mode.LEGACY) {
            abort(
                "Create flap-display component family is partial or incompatible: " +
                    "write=$writeMode, update=$updateMode"
            )
        }

        LexicalPreservingPrinter.setup(unit)
        migrateWrite(write)
        migrateUpdate(update)
        val after = LexicalPreservingPrinter.print(unit)

        val verified = parse(parser, after, sourceFile)
        val verifiedOwner = requireOwner(verified)
        val verifiedWrite =
            requireMethod(verifiedOwner, "write", "CompoundTag", listOf("HolderLookup.Provider"))
        val verifiedLoad = requireMethod(
            verifiedOwner,
            "load",
            "FlapDisplaySection",
            listOf("CompoundTag", "HolderLookup.Provider")
        )
        val verifiedUpdate = requireMethod(
            verifiedOwner,
            "update",
            "void",
            listOf("CompoundTag", "HolderLookup.Provider")
        )
        requireNativeLoadBoundary(verifiedLoad)
        if (
            classifyWrite(verifiedWrite) != Mode.TARGET ||
            classifyUpdate(verifiedUpdate) != Mode.TARGET
        ) {
            abort("Create flap-display component family did not reach native 1.21 semantics")
        }

        if (!dryRun) atomicWrite(sourceFile, after)
        return Result(
            listOf(
                Change(
                    file = sourceFile,
                    line = 1,
                    description =
                        "Use Create's native registry-aware flap-display component serialization",
                    before = before.take(160),
                    after = after.take(160),
                    confidence = Confidence.HIGH,
                    ruleId = RULE_ID
                )
            ),
            emptyList()
        )
    }

    private fun requireOwner(unit: CompilationUnit): ClassOrInterfaceDeclaration {
        if (unit.packageDeclaration.map { it.nameAsString }.orElse("") != PACKAGE_NAME) {
            abort("$CLASS_NAME source has an unexpected package")
        }
        val owner = unit.types.singleOrNull() as? ClassOrInterfaceDeclaration
            ?: abort("$CLASS_NAME source must contain one top-level class")
        if (owner.nameAsString != CLASS_NAME || owner.isInterface) {
            abort("$CLASS_NAME source owns an unexpected top-level type")
        }
        return owner
    }

    private fun requireMethod(
        owner: ClassOrInterfaceDeclaration,
        name: String,
        returnType: String,
        parameterTypes: List<String>
    ): MethodDeclaration {
        val candidates = owner.methods.filter { it.nameAsString == name }
        if (candidates.size != 1) abort("$CLASS_NAME must own one exact $name method")
        val method = candidates.single()
        if (
            method.typeAsString != returnType ||
            method.parameters.map { normalize(it.typeAsString) } !=
            parameterTypes.map(::normalize) ||
            method.body.isEmpty
        ) {
            abort("$CLASS_NAME.$name has an incompatible declaration")
        }
        return method
    }

    private fun requireNativeLoadBoundary(load: MethodDeclaration) {
        val body = load.body.orElseThrow()
        val normalized = normalize(body.toString())
        val guard = "if(!tag.contains(\"Text\"))returnsection;"
        val read =
            "section.component=Component.Serializer.fromJson(tag.getString(\"Text\"),${providerName(load)});"
        if (!normalized.contains(guard) || !normalized.contains(read)) {
            abort("$CLASS_NAME.load is not the expected provider-aware native boundary")
        }
        val componentReads = body.findAll(MethodCallExpr::class.java).count {
            it.nameAsString == "fromJson"
        }
        if (componentReads != 1) {
            abort("$CLASS_NAME.load has an unexpected component parser graph")
        }
    }

    private fun classifyWrite(write: MethodDeclaration): Mode {
        val calls = write.findAll(MethodCallExpr::class.java).filter {
            it.nameAsString == "putString" &&
                it.arguments.size == 2 &&
                (it.arguments[0] as? StringLiteralExpr)?.value == "Text"
        }
        if (calls.size != 1) return Mode.OTHER
        return when (normalize(calls.single().arguments[1].toString())) {
            normalize(LEGACY_WRITE) -> Mode.LEGACY
            normalize(targetWrite(write)) -> Mode.TARGET
            else -> Mode.OTHER
        }
    }

    private fun classifyUpdate(update: MethodDeclaration): Mode {
        val provider = providerName(update)
        val body = update.body.orElse(null) ?: return Mode.OTHER
        val statements = body.statements.map { normalize(it.toString()) }
        val legacy = listOf(
            "component=Component.Serializer.fromJson(tag.getString(\"Text\"),$provider);",
            "if(cyclingOptions==null)cyclingOptions=getFlapCycle(cycle);",
            "refresh(tag.getBoolean(\"Transition\"));"
        )
        val target = listOf(
            "Stringtext=tag.getString(\"Text\");",
            "StringtrimmedText=text.trim();",
            "component=trimmedText.isEmpty()||trimmedText.equals(\"null\")?null:" +
                "Component.Serializer.fromJson(text,$provider);",
            "if(cyclingOptions==null)cyclingOptions=getFlapCycle(cycle);",
            "refresh(tag.getBoolean(\"Transition\"));"
        )
        return when (statements) {
            legacy.map(::normalize) -> Mode.LEGACY
            target.map(::normalize) -> Mode.TARGET
            else -> Mode.OTHER
        }
    }

    private fun migrateWrite(write: MethodDeclaration) {
        if (classifyWrite(write) != Mode.LEGACY) {
            abort("$CLASS_NAME.write changed after admission")
        }
        val call = write.findAll(MethodCallExpr::class.java).single {
            it.nameAsString == "putString" &&
                it.arguments.size == 2 &&
                (it.arguments[0] as? StringLiteralExpr)?.value == "Text"
        }
        call.setArgument(1, StaticJavaParser.parseExpression(targetWrite(write)))
    }

    private fun migrateUpdate(update: MethodDeclaration) {
        if (classifyUpdate(update) != Mode.LEGACY) {
            abort("$CLASS_NAME.update changed after admission")
        }
        val body: BlockStmt = update.body.orElseThrow()
        body.setStatement(
            0,
            StaticJavaParser.parseStatement("""String text = tag.getString("Text");""")
        )
        body.addStatement(
            1,
            StaticJavaParser.parseStatement("String trimmedText = text.trim();")
        )
        body.addStatement(
            2,
            StaticJavaParser.parseStatement(
                "component = trimmedText.isEmpty() || trimmedText.equals(\"null\") " +
                    "? null : Component.Serializer.fromJson(text, ${providerName(update)});"
            )
        )
    }

    private fun parse(
        parser: JavaParser,
        source: String,
        path: Path
    ): CompilationUnit {
        val result = parser.parse(source)
        if (!result.isSuccessful || result.result.isEmpty) {
            abort(
                "Cannot parse $path: " +
                    result.problems.joinToString("; ") { it.verboseMessage }
                        .ifBlank { "unknown parse failure" }
            )
        }
        return result.result.orElseThrow()
    }

    private fun normalize(source: String): String =
        source.replace(Regex("""\s+"""), "")

    private fun providerName(method: MethodDeclaration): String =
        method.parameters.singleOrNull {
            normalize(it.typeAsString) == "HolderLookup.Provider"
        }?.nameAsString ?: abort("$CLASS_NAME.${method.nameAsString} has no unique provider")

    private fun atomicWrite(path: Path, source: String) {
        val temporary =
            Files.createTempFile(path.parent, ".modporter-create-flap-display-", ".java")
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8)
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun abort(message: String): Nothing = throw AdmissionAbort(message)

    private class AdmissionAbort(message: String) : RuntimeException(message)

    private enum class Mode {
        LEGACY,
        TARGET,
        OTHER
    }
}
