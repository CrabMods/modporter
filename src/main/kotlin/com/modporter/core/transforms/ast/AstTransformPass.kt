package com.modporter.core.transforms.ast

import com.modporter.core.pipeline.*
import com.modporter.mapping.MappingDatabase
import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.visitor.ModifierVisitor
import com.github.javaparser.ast.visitor.Visitable
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

private val logger = KotlinLogging.logger {}

/**
 * Pass 2: AST-based transformations using JavaParser.
 * Handles structural code changes that require understanding Java syntax.
 */
class AstTransformPass(
    private val mappingDb: MappingDatabase
) : Pass {
    override val name = "AST Transform"
    override val order = 2

    override fun analyze(projectDir: Path): PassResult = processFiles(projectDir, dryRun = true)
    override fun apply(projectDir: Path): PassResult = processFiles(projectDir, dryRun = false)

    private fun processFiles(projectDir: Path, dryRun: Boolean): PassResult {
        val changes = mutableListOf<Change>()
        val errors = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))

        val javaFiles = Files.walk(projectDir)
            .filter { it.extension == "java" }
            .filter { !projectDir.relativize(it).toString().replace('\\', '/').let { rel ->
                rel.startsWith("build/") || rel.contains("/build/")
            }}
            .toList()

        for (file in javaFiles) {
            try {
                val result = processFile(file, parser, dryRun, skipped)
                changes.addAll(result)
            } catch (e: Exception) {
                errors.add("AST parse error in ${file}: ${e.message}")
                logger.error(e) { "Failed to parse $file" }
            }
        }

        return PassResult(name, changes, errors, skipped)
    }

    private fun processFile(file: Path, parser: JavaParser, dryRun: Boolean, skipped: MutableList<String>): List<Change> {
        val source = file.readText()
        val parseResult = parser.parse(source)
        if (!parseResult.isSuccessful) {
            val problems = parseResult.problems.joinToString("; ") { it.verboseMessage }.ifBlank { "unknown parse failure" }
            val languageLevel = parser.parserConfiguration.languageLevel
            skipped.add("Could not parse $file for AST transforms (languageLevel=$languageLevel): $problems")
            logger.warn { "Could not parse $file for AST transforms (languageLevel=$languageLevel): $problems" }
            return emptyList()
        }

        val cu = parseResult.result.orElse(null) ?: return emptyList()
        val changes = mutableListOf<Change>()

        // Apply all AST transformations
        val transformers = listOf(
            ResourceLocationTransformer(file, changes),
            ModEntryPointTransformer(file, changes),
            CancelableAnnotationTransformer(file, changes),
            EventBusSubscriberTransformer(file, changes),
            EndVertexRemover(file, changes),
            WidgetRenderRenameTransformer(file, changes),
            SelectionListConstructorTransformer(file, changes)
        )

        for (transformer in transformers) {
            transformer.visit(cu, null)
        }

        val staleImportsRemoved = removeStaleImports(cu)

        // Add missing imports for types introduced by AST transforms
        if (changes.isNotEmpty()) {
            addMissingImports(cu)
        }

        if (staleImportsRemoved) {
            changes.add(Change(
                file = file,
                line = 1,
                description = "Remove stale Forge/NeoForge imports for APIs removed in 1.21",
                before = "stale removed API imports",
                after = "imports removed",
                confidence = Confidence.HIGH,
                ruleId = "ast-stale-imports"
            ))
        }

        if (!dryRun && changes.isNotEmpty()) {
            file.writeText(cu.toString())
        }

        return changes
    }

    /**
     * Add imports for types introduced by AST transformations.
     */
    private fun addMissingImports(cu: CompilationUnit) {
        val existingImports = cu.imports.map { it.nameAsString }.toSet()
        val sourceCode = cu.toString()

        val neededImports = mapOf(
            "net.neoforged.fml.ModContainer" to "ModContainer",
            "net.neoforged.fml.common.EventBusSubscriber" to "@EventBusSubscriber",
            "net.neoforged.bus.api.ICancellableEvent" to "ICancellableEvent",
        )

        for ((importFqn, marker) in neededImports) {
            if (sourceCode.contains(marker) && importFqn !in existingImports) {
                cu.addImport(importFqn)
            }
        }

    }

    private fun removeStaleImports(cu: CompilationUnit): Boolean {
        val fmlLoadingContext = "net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext"
        val loadingContextStillUsed =
            cu.findAll(NameExpr::class.java).any { it.nameAsString == "FMLJavaModLoadingContext" } ||
                cu.findAll(ClassOrInterfaceType::class.java).any {
                    it.nameAsString == "FMLJavaModLoadingContext"
                }
        val staleImports = listOf(
            fmlLoadingContext,
            "net.minecraftforge.eventbus.api.Cancelable",
            "net.neoforged.bus.api.Cancelable",
        )
        return cu.imports.removeIf {
            it.nameAsString in staleImports &&
                (it.nameAsString != fmlLoadingContext || !loadingContextStillUsed)
        }
    }
}

/**
 * Transforms `new ResourceLocation(ns, path)` -> `ResourceLocation.fromNamespaceAndPath(ns, path)`
 * and `new ResourceLocation(str)` -> `ResourceLocation.parse(str)`
 */
class ResourceLocationTransformer(
    private val file: Path,
    private val changes: MutableList<Change>
) : ModifierVisitor<Void>() {

    override fun visit(n: ObjectCreationExpr, arg: Void?): Visitable {
        if (n.typeAsString == "ResourceLocation") {
            val line = n.begin.map { it.line }.orElse(0)
            val args = n.arguments

            when (args.size) {
                2 -> {
                    // new ResourceLocation(ns, path) -> ResourceLocation.fromNamespaceAndPath(ns, path)
                    val replacement = MethodCallExpr(
                        NameExpr("ResourceLocation"),
                        "fromNamespaceAndPath",
                        n.arguments
                    )
                    changes.add(Change(
                        file = file, line = line,
                        description = "ResourceLocation constructor -> fromNamespaceAndPath",
                        before = n.toString(),
                        after = replacement.toString(),
                        confidence = Confidence.HIGH,
                        ruleId = "ast-rl-two-arg"
                    ))
                    return replacement
                }
                1 -> {
                    // new ResourceLocation(str) -> ResourceLocation.parse(str)
                    val replacement = MethodCallExpr(
                        NameExpr("ResourceLocation"),
                        "parse",
                        n.arguments
                    )
                    changes.add(Change(
                        file = file, line = line,
                        description = "ResourceLocation constructor -> parse",
                        before = n.toString(),
                        after = replacement.toString(),
                        confidence = Confidence.HIGH,
                        ruleId = "ast-rl-one-arg"
                    ))
                    return replacement
                }
            }
        }
        return super.visit(n, arg)
    }
}

/**
 * Transforms @Mod constructor to accept IEventBus and ModContainer parameters.
 * Removes FMLJavaModLoadingContext.get().getModEventBus() calls.
 */
class ModEntryPointTransformer(
    private val file: Path,
    private val changes: MutableList<Change>
) : ModifierVisitor<Void>() {

    override fun visit(n: ClassOrInterfaceDeclaration, arg: Void?): Visitable {
        // Check if class has @Mod annotation
        val modAnnotation = n.annotations.find { it.nameAsString == "Mod" }
        if (modAnnotation != null) {
            // Find constructors and check if they already have ModContainer param
            for (constructor in n.constructors) {
                val hasModContainer = constructor.parameters.any {
                    it.typeAsString == "ModContainer"
                }
                if (!hasModContainer) {
                    transformModConstructor(constructor, n)
                }
            }
        }
        return super.visit(n, arg)
    }

    private fun transformModConstructor(constructor: ConstructorDeclaration, classDecl: ClassOrInterfaceDeclaration) {
        val line = constructor.begin.map { it.line }.orElse(0)

        // In NeoForge 1.21.1 the @Mod constructor receives only ModContainer (no IEventBus).
        // The mod obtains the event bus via modContainer.getEventBus().
        val beforeParams = constructor.parameters.joinToString(", ") { "${it.type} ${it.name}" }

        constructor.addParameter("ModContainer", "modContainer")

        val afterParams = constructor.parameters.joinToString(", ") { "${it.type} ${it.name}" }

        changes.add(Change(
            file = file, line = line,
            description = "Add ModContainer to @Mod constructor (NeoForge 1.21 style)",
            before = "public ${classDecl.nameAsString}($beforeParams)",
            after = "public ${classDecl.nameAsString}($afterParams)",
            confidence = Confidence.HIGH,
            ruleId = "ast-mod-constructor"
        ))

        // Replace FMLJavaModLoadingContext.get().getModEventBus() with modContainer.getEventBus()
        for (stmt in constructor.body.statements) {
            val stmtStr = stmt.toString()
            if (stmtStr.contains("FMLJavaModLoadingContext.get().getModEventBus()")) {
                // If it's an assignment like: IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
                // Replace RHS with modContainer.getEventBus()
                if (stmt is com.github.javaparser.ast.stmt.ExpressionStmt) {
                    val expr = stmt.expression
                    if (expr is com.github.javaparser.ast.expr.VariableDeclarationExpr) {
                        for (v in expr.variables) {
                            val init = v.initializer.orElse(null)?.toString() ?: ""
                            if (init.contains("FMLJavaModLoadingContext.get().getModEventBus()")) {
                                v.setInitializer(com.github.javaparser.StaticJavaParser.parseExpression("modContainer.getEventBus()"))
                                changes.add(Change(
                                    file = file,
                                    line = stmt.begin.map { it.line }.orElse(0),
                                    description = "Replace FMLJavaModLoadingContext.get().getModEventBus() with modContainer.getEventBus()",
                                    before = stmtStr.trim(),
                                    after = stmt.toString().trim(),
                                    confidence = Confidence.HIGH,
                                    ruleId = "ast-replace-fmljavamod-eventbus"
                                ))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Transforms @Cancelable annotation to implements ICancellableEvent.
 */
class CancelableAnnotationTransformer(
    private val file: Path,
    private val changes: MutableList<Change>
) : ModifierVisitor<Void>() {

    override fun visit(n: ClassOrInterfaceDeclaration, arg: Void?): Visitable {
        val cancelable = n.annotations.find {
            it.nameAsString == "Cancelable" || it.nameAsString == "net.minecraftforge.eventbus.api.Cancelable"
        }
        if (cancelable != null) {
            n.annotations.remove(cancelable)
            // Add ICancellableEvent to implements list
            if (!n.implementedTypes.any { it.nameAsString == "ICancellableEvent" }) {
                n.addImplementedType("ICancellableEvent")
            }
            val line = cancelable.begin.map { it.line }.orElse(0)
            changes.add(Change(
                file = file, line = line,
                description = "@Cancelable -> implements ICancellableEvent",
                before = "@Cancelable",
                after = "implements ICancellableEvent",
                confidence = Confidence.HIGH,
                ruleId = "ast-cancelable"
            ))
        }
        return super.visit(n, arg)
    }
}

/**
 * Transforms @Mod.EventBusSubscriber to @EventBusSubscriber.
 */
class EventBusSubscriberTransformer(
    private val file: Path,
    private val changes: MutableList<Change>
) : ModifierVisitor<Void>() {

    override fun visit(n: ClassOrInterfaceDeclaration, arg: Void?): Visitable {
        val annotation = n.annotations.find {
            it.nameAsString == "Mod.EventBusSubscriber"
        }
        if (annotation != null) {
            val line = annotation.begin.map { it.line }.orElse(0)
            // Replace annotation name
            annotation.setName("EventBusSubscriber")
            changes.add(Change(
                file = file, line = line,
                description = "@Mod.EventBusSubscriber -> @EventBusSubscriber",
                before = "@Mod.EventBusSubscriber",
                after = "@EventBusSubscriber",
                confidence = Confidence.HIGH,
                ruleId = "ast-eventbus-subscriber"
            ))
        }
        return super.visit(n, arg)
    }
}

/**
 * Removes .endVertex() calls (no longer needed in 1.21).
 */
class EndVertexRemover(
    private val file: Path,
    private val changes: MutableList<Change>
) : ModifierVisitor<Void>() {

    override fun visit(n: MethodCallExpr, arg: Void?): Visitable {
        if (n.nameAsString == "endVertex" && n.arguments.isEmpty()) {
            val line = n.begin.map { it.line }.orElse(0)
            val replacement = n.scope.map { it.toString() }.orElse("")
            changes.add(Change(
                file = file, line = line,
                description = "Remove endVertex() call (auto-flushed in 1.21)",
                before = n.toString(),
                after = replacement,
                confidence = Confidence.HIGH,
                ruleId = "ast-remove-endvertex"
            ))
            // Return the scope expression (the object the method was called on)
            // so the chain continues without endVertex
            return n.scope.orElse(n)
        }
        return super.visit(n, arg)
    }
}

/**
 * Renames render() to renderWidget() in classes that extend AbstractWidget subclasses.
 * In NeoForge 1.21.1, AbstractWidget.render() is final; subclasses must override renderWidget() instead.
 * Affected superclasses: EditBox, Button, AbstractButton, AbstractSliderButton,
 * AbstractWidget, ObjectSelectionList, AbstractSelectionList.
 * Screen subclasses must keep render().
 */
class WidgetRenderRenameTransformer(
    private val file: Path,
    private val changes: MutableList<Change>
) : ModifierVisitor<Void>() {

    companion object {
        /** Known AbstractWidget subclasses where render() became renderWidget() */
        private val WIDGET_PARENT_TYPES = setOf(
            "AbstractWidget", "EditBox", "Button", "AbstractButton",
            "AbstractSliderButton", "ImageButton", "StateSwitchingButton",
            "ObjectSelectionList", "AbstractSelectionList"
        )
    }

    override fun visit(n: ClassOrInterfaceDeclaration, arg: Void?): Visitable {
        // Check if this class extends a known widget type
        val extendsWidget = n.extendedTypes.any { ext -> isWidgetType(ext.nameAsString) }

        if (extendsWidget) {
            // Find render methods with the GuiGraphics signature and rename to renderWidget
            for (method in n.methods) {
                renameWidgetRenderMethod(method)
            }
        }
        return super.visit(n, arg)
    }

    override fun visit(n: ObjectCreationExpr, arg: Void?): Visitable {
        if (isWidgetType(n.type.nameAsString)) {
            n.anonymousClassBody.ifPresent { body ->
                body.filterIsInstance<MethodDeclaration>()
                    .forEach { renameWidgetRenderMethod(it) }
            }
        }
        return super.visit(n, arg)
    }

    private fun isWidgetType(typeName: String): Boolean =
        WIDGET_PARENT_TYPES.any { typeName == it || typeName.endsWith(".$it") }

    private fun renameWidgetRenderMethod(method: MethodDeclaration) {
        if (method.nameAsString == "render" && hasGuiGraphicsSignature(method)) {
            val line = method.begin.map { it.line }.orElse(0)
            changes.add(Change(
                file = file, line = line,
                description = "Widget render() -> renderWidget() (AbstractWidget.render is final in 1.21)",
                before = "public void render(GuiGraphics ...)",
                after = "public void renderWidget(GuiGraphics ...)",
                confidence = Confidence.HIGH,
                ruleId = "ast-widget-render-rename"
            ))
            method.setName("renderWidget")
        }
    }

    private fun hasGuiGraphicsSignature(method: MethodDeclaration): Boolean {
        // render(GuiGraphics, int, int, float) — the standard widget render signature
        val params = method.parameters
        return params.size == 4 &&
            params[0].typeAsString.contains("GuiGraphics") &&
            params[1].typeAsString == "int" &&
            params[2].typeAsString == "int" &&
            params[3].typeAsString == "float"
    }
}

/**
 * Transforms ObjectSelectionList/AbstractSelectionList super() constructor calls
 * from 6-param (Forge) to 5-param (NeoForge).
 * Forge:    super(mc, width, height, top, bottom, slotHeight)
 * NeoForge: super(mc, width, height, y, itemHeight)
 * The 'bottom' parameter (5th) is removed; 'height' (3rd) is no longer full screen height
 * but the visible area height (bottom - top).
 */
class SelectionListConstructorTransformer(
    private val file: Path,
    private val changes: MutableList<Change>
) : ModifierVisitor<Void>() {

    companion object {
        private val SELECTION_LIST_TYPES = setOf(
            "ObjectSelectionList", "AbstractSelectionList"
        )
    }

    override fun visit(n: ClassOrInterfaceDeclaration, arg: Void?): Visitable {
        val extendsSelectionList = n.extendedTypes.any { ext ->
            SELECTION_LIST_TYPES.any { ext.nameAsString == it || ext.nameAsString.endsWith(".$it") }
        }

        if (extendsSelectionList) {
            // Transform super() calls in constructors from 6 args to 5 args
            for (constructor in n.constructors) {
                transformSuperCall(constructor)
                transformConstructorParams(constructor, n)
            }
        }
        return super.visit(n, arg)
    }

    /**
     * Transform super(mc, width, height, top, bottom, slotHeight) to super(mc, width, height, y, itemHeight)
     */
    private fun transformSuperCall(constructor: ConstructorDeclaration) {
        for (stmt in constructor.body.statements) {
            if (stmt is ExplicitConstructorInvocationStmt && !stmt.isThis && stmt.arguments.size == 6) {
                val line = stmt.begin.map { it.line }.orElse(0)
                val before = stmt.toString().trim()

                // Remove the 5th argument (index 4 = bottom)
                stmt.arguments.removeAt(4)

                val after = stmt.toString().trim()
                changes.add(Change(
                    file = file, line = line,
                    description = "ObjectSelectionList super() 6-param -> 5-param (remove bottom)",
                    before = before,
                    after = after,
                    confidence = Confidence.HIGH,
                    ruleId = "ast-selection-list-super"
                ))
            }
        }
    }

    /**
     * Transform constructor parameters: remove the 'bottom' parameter (typically the 6th in the
     * subclass constructor matching the super pattern).
     * Forge:    (parentScreen, mc, width, height, top, bottom, slotHeight)
     * NeoForge: (parentScreen, mc, width, height, y, itemHeight)
     * We detect by looking for constructors with 7 int-heavy params and remove the one
     * corresponding to 'bottom'.
     */
    private fun transformConstructorParams(constructor: ConstructorDeclaration, classDecl: ClassOrInterfaceDeclaration) {
        val params = constructor.parameters
        // Look for patterns where the constructor has the selection list params
        // Forge:    (mc, width, height, top, bottom, slotHeight) — 6 params
        //      or   (parentScreen, mc, width, height, top, bottom, slotHeight) — 7 params
        // NeoForge removes 'bottom', so 6->5 or 7->6
        if (params.size >= 6) {
            // Find int params — the selection list geometry params
            // Typically: ..., int width, int height, int top, int bottom, int slotHeight
            // We need to remove the 'bottom' param (2nd-to-last int before slotHeight)
            val intParamIndices = params.indices.filter { params[it].typeAsString == "int" }
            // If we have at least 4 consecutive int params, remove the 2nd-to-last
            if (intParamIndices.size >= 4) {
                // The 'bottom' param is the one just before slotHeight in the int sequence
                val bottomIdx = intParamIndices[intParamIndices.size - 2]
                val line = constructor.begin.map { it.line }.orElse(0)
                val bottomParam = params[bottomIdx]

                changes.add(Change(
                    file = file, line = line,
                    description = "Remove 'bottom' parameter from SelectionList subclass constructor",
                    before = "..., ${bottomParam.typeAsString} ${bottomParam.nameAsString}, ...",
                    after = "(parameter removed)",
                    confidence = Confidence.HIGH,
                    ruleId = "ast-selection-list-constructor"
                ))
                params.removeAt(bottomIdx)
            }
        }
    }
}
