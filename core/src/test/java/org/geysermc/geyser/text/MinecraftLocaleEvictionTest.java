/*
 * Copyright (c) 2019-2025 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.text;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MinecraftLocaleEvictionTest {
    private static final long IDLE = 30 * 60 * 1000L;
    private static final long NOW = 10 * IDLE;
    private static final Set<String> PINNED = Set.of("en_us", "de_de");

    @Test
    public void idleUnusedLocaleIsEvicted() {
        assertTrue(MinecraftLocale.shouldEvict("fr_fr", Set.of(), PINNED, NOW - IDLE - 1, NOW, IDLE));
    }

    @Test
    public void recentlyUsedLocaleIsKept() {
        assertFalse(MinecraftLocale.shouldEvict("fr_fr", Set.of(), PINNED, NOW - IDLE, NOW, IDLE));
        assertFalse(MinecraftLocale.shouldEvict("fr_fr", Set.of(), PINNED, NOW - 1, NOW, IDLE));
    }

    @Test
    public void activeLocaleIsKept() {
        assertFalse(MinecraftLocale.shouldEvict("fr_fr", Set.of("fr_fr"), PINNED, 0L, NOW, IDLE));
    }

    @Test
    public void pinnedLocaleIsKept() {
        assertFalse(MinecraftLocale.shouldEvict("en_us", Set.of(), PINNED, 0L, NOW, IDLE));
        assertFalse(MinecraftLocale.shouldEvict("de_de", Set.of(), PINNED, 0L, NOW, IDLE));
    }

    @Test
    public void unknownLastUseIsKept() {
        assertFalse(MinecraftLocale.shouldEvict("fr_fr", Set.of(), PINNED, null, NOW, IDLE));
    }

    @Test
    public void norwegianIsStoredUnderBedrockCode() {
        assertEquals("nb_no", MinecraftLocale.normalizeLocale("no_no"));
        assertEquals("nb_no", MinecraftLocale.normalizeLocale("nb_no"));
        assertEquals("fr_fr", MinecraftLocale.normalizeLocale("fr_fr"));
    }

    @Test
    public void evictionIsNoOpWithoutInstance() {
        MinecraftLocale.LOCALE_MAPPINGS.put("fr_fr", Map.of("a", "b"));
        try {
            MinecraftLocale.evictIdleLocales();
            assertTrue(MinecraftLocale.isLocaleLoaded("fr_fr"));
        } finally {
            MinecraftLocale.LOCALE_MAPPINGS.remove("fr_fr");
        }
    }
}
