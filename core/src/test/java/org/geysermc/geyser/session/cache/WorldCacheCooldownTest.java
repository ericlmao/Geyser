/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.session.cache;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.item.type.Item;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.util.CooldownUtils;
import org.geysermc.geyser.util.CooldownUtils.CooldownType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.geysermc.geyser.scoreboard.network.util.GeyserMockContext.mockContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorldCacheCooldownTest {

    private static GeyserItemStack item(String javaIdentifier) {
        Item item = mock(Item.class);
        when(item.javaIdentifier()).thenReturn(javaIdentifier);
        GeyserItemStack stack = mock(GeyserItemStack.class);
        when(stack.getComponent(DataComponentTypes.USE_COOLDOWN)).thenReturn(null);
        when(stack.asItem()).thenReturn(item);
        return stack;
    }

    @Test
    void cooldownExpiresWhenTicked() {
        mockContext(context -> {
            GeyserSession session = context.mock(GeyserSession.class);
            when(session.getTicks()).thenReturn(100);
            WorldCache worldCache = new WorldCache(session);
            GeyserItemStack shield = item("minecraft:shield");

            worldCache.setCooldown(Key.key("minecraft:shield"), 10);
            assertTrue(worldCache.hasCooldown(shield));

            when(session.getTicks()).thenReturn(109);
            worldCache.tick();
            assertTrue(worldCache.hasCooldown(shield));

            when(session.getTicks()).thenReturn(110);
            worldCache.tick();
            assertFalse(worldCache.hasCooldown(shield));
        });
    }

    @Test
    void serverCanClearCooldown() {
        mockContext(context -> {
            GeyserSession session = context.mock(GeyserSession.class);
            WorldCache worldCache = new WorldCache(session);
            GeyserItemStack pearl = item("minecraft:ender_pearl");

            worldCache.setCooldown(Key.key("minecraft:ender_pearl"), 20);
            assertTrue(worldCache.hasCooldown(pearl));
            worldCache.setCooldown(Key.key("minecraft:ender_pearl"), 0);
            assertFalse(worldCache.hasCooldown(pearl));
        });
    }

    private static GeyserSession attackCooldownSession(double attackSpeed, boolean needClear) {
        GeyserSession session = mock(GeyserSession.class);
        when(session.getGameMode()).thenReturn(GameMode.SURVIVAL);
        PreferencesCache preferences = mock(PreferencesCache.class);
        when(preferences.getCooldownPreference()).thenReturn(CooldownType.CROSSHAIR);
        when(session.getPreferencesCache()).thenReturn(preferences);
        when(session.getAttackSpeed()).thenReturn(attackSpeed);
        when(session.isNeedAttackCooldownClear()).thenReturn(needClear);
        return session;
    }

    @Test
    void attackCooldownIsNotClearedEveryTickWithoutAnIndicator() {
        // Attack speeds above 20 (e.g. OldCombatMechanics) and 0 (on login) never show the indicator
        for (double attackSpeed : new double[] {0.0, 25.0}) {
            GeyserSession session = attackCooldownSession(attackSpeed, false);

            CooldownUtils.tickCooldown(session);

            verify(session, never()).sendUpstreamPacket(any(BedrockPacket.class));
            verify(session, never()).sendJsonUIData(anyString(), anyString());
        }
    }

    @Test
    void shownAttackCooldownIsClearedOnceWhenTheIndicatorStops() {
        GeyserSession session = attackCooldownSession(25.0, true);

        CooldownUtils.tickCooldown(session);

        ArgumentCaptor<BedrockPacket> packet = ArgumentCaptor.forClass(BedrockPacket.class);
        verify(session).sendUpstreamPacket(packet.capture());
        assertEquals(SetTitlePacket.Type.CLEAR, ((SetTitlePacket) packet.getValue()).getType());
        verify(session).setNeedAttackCooldownClear(false);
    }
}
