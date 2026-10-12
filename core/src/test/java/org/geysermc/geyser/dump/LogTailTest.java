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

package org.geysermc.geyser.dump;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTailTest {
    private static final String HEADER = "[Geyser] log truncated to last ";

    @TempDir
    Path dir;

    @Test
    void manyLinesAreCappedToTheLastLines() throws IOException {
        Path log = dir.resolve("latest.log");
        int total = 100_000;
        try (BufferedWriter writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
            for (int i = 0; i < total; i++) {
                writer.write("line " + i + "\n");
            }
        }
        assertTrue(Files.size(log) > 1024 * 1024);

        String content = DumpInfo.LogsInfo.readLog(log);
        assertTrue(content.startsWith(HEADER), content.substring(0, 64));
        String[] lines = content.split("\n");
        // header plus the tail
        assertEquals(DumpInfo.LogsInfo.MAX_LOG_LINES + 1, lines.length);
        assertEquals("line " + (total - DumpInfo.LogsInfo.MAX_LOG_LINES), lines[1]);
        assertTrue(content.endsWith("line " + (total - 1) + "\n"));
        assertTrue(content.getBytes(StandardCharsets.UTF_8).length <= DumpInfo.LogsInfo.MAX_LOG_BYTES + HEADER.length() + 32);
    }

    @Test
    void largeFileIsCappedToMaxBytes() throws IOException {
        Path log = dir.resolve("latest.log");
        String padding = "x".repeat(1000);
        int total = 11 * 1024;
        try (BufferedWriter writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
            for (int i = 0; i < total; i++) {
                writer.write(i + " " + padding + "\n");
            }
        }
        assertTrue(Files.size(log) > DumpInfo.LogsInfo.MAX_LOG_BYTES);

        String content = DumpInfo.LogsInfo.readLog(log);
        assertTrue(content.startsWith(HEADER));
        String body = content.substring(content.indexOf('\n') + 1);
        assertTrue(body.getBytes(StandardCharsets.UTF_8).length <= DumpInfo.LogsInfo.MAX_LOG_BYTES);
        assertTrue(body.substring(0, body.indexOf('\n')).matches("\\d+ x{1000}"), "tail starts mid-line");
        assertTrue(body.endsWith((total - 1) + " " + padding + "\n"));
    }

    @Test
    void smallFileIsUnchanged() throws IOException {
        Path log = dir.resolve("latest.log");
        String text = "first\n[INFO] café §aok\nlast\n";
        Files.writeString(log, text, StandardCharsets.UTF_8);
        assertEquals(text, DumpInfo.LogsInfo.readLog(log));
    }
}
