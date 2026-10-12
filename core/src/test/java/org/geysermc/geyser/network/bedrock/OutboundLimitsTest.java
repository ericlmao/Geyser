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

package org.geysermc.geyser.network.bedrock;

import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.geysermc.geyser.GeyserLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OutboundLimitsTest {
    private final GeyserLogger logger = mock(GeyserLogger.class);

    @AfterEach
    void clearProperties() {
        System.clearProperty("Geyser.Outbound.PeerMaxPackets");
        System.clearProperty("Geyser.Outbound.DrainPackets");
        System.clearProperty("Geyser.Outbound.StallMillis");
        System.clearProperty("Geyser.RakMaxQueuedBytes");
    }

    @Test
    void defaultsWithoutProperties() {
        assertEquals(OutboundLimits.DEFAULTS, OutboundLimits.fromSystemProperties(this.logger));
        verify(this.logger, never()).warning(contains("Geyser."));
    }

    @Test
    void validValuesAreUsedAndKeptConsistent() {
        System.setProperty("Geyser.Outbound.PeerMaxPackets", "100");
        System.setProperty("Geyser.RakMaxQueuedBytes", "1048576");
        OutboundLimits limits = OutboundLimits.fromSystemProperties(this.logger);
        assertEquals(100, limits.peerMaxPackets());
        assertEquals(1048576, limits.rakMaxQueuedBytes());
        assertEquals(100, limits.drainPackets(), "a drain never exceeds the per-connection limit");
    }

    @Test
    void invalidValuesFallBackToTheDefaultInsteadOfDisablingTheLimit() {
        System.setProperty("Geyser.Outbound.PeerMaxPackets", "0");
        System.setProperty("Geyser.Outbound.StallMillis", "soon");
        OutboundLimits limits = OutboundLimits.fromSystemProperties(this.logger);
        assertEquals(OutboundLimits.DEFAULTS.peerMaxPackets(), limits.peerMaxPackets());
        assertEquals(OutboundLimits.DEFAULTS.stallMillis(), limits.stallMillis());
        verify(this.logger).warning(contains("Geyser.Outbound.PeerMaxPackets"));
        verify(this.logger).warning(contains("Geyser.Outbound.StallMillis"));
    }

    @Test
    void sharedBudgetIsReturnedExactly() {
        GeyserOutboundPolicy policy = new GeyserOutboundPolicy(OutboundLimits.DEFAULTS, this.logger);
        assertTrue(policy.tryReserveShared(1, 1000));
        assertTrue(policy.tryReserveShared(1, 2000));
        policy.releaseShared(1, 1000);
        policy.releaseShared(1, 2000);
        assertEquals(0, policy.sharedQueuedPackets());
        assertEquals(0, policy.sharedQueuedWeight());
    }

    @Test
    void sharedWeightLimitAdmitsOneLargePacketWhenEmpty() {
        OutboundLimits small = new OutboundLimits(10, 1000, 10, 1000, 10, 1000, 1000, 1, 1000, 1000, 1000, 0, 0.8, 0.9, 0.7);
        GeyserOutboundPolicy policy = new GeyserOutboundPolicy(small, this.logger);
        assertTrue(policy.tryReserveShared(1, 5000));
        assertFalse(policy.tryReserveShared(1, 1));
        assertEquals(1, policy.sharedQueuedPackets(), "a refused reservation is rolled back");
    }

    @Test
    void stopPressureRefusesLargeReservationsOnlyWhenTheSharedQueueIsHalfFull() {
        long large = GeyserOutboundPolicy.LARGE_PAYLOAD_WEIGHT;
        OutboundLimits limits = new OutboundLimits(10, large * 8, 100, large * 4, 10, 1000, 1000, 1, 1000, 1000, 1000, 0, 0.8, 0.9, 0.7);
        GeyserOutboundPolicy policy = new GeyserOutboundPolicy(limits, this.logger);
        policy.setPressure(GeyserOutboundPolicy.Pressure.STOP);
        assertTrue(policy.tryReserveShared(1, large), "empty: admitted");
        assertTrue(policy.tryReserveShared(1, large), "a quarter full: admitted");
        assertTrue(policy.tryReserveShared(1, large), "exactly half full: admitted");
        assertFalse(policy.tryReserveShared(1, large), "over half full: large packets are refused");
        assertTrue(policy.tryReserveShared(1, large - 1), "smaller packets are still admitted");
        policy.releaseShared(1, large - 1);
        policy.setPressure(GeyserOutboundPolicy.Pressure.SOFT);
        assertTrue(policy.tryReserveShared(1, large), "only the stop level refuses them");
    }

    @Test
    void payloadWeightCountsKnownBuffers() {
        LevelChunkPacket chunk = new LevelChunkPacket();
        chunk.setData(Unpooled.buffer(4096).writeZero(4096));
        assertEquals(4096, GeyserOutboundPolicy.payloadWeight(chunk));
        assertEquals(0, GeyserOutboundPolicy.payloadWeight(new TextPacket()));
        chunk.getData().release();
        assertEquals(0, GeyserOutboundPolicy.payloadWeight(chunk), "a released buffer is not read");
    }
}
