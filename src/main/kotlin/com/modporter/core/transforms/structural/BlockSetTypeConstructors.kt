package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.BodyDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter

/** New BlockSetType properties use its public 1.21.1 String constructor's defaults. */
internal fun StructuralRefactorPass.migrateBlockSetTypeConstructors(source: String): String {
    val canonical = "net.minecraft.world.level.block.state.properties.BlockSetType"
    if (!source.contains("BlockSetType")) return source
    val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
    val unit = parser.parse(source).result.orElse(null) ?: return source
    if (unit.findAll(TypeDeclaration::class.java).any { it.nameAsString == "BlockSetType" }) return source
    val visible = unit.imports.any { !it.isStatic && (it.nameAsString == canonical || it.isAsterisk && it.nameAsString == canonical.substringBeforeLast('.')) }
    val calls = unit.findAll(ObjectCreationExpr::class.java).filter {
        it.arguments.size == 11 && (it.type.nameWithScope == canonical || it.type.nameWithScope == "BlockSetType" && visible)
    }
    if (calls.isEmpty()) return source
    LexicalPreservingPrinter.setup(unit)
    val helpers = java.util.IdentityHashMap<TypeDeclaration<*>, String>()
    for (call in calls.asReversed()) {
        var parent = call.parentNode.orElse(null)
        while (parent != null && parent !is TypeDeclaration<*>) parent = parent.parentNode.orElse(null)
        val owner = parent as? TypeDeclaration<*> ?: continue
        val helper = helpers.getOrPut(owner) {
            var name = "modporterBlockSetType"
            while (source.contains(name) || name in helpers.values) name += "_"
            name
        }
        val replacement = MethodCallExpr(helper).setArguments(NodeList(call.arguments.map { it.clone() }))
        call.replace(replacement)
    }
    for ((owner, name) in helpers) {
        val method = """
            private static $canonical $name(String name, boolean canOpenByHand,
                    net.minecraft.world.level.block.SoundType sound,
                    net.minecraft.sounds.SoundEvent doorClose, net.minecraft.sounds.SoundEvent doorOpen,
                    net.minecraft.sounds.SoundEvent trapdoorClose, net.minecraft.sounds.SoundEvent trapdoorOpen,
                    net.minecraft.sounds.SoundEvent pressureOff, net.minecraft.sounds.SoundEvent pressureOn,
                    net.minecraft.sounds.SoundEvent buttonOff, net.minecraft.sounds.SoundEvent buttonOn) {
                $canonical defaults = new $canonical(name);
                return new $canonical(name, canOpenByHand, defaults.canOpenByWindCharge(),
                    defaults.canButtonBeActivatedByArrows(), defaults.pressurePlateSensitivity(), sound,
                    doorClose, doorOpen, trapdoorClose, trapdoorOpen, pressureOff, pressureOn, buttonOff, buttonOn);
            }
        """.trimIndent()
        owner.addMember(parser.parseBodyDeclaration<BodyDeclaration<*>>(method).result.orElseThrow())
    }
    return LexicalPreservingPrinter.print(unit)
}
