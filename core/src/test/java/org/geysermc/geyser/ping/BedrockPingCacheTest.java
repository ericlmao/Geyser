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

package org.geysermc.geyser.ping;

import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.geysermc.geyser.GeyserBootstrap;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.event.GeyserEventBus;
import org.geysermc.geyser.network.BedrockPingHandler;
import org.geysermc.geyser.session.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BedrockPingCacheTest {

    private static final InetSocketAddress CLIENT = new InetSocketAddress("127.0.0.1", 19132);

    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicReference<GeyserPingInfo> reply = new AtomicReference<>();
    private BedrockPingHandler handler;

    @BeforeEach
    void setUp() {
        GeyserImpl geyser = mock(GeyserImpl.class);
        GeyserConfig config = mock(GeyserConfig.class, RETURNS_DEEP_STUBS);
        when(geyser.config()).thenReturn(config);
        when(config.motd().passthroughMotd()).thenReturn(false);
        when(config.motd().passthroughPlayerCounts()).thenReturn(true);
        when(config.motd().primaryMotd()).thenReturn("Primary");
        when(config.motd().secondaryMotd()).thenReturn("Secondary");
        when(config.motd().maxPlayers()).thenReturn(100);
        when(config.advanced().bedrock().broadcastPort()).thenReturn(19132);
        when(geyser.eventBus()).thenReturn(new GeyserEventBus());
        when(geyser.getSessionManager()).thenReturn(new SessionManager());

        GeyserBootstrap bootstrap = mock(GeyserBootstrap.class);
        when(geyser.getBootstrap()).thenReturn(bootstrap);
        when(bootstrap.getGeyserPingPassthrough()).thenReturn(address -> {
            fetches.incrementAndGet();
            return reply.get();
        });

        handler = new BedrockPingHandler(geyser);
    }

    @Test
    void passthroughResultIsReusedWithinOneSecond() throws InterruptedException {
        reply.set(new GeyserPingInfo(null, 50, 7));

        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            BedrockPong pong = handler.onQuery(i, CLIENT);
            assertEquals(7, pong.playerCount());
            assertEquals(50, pong.maximumPlayerCount());
        }
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        // Exactly one fetch unless the loop itself took longer than the cache window
        assertTrue(fetches.get() <= 1 + elapsedSeconds, "fetched " + fetches.get() + " times");

        int before = fetches.get();
        Thread.sleep(1100);
        handler.onQuery(0, CLIENT);
        assertEquals(before + 1, fetches.get(), "expired cache entry was not refreshed");
    }

    @Test
    void missingPlayersFallsBackToLocalCounts() {
        reply.set(new GeyserPingInfo(null, null));

        BedrockPong pong = handler.onQuery(0, CLIENT);
        assertEquals(0, pong.playerCount());
        assertEquals(100, pong.maximumPlayerCount());
    }
}
