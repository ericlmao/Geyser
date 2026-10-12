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

import org.geysermc.mcprotocollib.protocol.data.game.statistic.StatisticFormat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatisticFormattersTest {
    private static Locale previousLocale;

    @BeforeAll
    static void setLocale() {
        previousLocale = Locale.getDefault();
        // A locale with ',' as the decimal separator and '.' for grouping
        Locale.setDefault(Locale.GERMANY);
    }

    @AfterAll
    static void restoreLocale() {
        Locale.setDefault(previousLocale);
    }

    @Test
    void tenths() {
        assertEquals("1,234.50", StatisticFormatters.get(StatisticFormat.TENTHS).apply(12345));
    }

    @Test
    void distance() {
        assertEquals("1.50 m", StatisticFormatters.get(StatisticFormat.DISTANCE).apply(150));
        assertEquals("0.60 m", StatisticFormatters.get(StatisticFormat.DISTANCE).apply(60));
        assertEquals("40 cm", StatisticFormatters.get(StatisticFormat.DISTANCE).apply(40));
    }

    @Test
    void time() {
        assertEquals("1.00 h", StatisticFormatters.get(StatisticFormat.TIME).apply(72000));
    }
}
