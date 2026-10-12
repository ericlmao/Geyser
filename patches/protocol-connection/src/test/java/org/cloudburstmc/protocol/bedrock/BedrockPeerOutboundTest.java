package org.cloudburstmc.protocol.bedrock;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.protocol.bedrock.BedrockOutboundPolicy.Rejection;
import org.cloudburstmc.protocol.bedrock.TestSupport.PausablePeer;
import org.cloudburstmc.protocol.bedrock.TestSupport.RefPacket;
import org.cloudburstmc.protocol.bedrock.TestSupport.TestPolicy;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockPeerOutboundTest {
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        this.channel = new EmbeddedChannel();
    }

    @AfterEach
    void tearDown() {
        Object msg;
        while ((msg = this.channel.readOutbound()) != null) {
            ReferenceCountUtil.release(msg);
        }
        this.channel.finishAndReleaseAll();
    }

    @Test
    void pausedLoopBoundsEverySendPathUnderConcurrentProducers() throws Exception {
        int limit = 64;
        TestPolicy policy = new TestPolicy(limit, 0, 256, Long.MAX_VALUE);
        PausablePeer peer = new PausablePeer(this.channel, policy);

        int threads = 8;
        int perThread = 1000; // 125x the limit in total
        List<RefPacket> sent = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService producers = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            int thread = t;
            producers.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    RefPacket packet = new RefPacket(thread * perThread + i);
                    sent.add(packet);
                    switch (i % 4) {
                        case 0 -> peer.sendPacket(0, 0, packet);
                        case 1 -> peer.sendPacketImmediately(0, 0, packet);
                        case 2 -> peer.sendPacket(BedrockPacketWrapper.create(0, 0, 0, packet, null));
                        default -> {
                            RefPacket second = new RefPacket(-1);
                            sent.add(second);
                            peer.sendPacketsImmediately(0, 0, packet, second);
                        }
                    }
                }
            });
        }
        start.countDown();
        producers.shutdown();
        assertTrue(producers.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(peer.getQueuedPacketCount() <= limit, "queued " + peer.getQueuedPacketCount());
        assertEquals(peer.getQueuedPacketCount(), policy.sharedPackets.get());
        assertTrue(peer.isAdmissionClosed());
        assertFalse(peer.isConnected());
        assertEquals(1, policy.overloads.get());
        assertEquals(Rejection.PEER_PACKET_LIMIT, policy.lastRejection);
        // Producers never ran network work, and only a bounded number of tasks is waiting for the loop
        assertTrue(peer.tasks.size() <= 3, "pending tasks " + peer.tasks.size());

        long retained = sent.stream().filter(p -> p.refCnt() > 0).count();
        assertEquals(peer.getQueuedPacketCount(), retained);

        // The loop resumes: the abort closes the channel without writing anything, then cleanup releases the queue
        peer.runAll();
        assertFalse(this.channel.isOpen());
        assertNull(this.channel.readOutbound());
        peer.closeOnLoop();
        assertEquals(0, peer.getQueuedPacketCount());
        assertEquals(0, policy.sharedPackets.get());
        assertTrue(sent.stream().allMatch(p -> p.refCnt() == 0), "every packet released exactly once");
    }

    @Test
    void immediateSendsArePartOfTheSameBudget() {
        TestPolicy policy = new TestPolicy(10, 0, 256, Long.MAX_VALUE);
        PausablePeer peer = new PausablePeer(this.channel, policy);
        List<RefPacket> sent = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            RefPacket packet = new RefPacket(i);
            sent.add(packet);
            peer.sendPacketImmediately(0, 0, packet);
        }
        assertEquals(10, peer.getQueuedPacketCount());
        assertTrue(peer.tasks.size() <= 2, "one drain and one abort at most, got " + peer.tasks.size());
        assertEquals(990, sent.stream().filter(p -> p.refCnt() == 0).count());
        peer.runAll();
        peer.closeOnLoop();
        assertTrue(sent.stream().allMatch(p -> p.refCnt() == 0));
    }

    @Test
    void queuedAndImmediatePacketsKeepTheirOrder() {
        TestPolicy policy = new TestPolicy(100, 0, 256, Long.MAX_VALUE);
        PausablePeer peer = new PausablePeer(this.channel, policy);
        peer.sendPacket(0, 0, new RefPacket(1));
        peer.sendPacketImmediately(0, 0, new RefPacket(2));
        peer.sendPacket(0, 0, new RefPacket(3));
        peer.sendPacketsImmediately(0, 0, new RefPacket(4), new RefPacket(5));
        peer.runAll();
        List<Integer> order = new ArrayList<>();
        BedrockPacketWrapper wrapper;
        while ((wrapper = this.channel.readOutbound()) != null) {
            order.add(((RefPacket) wrapper.getPacket()).id);
            wrapper.release();
        }
        assertEquals(List.of(1, 2, 3, 4, 5), order);
        assertEquals(0, peer.getQueuedPacketCount());
        assertEquals(0, policy.sharedPackets.get());
    }

    @Test
    void drainYieldsAfterItsBatch() {
        TestPolicy policy = new TestPolicy(100, 0, 4, Long.MAX_VALUE);
        PausablePeer peer = new PausablePeer(this.channel, policy);
        for (int i = 0; i < 10; i++) {
            peer.sendPacket(0, 0, new RefPacket(i));
        }
        assertEquals(1, peer.tasks.size(), "one delayed flush for the whole burst");
        peer.runOne();
        assertEquals(4, this.channel.outboundMessages().size());
        assertEquals(6, peer.getQueuedPacketCount());
        assertEquals(1, peer.tasks.size(), "the rest continues in a separate task");
        peer.runAll();
        assertEquals(10, this.channel.outboundMessages().size());
        assertEquals(0, peer.getQueuedPacketCount());
    }

    @Test
    void weightLimitAdmitsOneOversizedPacketWhenEmpty() {
        TestPolicy policy = new TestPolicy(100, 1000, 256, Long.MAX_VALUE);
        PausablePeer peer = new PausablePeer(this.channel, policy);
        RefPacket big = new RefPacket(1, 5000);
        peer.sendPacket(0, 0, big);
        assertEquals(1, peer.getQueuedPacketCount());
        assertEquals(5000, peer.getQueuedPacketWeight());
        RefPacket next = new RefPacket(2, 10);
        peer.sendPacket(0, 0, next);
        assertEquals(0, next.refCnt());
        assertEquals(Rejection.PEER_WEIGHT_LIMIT, policy.lastRejection);
        assertTrue(peer.isAdmissionClosed());
    }

    @Test
    void sharedLimitAbortsThePeerThatExceedsIt() {
        TestPolicy policy = new TestPolicy(100, 0, 256, 3);
        PausablePeer first = new PausablePeer(this.channel, policy);
        EmbeddedChannel otherChannel = new EmbeddedChannel();
        PausablePeer second = new PausablePeer(otherChannel, policy);
        for (int i = 0; i < 3; i++) {
            first.sendPacket(0, 0, new RefPacket(i));
        }
        RefPacket rejected = new RefPacket(99);
        second.sendPacket(0, 0, rejected);
        assertEquals(0, rejected.refCnt());
        assertEquals(Rejection.SHARED_LIMIT, policy.lastRejection);
        assertTrue(second.isAdmissionClosed());
        assertFalse(first.isAdmissionClosed());
        assertEquals(0, second.getQueuedPacketCount());
        assertEquals(3, policy.sharedPackets.get());
        otherChannel.finishAndReleaseAll();
    }

    @Test
    void partiallyAdmittedImmediateArrayKeepsOrderAndReleasesTheRest() {
        TestPolicy policy = new TestPolicy(3, 0, 256, Long.MAX_VALUE);
        PausablePeer peer = new PausablePeer(this.channel, policy);
        RefPacket[] packets = new RefPacket[5];
        for (int i = 0; i < packets.length; i++) {
            packets[i] = new RefPacket(i);
        }
        peer.sendPacketsImmediately(0, 0, packets);
        assertEquals(3, peer.getQueuedPacketCount());
        assertEquals(1, packets[2].refCnt());
        assertEquals(0, packets[3].refCnt());
        assertEquals(0, packets[4].refCnt());
        assertTrue(peer.isAdmissionClosed());
        // The abort was requested before the drain could run; nothing is written once the peer is closing
        peer.runAll();
        assertNull(this.channel.readOutbound());
        peer.closeOnLoop();
        for (RefPacket packet : packets) {
            assertEquals(0, packet.refCnt());
        }
    }

    @Test
    void abortIsIdempotentAndIndependentOfTheLoop() {
        TestPolicy policy = new TestPolicy(100, 0, 256, Long.MAX_VALUE);
        PausablePeer peer = new PausablePeer(this.channel, policy);
        peer.sendPacket(0, 0, new RefPacket(1));
        assertTrue(peer.abort("test"));
        assertFalse(peer.abort("test"));
        // Admission closed on this thread even though the loop has not run
        assertTrue(peer.isAdmissionClosed());
        RefPacket late = new RefPacket(2);
        peer.sendPacketImmediately(0, 0, late);
        assertEquals(0, late.refCnt());
        assertEquals(0, policy.overloads.get(), "a closed peer does not count as overloaded");
        peer.runAll();
        assertFalse(this.channel.isOpen());
        peer.closeOnLoop();
        assertEquals(0, peer.getQueuedPacketCount());
    }

    @Test
    void packetsPolledBeforeTheChannelClosedAreReleasedByTheChannel() {
        TestPolicy policy = new TestPolicy(100, 0, 256, Long.MAX_VALUE);
        PausablePeer peer = new PausablePeer(this.channel, policy);
        RefPacket packet = new RefPacket(1);
        peer.sendPacket(0, 0, packet);
        // The channel dies while the packet is queued; the drain hands it over and the failed write releases it
        this.channel.close();
        peer.runAll();
        assertEquals(0, packet.refCnt());
        assertEquals(0, peer.getQueuedPacketCount());
        assertEquals(0, policy.sharedPackets.get());
    }

    @Test
    void cleanupReleasesEverythingExactlyOnce() {
        TestPolicy policy = new TestPolicy(100, 0, 256, Long.MAX_VALUE);
        PausablePeer peer = new PausablePeer(this.channel, policy);
        List<RefPacket> packets = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            RefPacket packet = new RefPacket(i);
            packets.add(packet);
            peer.sendPacket(0, 0, packet);
        }
        peer.closeOnLoop();
        peer.closeOnLoop(); // repeated cleanup must not release anything twice
        assertTrue(packets.stream().allMatch(p -> p.refCnt() == 0));
        assertEquals(0, peer.getQueuedPacketCount());
        assertEquals(0, policy.sharedPackets.get());
        assertTrue(peer.isClosed());

        RefPacket late = new RefPacket(100);
        peer.sendPacket(0, 0, late);
        assertEquals(0, late.refCnt());
        assertEquals(0, peer.getQueuedPacketCount());
    }
}
