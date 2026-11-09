package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AssignExpr
import com.modporter.core.transforms.util.JavaSourceText

/** A deferred Level field cannot supply the provider while its value is being serialized. */
internal object DetachedFluidTankNbt {
    fun requiresCallerProvider(
        pass: StructuralRefactorPass,
        source: String,
        methodRange: IntRange,
        tankNames: Set<String>,
        inheritance: JavaInheritanceIndex
    ): Boolean {
        if (tankNames.isEmpty()) return false
        val body = JavaSourceText.maskCommentsAndLiterals(source.substring(methodRange))
        if (tankNames.none { name ->
                Regex("""\b${Regex.escape(name)}\s*\.\s*(?:writeToNBT|readFromNBT)\s*\(""")
                    .containsMatchIn(body)
            }) return false
        val registry = with(pass) { registryAccessExpressionAt(source, methodRange.first, inheritance) }
            ?: return false
        val fieldName = Regex("""^([A-Za-z_$][\w$]*)\.registryAccess\(\)$""")
            .matchEntire(registry)?.groupValues?.get(1) ?: return false
        if (SourceValueTypes(pass, source).typeOf(fieldName, methodRange.first) !in setOf(
                "net.minecraft.world.level.Level",
                "net.minecraft.server.level.ServerLevel",
                "net.minecraft.client.multiplayer.ClientLevel"
            )) return false

        val parser = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        val unit = parser.parse(source).result.orElse(null) ?: return false
        val line = source.take(methodRange.first).count { it == '\n' } + 1
        val owner = unit.findAll(TypeDeclaration::class.java)
            .filter { type -> type.range.map { line in it.begin.line..it.end.line }.orElse(false) }
            .minByOrNull { type -> type.range.map { it.end.line - it.begin.line }.orElse(Int.MAX_VALUE) }
            ?: return false
        val field = owner.fields.flatMap { it.variables }.singleOrNull { it.nameAsString == fieldName }
            ?: return false
        if (field.initializer.isPresent && !field.initializer.get().isNullLiteralExpr) return false
        // Leave constructor-bound owners on their existing registry source. The detached case
        // starts with Java's null field value and receives its world through a later callback.
        if (owner.members.filterIsInstance<ConstructorDeclaration>().any { constructor ->
                constructor.findAll(AssignExpr::class.java).any {
                    it.target.toString() == fieldName || it.target.toString() == "this.$fieldName"
                }
            }) return false
        return true
    }
}
