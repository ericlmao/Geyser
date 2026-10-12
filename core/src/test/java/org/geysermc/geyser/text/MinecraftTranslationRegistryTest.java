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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MinecraftTranslationRegistryTest {

    private MinecraftTranslationRegistry registry;

    @BeforeEach
    public void setUp() {
        registry = new MinecraftTranslationRegistry();
        MinecraftLocale.LOCALE_MAPPINGS.put("en_us", Map.of(
            "death.attack.player", "%1$s was slain by %2$s",
            "test.quote", "Don't {touch} %s",
            "test.english_only", "English %s"));
        MinecraftLocale.LOCALE_MAPPINGS.put("de_de", Map.of("death.attack.player", "%1$s wurde von %2$s erschlagen"));
    }

    @AfterEach
    public void tearDown() {
        MinecraftLocale.LOCALE_MAPPINGS.remove("en_us");
        MinecraftLocale.LOCALE_MAPPINGS.remove("de_de");
        MinecraftLocale.LOCALE_MAPPINGS.remove("fr_fr");
    }

    @Test
    public void secondLookupUsesCachedPattern() {
        MessageFormat first = registry.translate("death.attack.player", "de_DE");
        MessageFormat second = registry.translate("death.attack.player", "de_DE");
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.toPattern(), second.toPattern());
        assertEquals("a wurde von b erschlagen", second.format(new Object[]{"a", "b"}));
        assertEquals(1, registry.cachedPatterns("de_de"));
    }

    @Test
    public void conversionUnchanged() {
        MessageFormat format = registry.translate("test.quote", "en_us");
        assertNotNull(format);
        assertEquals("Don't {touch} x", format.format(new Object[]{"x"}));
        // Cached copy formats the same
        assertEquals("Don't {touch} x", registry.translate("test.quote", "en_us").format(new Object[]{"x"}));
    }

    @Test
    public void invalidateClearsLocale() {
        registry.translate("death.attack.player", "de_de");
        registry.translate("death.attack.player", "en_us");
        registry.invalidate("de_DE");
        assertEquals(0, registry.cachedPatterns("de_de"));
        assertEquals(1, registry.cachedPatterns("en_us"));
    }

    @Test
    public void unloadedLocaleIsNotCached() {
        // fr_fr isn't loaded (yet): the en_us string is used but must not stick once fr_fr loads
        MessageFormat english = registry.translate("death.attack.player", "fr_fr");
        assertEquals("a was slain by b", english.format(new Object[]{"a", "b"}));
        assertEquals(0, registry.cachedPatterns("fr_fr"));

        MinecraftLocale.LOCALE_MAPPINGS.put("fr_fr", Map.of("death.attack.player", "%1$s a été tué par %2$s"));
        assertEquals("a a été tué par b", registry.translate("death.attack.player", "fr_fr").format(new Object[]{"a", "b"}));
    }

    @Test
    public void englishFallbackInLoadedLocaleIsCached() {
        assertEquals("English x", registry.translate("test.english_only", "de_de").format(new Object[]{"x"}));
        assertEquals(1, registry.cachedPatterns("de_de"));
    }

    @Test
    public void replacedLocaleMapRebuildsCache() {
        registry.translate("death.attack.player", "de_de");
        MinecraftLocale.LOCALE_MAPPINGS.put("de_de", Map.of("death.attack.player", "%2$s besiegte %1$s"));
        assertEquals("b besiegte a", registry.translate("death.attack.player", "de_de").format(new Object[]{"a", "b"}));
    }

    @Test
    public void pruneDropsUnloadedLocales() {
        registry.translate("death.attack.player", "de_de");
        registry.translate("death.attack.player", "en_us");
        MinecraftLocale.LOCALE_MAPPINGS.remove("de_de");
        registry.prune();
        assertEquals(0, registry.cachedPatterns("de_de"));
        assertEquals(1, registry.cachedPatterns("en_us"));
    }

    @Test
    public void fallbackIsNotCached() {
        MessageFormat format = registry.translate("missing.key", "Fallback %s", "de_de");
        assertNotNull(format);
        assertEquals("Fallback x", format.format(new Object[]{"x"}));
        assertEquals(0, registry.cachedPatterns("de_de"));
        assertNull(registry.translate("missing.key", "de_de"));
    }
}
