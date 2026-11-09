package com.modporter.core.transforms.structural

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyCuriosInventoryCapabilitiesTest {
    @Test
    fun `raw Curios inventory capability regains its Optional API`() {
        val source =
            """
            package example;

            import java.util.Map;
            import java.util.Optional;
            import net.minecraft.world.entity.LivingEntity;
            import top.theillusivec4.curios.api.CuriosCapability;
            import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
            import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

            final class CuriosSurface {
                static Optional<Map<String, ICurioStacksHandler>> resolve(
                    LivingEntity entity
                ) {
                    return entity
                        .getCapability(CuriosCapability.INVENTORY)
                        .map(ICuriosItemHandler::getCurios);
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateLegacyCuriosInventoryCapabilities(source)
        assertTrue(
            migrated.contains(
                "import top.theillusivec4.curios.api.CuriosApi;"
            ),
            migrated
        )
        assertTrue(
            compact(migrated).contains(
                "return CuriosApi.getCuriosInventory(entity)" +
                    ".map(ICuriosItemHandler::getCurios);"
            ),
            migrated
        )
        assertFalse(
            migrated.contains(
                "import top.theillusivec4.curios.api.CuriosCapability;"
            ),
            migrated
        )
        assertEquals(
            migrated,
            StructuralRefactorPass()
                .migrateLegacyCuriosInventoryCapabilities(migrated)
        )
    }

    @Test
    fun `direct native access and non Curios symbols remain unchanged`() {
        val source =
            """
            package example;

            import net.minecraft.world.entity.LivingEntity;
            import top.theillusivec4.curios.api.CuriosCapability;

            final class CuriosSurface {
                static Object direct(LivingEntity entity) {
                    return entity.getCapability(
                        CuriosCapability.INVENTORY);
                }

                static String retained(LivingEntity entity) {
                    // entity.getCapability(CuriosCapability.INVENTORY).map(x -> x)
                    return "entity.getCapability(" +
                        "CuriosCapability.INVENTORY).map(x -> x)";
                }
            }
            """.trimIndent() + "\n"

        assertEquals(
            source,
            StructuralRefactorPass()
                .migrateLegacyCuriosInventoryCapabilities(source)
        )
    }

    @Test
    fun `unproven receiver and foreign capability import remain unchanged`() {
        val unprovenReceiver =
            """
            package example;

            import top.theillusivec4.curios.api.CuriosCapability;

            final class CuriosSurface {
                static Object resolve(Object entity) {
                    return entity.getCapability(
                        CuriosCapability.INVENTORY).map(x -> x);
                }
            }
            """.trimIndent() + "\n"
        val foreignCapability =
            """
            package example;

            import example.compat.CuriosCapability;
            import net.minecraft.world.entity.LivingEntity;

            final class CuriosSurface {
                static Object resolve(LivingEntity entity) {
                    return entity.getCapability(
                        CuriosCapability.INVENTORY).map(x -> x);
                }
            }
            """.trimIndent() + "\n"

        assertEquals(
            unprovenReceiver,
            StructuralRefactorPass()
                .migrateLegacyCuriosInventoryCapabilities(unprovenReceiver)
        )
        assertEquals(
            foreignCapability,
            StructuralRefactorPass()
                .migrateLegacyCuriosInventoryCapabilities(foreignCapability)
        )
    }

    @Test
    fun `CuriosApi simple name collision uses the exact API owner`() {
        val source =
            """
            package example;

            import net.minecraft.world.entity.LivingEntity;
            import top.theillusivec4.curios.api.CuriosCapability;

            final class CuriosSurface {
                static final class CuriosApi {
                }

                static boolean resolve(LivingEntity entity) {
                    return entity.getCapability(
                        CuriosCapability.INVENTORY).isPresent();
                }
            }
            """.trimIndent() + "\n"

        val migrated = StructuralRefactorPass()
            .migrateLegacyCuriosInventoryCapabilities(source)
        assertTrue(
            compact(migrated).contains(
                "return top.theillusivec4.curios.api.CuriosApi" +
                    ".getCuriosInventory(entity).isPresent();"
            ),
            migrated
        )
        assertFalse(
            migrated.contains(
                "import top.theillusivec4.curios.api.CuriosApi;"
            ),
            migrated
        )
    }

    private fun compact(source: String): String =
        source.replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s*\.\s*"""), ".")
            .replace(Regex("""\(\s+"""), "(")
            .replace(Regex("""\s+\)"""), ")")
            .trim()
}
