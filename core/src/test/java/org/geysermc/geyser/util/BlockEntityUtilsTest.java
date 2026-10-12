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

package org.geysermc.geyser.util;

import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockEntityUtilsTest {

    @Test
    void pinnedBedrockBlockEntityIds() {
        assertEquals("Chest", BlockEntityUtils.getBedrockBlockEntityId(BlockEntityType.CHEST));
        assertEquals("Chest", BlockEntityUtils.getBedrockBlockEntityId(BlockEntityType.TRAPPED_CHEST));
        assertEquals("EnchantTable", BlockEntityUtils.getBedrockBlockEntityId(BlockEntityType.ENCHANTING_TABLE));
        assertEquals("MobSpawner", BlockEntityUtils.getBedrockBlockEntityId(BlockEntityType.MOB_SPAWNER));
        assertEquals("Skull", BlockEntityUtils.getBedrockBlockEntityId(BlockEntityType.SKULL));
    }

    @Test
    void precomputedIdsMatchLegacyAlgorithmForEveryType() {
        for (BlockEntityType type : BlockEntityType.values()) {
            assertEquals(legacyBedrockBlockEntityId(type), BlockEntityUtils.getBedrockBlockEntityId(type), type.name());
        }
    }

    /**
     * Verbatim copy of the per-call string munging that getBedrockBlockEntityId performed before ids were precomputed.
     */
    private static String legacyBedrockBlockEntityId(BlockEntityType type) {
        String value = BlockEntityUtils.BLOCK_ENTITY_TRANSLATIONS.get(type);
        if (value != null) {
            return value;
        }

        String id = type.name();
        String[] words = id.split("_");
        for (int i = 0; i < words.length; i++) {
            words[i] = words[i].substring(0, 1).toUpperCase(Locale.ROOT) + words[i].substring(1).toLowerCase(Locale.ROOT);
        }

        return String.join("", words);
    }
}
