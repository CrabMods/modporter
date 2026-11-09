package com.modporter.transforms

import com.modporter.core.transforms.structural.StructuralRefactorPass
import com.modporter.core.transforms.structural.migrateLegacyScoreboardValues
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyScoreboardValuesTest {
    private val pass = StructuralRefactorPass()

    @Test
    fun `legacy scoreboard values retain ordering names and objective defaults`() {
        val source = """
            package example;

            import java.util.stream.Stream;
            import net.minecraft.network.chat.Component;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.scores.Objective;
            import net.minecraft.world.scores.Scoreboard;
            import net.minecraft.world.scores.criteria.ObjectiveCriteria;
            import net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType;

            class ScoreboardValues {
                Stream<String> entries(Level level, String objectiveName) {
                    if (!(level instanceof ServerLevel serverLevel))
                        return Stream.empty();
                    Objective objective = serverLevel.getScoreboard().getObjective(objectiveName);
                    if (objective == null)
                        return Stream.empty();
                    return serverLevel.getScoreboard()
                        .getPlayerScores(objective)
                        .stream()
                        .map(score -> score.getScore() + ":" + score.getOwner())
                        .sorted();
                }

                void track(Level level, String name, ServerPlayer player) {
                    Scoreboard scoreboard = level.getScoreboard();
                    if (!scoreboard.hasObjective(name))
                        scoreboard.addObjective(
                            name, ObjectiveCriteria.DUMMY, Component.literal(name), RenderType.INTEGER);
                    Objective objective = scoreboard.getObjective(name);
                    scoreboard.getOrCreatePlayerScore(player.getScoreboardName(), objective)
                        .setScore(42);
                }

                boolean exists(Scoreboard scoreboard, String name) {
                    return scoreboard.hasObjective(name);
                }
            }
        """.trimIndent()

        val result = pass.migrateLegacyScoreboardValues(source)

        assertTrue(result.contains(".listPlayerScores(objective)"), result)
        assertTrue(
            result.contains(
                ".stream().sorted(java.util.Comparator.comparingInt(" +
                    "net.minecraft.world.scores.PlayerScoreEntry::value)" +
                    ".thenComparing(net.minecraft.world.scores.PlayerScoreEntry::owner, " +
                    "String.CASE_INSENSITIVE_ORDER.reversed()))"
            ),
            result
        )
        assertTrue(result.contains("score.value() + \":\" + score.owner()"), result)
        assertTrue(result.contains("if (scoreboard.getObjective(name) == null)"), result)
        assertTrue(
            result.replace(Regex("""\s+"""), " ").contains(
                "scoreboard.addObjective( name, ObjectiveCriteria.DUMMY, " +
                    "Component.literal(name), RenderType.INTEGER, false, null);"
            ),
            result
        )
        assertTrue(
            result.replace(Regex("""\s+"""), " ").contains(
                "scoreboard.getOrCreatePlayerScore(" +
                    "net.minecraft.world.scores.ScoreHolder.forNameOnly(player.getScoreboardName()), objective) " +
                    ".set(42);"
            ),
            result
        )
        assertTrue(result.contains("return (scoreboard.getObjective(name) != null);"), result)
        assertFalse(result.contains("getPlayerScores(objective)"), result)
        assertFalse(result.contains("hasObjective(name)"), result)
        assertFalse(result.contains(".setScore(42)"), result)
        assertTrue(pass.migrateLegacyScoreboardValues(result) == result, result)
    }

    @Test
    fun `scoreboard lookalikes and non objective collections remain unchanged`() {
        val source = """
            class Objective {}
            class Scores {
                java.util.List<Value> getPlayerScores(Objective objective) { return null; }
                boolean hasObjective(String name) { return false; }
                void addObjective(String name, Object criteria, Object display, Object render) {}
                Value getOrCreatePlayerScore(String name, Objective objective) { return null; }
            }
            class Value {
                int getScore() { return 0; }
                String getOwner() { return ""; }
                void setScore(int score) {}
            }
            class Lookalike {
                void use(Scores scores, Objective objective) {
                    scores.getPlayerScores(objective).stream()
                        .map(score -> score.getScore() + score.getOwner());
                    if (!scores.hasObjective("x"))
                        scores.addObjective("x", null, null, null);
                    scores.getOrCreatePlayerScore("player", objective).setScore(1);
                }
            }
        """.trimIndent()

        assertTrue(pass.migrateLegacyScoreboardValues(source) == source)
    }
}
