/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.scoreboard.network.belowname;

import static org.geysermc.geyser.scoreboard.network.util.AssertUtils.assertNextPacket;
import static org.geysermc.geyser.scoreboard.network.util.AssertUtils.assertNoNextPacket;
import static org.geysermc.geyser.scoreboard.network.util.GeyserMockContextScoreboard.mockContextScoreboard;
import static org.geysermc.geyser.scoreboard.network.util.GeyserMockContextScoreboard.spawnPlayerSilently;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import net.kyori.adventure.text.Component;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes;
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket;
import org.geysermc.geyser.entity.type.player.PlayerEntity;
import org.geysermc.geyser.translator.protocol.java.scoreboard.JavaResetScorePacket;
import org.geysermc.geyser.translator.protocol.java.scoreboard.JavaSetDisplayObjectiveTranslator;
import org.geysermc.geyser.translator.protocol.java.scoreboard.JavaSetObjectiveTranslator;
import org.geysermc.geyser.translator.protocol.java.scoreboard.JavaSetScoreTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ObjectiveAction;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreType;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreboardPosition;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundResetScorePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetDisplayObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetScorePacket;
import org.junit.jupiter.api.Test;

public class BelownameScoreRemovalTests {
    @Test
    void removedScoreSettlesAfterOneUpdate() {
        mockContextScoreboard(context -> {
            var setObjectiveTranslator = new JavaSetObjectiveTranslator();
            var setDisplayObjectiveTranslator = new JavaSetDisplayObjectiveTranslator();
            var setScoreTranslator = new JavaSetScoreTranslator();
            var resetScoreTranslator = new JavaResetScorePacket();

            PlayerEntity player1 = spawnPlayerSilently(context, "player1", 2);

            context.translate(
                setObjectiveTranslator,
                new ClientboundSetObjectivePacket(
                    "objective",
                    ObjectiveAction.ADD,
                    Component.text("objective"),
                    ScoreType.INTEGER,
                    null
                )
            );
            context.translate(setScoreTranslator, new ClientboundSetScorePacket("player1", "objective", 1));
            assertNoNextPacket(context);

            context.translate(
                setDisplayObjectiveTranslator,
                new ClientboundSetDisplayObjectivePacket(ScoreboardPosition.BELOW_NAME, "objective")
            );
            assertNextPacket(context, () -> {
                var packet = new SetEntityDataPacket();
                packet.setRuntimeEntityId(2);
                packet.getMetadata().put(EntityDataTypes.SCORE, "1 §robjective");
                return packet;
            });
            assertNoNextPacket(context);

            // the update triggered by the reset clears the score (vanilla shows 0 for players without a score)
            context.translate(resetScoreTranslator, new ClientboundResetScorePacket("player1", "objective"));
            assertNextPacket(context, () -> {
                var packet = new SetEntityDataPacket();
                packet.setRuntimeEntityId(2);
                packet.getMetadata().put(EntityDataTypes.SCORE, "0 §robjective");
                return packet;
            });
            assertNoNextPacket(context);

            // after that, the removed score should no longer be processed on every update
            clearInvocations(player1);
            var scoreboard = context.session().getWorldCache().getScoreboard();
            scoreboard.onUpdate();
            scoreboard.onUpdate();
            assertNoNextPacket(context);
            verify(player1, never()).setBelowNameText(any());
            verify(player1, never()).updateBedrockMetadata();

            // a new score for the same player is still shown
            context.translate(setScoreTranslator, new ClientboundSetScorePacket("player1", "objective", 5));
            assertNextPacket(context, () -> {
                var packet = new SetEntityDataPacket();
                packet.setRuntimeEntityId(2);
                packet.getMetadata().put(EntityDataTypes.SCORE, "5 §robjective");
                return packet;
            });
            assertNoNextPacket(context);

            // and settles again once that one is removed
            context.translate(resetScoreTranslator, new ClientboundResetScorePacket("player1", "objective"));
            assertNextPacket(context, () -> {
                var packet = new SetEntityDataPacket();
                packet.setRuntimeEntityId(2);
                packet.getMetadata().put(EntityDataTypes.SCORE, "0 §robjective");
                return packet;
            });
            clearInvocations(player1);
            scoreboard.onUpdate();
            assertNoNextPacket(context);
            verify(player1, never()).setBelowNameText(any());
        });
    }
}
