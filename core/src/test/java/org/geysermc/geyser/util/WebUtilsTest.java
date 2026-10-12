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

import com.sun.net.httpserver.HttpServer;
import org.geysermc.geyser.GeyserBootstrap;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.api.util.PlatformType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link WebUtils} against an in-process HTTP server.
 */
public class WebUtilsTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private ExecutorService executor;
    private MockedStatic<GeyserImpl> geyserMock;
    private final CountDownLatch release = new CountDownLatch(1);

    private final byte[] body = new byte[1024];
    private byte[] pack;

    @BeforeEach
    void setUp() throws IOException {
        new Random(42).nextBytes(body);
        pack = createPack();

        GeyserImpl geyser = mock(GeyserImpl.class);
        GeyserBootstrap bootstrap = mock(GeyserBootstrap.class);
        when(geyser.getLogger()).thenReturn(mock(GeyserLogger.class));
        when(geyser.platformType()).thenReturn(new PlatformType("Test"));
        when(geyser.getBootstrap()).thenReturn(bootstrap);
        when(bootstrap.getConfigFolder()).thenReturn(dir.resolve("config"));
        geyserMock = mockStatic(GeyserImpl.class);
        geyserMock.when(GeyserImpl::getInstance).thenReturn(geyser);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/file", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/missing", exchange -> {
            byte[] message = "not found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, message.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(message);
            }
        });
        server.createContext("/stall", exchange -> {
            try {
                // Never answer until the test is over
                release.await();
            } catch (InterruptedException ignored) {
            }
            exchange.close();
        });
        server.createContext("/pack.zip", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            exchange.sendResponseHeaders(200, pack.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(pack);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
        geyserMock.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static byte[] createPack() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("pack/manifest.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    public void downloadFileWritesAtomically() throws IOException {
        Path target = dir.resolve("file.bin");
        Files.writeString(target, "old contents");

        WebUtils.downloadFile(url("/file"), target);

        assertArrayEquals(body, Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("file.bin.tmp")));
    }

    @Test
    public void downloadFileFailureLeavesNoFiles() {
        Path target = dir.resolve("missing.bin");

        assertThrows(RuntimeException.class, () -> WebUtils.downloadFile(url("/missing"), target));

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("missing.bin.tmp")));
    }

    @Test
    @Timeout(30)
    public void downloadFileTimesOut() {
        Path target = dir.resolve("stall.bin");

        assertThrows(RuntimeException.class, () -> WebUtils.downloadFile(url("/stall"), target));

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("stall.bin.tmp")));
    }

    @Test
    public void forcedRemotePackDownloadDoesNotReplaceCachedCopy() throws Exception {
        String url = url("/pack.zip");
        Path cache = dir.resolve("config").resolve("cache").resolve("remote_packs");
        Path metadata = cache.resolve(url.hashCode() + ".metadata");
        Path pending = cache.resolve(url.hashCode() + ".metadata.pending");

        Path cached = WebUtils.downloadRemotePack(url, false);
        assertArrayEquals(pack, Files.readAllBytes(cached));
        assertEquals(cached.getFileName().toString(), Files.readAllLines(metadata).get(3));

        // Not forced: the cached copy is reused
        assertEquals(cached, WebUtils.downloadRemotePack(url, false));

        // Forced, then not adopted: the cached copy and its metadata stay, the candidate is removed
        Thread.sleep(5); // pack file names contain the current time in millis
        Path candidate = WebUtils.downloadRemotePack(url, true);
        assertNotEquals(cached, candidate);
        assertEquals(cached.getFileName().toString(), Files.readAllLines(metadata).get(3));
        assertTrue(Files.exists(pending));
        WebUtils.discardRemotePack(url, candidate);
        assertFalse(Files.exists(candidate));
        assertFalse(Files.exists(pending));
        assertTrue(Files.exists(cached));

        // Forced, then adopted: the metadata now references the new file
        Thread.sleep(5);
        Path adopted = WebUtils.downloadRemotePack(url, true);
        assertTrue(WebUtils.promoteRemotePack(url, adopted));
        assertEquals(adopted.getFileName().toString(), Files.readAllLines(metadata).get(3));
        assertFalse(Files.exists(pending));

        // The cached copy is never discarded
        WebUtils.discardRemotePack(url, adopted);
        assertTrue(Files.exists(adopted));
        assertEquals(List.of(), Files.list(cache).filter(p -> p.toString().endsWith(".pending")).toList());
    }
}
