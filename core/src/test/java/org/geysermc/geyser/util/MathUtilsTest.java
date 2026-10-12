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

import org.junit.jupiter.api.Test;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MathUtilsTest {

    @Test
    void roundToFiveDecimalsPinnedValues() {
        assertEquals(64.0, MathUtils.roundToFiveDecimals(64.0));
        assertEquals(64.12346, MathUtils.roundToFiveDecimals(64.123456));
        assertEquals(64.12345, MathUtils.roundToFiveDecimals(64.1234549));
        assertEquals(-12.34568, MathUtils.roundToFiveDecimals(-12.345678));
        assertEquals(0.3, MathUtils.roundToFiveDecimals(0.1 + 0.2));
        assertEquals(70.00001, MathUtils.roundToFiveDecimals(70.00000999));
        assertEquals(100.0, MathUtils.roundToFiveDecimals(100.000004));
        assertEquals(-64.5, MathUtils.roundToFiveDecimals(-64.5));
        assertEquals(1234567.89123, MathUtils.roundToFiveDecimals(1234567.891234567));
    }

    @Test
    void roundToFiveDecimalsPreservesNegativeZero() {
        // assertEquals(double, double) distinguishes -0.0 from 0.0
        assertEquals(-0.0, MathUtils.roundToFiveDecimals(-0.000004));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(MathUtils.roundToFiveDecimals(-0.000004)));
    }

    @Test
    void roundToFiveDecimalsNonFinite() {
        assertEquals(Double.NaN, MathUtils.roundToFiveDecimals(Double.NaN));
        assertEquals(Double.POSITIVE_INFINITY, MathUtils.roundToFiveDecimals(Double.POSITIVE_INFINITY));
        assertEquals(Double.NEGATIVE_INFINITY, MathUtils.roundToFiveDecimals(Double.NEGATIVE_INFINITY));
    }

    @Test
    void roundToFiveDecimalsMatchesDecimalFormat() {
        // The previous implementation in CollisionManager
        DecimalFormat format = new DecimalFormat("#.#####", new DecimalFormatSymbols(Locale.ENGLISH));
        Random random = new Random(42);
        for (int i = 0; i < 200_000; i++) {
            double value = -256 + random.nextDouble() * 768;
            double expected = Double.parseDouble(format.format(value));
            assertEquals(expected, MathUtils.roundToFiveDecimals(value), () -> "Mismatch for " + value);
        }
    }
}
