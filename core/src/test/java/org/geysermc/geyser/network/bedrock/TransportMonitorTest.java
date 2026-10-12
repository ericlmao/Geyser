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
import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.network.bedrock.GeyserOutboundPolicy.Pressure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransportMonitorTest {
    private final GeyserLogger logger = mock(GeyserLogger.class);
    private final OutboundLimits limits = OutboundLimits.DEFAULTS;
    private final AtomicLong clockOffsetNanos = new AtomicLong();
    private final AtomicLong directUsed = new AtomicLong();
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private GeyserOutboundPolicy policy;
    private TransportMonitor monitor;

    @BeforeEach
    void setUp() {
        this.policy = new GeyserOutboundPolicy(this.limits, this.logger);
        // Starts from the real clock so peer timestamps line up, and only moves when the test says so
        long base = System.nanoTime();
        this.monitor = new TransportMonitor(this.logger, this.limits, this.policy,
            () -> true, () -> base + this.clockOffsetNanos.get(), () -> System.currentTimeMillis(), this.directUsed::get, () -> -1, 1000);
    }

    @AfterEach
    void tearDown() {
        this.monitor.shutdown();
        this.channels.forEach(EmbeddedChannel::finishAndReleaseAll);
    }

    private GeyserBedrockPeer peer() {
        EmbeddedChannel channel = new EmbeddedChannel();
        this.channels.add(channel);
        return new GeyserBedrockPeer(channel, (peer, id) -> null, this.policy, this.monitor);
    }

    private void advance(long millis) {
        this.clockOffsetNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    @Test
    void stalledOutboundWorkIsAbortedAndTheSessionToldOnce() {
        GeyserBedrockPeer peer = peer();
        List<String> reasons = new ArrayList<>();
        peer.setTerminalListener(reasons::add);
        // The batching flush is scheduled but the embedded loop never runs it: the queue is stalled
        peer.sendPacket(0, 0, new TextPacket());
        assertEquals(1, peer.getQueuedPacketCount());

        this.advance(this.limits.stallMillis() - 1000);
        this.monitor.scan();
        assertFalse(peer.isAdmissionClosed(), "not stalled yet");

        this.advance(2000);
        this.monitor.scan();
        this.monitor.scan();
        assertTrue(peer.isAdmissionClosed());
        assertFalse(peer.getChannel().isOpen());
        assertEquals(1, reasons.size());
    }

    @Test
    void idleConnectionsAreLeftAlone() {
        GeyserBedrockPeer peer = peer();
        this.advance(TimeUnit.HOURS.toMillis(2));
        this.monitor.scan();
        assertFalse(peer.isAdmissionClosed());
    }

    @Test
    void progressingConnectionsAreLeftAlone() {
        GeyserBedrockPeer peer = peer();
        EmbeddedChannel channel = (EmbeddedChannel) peer.getChannel();
        for (int i = 0; i < 5; i++) {
            peer.sendPacket(0, 0, new TextPacket());
            this.advance(this.limits.stallMillis() / 2);
            // The loop drains, so the next packet starts a fresh wait
            channel.advanceTimeBy(1, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            this.monitor.scan();
        }
        assertFalse(peer.isAdmissionClosed());
    }

    @Test
    void aFailingConnectionDoesNotStopTheScan() {
        EmbeddedChannel channel = new EmbeddedChannel();
        this.channels.add(channel);
        new GeyserBedrockPeer(channel, (peer, id) -> null, this.policy, this.monitor) {
            @Override
            public long getPendingWaitNanos(long nowNanos) {
                throw new IllegalStateException("broken");
            }
        };
        GeyserBedrockPeer stalled = peer();
        stalled.sendPacket(0, 0, new TextPacket());
        this.advance(this.limits.stallMillis() + 1000);
        assertDoesNotThrow(this.monitor::scan);
        assertTrue(stalled.isAdmissionClosed(), "the scan continued past the failing connection");
    }

    @Test
    void connectionsAreWatchedUntilTheirCleanupCompletes() {
        EmbeddedChannel channel = new EmbeddedChannel();
        this.channels.add(channel);
        GeyserBedrockPeer peer = new GeyserBedrockPeer(channel, (p, id) -> null, this.policy, this.monitor);
        channel.pipeline().addLast(peer);
        assertEquals(1, this.monitor.monitoredConnections());

        peer.abortTransport("test");
        // Admission closed and the close was requested, but the monitor keeps it until the peer is cleaned up
        this.monitor.scan();
        assertTrue(peer.isDisposed(), "the embedded loop closed it right away");
        this.monitor.scan();
        assertEquals(0, this.monitor.monitoredConnections());
    }

    @Test
    void shutdownForgetsConnectionsAndLeavesNoThread() throws InterruptedException {
        this.monitor.start();
        peer();
        this.monitor.shutdown();
        assertEquals(0, this.monitor.monitoredConnections());
        peer();
        assertEquals(0, this.monitor.monitoredConnections(), "registrations after shutdown are ignored");
        // The executor thread terminates after shutdownNow()
        for (int i = 0; i < 50 && monitorThreadAlive(); i++) {
            Thread.sleep(20);
        }
        assertFalse(monitorThreadAlive());
    }

    private static boolean monitorThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
            .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith("Geyser Transport Monitor"));
    }

    @Test
    void directMemoryPressureUsesHysteresis() {
        this.directUsed.set(500);
        this.monitor.scan();
        assertEquals(Pressure.NORMAL, this.policy.pressure());
        this.directUsed.set(850);
        this.monitor.scan();
        assertEquals(Pressure.SOFT, this.policy.pressure());
        this.directUsed.set(950);
        this.monitor.scan();
        assertEquals(Pressure.STOP, this.policy.pressure());
        this.directUsed.set(800);
        this.monitor.scan();
        assertEquals(Pressure.STOP, this.policy.pressure(), "stays until usage drops below the resume level");
        this.directUsed.set(650);
        this.monitor.scan();
        assertEquals(Pressure.NORMAL, this.policy.pressure());
    }

    @Test
    void pressureShedsOnlyConnectionsWithLargeBacklogs() {
        GeyserBedrockPeer heavy = peer();
        GeyserBedrockPeer light = peer();
        // Weight above half of the per-connection limit, still within the limit itself
        heavy.sendPacket(0, 0, chunk((int) (this.limits.peerMaxWeight() * 3 / 5)));
        light.sendPacket(0, 0, new TextPacket());

        this.monitor.scan();
        assertFalse(heavy.isAdmissionClosed(), "no pressure, no shedding");

        this.directUsed.set(850);
        this.monitor.scan();
        assertTrue(heavy.isAdmissionClosed());
        assertFalse(light.isAdmissionClosed());
    }

    @Test
    void stopPressureAdmitsLargePayloadsWhileQueuesAreLight() {
        GeyserBedrockPeer peer = peer();
        this.directUsed.set(950);
        this.monitor.scan();
        assertEquals(Pressure.STOP, this.policy.pressure());
        peer.sendPacket(0, 0, new TextPacket());
        peer.sendPacket(0, 0, chunk((int) GeyserOutboundPolicy.LARGE_PAYLOAD_WEIGHT));
        this.monitor.scan();
        assertFalse(peer.isAdmissionClosed(), "memory used elsewhere does not get a healthy connection aborted");
    }

    @Test
    void stopPressureRefusesLargePayloadsOnceQueuesAreHalfFull() {
        GeyserBedrockPeer peer = peer();
        this.directUsed.set(950);
        this.monitor.scan();
        assertEquals(Pressure.STOP, this.policy.pressure());
        // Other connections hold half of the shared limit
        long othersWeight = this.limits.sharedMaxWeight() / 2;
        assertTrue(this.policy.tryReserveShared(1, othersWeight));
        try {
            peer.sendPacket(0, 0, new TextPacket());
            assertFalse(peer.isAdmissionClosed(), "small packets are still admitted");
            peer.sendPacket(0, 0, chunk((int) GeyserOutboundPolicy.LARGE_PAYLOAD_WEIGHT));
            assertTrue(peer.isAdmissionClosed());
        } finally {
            this.policy.releaseShared(1, othersWeight);
        }
    }

    @Test
    void directMemoryUsageIsTheLargerOfBothSources() {
        assertEquals(100, TransportMonitor.directMemoryUsed(100, -1), "Netty uses the cleaner: the JDK pool counts its buffers");
        assertEquals(900, TransportMonitor.directMemoryUsed(100, 900), "Netty allocates without the cleaner: only its counter sees them");
        assertEquals(900, TransportMonitor.directMemoryUsed(900, 100));
        assertEquals(900, TransportMonitor.directMemoryUsed(-1, 900));
        assertEquals(-1, TransportMonitor.directMemoryUsed(-1, -1));
    }

    @Test
    void nettyDirectMemoryCounterDrivesPressure() {
        TransportMonitor nettyOnly = new TransportMonitor(this.logger, this.limits, this.policy, () -> true,
            System::nanoTime, System::currentTimeMillis, () -> 100, () -> 950, 1000);
        nettyOnly.scan();
        assertEquals(Pressure.STOP, this.policy.pressure());

        GeyserOutboundPolicy other = new GeyserOutboundPolicy(this.limits, this.logger);
        TransportMonitor unmeasured = new TransportMonitor(this.logger, this.limits, other, () -> true,
            System::nanoTime, System::currentTimeMillis, null, () -> -1, 1000);
        unmeasured.scan();
        assertEquals(Pressure.NORMAL, other.pressure(), "nothing to measure, nothing changes");
    }

    @Test
    void addressesAreWithheldWhenConfigured() {
        GeyserOutboundPolicy withheldPolicy = new GeyserOutboundPolicy(this.limits, this.logger, () -> false);
        long base = System.nanoTime();
        TransportMonitor withheld = new TransportMonitor(this.logger, this.limits, withheldPolicy, () -> false,
            () -> base + this.clockOffsetNanos.get(), System::currentTimeMillis, () -> -1, () -> -1, 1000);
        EmbeddedChannel channel = new EmbeddedChannel();
        this.channels.add(channel);
        GeyserBedrockPeer peer = new GeyserBedrockPeer(channel, (p, id) -> null, withheldPolicy, withheld);
        peer.sendPacket(0, 0, new TextPacket());
        this.advance(this.limits.stallMillis() + 1000);
        withheld.scan();
        assertTrue(peer.isAdmissionClosed());

        ArgumentCaptor<String> warnings = ArgumentCaptor.forClass(String.class);
        verify(this.logger, atLeastOnce()).warning(warnings.capture());
        assertTrue(warnings.getValue().contains("<IP address withheld>"));
        assertFalse(warnings.getAllValues().stream().anyMatch(message -> message.contains(String.valueOf(channel.remoteAddress()))));
    }

    @Test
    void connectionsThatAreNotCleanedUpAreReportedOnce() {
        EmbeddedChannel channel = new EmbeddedChannel();
        this.channels.add(channel);
        // Aborted, but its network thread never got to close it
        new GeyserBedrockPeer(channel, (p, id) -> null, this.policy, this.monitor) {
            @Override
            public boolean isAdmissionClosed() {
                return true;
            }
        };
        this.monitor.scan();
        this.advance(this.limits.stallMillis() - 1000);
        this.monitor.scan();
        verify(this.logger, times(0)).warning(contains("has not been cleaned up"));

        this.advance(2000);
        this.monitor.scan();
        this.monitor.scan();
        verify(this.logger, times(1)).warning(contains("has not been cleaned up"));
        assertEquals(1, this.monitor.monitoredConnections(), "still watched; the monitor does not release the queue itself");
    }

    @Test
    void aStalledEventLoopIsReportedWithItsStack() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop();
        CountDownLatch release = new CountDownLatch(1);
        try {
            Thread loopThread = loop.submit(Thread::currentThread).get();
            loop.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Channel stuckChannel = mock(Channel.class);
            when(stuckChannel.eventLoop()).thenReturn(loop);
            when(stuckChannel.isOpen()).thenReturn(true);

            EmbeddedChannel channel = new EmbeddedChannel();
            this.channels.add(channel);
            new GeyserBedrockPeer(channel, (p, id) -> null, this.policy, this.monitor) {
                @Override
                public boolean isAdmissionClosed() {
                    return true;
                }

                @Override
                public Channel getChannel() {
                    return stuckChannel;
                }
            };
            this.monitor.scan();
            this.advance(this.limits.stallMillis() + 1000);
            this.monitor.scan();

            ArgumentCaptor<String> warning = ArgumentCaptor.forClass(String.class);
            verify(this.logger).warning(warning.capture());
            assertTrue(warning.getValue().contains(loopThread.getName()), warning.getValue());
            assertTrue(warning.getValue().contains("CountDownLatch.await"), "the stuck loop's stack is included");
        } finally {
            release.countDown();
            loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Test
    void eventLoopThreadIsOnlyNamedWhenUnambiguous() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop();
        try {
            Thread loopThread = loop.submit(Thread::currentThread).get();
            assertSame(loopThread, TransportMonitor.eventLoopThread(loop, Thread.getAllStackTraces().keySet()));
        } finally {
            loop.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        EmbeddedChannel channel = new EmbeddedChannel();
        this.channels.add(channel);
        assertNull(TransportMonitor.eventLoopThread(channel.eventLoop(), Thread.getAllStackTraces().keySet()),
            "an embedded loop runs on any thread");
    }

    @Test
    void queuePeaksAreKeptUntilTheMonitorTakesThem() {
        GeyserBedrockPeer peer = peer();
        EmbeddedChannel channel = (EmbeddedChannel) peer.getChannel();
        for (int i = 0; i < 3; i++) {
            peer.sendPacket(0, 0, new TextPacket());
        }
        long weight = peer.getQueuedPacketWeight();
        channel.advanceTimeBy(1, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertEquals(0, peer.getQueuedPacketCount(), "drained");
        peer.sendPacket(0, 0, new TextPacket());

        assertEquals(3, peer.takePeakQueuedPackets());
        assertEquals(weight, peer.takePeakQueuedWeight());
        assertEquals(1, peer.takePeakQueuedPackets(), "starts again from what is queued now");
    }

    @Test
    void queuePeaksAreLoggedWhenTheConnectionIsCleanedUp() {
        when(this.logger.isDebug()).thenReturn(true);
        EmbeddedChannel channel = new EmbeddedChannel();
        this.channels.add(channel);
        GeyserBedrockPeer peer = new GeyserBedrockPeer(channel, (p, id) -> null, this.policy, this.monitor);
        channel.pipeline().addLast(peer);
        for (int i = 0; i < 3; i++) {
            peer.sendPacket(0, 0, new TextPacket());
        }
        this.advance(5000);
        peer.abortTransport("test");
        assertTrue(peer.isDisposed());
        this.monitor.scan();

        verify(this.logger).debug(contains("cleaned up after 5 seconds; at most 3 packets"));
        assertEquals(0, this.monitor.monitoredConnections());
    }

    @Test
    void peaksAreSummarisedEveryTenMinutes() {
        GeyserBedrockPeer peer = peer();
        EmbeddedChannel channel = (EmbeddedChannel) peer.getChannel();
        peer.sendPacket(0, 0, chunk(64 * 1024));
        peer.sendPacket(0, 0, new TextPacket());
        channel.advanceTimeBy(1, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        this.directUsed.set(300);

        this.monitor.scan();
        verify(this.logger, never()).info(contains("outbound peaks"));
        this.advance(TimeUnit.MINUTES.toMillis(10));
        this.monitor.scan();
        this.advance(TimeUnit.MINUTES.toMillis(10));
        this.monitor.scan();

        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(this.logger, times(2)).info(lines.capture());
        String first = lines.getAllValues().get(0);
        assertTrue(first.contains("1 connections (1 now)"), first);
        assertTrue(first.contains("2 of " + this.limits.peerMaxPackets() + " packets"), first);
        assertFalse(first.contains("not measured"), first);
        String second = lines.getAllValues().get(1);
        assertTrue(second.contains("per connection 0 of " + this.limits.peerMaxPackets() + " packets"), "peaks restart with each summary: " + second);
        assertFalse(peer.isAdmissionClosed());
    }

    @Test
    void noSummaryWithoutConnections() {
        this.advance(TimeUnit.MINUTES.toMillis(10));
        this.monitor.scan();
        verify(this.logger, never()).info(contains("outbound peaks"));
    }

    private static LevelChunkPacket chunk(int size) {
        LevelChunkPacket packet = new LevelChunkPacket();
        packet.setData(Unpooled.buffer(size).writeZero(size));
        return packet;
    }
}
