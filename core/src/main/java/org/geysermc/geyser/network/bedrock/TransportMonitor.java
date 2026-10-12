/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
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

import io.netty.util.NettyRuntime;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.RakOutboundBudget;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.network.bedrock.GeyserOutboundPolicy.Pressure;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Watches every Bedrock connection from its own thread, independently of player ticking, and aborts connections
 * whose outbound work stopped making progress, whose client stopped responding, or that hold a large share of their
 * limits while direct memory is running out.
 * <p>
 * Connections register when their peer is created and are only forgotten once the peer has been closed and its
 * queue released, so connections whose session already ended are still watched until cleanup completes. A connection
 * that stopped accepting packets but is not cleaned up within the stall time is reported once, with the stack of its
 * event loop thread; the monitor never releases another thread's queue itself.
 * <p>
 * It also records how close connections come to the outbound limits, so they can be calibrated: one debug line per
 * connection when it is cleaned up, and an info line with the server-wide peaks every 10 minutes.
 */
public final class TransportMonitor {
    private static final long LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    /**
     * How often the stack of the same event loop thread may be logged for connections that are not cleaned up.
     */
    private static final long STACK_DUMP_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final long SUMMARY_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final GeyserLogger logger;
    private final OutboundLimits limits;
    private final GeyserOutboundPolicy policy;
    private final BooleanSupplier logAddresses;
    private final LongSupplier nanoClock;
    private final LongSupplier millisClock;
    private final @Nullable LongSupplier jdkDirectMemoryUsed;
    private final LongSupplier nettyDirectMemoryUsed;
    private final long directMemoryMax;
    private final Map<GeyserBedrockPeer, Watch> peers = new ConcurrentHashMap<>();
    // Only used by the monitoring pass
    private final Map<EventExecutor, Long> lastStackDumpNanos = new HashMap<>();

    private ScheduledExecutorService executor;
    private volatile boolean stopped;
    private long lastErrorLogNanos = Long.MIN_VALUE / 2;
    private final Peaks peaks = new Peaks();
    private long peaksSinceNanos;

    public TransportMonitor(GeyserLogger logger, OutboundLimits limits, GeyserOutboundPolicy policy) {
        this(logger, limits, policy, GeyserOutboundPolicy.configuredLogAddresses());
    }

    /**
     * @param logAddresses whether warnings may include the connection's address
     */
    public TransportMonitor(GeyserLogger logger, OutboundLimits limits, GeyserOutboundPolicy policy, BooleanSupplier logAddresses) {
        this(logger, limits, policy, logAddresses, System::nanoTime, System::currentTimeMillis, jdkDirectMemoryUsedSupplier(),
            PlatformDependent::usedDirectMemory, PlatformDependent.maxDirectMemory());
    }

    /**
     * @param jdkDirectMemoryUsed the JDK's direct buffer pool, or null if it is not available
     * @param nettyDirectMemoryUsed Netty's own direct memory counter, which reports -1 unless Netty allocates direct
     *                              buffers without the JDK's cleaner (and so without the JDK pool seeing them)
     */
    TransportMonitor(GeyserLogger logger, OutboundLimits limits, GeyserOutboundPolicy policy, BooleanSupplier logAddresses,
                     LongSupplier nanoClock, LongSupplier millisClock, @Nullable LongSupplier jdkDirectMemoryUsed,
                     LongSupplier nettyDirectMemoryUsed, long directMemoryMax) {
        this.logger = logger;
        this.limits = limits;
        this.policy = policy;
        this.logAddresses = logAddresses;
        this.nanoClock = nanoClock;
        this.millisClock = millisClock;
        this.jdkDirectMemoryUsed = jdkDirectMemoryUsed;
        this.nettyDirectMemoryUsed = nettyDirectMemoryUsed;
        this.directMemoryMax = directMemoryMax;
        this.peaksSinceNanos = nanoClock.getAsLong();
    }

    public synchronized void start() {
        if (this.executor != null || this.stopped) {
            return;
        }
        this.logDirectMemorySource();
        this.logEventLoopSizing();
        this.executor = Executors.newSingleThreadScheduledExecutor(new DefaultThreadFactory("Geyser Transport Monitor", true));
        this.executor.scheduleWithFixedDelay(this::scan, this.limits.monitorIntervalMillis(), this.limits.monitorIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Stops monitoring and forgets every connection. Connections registering afterwards are ignored.
     */
    public synchronized void shutdown() {
        this.stopped = true;
        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }
        this.peers.clear();
    }

    public void register(GeyserBedrockPeer peer) {
        if (!this.stopped) {
            this.peers.putIfAbsent(peer, new Watch(this.nanoClock.getAsLong()));
        }
    }

    /**
     * @return connections still being watched, including ones waiting for their cleanup to complete
     */
    public int monitoredConnections() {
        return this.peers.size();
    }

    /**
     * One monitoring pass. Never throws, so the schedule survives any failure.
     */
    void scan() {
        try {
            this.updatePressure();
            long nowNanos = this.nanoClock.getAsLong();
            long nowMillis = this.millisClock.getAsLong();
            this.peaks.connections = Math.max(this.peaks.connections, this.peers.size());
            this.peaks.sharedPackets = Math.max(this.peaks.sharedPackets, this.policy.sharedQueuedPackets());
            this.peaks.sharedWeight = Math.max(this.peaks.sharedWeight, this.policy.sharedQueuedWeight());
            this.peaks.rakTotalOutstanding = Math.max(this.peaks.rakTotalOutstanding, RakOutboundBudget.totalOutstandingBytes());
            for (Map.Entry<GeyserBedrockPeer, Watch> entry : this.peers.entrySet()) {
                GeyserBedrockPeer peer = entry.getKey();
                try {
                    this.sample(peer, entry.getValue());
                    this.check(peer, entry.getValue(), nowNanos, nowMillis);
                } catch (Throwable throwable) {
                    // One bad connection must not stop the others from being checked
                    this.logError("Failed to check Bedrock connection " + this.address(peer), throwable);
                }
            }
            if (nowNanos - this.peaksSinceNanos >= SUMMARY_INTERVAL_NANOS) {
                this.logPeaks();
                this.peaks.reset();
                this.peaksSinceNanos = nowNanos;
            }
        } catch (Throwable throwable) {
            this.logError("Bedrock connection monitoring pass failed; connections may not be checked until the next pass", throwable);
        }
    }

    private void check(GeyserBedrockPeer peer, Watch watch, long nowNanos, long nowMillis) {
        if (peer.isDisposed()) {
            this.peers.remove(peer);
            this.logConnectionPeaks(peer, watch, nowNanos);
            return;
        }
        long stallNanos = TimeUnit.MILLISECONDS.toNanos(this.limits.stallMillis());
        if (peer.isAdmissionClosed()) {
            // Already aborted or closing; waiting for the network thread to release its queue
            this.checkCleanup(peer, watch, nowNanos, stallNanos);
            return;
        }

        long queuedWait = peer.getPendingWaitNanos(nowNanos);
        if (queuedWait > stallNanos) {
            this.abort(peer, watch, nowNanos, peer.getQueuedPacketCount() + " queued packets were not sent for "
                + TimeUnit.NANOSECONDS.toSeconds(queuedWait) + " seconds");
            return;
        }

        Pressure pressure = this.policy.pressure();
        if (pressure != Pressure.NORMAL && peer.getQueuedPacketWeight() > this.limits.peerMaxWeight() / 2) {
            this.abort(peer, watch, nowNanos, "direct memory is running low and the connection has " + peer.getQueuedPacketWeight() + " bytes queued");
            return;
        }

        if (peer.getChannel() instanceof RakChildChannel rakChannel && rakChannel.isOpen()) {
            RakSessionCodec codec = rakChannel.getSessionCodec();
            long timeout = rakChannel.config().getOption(RakChannelOption.RAK_SESSION_TIMEOUT);
            long silentMillis = nowMillis - codec.getLastTouched();
            if (silentMillis > timeout + this.limits.unresponsiveGraceMillis()) {
                this.abort(peer, watch, nowNanos, "the client has not responded for " + TimeUnit.MILLISECONDS.toSeconds(silentMillis) + " seconds");
                return;
            }

            long pendingWait = rakChannel.getPendingWaitNanos(nowNanos);
            if (pendingWait > stallNanos) {
                this.abort(peer, watch, nowNanos, "RakNet did not accept pending writes for " + TimeUnit.NANOSECONDS.toSeconds(pendingWait) + " seconds");
                return;
            }

            long outstanding = codec.getOutstandingBytes() + rakChannel.getPendingBytes();
            if (pressure != Pressure.NORMAL && outstanding > this.limits.rakMaxOutstandingBytes() / 2) {
                this.abort(peer, watch, nowNanos, "direct memory is running low and the connection has " + outstanding + " bytes outstanding");
            }
        }
    }

    private void abort(GeyserBedrockPeer peer, Watch watch, long nowNanos, String reason) {
        this.logger.warning("Closing Bedrock connection " + this.address(peer) + ": " + reason);
        this.peaks.aborted++;
        watch.markClosing(nowNanos);
        peer.abortTransport(reason);
    }

    /**
     * Records how much the connection has queued and how much RakNet holds for it. The queue's peak is tracked by
     * the peer on every send; RakNet's outstanding bytes are only sampled here.
     */
    private void sample(GeyserBedrockPeer peer, Watch watch) {
        long packets = peer.takePeakQueuedPackets();
        long weight = peer.takePeakQueuedWeight();
        watch.peakPackets = Math.max(watch.peakPackets, packets);
        watch.peakWeight = Math.max(watch.peakWeight, weight);
        this.peaks.peerPackets = Math.max(this.peaks.peerPackets, packets);
        this.peaks.peerWeight = Math.max(this.peaks.peerWeight, weight);
        if (peer.getChannel() instanceof RakChildChannel rakChannel && rakChannel.isOpen()) {
            RakSessionCodec codec = rakChannel.getSessionCodec();
            if (codec != null) {
                long outstanding = codec.getOutstandingBytes() + rakChannel.getPendingBytes();
                watch.peakRakOutstanding = Math.max(watch.peakRakOutstanding, outstanding);
                this.peaks.rakOutstanding = Math.max(this.peaks.rakOutstanding, outstanding);
            }
        }
    }

    private void logConnectionPeaks(GeyserBedrockPeer peer, Watch watch, long nowNanos) {
        if (!this.logger.isDebug()) {
            return;
        }
        this.logger.debug("Bedrock connection " + this.address(peer) + " cleaned up after "
            + TimeUnit.NANOSECONDS.toSeconds(nowNanos - watch.registeredNanos) + " seconds; at most "
            + watch.peakPackets + " packets and " + kib(watch.peakWeight) + " KiB were queued, and "
            + kib(watch.peakRakOutstanding) + " KiB outstanding in RakNet");
    }

    private void logPeaks() {
        Peaks p = this.peaks;
        if (p.connections == 0) {
            return;
        }
        String directMemory = p.directMemoryUsed < 0 || this.directMemoryMax <= 0 ? "not measured"
            : (p.directMemoryUsed >> 20) + " of " + (this.directMemoryMax >> 20) + " MiB";
        this.logger.info("Bedrock outbound peaks over the last " + TimeUnit.NANOSECONDS.toMinutes(SUMMARY_INTERVAL_NANOS)
            + " minutes: " + p.connections + " connections (" + this.peers.size() + " now); per connection "
            + p.peerPackets + " of " + this.limits.peerMaxPackets() + " packets and "
            + kib(p.peerWeight) + " of " + kib(this.limits.peerMaxWeight()) + " KiB queued, "
            + kib(p.rakOutstanding) + " of " + kib(this.limits.rakMaxOutstandingBytes()) + " KiB outstanding in RakNet; server-wide "
            + p.sharedPackets + " of " + this.limits.sharedMaxPackets() + " packets and "
            + kib(p.sharedWeight) + " of " + kib(this.limits.sharedMaxWeight()) + " KiB queued, "
            + kib(p.rakTotalOutstanding) + " of " + kib(this.limits.rakMaxTotalOutstandingBytes()) + " KiB outstanding in RakNet; direct memory "
            + directMemory + "; " + p.aborted + " connections closed by the monitor");
    }

    private static long kib(long bytes) {
        return bytes >> 10;
    }

    /**
     * Reports, once, a connection that stopped accepting packets but whose network thread has not cleaned it up
     * within the stall time. Diagnostic only: its queue may only be released by its own network thread.
     */
    private void checkCleanup(GeyserBedrockPeer peer, Watch watch, long nowNanos, long stallNanos) {
        // Closed by the policy, the session or the network thread: start counting from when this was first seen
        watch.markClosing(nowNanos);
        long waited = nowNanos - watch.closingSinceNanos;
        if (watch.cleanupReported || waited <= stallNanos) {
            return;
        }
        watch.cleanupReported = true;

        EventExecutor loop = eventLoop(peer);
        Map<Thread, StackTraceElement[]> stacks = loop == null ? Map.of() : Thread.getAllStackTraces();
        Thread thread = loop == null ? null : eventLoopThread(loop, stacks.keySet());
        StringBuilder message = new StringBuilder("Bedrock connection ").append(this.address(peer))
            .append(" stopped accepting packets ").append(TimeUnit.NANOSECONDS.toSeconds(waited))
            .append(" seconds ago but has not been cleaned up (channel open: ").append(peer.getChannel().isOpen())
            .append(", closed: ").append(peer.isClosed()).append("); ");
        if (thread == null) {
            message.append("its event loop may be stalled");
        } else {
            message.append("its event loop thread ").append(thread.getName()).append(" may be stalled");
            Long lastDump = this.lastStackDumpNanos.get(loop);
            if (lastDump == null || nowNanos - lastDump >= STACK_DUMP_INTERVAL_NANOS) {
                this.lastStackDumpNanos.put(loop, nowNanos);
                message.append(", currently at:");
                for (StackTraceElement element : stacks.getOrDefault(thread, new StackTraceElement[0])) {
                    message.append(System.lineSeparator()).append("\tat ").append(element);
                }
            }
        }
        this.logger.warning(message.toString());
    }

    private static @Nullable EventExecutor eventLoop(GeyserBedrockPeer peer) {
        try {
            return peer.getChannel().eventLoop();
        } catch (IllegalStateException e) {
            // Never registered
            return null;
        }
    }

    /**
     * @return the only one of {@code threads} that {@code loop} runs on, or null if that is not exactly one
     */
    static @Nullable Thread eventLoopThread(EventExecutor loop, Iterable<Thread> threads) {
        Thread found = null;
        for (Thread thread : threads) {
            if (loop.inEventLoop(thread)) {
                if (found != null) {
                    // Not bound to a single thread (an embedded loop, for example)
                    return null;
                }
                found = thread;
            }
        }
        return found;
    }

    private String address(GeyserBedrockPeer peer) {
        return GeyserOutboundPolicy.loggableAddress(peer, this.logAddresses);
    }

    private void updatePressure() {
        long max = this.directMemoryMax;
        long used = directMemoryUsed(this.jdkDirectMemoryUsed == null ? -1 : this.jdkDirectMemoryUsed.getAsLong(),
            this.nettyDirectMemoryUsed.getAsLong());
        if (max <= 0 || used < 0) {
            return;
        }
        this.peaks.directMemoryUsed = Math.max(this.peaks.directMemoryUsed, used);
        double ratio = (double) used / max;
        Pressure current = this.policy.pressure();
        Pressure next;
        if (ratio >= this.limits.pressureStop()) {
            next = Pressure.STOP;
        } else if (ratio < this.limits.pressureResume()) {
            next = Pressure.NORMAL;
        } else if (ratio >= this.limits.pressureSoft() && current == Pressure.NORMAL) {
            next = Pressure.SOFT;
        } else {
            next = current;
        }
        if (next != current) {
            this.policy.setPressure(next);
            String usage = String.format("%.0f%% (%d of %d MiB)", ratio * 100, used >> 20, max >> 20);
            if (next == Pressure.NORMAL) {
                this.logger.info("Direct memory usage is back to " + usage + "; lifting outbound restrictions");
            } else {
                this.logger.warning("Direct memory usage is at " + usage + "; "
                    + (next == Pressure.STOP ? "refusing large packets while outbound queues are over half full, and " : "") + "closing connections with large outbound backlogs");
            }
        }
    }

    private void logError(String message, Throwable throwable) {
        long now = this.nanoClock.getAsLong();
        if (now - this.lastErrorLogNanos >= LOG_INTERVAL_NANOS) {
            this.lastErrorLogNanos = now;
            this.logger.error(message, throwable);
        }
    }

    private void logDirectMemorySource() {
        boolean jdk = this.jdkDirectMemoryUsed != null && this.jdkDirectMemoryUsed.getAsLong() >= 0;
        boolean netty = this.nettyDirectMemoryUsed.getAsLong() >= 0;
        if (this.directMemoryMax <= 0 || (!jdk && !netty)) {
            this.logger.warning("Direct memory usage cannot be measured; outbound limits will not react to direct memory running low");
            return;
        }
        String source;
        if (jdk && netty) {
            source = "the larger of the JDK direct buffer pool and Netty's direct memory counter";
        } else if (jdk) {
            source = "the JDK direct buffer pool (Netty's direct memory counter is not in use)";
        } else {
            source = "Netty's direct memory counter (the JDK direct buffer pool is not available)";
        }
        this.logger.info("Direct memory pressure is measured from " + source + ", against a maximum of " + (this.directMemoryMax >> 20) + " MiB");
    }

    private void logEventLoopSizing() {
        int processors = NettyRuntime.availableProcessors();
        String configured = SystemPropertyUtil.get("io.netty.eventLoopThreads");
        int threads = Math.max(1, SystemPropertyUtil.getInt("io.netty.eventLoopThreads", processors * 2));
        this.logger.info("Netty sees " + processors + " processors (the JVM reports " + Runtime.getRuntime().availableProcessors()
            + "), so each Bedrock event loop group may run " + threads + " threads"
            + (configured == null ? " (io.netty.eventLoopThreads is not set)" : " (io.netty.eventLoopThreads=" + configured + ")"));
    }

    /**
     * Netty counts the direct buffers it allocates without the JDK's cleaner only in its own counter, and reports -1
     * from it when it uses the cleaner (then the JDK pool sees its buffers). Taking the larger of both measures the
     * right one either way.
     *
     * @return direct memory in use, or -1 if neither source is available
     */
    static long directMemoryUsed(long jdkUsed, long nettyUsed) {
        return Math.max(Math.max(jdkUsed, nettyUsed), -1);
    }

    private static @Nullable LongSupplier jdkDirectMemoryUsedSupplier() {
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                // Reads JDK counters only; does not touch Netty's allocator or its locks
                return pool::getMemoryUsed;
            }
        }
        return null;
    }

    /**
     * What the monitoring pass remembers about a connection. Only used by the monitoring pass.
     */
    private static final class Watch {
        private final long registeredNanos;
        private boolean closing;
        private long closingSinceNanos;
        private boolean cleanupReported;
        private long peakPackets;
        private long peakWeight;
        private long peakRakOutstanding;

        Watch(long registeredNanos) {
            this.registeredNanos = registeredNanos;
        }

        void markClosing(long nowNanos) {
            if (!this.closing) {
                this.closing = true;
                this.closingSinceNanos = nowNanos;
            }
        }
    }

    /**
     * Server-wide peaks since the last summary. Only used by the monitoring pass.
     */
    private static final class Peaks {
        private long connections;
        private long peerPackets;
        private long peerWeight;
        private long rakOutstanding;
        private long sharedPackets;
        private long sharedWeight;
        private long rakTotalOutstanding;
        private long directMemoryUsed = -1;
        private long aborted;

        void reset() {
            this.connections = 0;
            this.peerPackets = 0;
            this.peerWeight = 0;
            this.rakOutstanding = 0;
            this.sharedPackets = 0;
            this.sharedWeight = 0;
            this.rakTotalOutstanding = 0;
            this.directMemoryUsed = -1;
            this.aborted = 0;
        }
    }
}
