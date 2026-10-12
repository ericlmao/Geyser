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

package org.geysermc.geyser.translator.item;

import org.geysermc.geyser.util.MathUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Attribute lore amounts formatted by {@link ItemTranslator}. The formatter lives in {@link MathUtils} because
 * ItemTranslator's static initialiser loads {@code Items}, which cannot be initialised in a plain unit test.
 */
class ItemTranslatorAttributeFormatTest {
    private static final Map<Double, String> PINNED = new LinkedHashMap<>();

    static {
        PINNED.put(1.5, "1.5");
        PINNED.put(-2.0, "-2");
        PINNED.put(0.0005, "0.0005");
        PINNED.put(0.123456, "0.12346");
        PINNED.put(0.1234549, "0.12345");
        PINNED.put(400.0, "400");
        PINNED.put(-0.75, "-0.75");
        PINNED.put(12.0000001, "12");
        PINNED.put(1234567.5, "1234567.5"); // no grouping
        PINNED.put(0.3 * 100, "30"); // ADD_MULTIPLIED path: 30.000000000000004
        PINNED.put(-0.000001, "-0"); // existing DecimalFormat behaviour; amount == 0 is filtered before formatting
    }

    private static Locale previousLocale;

    @BeforeAll
    static void setLocale() {
        previousLocale = Locale.getDefault();
        // A locale with ',' as the decimal separator; output must not depend on it
        Locale.setDefault(Locale.GERMANY);
    }

    @AfterAll
    static void restoreLocale() {
        Locale.setDefault(previousLocale);
    }

    @Test
    void pinnedOutputs() {
        for (Map.Entry<Double, String> entry : PINNED.entrySet()) {
            assertEquals(entry.getValue(), MathUtils.formatAttributeAmount(entry.getKey()), "Formatting " + entry.getKey());
        }
    }

    @Test
    void concurrentFormatting() throws Exception {
        int threads = 8;
        int iterations = 20_000;
        List<Map.Entry<Double, String>> entries = new ArrayList<>(PINNED.entrySet());
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int offset = t;
                futures.add(executor.submit(() -> {
                    for (int i = 0; i < iterations; i++) {
                        Map.Entry<Double, String> entry = entries.get((i + offset) % entries.size());
                        assertEquals(entry.getValue(), MathUtils.formatAttributeAmount(entry.getKey()), "Formatting " + entry.getKey());
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(); // rethrows any assertion failure
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
