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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileUtilsTest {

    @TempDir
    Path dir;

    private Path writeLines(int count) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append("line ").append(i).append('\n');
        }
        Path file = dir.resolve("latest.log");
        Files.writeString(file, builder, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    public void readTailLimitsBytes() throws IOException {
        Path file = writeLines(100_000); // ~1.1 MB
        long maxBytes = 64 * 1024;

        String tail = FileUtils.readTail(file, maxBytes, Integer.MAX_VALUE);

        assertTrue(tail.getBytes(StandardCharsets.UTF_8).length <= maxBytes);
        assertTrue(tail.endsWith("line 99999\n"));
        // The partial first line is dropped
        assertTrue(tail.startsWith("line "));
        String firstLine = tail.substring(0, tail.indexOf('\n'));
        int firstIndex = Integer.parseInt(firstLine.substring("line ".length()));
        assertEquals(100_000 - tail.lines().count(), firstIndex);
    }

    @Test
    public void readTailLimitsLines() throws IOException {
        Path file = writeLines(100_000);

        String tail = FileUtils.readTail(file, 10 * 1024 * 1024, 25);

        assertEquals(25, tail.lines().count());
        assertTrue(tail.startsWith("line 99975\n"));
        assertTrue(tail.endsWith("line 99999\n"));
    }

    @Test
    public void readTailSmallFile() throws IOException {
        Path file = dir.resolve("small.log");
        Files.writeString(file, "first\nsecond\nthird", StandardCharsets.UTF_8);

        assertEquals("first\nsecond\nthird", FileUtils.readTail(file, 1024, 10));
        assertEquals("second\nthird", FileUtils.readTail(file, 1024, 2));
        assertEquals("", FileUtils.readTail(file, 1024, 0));
        // Truncated by bytes: "ond\nthird" -> partial line dropped
        assertEquals("third", FileUtils.readTail(file, 9, 10));
    }

    @Test
    public void readTailDoesNotSplitUtf8() throws IOException {
        Path file = dir.resolve("utf8.log");
        // One long line of 3-byte characters, no newline to resync on
        Files.writeString(file, "€".repeat(100), StandardCharsets.UTF_8);

        String tail = FileUtils.readTail(file, 10, 10);
        assertFalse(tail.contains("�"));
        assertEquals("€".repeat(3), tail);
    }

    @Test
    public void readTailRejectsNegativeLimits() {
        assertThrows(IllegalArgumentException.class, () -> FileUtils.readTail(dir.resolve("missing"), -1, 1));
    }

    @Test
    public void writeFileWritesUtf8() throws IOException {
        Path file = dir.resolve("hash.txt");
        FileUtils.writeFile(file.toFile(), "abcé€".toCharArray());
        assertEquals("abcé€", Files.readString(file, StandardCharsets.UTF_8));
    }
}
