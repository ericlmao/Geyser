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

package org.geysermc.geyser.skin;

import org.geysermc.geyser.GeyserBootstrap;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.skin.Cape;
import org.geysermc.geyser.api.skin.Skin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Network-free tests for SkinProvider result caching, in-flight bookkeeping and image conversion.
 */
public class SkinProviderTest {

    @BeforeAll
    static void initSkinProvider() {
        // SkinProvider's static initialiser reads its geometry resources through GeyserImpl's bootstrap
        GeyserImpl geyser = mock(GeyserImpl.class);
        GeyserBootstrap bootstrap = mock(GeyserBootstrap.class, CALLS_REAL_METHODS);
        when(geyser.getBootstrap()).thenReturn(bootstrap);
        try (MockedStatic<GeyserImpl> geyserMock = mockStatic(GeyserImpl.class)) {
            geyserMock.when(GeyserImpl::getInstance).thenReturn(geyser);
            assertNotNull(SkinProvider.EMPTY_SKIN);
        }
    }

    @AfterAll
    static void shutdownExecutor() {
        SkinProvider.shutdown();
    }

    private static String uniqueUrl(String name) {
        return "https://textures.minecraft.net/texture/" + name + "-" + UUID.randomUUID();
    }

    @Test
    void failedSkinIsOnlyNegativelyCached() {
        String url = uniqueUrl("failed");
        SkinProvider.cacheSkinResult(url, SkinProvider.FAILED_SKIN);
        assertNull(SkinProvider.getCachedSkin(url));

        // Served from the negative cache: already done, nothing registered as in flight, nothing downloaded
        CompletableFuture<Skin> future = SkinProvider.requestSkin(UUID.randomUUID(), url, true);
        assertTrue(future.isDone());
        assertSame(SkinProvider.FAILED_SKIN, future.join());
        assertTrue(future.join().failed());
        assertFalse(SkinProvider.requestedSkins.containsKey(url));

        // A null result (e.g. an Error escaping the download) must not be put into the cache either
        String nullUrl = uniqueUrl("null");
        SkinProvider.cacheSkinResult(nullUrl, null);
        assertNull(SkinProvider.getCachedSkin(nullUrl));
        assertSame(SkinProvider.FAILED_SKIN, SkinProvider.requestSkin(UUID.randomUUID(), nullUrl, true).join());
    }

    @Test
    void successfulSkinIsCached() {
        String url = uniqueUrl("ok");
        Skin skin = new Skin(url, new byte[64 * 64 * 4]);
        SkinProvider.cacheSkinResult(url, skin);
        assertSame(skin, SkinProvider.getCachedSkin(url));

        CompletableFuture<Skin> future = SkinProvider.requestSkin(UUID.randomUUID(), url, true);
        assertTrue(future.isDone());
        assertSame(skin, future.join());
    }

    @Test
    void failedCapeIsNotCached() {
        String failedUrl = uniqueUrl("cape-failed");
        SkinProvider.cacheCapeResult(failedUrl, new Cape(failedUrl, "id", new byte[0], true));
        assertNull(SkinProvider.getCachedCape(failedUrl));
        SkinProvider.cacheCapeResult(failedUrl, null);
        assertNull(SkinProvider.getCachedCape(failedUrl));

        String okUrl = uniqueUrl("cape-ok");
        Cape cape = new Cape(okUrl, "id", new byte[64 * 32 * 4], false);
        SkinProvider.cacheCapeResult(okUrl, cape);
        assertSame(cape, SkinProvider.getCachedCape(okUrl));
    }

    @Test
    void requestSkinJoinsInFlightRequest() {
        String url = uniqueUrl("in-flight");
        CompletableFuture<Skin> pending = new CompletableFuture<>();
        SkinProvider.requestedSkins.put(url, pending);
        try {
            assertSame(pending, SkinProvider.requestSkin(UUID.randomUUID(), url, true));
        } finally {
            SkinProvider.requestedSkins.remove(url);
        }
    }

    @Test
    void inFlightRequestsAreDedupedAndCleanedUp() throws Exception {
        Map<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
        Map<String, String> cached = new ConcurrentHashMap<>();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();

        CompletableFuture<String> first = SkinProvider.requestInFlight(inFlight, "url", key -> {
            loads.incrementAndGet();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "loaded " + key;
        }, key -> "failed", cached::put);
        CompletableFuture<String> second = SkinProvider.requestInFlight(inFlight, "url", key -> {
            loads.incrementAndGet();
            return "duplicate";
        }, key -> "failed", cached::put);

        assertSame(first, second, "A second request for the same URL must join the first");
        assertSame(first, inFlight.get("url"));

        release.countDown();
        assertEquals("loaded url", first.get(5, TimeUnit.SECONDS));
        // The returned future completes only after caching and removal have both happened
        assertTrue(inFlight.isEmpty());
        assertEquals("loaded url", cached.get("url"));
        assertEquals(1, loads.get());
    }

    @Test
    void inFlightFailureCompletesNormally() throws Exception {
        Map<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
        Map<String, String> cached = new ConcurrentHashMap<>();

        CompletableFuture<String> future = SkinProvider.requestInFlight(inFlight, "url", key -> {
            throw new IllegalStateException("simulated download failure");
        }, key -> "failed " + key, cached::put);

        assertEquals("failed url", future.get(5, TimeUnit.SECONDS));
        assertFalse(future.isCompletedExceptionally());
        assertTrue(inFlight.isEmpty());
        assertEquals("failed url", cached.get("url"));
    }

    @Test
    void imageDataRoundTrip() {
        Random random = new Random(42);
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }

        byte[] data = SkinProvider.bufferedImageToImageData(image);
        assertEquals(64 * 64 * 4, data.length);

        // RGBA byte order, row by row
        byte[] expected = new byte[64 * 64 * 4];
        int index = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int argb = image.getRGB(x, y);
                expected[index++] = (byte) ((argb >> 16) & 0xFF);
                expected[index++] = (byte) ((argb >> 8) & 0xFF);
                expected[index++] = (byte) (argb & 0xFF);
                expected[index++] = (byte) ((argb >> 24) & 0xFF);
            }
        }
        assertArrayEquals(expected, data);

        BufferedImage roundTrip = SkinProvider.imageDataToBufferedImage(data, 64, 64);
        assertArrayEquals(image.getRGB(0, 0, 64, 64, null, 0, 64), roundTrip.getRGB(0, 0, 64, 64, null, 0, 64));
    }
}
