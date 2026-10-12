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

package org.geysermc.geyser.inventory;

import org.geysermc.geyser.scoreboard.network.util.GeyserMockContext;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Out-of-range slots must return {@link GeyserItemStack#EMPTY} instead of throwing.
 */
class InventoryBoundsTest {

    @Test
    void outOfBoundsSlotsReturnEmpty() {
        GeyserMockContext.mockContext(context -> {
            GeyserSession session = context.mock(GeyserSession.class);
            PlayerInventory inventory = new PlayerInventory(session);

            assertSame(GeyserItemStack.EMPTY, inventory.getItem(inventory.getSize()));
            assertSame(GeyserItemStack.EMPTY, inventory.getItem(inventory.getSize() + 1));
            assertSame(GeyserItemStack.EMPTY, inventory.getItem(-1));
            assertSame(GeyserItemStack.EMPTY, inventory.getItem(Integer.MIN_VALUE));
            // Last valid slot is still readable
            assertSame(GeyserItemStack.EMPTY, inventory.getItem(inventory.getSize() - 1));
        });
    }

    @Test
    void outOfRangeHeldSlotReturnsEmpty() {
        GeyserMockContext.mockContext(context -> {
            GeyserSession session = context.mock(GeyserSession.class);
            PlayerInventory inventory = new PlayerInventory(session);

            inventory.setHeldItemSlot(inventory.getSize() - 36);
            assertSame(GeyserItemStack.EMPTY, inventory.getItemInHand());
            inventory.setHeldItemSlot(-1);
            assertSame(GeyserItemStack.EMPTY, inventory.getItemInHand());
        });
    }

    @Test
    void equipmentIteratesInSlotOrder() {
        GeyserMockContext.mockContext(context -> {
            GeyserSession session = context.mock(GeyserSession.class);
            PlayerInventory inventory = new PlayerInventory(session);

            assertEquals(List.of(EquipmentSlot.MAIN_HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.BOOTS,
                    EquipmentSlot.LEGGINGS, EquipmentSlot.CHESTPLATE, EquipmentSlot.HELMET),
                List.copyOf(inventory.getEquipment().keySet()));
        });
    }
}
