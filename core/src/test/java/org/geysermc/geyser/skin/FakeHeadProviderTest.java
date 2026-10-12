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
import org.geysermc.geyser.api.skin.SkinData;
import org.geysermc.geyser.api.skin.SkinGeometry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Network-free tests for the merged head skin cache and its in-flight bookkeeping.
 */
public class FakeHeadProviderTest {
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final String HEAD_URL = "http://textures.minecraft.net/texture/head";

    @BeforeAll
    static void initSkinProvider() {
        // SkinProvider's static initialiser reads its geometry resources through GeyserImpl's bootstrap
        GeyserImpl geyser = mock(GeyserImpl.class);
        GeyserBootstrap bootstrap = mock(GeyserBootstrap.class, CALLS_REAL_METHODS);
        when(geyser.getBootstrap()).thenReturn(bootstrap);
        try (MockedStatic<GeyserImpl> geyserMock = mockStatic(GeyserImpl.class)) {
            geyserMock.when(GeyserImpl::getInstance).thenReturn(geyser);
            assertNotNull(SkinProvider.WEARING_CUSTOM_SKULL);
        }
    }

    @BeforeEach
    void clearCaches() {
        FakeHeadProvider.MERGED_SKINS.invalidateAll();
        FakeHeadProvider.IN_FLIGHT.clear();
    }

    @Test
    void mergeReplacesOnlyTheHeadRegion() {
        SkinData merged = FakeHeadProvider.merge(originalSkinData(SkinGeometry.WIDE), solidSkin("head", BLUE), HEAD_URL);

        BufferedImage image = SkinProvider.imageDataToBufferedImage(merged.skin().skinData(), 64, 64);
        assertEquals(BLUE, image.getRGB(10, 10));
        assertEquals(RED, image.getRGB(10, 40));
        assertEquals("customPlayerHead_" + HEAD_URL + "_original", merged.skin().textureUrl());
        assertSame(SkinProvider.WEARING_CUSTOM_SKULL, merged.geometry());
        assertSame(SkinProvider.WEARING_CUSTOM_SKULL_SLIM,
                FakeHeadProvider.merge(originalSkinData(SkinGeometry.SLIM), solidSkin("head", BLUE), HEAD_URL).geometry());
    }

    @Test
    void completedMergeIsCachedAndLeavesNothingInFlight() {
        FakeHeadProvider.FakeHeadEntry key = new FakeHeadProvider.FakeHeadEntry("skin", HEAD_URL);
        SkinData first = FakeHeadProvider.mergedSkin(key,
                () -> CompletableFuture.completedFuture(originalSkinData(SkinGeometry.WIDE)),
                () -> CompletableFuture.completedFuture(solidSkin("head", BLUE))).join();

        assertTrue(FakeHeadProvider.IN_FLIGHT.isEmpty());
        assertSame(first, FakeHeadProvider.MERGED_SKINS.getIfPresent(key));
        assertSame(first, FakeHeadProvider.mergedSkin(key, unexpected(), unexpected()).join());
    }

    @Test
    void concurrentRequestsShareOneMerge() throws Exception {
        FakeHeadProvider.FakeHeadEntry key = new FakeHeadProvider.FakeHeadEntry("skin", HEAD_URL);
        CompletableFuture<SkinData> original = new CompletableFuture<>();
        CompletableFuture<Skin> head = new CompletableFuture<>();
        AtomicInteger requests = new AtomicInteger();

        CompletableFuture<SkinData> first = FakeHeadProvider.mergedSkin(key, counting(requests, original), counting(requests, head));
        CompletableFuture<SkinData> second = FakeHeadProvider.mergedSkin(key, counting(requests, original), counting(requests, head));
        assertSame(first, second);
        assertEquals(2, requests.get());

        original.complete(originalSkinData(SkinGeometry.WIDE));
        head.complete(solidSkin("head", BLUE));
        assertNotNull(first.get(5, TimeUnit.SECONDS));
        assertTrue(FakeHeadProvider.IN_FLIGHT.isEmpty());
    }

    @Test
    void failedHeadIsNotCached() {
        FakeHeadProvider.FakeHeadEntry key = new FakeHeadProvider.FakeHeadEntry("skin", HEAD_URL);
        CompletableFuture<SkinData> future = FakeHeadProvider.mergedSkin(key,
                () -> CompletableFuture.completedFuture(originalSkinData(SkinGeometry.WIDE)),
                () -> CompletableFuture.completedFuture(SkinProvider.FAILED_SKIN));

        assertThrows(CompletionException.class, future::join);
        assertTrue(FakeHeadProvider.IN_FLIGHT.isEmpty());
        assertNull(FakeHeadProvider.MERGED_SKINS.getIfPresent(key));
    }

    @Test
    void rejectedRequestRegistersNothing() {
        FakeHeadProvider.FakeHeadEntry key = new FakeHeadProvider.FakeHeadEntry("skin", HEAD_URL);
        CompletableFuture<SkinData> future = FakeHeadProvider.mergedSkin(key, () -> {
            throw new RejectedExecutionException("shut down");
        }, unexpected());

        assertTrue(future.isCompletedExceptionally());
        assertTrue(FakeHeadProvider.IN_FLIGHT.isEmpty());
    }

    @Test
    void cacheIsBoundedByWeight() {
        byte[] skinBytes = new byte[64 * 64 * 4];
        Cape cape = new Cape("cape", "cape", new byte[64 * 32 * 4], false);
        int entries = 4096;
        for (int i = 0; i < entries; i++) {
            FakeHeadProvider.MERGED_SKINS.put(new FakeHeadProvider.FakeHeadEntry("skin" + i, HEAD_URL),
                    new SkinData(new Skin("merged" + i, skinBytes), cape, SkinProvider.WEARING_CUSTOM_SKULL));
        }
        FakeHeadProvider.MERGED_SKINS.cleanUp();

        long perEntry = skinBytes.length + cape.capeData().length + 128L;
        long size = FakeHeadProvider.MERGED_SKINS.size();
        assertTrue(size < entries, "cache was not bounded: " + size);
        assertTrue(size * perEntry <= 32L * 1024 * 1024, "cache over budget: " + size * perEntry);
    }

    private static SkinData originalSkinData(SkinGeometry geometry) {
        return new SkinData(solidSkin("original", RED), SkinProvider.EMPTY_CAPE, geometry);
    }

    private static Skin solidSkin(String textureUrl, int argb) {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return new Skin(textureUrl, SkinProvider.bufferedImageToImageData(image));
    }

    private static <T> Supplier<CompletableFuture<T>> counting(AtomicInteger counter, CompletableFuture<T> future) {
        return () -> {
            counter.incrementAndGet();
            return future;
        };
    }

    private static <T> Supplier<CompletableFuture<T>> unexpected() {
        return () -> fail("should not have been requested");
    }
}
