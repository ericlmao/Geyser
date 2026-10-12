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

package org.geysermc.geyser.registry.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RemotePackCacheSweepTest {

    private static final long NOW = System.currentTimeMillis();
    private static final long EXPIRE = TimeUnit.HOURS.toMillis(1);
    private static final long OLD = NOW - TimeUnit.DAYS.toMillis(2);

    @TempDir
    Path cache;

    private Path file(String name, long mtime, String... lines) throws IOException {
        Path path = cache.resolve(name);
        Files.write(path, List.of(lines));
        Files.setLastModifiedTime(path, FileTime.fromMillis(mtime));
        return path;
    }

    private static List<String> metadata(String packFile) {
        return List.of("123", "etag", "0", packFile);
    }

    @Test
    public void deletesOnlyExpiredUnreferencedFiles() throws IOException {
        // Current pack: metadata in use, pack file referenced by it (kept even if its own mtime is old)
        Path currentMetadata = file("1.metadata", NOW, metadata("1_100.zip").toArray(String[]::new));
        Path currentPack = file("1_100.zip", OLD, "zip");
        // Superseded / orphaned pack (e.g. an old fallback after a version change)
        Path orphan = file("1_50.zip", OLD, "zip");
        // A recent orphan may still be served to a client that is mid-download
        Path recentOrphan = file("1_200.zip", NOW, "zip");
        // Pack of a URL that is no longer configured
        Path staleMetadata = file("2.metadata", OLD, metadata("2_10.zip").toArray(String[]::new));
        Path stalePack = file("2_10.zip", OLD, "zip");
        // Abandoned forced download
        Path stalePending = file("1.metadata.pending", OLD, metadata("1_200.zip").toArray(String[]::new));

        int deleted = ResourcePackLoader.cleanupRemotePackCache(cache, EXPIRE, NOW);

        assertEquals(4, deleted);
        assertTrue(Files.exists(currentMetadata));
        assertTrue(Files.exists(currentPack));
        assertTrue(Files.exists(recentOrphan));
        assertFalse(Files.exists(orphan));
        assertFalse(Files.exists(staleMetadata));
        assertFalse(Files.exists(stalePack));
        assertFalse(Files.exists(stalePending));
    }

    @Test
    public void isIdempotent() throws IOException {
        file("1.metadata", NOW, metadata("1_100.zip").toArray(String[]::new));
        file("1_100.zip", NOW, "zip");
        file("1_50.zip", OLD, "zip");

        assertEquals(1, ResourcePackLoader.cleanupRemotePackCache(cache, EXPIRE, NOW));
        assertEquals(0, ResourcePackLoader.cleanupRemotePackCache(cache, EXPIRE, NOW));
        assertTrue(Files.exists(cache.resolve("1_100.zip")));
    }
}
