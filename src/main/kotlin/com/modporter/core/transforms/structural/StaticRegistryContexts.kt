package com.modporter.core.transforms.structural

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Position
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration

/** Called only outside discovered methods: static field and initializer expressions have no this. */
internal fun staticRegistryMemberAt(source: String, offset: Int): Boolean {
    if (!source.contains("static")) return false
    val prefix = source.take(offset)
    val position = Position(prefix.count { it == '\n' } + 1, offset - prefix.lastIndexOf('\n'))
    val unit = JavaParser(ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
        .parse(source).result.orElse(null) ?: return false
    val fields: List<Node> = unit.findAll(FieldDeclaration::class.java) + unit.findAll(InitializerDeclaration::class.java)
    val member = fields.filter { it.range.map { range -> range.contains(position) }.orElse(false) }
        .minByOrNull { it.range.map { range -> (range.end.line - range.begin.line) * (source.length + 1L) + range.end.column - range.begin.column }.orElse(Long.MAX_VALUE) }
    return when (member) {
        is FieldDeclaration -> member.isStatic
        is InitializerDeclaration -> member.isStatic
        else -> false
    }
}
