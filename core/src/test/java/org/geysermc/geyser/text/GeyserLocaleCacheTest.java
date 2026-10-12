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

package org.geysermc.geyser.text;

import org.geysermc.geyser.GeyserBootstrap;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeyserLocaleCacheTest {
    private static final String EN_US = "languages/texts/en_US.properties";

    @TempDir
    Path configFolder;

    private GeyserBootstrap bootstrap;

    @BeforeEach
    void setUp() throws Exception {
        resetStatics();
        bootstrap = mock(GeyserBootstrap.class);
        when(bootstrap.getConfigFolder()).thenReturn(configFolder);
        when(bootstrap.getGeyserLogger()).thenReturn(mock(GeyserLogger.class));
        when(bootstrap.getResourceOrNull(anyString())).thenAnswer(invocation ->
            EN_US.equals(invocation.getArgument(0)) ? new ByteArrayInputStream("test.key=Hello\n".getBytes(StandardCharsets.UTF_8)) : null);
        GeyserLocale.init(bootstrap);
    }

    @AfterEach
    void tearDown() throws Exception {
        resetStatics();
    }

    @Test
    void unknownLocaleIsProbedOnce() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertNull(load("xx_YY"));
        }
        verify(bootstrap, times(1)).getResourceOrNull("languages/texts/xx_YY.properties");
        assertEquals("Hello", GeyserLocale.getPlayerLocaleString("test.key", "xx_YY"));
    }

    @Test
    void knownLocaleIsLoadedOnce() throws Exception {
        assertEquals("en_US", load("en_us"));
        assertEquals("en_US", load("en_US"));
        verify(bootstrap, times(1)).getResourceOrNull(EN_US);
    }

    @Test
    void negativeCacheIsBounded() throws Exception {
        for (int i = 0; i < 1000; i++) {
            load("xx_%04d".formatted(i));
        }
        assertTrue(missingLocales().size() <= 256, "negative cache grew to " + missingLocales().size());
    }

    @Test
    void finalizeDefaultLocaleClearsNegativeCache() throws Exception {
        load("xx_YY");
        GeyserImpl geyser = mock(GeyserImpl.class, RETURNS_DEEP_STUBS);
        when(geyser.config().defaultLocale()).thenReturn(GeyserLocale.SYSTEM_LOCALE);
        GeyserLocale.finalizeDefaultLocale(geyser);

        load("xx_YY");
        verify(bootstrap, times(2)).getResourceOrNull("languages/texts/xx_YY.properties");
    }

    @Test
    void traversalLocaleIsRejected() throws Exception {
        Path languages = configFolder.resolve("languages");
        java.nio.file.Files.createDirectories(languages);
        java.nio.file.Files.writeString(configFolder.resolve("x.properties"), "test.key=Leaked\n");
        Set<String> missingBefore = Set.copyOf(missingLocales());

        for (String locale : new String[] {"../../x", "../x", "..", "en_US/../../x", "en_US\u0000", "/etc/passwd", "en_US/x"}) {
            assertNull(load(locale), locale);
            assertEquals("Hello", GeyserLocale.getPlayerLocaleString("test.key", locale), locale);
        }
        verify(bootstrap, never()).getResourceOrNull(contains(".."));
        assertEquals(missingBefore, missingLocales());
    }

    @Test
    void wellFormedLocalesAreAccepted() {
        for (String locale : new String[] {"en_US", "nb_NO", "fil_PH", "zh_Hant", "es_419", "en"}) {
            assertTrue(GeyserLocale.isValidLocale(locale), locale);
        }
        for (String locale : new String[] {"", "e", "en_", "_US", "en-US", "en_US_x", "../../x", "system"}) {
            assertFalse(GeyserLocale.isValidLocale(locale), locale);
        }
    }

    private String load(String locale) throws Exception {
        Method method = GeyserLocale.class.getDeclaredMethod("loadGeyserLocale", String.class, GeyserBootstrap.class);
        method.setAccessible(true);
        return (String) method.invoke(null, locale, bootstrap);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> missingLocales() throws Exception {
        Field field = GeyserLocale.class.getDeclaredField("MISSING_LOCALES");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static void resetStatics() throws Exception {
        Field mappings = GeyserLocale.class.getDeclaredField("LOCALE_MAPPINGS");
        mappings.setAccessible(true);
        ((Map<String, ?>) mappings.get(null)).clear();
        missingLocales().clear();
        Field defaultLocale = GeyserLocale.class.getDeclaredField("DEFAULT_LOCALE");
        defaultLocale.setAccessible(true);
        defaultLocale.set(null, null);
    }
}
