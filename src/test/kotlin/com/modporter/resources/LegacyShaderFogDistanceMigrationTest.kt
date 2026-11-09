package com.modporter.resources

import com.modporter.mapping.MappingDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyShaderFogDistanceMigrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `legacy fog distance shader and program uniform migrate together`() {
        val shaderDir = tempDir.resolve("src/main/resources/assets/example/shaders/core")
        shaderDir.createDirectories()
        val vertex = shaderDir.resolve("glowing_shader.vsh")
        vertex.writeText(
            """
            #version 150
            #moj_import <fog.glsl>
            in vec3 Position;
            uniform mat4 ModelViewMat;
            uniform mat3 IViewRotMat;
            uniform int FogShape;
            out float vertexDistance;
            void main() {
                vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
            }
            """.trimIndent() + "\n"
        )
        val program = shaderDir.resolve("glowing_shader.json")
        program.writeText(
            """
            {
              "vertex": "example:glowing_shader",
              "fragment": "example:glowing_shader",
              "uniforms": [
                { "name": "ModelViewMat", "type": "matrix4x4", "count": 16, "values": [ 1.0 ] },
                { "name": "IViewRotMat", "type": "matrix3x3", "count": 9, "values": [ 1.0 ] },
                { "name": "FogShape", "type": "int", "count": 1, "values": [ 0 ] }
              ]
            }
            """.trimIndent() + "\n"
        )

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertEquals(2, result.changes.count { it.ruleId == "res-shader-fog-distance-121" })
        assertTrue(vertex.readText().contains("fog_distance(Position, FogShape)"))
        assertFalse(vertex.readText().contains("IViewRotMat"))
        assertFalse(program.readText().contains("IViewRotMat"))
    }

    @Test
    fun `legacy fog distance migration rejects extra view rotation use without partial writes`() {
        val shaderDir = tempDir.resolve("src/main/resources/assets/example/shaders/core")
        shaderDir.createDirectories()
        val vertex = shaderDir.resolve("custom_shader.vsh")
        val originalVertex =
            """
            #version 150
            #moj_import <fog.glsl>
            in vec3 Position;
            uniform mat4 ModelViewMat;
            uniform mat3 IViewRotMat;
            uniform int FogShape;
            out float vertexDistance;
            void main() {
                vec3 rotated = IViewRotMat * Position;
                vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
            }
            """.trimIndent() + "\n"
        vertex.writeText(originalVertex)
        val program = shaderDir.resolve("custom_shader.json")
        val originalProgram =
            """
            {
              "vertex": "example:custom_shader",
              "fragment": "example:custom_shader",
              "uniforms": [
                { "name": "IViewRotMat", "type": "matrix3x3", "count": 9, "values": [ 1.0 ] }
              ]
            }
            """.trimIndent() + "\n"
        program.writeText(originalProgram)

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(tempDir)

        assertTrue(result.errors.any { it.contains("no other IViewRotMat references") })
        assertEquals(originalVertex, vertex.readText())
        assertEquals(originalProgram, program.readText())
        assertTrue(result.changes.none { it.ruleId == "res-shader-fog-distance-121" })
    }

    @Test
    fun `legacy fog distance migration ignores comment decoys and rewrites only live code`() {
        val shaderDir = tempDir.resolve("src/main/resources/assets/example/shaders/core")
        shaderDir.createDirectories()
        val vertex = shaderDir.resolve("commented_shader.vsh")
        vertex.writeText(
            """
            #version 150
            #moj_import <fog.glsl>
            in vec3 Position;
            uniform mat4 ModelViewMat;
            uniform mat3 IViewRotMat;
            uniform int FogShape;
            out float vertexDistance;
            void main() {
                // fog_distance(ModelViewMat, IViewRotMat * CommentPosition, FogShape)
                vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
            }
            """.trimIndent() + "\n"
        )
        val program = shaderDir.resolve("commented_shader.json")
        program.writeText(shaderProgramJson("commented_shader"))

        val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(tempDir)
        val migrated = vertex.readText()

        assertTrue(result.errors.isEmpty(), result.errors.joinToString("\n"))
        assertTrue(
            migrated.contains(
                "// fog_distance(ModelViewMat, IViewRotMat * CommentPosition, FogShape)"
            )
        )
        assertTrue(migrated.contains("vertexDistance = fog_distance(Position, FogShape);"))
        assertFalse(migrated.contains("uniform mat3 IViewRotMat"))
        assertFalse(program.readText().contains("IViewRotMat"))
    }

    @Test
    fun `legacy fog distance migration rejects comment-only prefixed and duplicate calls atomically`() {
        data class Fixture(val name: String, val body: String)

        val fixtures = listOf(
            Fixture(
                "comment_only",
                "// fog_distance(ModelViewMat, IViewRotMat * Position, FogShape)"
            ),
            Fixture(
                "prefixed",
                "vertexDistance = custom_fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);"
            ),
            Fixture(
                "duplicate",
                """
                vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
                vertexDistance += fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
                """.trimIndent()
            )
        )

        fixtures.forEach { fixture ->
            val project = tempDir.resolve(fixture.name)
            val shaderDir = project.resolve("src/main/resources/assets/example/shaders/core")
            shaderDir.createDirectories()
            val vertex = shaderDir.resolve("${fixture.name}.vsh")
            val originalVertex =
                """
                #version 150
                #moj_import <fog.glsl>
                in vec3 Position;
                uniform mat4 ModelViewMat;
                uniform mat3 IViewRotMat;
                uniform int FogShape;
                out float vertexDistance;
                void main() {
                    ${fixture.body}
                }
                """.trimIndent() + "\n"
            vertex.writeText(originalVertex)
            val program = shaderDir.resolve("${fixture.name}.json")
            val originalProgram = shaderProgramJson(fixture.name)
            program.writeText(originalProgram)

            val result = ResourceMigrationPass(MappingDatabase.loadDefault()).apply(project)

            assertTrue(
                result.errors.any { it.contains("expected one IViewRotMat uniform and one exact legacy call") },
                "${fixture.name}: ${result.errors}"
            )
            assertEquals(originalVertex, vertex.readText(), fixture.name)
            assertEquals(originalProgram, program.readText(), fixture.name)
            assertTrue(
                result.changes.none { it.ruleId == "res-shader-fog-distance-121" },
                fixture.name
            )
        }
    }

    private fun shaderProgramJson(name: String): String =
        """
        {
          "vertex": "example:$name",
          "fragment": "example:$name",
          "uniforms": [
            { "name": "ModelViewMat", "type": "matrix4x4", "count": 16, "values": [ 1.0 ] },
            { "name": "IViewRotMat", "type": "matrix3x3", "count": 9, "values": [ 1.0 ] },
            { "name": "FogShape", "type": "int", "count": 1, "values": [ 0 ] }
          ]
        }
        """.trimIndent() + "\n"
}
