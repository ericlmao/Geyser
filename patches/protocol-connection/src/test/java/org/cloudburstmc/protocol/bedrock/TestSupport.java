package org.cloudburstmc.protocol.bedrock;

import io.netty.channel.Channel;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketType;
import org.cloudburstmc.protocol.common.PacketSignal;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class TestSupport {
    private TestSupport() {
    }

    /**
     * A reference counted packet, so tests can verify that every packet is released exactly once.
     */
    static final class RefPacket extends AbstractReferenceCounted implements BedrockPacket {
        final int id;
        final long weight;

        RefPacket(int id) {
            this(id, 1);
        }

        RefPacket(int id, long weight) {
            this.id = id;
            this.weight = weight;
        }

        @Override
        protected void deallocate() {
        }

        @Override
        public ReferenceCounted touch(Object hint) {
            return this;
        }

        @Override
        public PacketSignal handle(BedrockPacketHandler handler) {
            return PacketSignal.UNHANDLED;
        }

        @Override
        public BedrockPacketType getPacketType() {
            return BedrockPacketType.UNKNOWN;
        }

        @Override
        public BedrockPacket clone() {
            throw new UnsupportedOperationException();
        }
    }

    static final class TestPolicy implements BedrockOutboundPolicy {
        final int maxPackets;
        final long maxWeight;
        final int drain;
        final long sharedMaxPackets;
        final AtomicLong sharedPackets = new AtomicLong();
        final AtomicLong sharedWeight = new AtomicLong();
        final AtomicInteger overloads = new AtomicInteger();
        volatile Rejection lastRejection;

        TestPolicy(int maxPackets, long maxWeight, int drain, long sharedMaxPackets) {
            this.maxPackets = maxPackets;
            this.maxWeight = maxWeight;
            this.drain = drain;
            this.sharedMaxPackets = sharedMaxPackets;
        }

        @Override
        public int maxQueuedPackets() {
            return this.maxPackets;
        }

        @Override
        public long maxQueuedWeight() {
            return this.maxWeight;
        }

        @Override
        public int maxDrainPackets() {
            return this.drain;
        }

        @Override
        public long estimateWeight(BedrockPacket packet) {
            return packet instanceof RefPacket ref ? ref.weight : 1;
        }

        @Override
        public boolean tryReserveShared(int packets, long weight) {
            if (this.sharedPackets.addAndGet(packets) > this.sharedMaxPackets) {
                this.sharedPackets.addAndGet(-packets);
                return false;
            }
            this.sharedWeight.addAndGet(weight);
            return true;
        }

        @Override
        public void releaseShared(int packets, long weight) {
            this.sharedPackets.addAndGet(-packets);
            this.sharedWeight.addAndGet(-weight);
        }

        @Override
        public void onOverload(BedrockPeer peer, Rejection rejection) {
            this.lastRejection = rejection;
            this.overloads.incrementAndGet();
        }
    }

    /**
     * A peer whose event loop only runs when the test says so. Producers on other threads see a busy loop.
     */
    static final class PausablePeer extends BedrockPeer {
        private static final ThreadLocal<Boolean> ON_LOOP = ThreadLocal.withInitial(() -> false);
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        PausablePeer(Channel channel, BedrockOutboundPolicy policy) {
            super(channel, (peer, id) -> null, policy);
        }

        @Override
        protected void scheduleFlushTask() {
            this.tasks.add(this::flushPacketQueue);
        }

        @Override
        protected void executeOnEventLoop(Runnable task) {
            this.tasks.add(task);
        }

        @Override
        protected boolean isOnEventLoop() {
            return ON_LOOP.get();
        }

        @Override
        protected void scheduleRejectedRetry(Runnable retry) {
            this.tasks.add(retry);
        }

        /**
         * Runs one pending task on the "event loop".
         */
        boolean runOne() {
            Runnable task = this.tasks.poll();
            if (task == null) {
                return false;
            }
            ON_LOOP.set(true);
            try {
                task.run();
            } finally {
                ON_LOOP.set(false);
            }
            return true;
        }

        void runAll() {
            //noinspection StatementWithEmptyBody
            while (this.runOne()) {
            }
        }

        void closeOnLoop() {
            ON_LOOP.set(true);
            try {
                this.channel.close();
                this.onClose();
            } finally {
                ON_LOOP.set(false);
            }
        }
    }
}
