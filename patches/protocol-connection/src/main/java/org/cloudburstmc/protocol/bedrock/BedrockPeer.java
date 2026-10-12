package org.cloudburstmc.protocol.bedrock;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.DecoderException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v428.Bedrock_v428;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.BlackholeInboundAdapter;
import org.cloudburstmc.protocol.bedrock.netty.codec.FrameIdCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.batch.BedrockBatchDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.encryption.BedrockEncryptionDecoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.encryption.BedrockEncryptionEncoder;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockChannelInitializer;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;

import javax.crypto.SecretKey;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Bedrock peer that represents a single network connection to the remote peer.
 * It can hold one or more {@link BedrockSession}s.
 * <p>
 * Patched by Geyser (see patches/README.md), derived from bedrock-connection 3.0.0.Beta14-20261009.172637-6: every
 * outbound packet, including immediate sends, is admitted through {@link BedrockOutboundPolicy} before it is queued,
 * the queue is only consumed on the channel's event loop, drains hand over a bounded number of packets at a time, and
 * a peer that exceeds its limits is aborted instead of buffering without bound.
 */
public class BedrockPeer extends ChannelInboundHandlerAdapter {

    public static final String NAME = "bedrock-peer";

    private static final InternalLogger log = InternalLoggerFactory.getInstance(BedrockPeer.class);

    static final String BATCH_FLUSH_DELAY_PROPERTY = "org.cloudburstmc.protocol.bedrock.batchFlushDelayMillis";
    static final int DEFAULT_BATCH_FLUSH_DELAY_MILLIS = 1;
    static final int MAX_BATCH_FLUSH_DELAY_MILLIS = 50;

    /**
     * How long the first packet of a burst waits in the outbound queue for the rest
     * of that burst to join its batch. A producer that hands its packets over
     * together still gets a single batch at any positive delay; the wait only
     * splits bursts that are emitted over a longer stretch than the window, and
     * each resulting batch is then compressed on its own. A wider window trades
     * latency for compression efficiency on such producers, while zero disables
     * coalescing across event loop iterations. Configurable in milliseconds with
     * the {@value #BATCH_FLUSH_DELAY_PROPERTY} system property, read once when this
     * class is initialized.
     * <p>
     * Note that a window is not equivalent to the periodic flush this replaced: it
     * starts at the first enqueue, so a burst handed over together always waits the
     * full window, where a periodic tick of the same period waited half of it on
     * average.
     */
    static final long BATCH_FLUSH_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(
            resolveFlushDelayMillis(SystemPropertyUtil.getInt(BATCH_FLUSH_DELAY_PROPERTY, DEFAULT_BATCH_FLUSH_DELAY_MILLIS)));
    static final long REJECTED_RETRY_DELAY_MILLIS = 10;

    /**
     * Disconnect reason of a peer that was aborted because it exceeded its outbound limits.
     */
    public static final String OUTBOUND_OVERLOAD_REASON = "Outbound queue limit exceeded";

    /*
     * Admission state, updated with a single CAS so that closing admission and admitting a packet are linearizable:
     * bit 62 marks admission as closed, bits 21-61 hold the queued estimated weight and bits 0-20 the queued packet count.
     */
    private static final int COUNT_BITS = 21;
    private static final long COUNT_MASK = (1L << COUNT_BITS) - 1;
    private static final long WEIGHT_MASK = (1L << (62 - COUNT_BITS)) - 1;
    private static final long ADMISSION_CLOSED = 1L << 62;

    protected final Int2ObjectMap<BedrockSession> sessions = new Int2ObjectOpenHashMap<>();
    // Consumed only on the channel's event loop, or by free() once that loop has terminated
    private final Queue<QueuedPacket> packetQueue = PlatformDependent.newMpscQueue();
    protected final Channel channel;
    protected final BedrockSessionFactory sessionFactory;
    protected final BedrockOutboundPolicy outboundPolicy;
    protected final AtomicBoolean flushScheduled = new AtomicBoolean();
    protected final AtomicBoolean drainScheduled = new AtomicBoolean();
    protected final AtomicBoolean flushRetryScheduled = new AtomicBoolean();
    protected final AtomicBoolean closeRetryScheduled = new AtomicBoolean();
    protected final AtomicBoolean freeScheduled = new AtomicBoolean();
    protected final AtomicBoolean abortRequested = new AtomicBoolean();
    protected AtomicBoolean closed = new AtomicBoolean();
    protected AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong admission = new AtomicLong();
    // Event loop confined
    private boolean draining;
    // Monotonic progress markers (System.nanoTime()) for monitoring from other threads
    private volatile long lastDrainNanos = System.nanoTime();
    private volatile long pendingSinceNanos;

    public BedrockPeer(Channel channel, BedrockSessionFactory sessionFactory) {
        this(channel, sessionFactory, BedrockOutboundPolicy.UNLIMITED);
    }

    public BedrockPeer(Channel channel, BedrockSessionFactory sessionFactory, BedrockOutboundPolicy outboundPolicy) {
        this.channel = channel;
        this.sessionFactory = sessionFactory;
        this.outboundPolicy = Objects.requireNonNull(outboundPolicy, "outboundPolicy");
    }

    /**
     * Returns the configured flush delay, falling back to the default when it is
     * outside the supported range. Out of range values are rejected rather than
     * saturated so a typo cannot silently pick the nearest bound.
     */
    static int resolveFlushDelayMillis(int delay) {
        if (delay < 0 || delay > MAX_BATCH_FLUSH_DELAY_MILLIS) {
            log.warn("Unable to use the flush delay system property '{}':{} - using the default value: {}",
                    BATCH_FLUSH_DELAY_PROPERTY, delay, DEFAULT_BATCH_FLUSH_DELAY_MILLIS);
            return DEFAULT_BATCH_FLUSH_DELAY_MILLIS;
        }
        return delay;
    }

    protected void onBedrockPacket(BedrockPacketWrapper wrapper) {
        if (this.closing.get()) {
            return;
        }
        int targetId = wrapper.getTargetSubClientId();
        BedrockSession session = this.sessions.computeIfAbsent(targetId, this::onSessionCreated);
        session.onPacket(wrapper);
    }

    protected BedrockSession onSessionCreated(int sessionId) {
        return this.sessionFactory.createSession(this, sessionId);
    }

    protected void checkForClosed() {
        if (this.closed.get()) {
            throw new IllegalStateException("Peer has been closed");
        }
    }

    protected void removeSession(BedrockSession session) {
        this.sessions.remove(session.subClientId, session);
    }

    /**
     * Hands queued packets to the channel. Runs on the channel's event loop only, and hands over at most
     * {@link BedrockOutboundPolicy#maxDrainPackets()} packets before yielding to other tasks on the loop.
     */
    protected void flushPacketQueue() {
        if (this.closed.get()) {
            this.flushScheduled.set(false);
            // Release anything enqueued after onClose() drained the queue.
            this.free();
            return;
        }
        if (this.closing.get() || this.abortRequested.get()) {
            // Once a disconnect is requested nothing more is written; the queue
            // is freed by onClose(), which is guaranteed to follow. An aborted
            // peer hands nothing more to a network that is failing to keep up.
            this.flushScheduled.set(false);
            return;
        }
        if (this.draining) {
            // Re-entered through a handler writing while we are already draining; the outer drain continues.
            return;
        }

        int limit = Math.max(1, this.outboundPolicy.maxDrainPackets());
        int written = 0;
        this.draining = true;
        try {
            QueuedPacket queued;
            // The per-write futures are never used here; the batch encoder completes them and
            // drops them. A void promise skips allocating one per packet, and write failures
            // still reach the pipeline as exceptions.
            ChannelPromise voidPromise = this.channel.voidPromise();
            while (written < limit && (queued = this.packetQueue.poll()) != null) {
                // Ownership passes to the channel pipeline here
                this.releaseAdmission(1, queued.weight);
                this.channel.write(queued.wrapper, voidPromise);
                written++;
            }
            if (written > 0) {
                this.channel.flush();
            }
        } finally {
            this.draining = false;
            this.lastDrainNanos = System.nanoTime();
            this.flushScheduled.set(false);

            // A producer can enqueue after the final poll but before the flag is cleared.
            if (!this.closing.get() && !this.closed.get() && !this.packetQueue.isEmpty()) {
                if (written >= limit) {
                    // More is queued than one drain may hand over: continue after other tasks had a chance to run
                    this.scheduleDrain();
                } else {
                    this.schedulePacketFlush();
                }
            }
        }
    }

    protected void schedulePacketFlush() {
        if (this.closed.get()) {
            // sendPacket() can race the door gate, enqueueing after onClose() has
            // already drained the queue and leaving the wrapper with no flush
            // task to release it. Hand the cleanup to the event loop rather than
            // draining here: the queue is MPSC, so drains must stay serialized.
            this.scheduleFree();
            return;
        }
        // Once closing, nothing schedules; onClose() frees the queue.
        if (this.closing.get() || !this.flushScheduled.compareAndSet(false, true)) {
            return;
        }

        try {
            this.scheduleFlushTask();
        } catch (RejectedExecutionException exception) {
            // The loop refused the task: it is shutting down, or a bounded task
            // queue is momentarily full while the peer stays live. The wrapper
            // stays owned by the packet queue, but without a retry a live peer
            // with no further sends would strand it until close, so retry off
            // the global executor, which never rejects. The CAS keeps the retry
            // deduplicated to one chain per peer; otherwise every send during
            // saturation would spawn its own self reproducing retry, turning
            // loop backpressure into an unbounded global executor backlog. The
            // chain ends once the loop accepts a flush or the peer closes.
            this.flushScheduled.set(false);
            if (this.flushRetryScheduled.compareAndSet(false, true)) {
                this.scheduleRejectedRetry(this::retryPacketFlush);
            }
        } catch (RuntimeException exception) {
            this.flushScheduled.set(false);
            throw exception;
        }
    }

    /**
     * Continues draining as soon as possible, without the batching delay. At most one such task is pending per peer.
     */
    protected void scheduleDrain() {
        if (this.closing.get() || this.closed.get() || !this.drainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            this.executeOnEventLoop(() -> {
                this.drainScheduled.set(false);
                this.flushPacketQueue();
            });
        } catch (RejectedExecutionException exception) {
            this.drainScheduled.set(false);
            // Fall back to the delayed flush, which has its own deduplicated retry
            this.schedulePacketFlush();
        }
    }

    /**
     * Releases everything still queued on the event loop. At most one such task is pending per peer. If the loop
     * rejects it, the queue is only drained here once the loop has terminated, so there is never a second consumer.
     */
    protected void scheduleFree() {
        if (!this.freeScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            this.executeOnEventLoop(() -> {
                this.freeScheduled.set(false);
                this.free();
            });
        } catch (RejectedExecutionException exception) {
            this.freeScheduled.set(false);
            if (this.channel.eventLoop().isTerminated()) {
                this.free();
            } else if (this.freeScheduled.compareAndSet(false, true)) {
                // Loop is shutting down or saturated but may still run tasks; try again later
                this.scheduleRejectedRetry(() -> {
                    this.freeScheduled.set(false);
                    this.scheduleFree();
                });
            }
        }
    }

    protected void retryPacketFlush() {
        this.flushRetryScheduled.set(false);
        if (this.closing.get() || this.closed.get() || this.packetQueue.isEmpty()) {
            return;
        }
        this.schedulePacketFlush();
    }

    protected void scheduleFlushTask() {
        this.channel.eventLoop().schedule(this::flushPacketQueue, BATCH_FLUSH_DELAY_NANOS, TimeUnit.NANOSECONDS);
    }

    protected void executeOnEventLoop(Runnable task) {
        this.channel.eventLoop().execute(task);
    }

    protected boolean isOnEventLoop() {
        return this.channel.eventLoop().inEventLoop();
    }

    protected void scheduleRejectedRetry(Runnable retry) {
        GlobalEventExecutor.INSTANCE.schedule(retry, REJECTED_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    protected void onRakNetDisconnect(ChannelHandlerContext ctx, RakDisconnectReason reason) {
        CharSequence disconnectReason = BedrockDisconnectReasons.getReason(reason);
        for (BedrockSession session : this.sessions.values()) {
            session.disconnectReason = disconnectReason;
        }
    }

    /**
     * Releases every queued packet wrapper and its admission. The queue is MPSC, so drains must be mutually exclusive:
     * this runs on the event loop, or from scheduleFree() once the loop has terminated. The lock additionally keeps
     * such a late drain exclusive. Repeated invocation is only safe because the drain is a destructive poll;
     * iterating instead would release the same wrappers twice.
     */
    protected synchronized void free() {
        QueuedPacket queued;
        while ((queued = this.packetQueue.poll()) != null) {
            this.releaseAdmission(1, queued.weight);
            ReferenceCountUtil.safeRelease(queued.wrapper);
        }
    }

    public void sendPacket(int senderClientId, int targetClientId, BedrockPacket packet) {
        long weight = Math.max(0, this.outboundPolicy.estimateWeight(packet));
        if (!this.admit(weight)) {
            ReferenceCountUtil.safeRelease(packet); // not queued, so ownership stays here
            return;
        }
        this.enqueue(BedrockPacketWrapper.create(0, senderClientId, targetClientId, packet, null), weight);
        this.schedulePacketFlush();
    }

    public void sendPacket(BedrockPacketWrapper wrapper) {
        long weight = Math.max(0, this.outboundPolicy.estimateWeight(wrapper));
        if (!this.admit(weight)) {
            ReferenceCountUtil.safeRelease(wrapper); // not queued, so ownership stays here
            return;
        }
        this.enqueue(wrapper, weight);
        this.schedulePacketFlush();
    }

    /**
     * Queues the packet behind any packets sent before it and drains the queue without waiting for the batching
     * delay. Immediate sends are subject to the same limits as queued ones.
     */
    public void sendPacketImmediately(int senderClientId, int targetClientId, BedrockPacket packet) {
        long weight = Math.max(0, this.outboundPolicy.estimateWeight(packet));
        if (!this.admit(weight)) {
            ReferenceCountUtil.safeRelease(packet);
            return;
        }
        this.enqueue(BedrockPacketWrapper.create(0, senderClientId, targetClientId, packet, null), weight);
        this.drainNow();
    }

    /**
     * Like {@link #sendPacketImmediately(int, int, BedrockPacket)} for several packets, which keep their order.
     * If a packet is not admitted, it and every packet after it are released and the peer is aborted.
     */
    public void sendPacketsImmediately(int senderClientId, int targetClientId, BedrockPacket... packets) {
        int i = 0;
        for (; i < packets.length; i++) {
            BedrockPacket packet = packets[i];
            long weight = Math.max(0, this.outboundPolicy.estimateWeight(packet));
            if (!this.admit(weight)) {
                break;
            }
            this.enqueue(BedrockPacketWrapper.create(0, senderClientId, targetClientId, packet, null), weight);
        }
        for (int j = i; j < packets.length; j++) {
            ReferenceCountUtil.safeRelease(packets[j]);
        }
        if (i > 0) {
            this.drainNow();
        }
    }

    private void enqueue(BedrockPacketWrapper wrapper, long weight) {
        if (this.packetQueue.isEmpty()) {
            this.pendingSinceNanos = System.nanoTime();
        }
        this.packetQueue.add(new QueuedPacket(wrapper, weight));
    }

    private void drainNow() {
        if (this.isOnEventLoop()) {
            this.flushPacketQueue();
        } else {
            this.scheduleDrain();
        }
    }

    /**
     * Reserves capacity for one packet. On failure, the peer is aborted and the caller keeps ownership of the packet.
     * The weight must be non-negative and is the one stored with the queued packet, so releasing it undoes exactly
     * this reservation; the callers clamp the estimate.
     */
    private boolean admit(long weight) {
        BedrockOutboundPolicy.Rejection rejection = this.tryReserve(weight);
        if (rejection == null) {
            return true;
        }
        if (rejection != BedrockOutboundPolicy.Rejection.CLOSED && this.abort(OUTBOUND_OVERLOAD_REASON)) {
            try {
                this.outboundPolicy.onOverload(this, rejection);
            } catch (Throwable throwable) {
                log.error("Exception thrown while handling outbound overload of {}", this.getSocketAddress(), throwable);
            }
        }
        return false;
    }

    private BedrockOutboundPolicy.Rejection tryReserve(long weight) {
        int maxPackets = this.outboundPolicy.maxQueuedPackets();
        long maxWeight = this.outboundPolicy.maxQueuedWeight();
        for (; ; ) {
            long state = this.admission.get();
            if ((state & ADMISSION_CLOSED) != 0) {
                return BedrockOutboundPolicy.Rejection.CLOSED;
            }
            long packets = state & COUNT_MASK;
            long queuedWeight = (state >>> COUNT_BITS) & WEIGHT_MASK;
            if ((maxPackets > 0 && packets >= maxPackets) || packets >= COUNT_MASK) {
                return BedrockOutboundPolicy.Rejection.PEER_PACKET_LIMIT;
            }
            // A single packet heavier than the limit may still pass when nothing else is queued
            if ((maxWeight > 0 && packets > 0 && queuedWeight + weight > maxWeight) || queuedWeight + weight > WEIGHT_MASK) {
                return BedrockOutboundPolicy.Rejection.PEER_WEIGHT_LIMIT;
            }
            if (this.admission.compareAndSet(state, state + 1 + (weight << COUNT_BITS))) {
                break;
            }
        }
        if (!this.outboundPolicy.tryReserveShared(1, weight)) {
            this.admission.addAndGet(-(1 + (weight << COUNT_BITS)));
            return BedrockOutboundPolicy.Rejection.SHARED_LIMIT;
        }
        return null;
    }

    private void releaseAdmission(int packets, long weight) {
        this.admission.addAndGet(-(packets + (weight << COUNT_BITS)));
        this.outboundPolicy.releaseShared(packets, weight);
    }

    /**
     * Stops accepting packets. Atomic with respect to admission: no packet is admitted after this returns.
     *
     * @return whether this call closed admission
     */
    protected boolean closeAdmission() {
        long previous = this.admission.getAndUpdate(state -> state | ADMISSION_CLOSED);
        return (previous & ADMISSION_CLOSED) == 0;
    }

    /**
     * Aborts the connection: admission closes immediately on the calling thread, and one forced close without a
     * disconnect packet is scheduled on the event loop. Safe to call from any thread, any number of times.
     *
     * @return whether this call started the abort
     */
    public boolean abort(CharSequence reason) {
        this.closeAdmission();
        if (!this.abortRequested.compareAndSet(false, true)) {
            return false;
        }
        if (this.isOnEventLoop()) {
            this.close0(reason, true);
            return true;
        }
        try {
            this.executeOnEventLoop(() -> this.close0(reason, true));
        } catch (RejectedExecutionException exception) {
            // Retry until the loop takes it or the channel is gone; at most one retry is outstanding
            this.scheduleRejectedRetry(() -> {
                this.abortRequested.set(false);
                if (!this.closed.get() && this.channel.isOpen()) {
                    this.abort(reason);
                }
            });
        }
        return true;
    }

    /**
     * @return whether this peer no longer accepts outbound packets
     */
    public boolean isAdmissionClosed() {
        return (this.admission.get() & ADMISSION_CLOSED) != 0;
    }

    /**
     * @return the number of packets admitted but not yet handed to the channel
     */
    public int getQueuedPacketCount() {
        return (int) (this.admission.get() & COUNT_MASK);
    }

    /**
     * @return the estimated weight of the packets admitted but not yet handed to the channel
     */
    public long getQueuedPacketWeight() {
        return (this.admission.get() >>> COUNT_BITS) & WEIGHT_MASK;
    }

    /**
     * @return when a drain last ran, or the peer was created, in {@link System#nanoTime()} units
     */
    public long getLastDrainNanos() {
        return this.lastDrainNanos;
    }

    /**
     * @return when a packet was last queued while the queue was empty, in {@link System#nanoTime()} units. Together
     * with {@link #getLastDrainNanos()} this tells how long queued packets have been waiting without progress.
     */
    public long getPendingSinceNanos() {
        return this.pendingSinceNanos;
    }

    /**
     * @return how long queued packets have been waiting without a drain handing any over, in nanoseconds, or 0 if
     * nothing is queued
     */
    public long getPendingWaitNanos(long nowNanos) {
        if (this.getQueuedPacketCount() == 0) {
            return 0;
        }
        return nowNanos - Math.max(this.pendingSinceNanos, this.lastDrainNanos);
    }

    /**
     * @return whether the peer has been closed and its queue released
     */
    public boolean isClosed() {
        return this.closed.get();
    }

    public BedrockOutboundPolicy getOutboundPolicy() {
        return this.outboundPolicy;
    }

    public void enableEncryption(@NonNull SecretKey secretKey) {
        Objects.requireNonNull(secretKey, "secretKey");
        if (!secretKey.getAlgorithm().equals("AES")) {
            throw new IllegalArgumentException("Invalid key algorithm");
        }
        // Check if the codecs exist in the pipeline
        if (this.channel.pipeline().get(BedrockEncryptionEncoder.class) != null ||
                this.channel.pipeline().get(BedrockEncryptionDecoder.class) != null) {
            throw new IllegalStateException("Encryption is already enabled");
        }

        int protocolVersion = this.getCodec().getProtocolVersion();
        boolean useCtr = protocolVersion >= Bedrock_v428.CODEC.getProtocolVersion();

        this.channel.pipeline().addAfter(FrameIdCodec.NAME, BedrockEncryptionEncoder.NAME,
                new BedrockEncryptionEncoder(secretKey, EncryptionUtils.createCipher(useCtr, true, secretKey)));
        this.channel.pipeline().addAfter(FrameIdCodec.NAME, BedrockEncryptionDecoder.NAME,
                new BedrockEncryptionDecoder(secretKey, EncryptionUtils.createCipher(useCtr, false, secretKey)));

        log.debug("Encryption enabled for {}", getSocketAddress());
    }

    public void setCompression(PacketCompressionAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        this.setCompression(BedrockChannelInitializer.getCompression(algorithm, this.getRakVersion(), false));
    }

    public void setCompression(CompressionStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");

        boolean needsPrefix = this.getCodec().getProtocolVersion() >= 649; // TODO: do not hardcode

        ChannelHandler handler = this.channel.pipeline().get(CompressionCodec.NAME);
        if (handler == null) {
            this.channel.pipeline().addBefore(BedrockBatchDecoder.NAME, CompressionCodec.NAME, new CompressionCodec(strategy, needsPrefix));
        } else {
            this.channel.pipeline().replace(CompressionCodec.NAME, CompressionCodec.NAME, new CompressionCodec(strategy, needsPrefix));
        }
    }

    public CompressionStrategy getCompressionStrategy() {
        ChannelHandler handler = this.channel.pipeline().get(CompressionCodec.NAME);
        if (!(handler instanceof CompressionCodec)) {
            return null;
        }
        return ((CompressionCodec) handler).getStrategy();
    }

    public BedrockCodec getCodec() {
        return this.channel.pipeline().get(BedrockPacketCodec.class).getCodec();
    }

    public BedrockCodecHelper getCodecHelper() {
        return this.channel.pipeline().get(BedrockPacketCodec.class).getHelper();
    }

    public void setCodec(BedrockCodec codec) {
        Objects.requireNonNull(codec, "codec");
        this.channel.pipeline().get(BedrockPacketCodec.class).setCodec(codec);
    }

    public void close(CharSequence reason) {
        if (this.isOnEventLoop()) {
            this.close0(reason, false);
            return;
        }
        // The session map is event loop confined, so the close must run there.
        // Rejection means the loop is shut down or a bounded task queue is full
        // while the channel may still be open, so retry off the never rejecting
        // global executor until the close lands or the channel dies on its own;
        // dropping the task would silently lose the disconnect reason. The CAS
        // keeps the retry deduplicated; the first reason wins.
        try {
            this.executeOnEventLoop(() -> this.close0(reason, false));
        } catch (RejectedExecutionException exception) {
            if (this.closeRetryScheduled.compareAndSet(false, true)) {
                this.scheduleRejectedRetry(() -> {
                    this.closeRetryScheduled.set(false);
                    if (!this.closing.get() && !this.closed.get() && this.channel.isOpen()) {
                        this.close(reason);
                    }
                });
            }
        }
    }

    protected void close0(CharSequence reason, boolean force) {
        this.closeAdmission();
        if (!this.closing.compareAndSet(false, true)) {
            if (force && this.channel.isOpen()) {
                // An abort must not be lost because a graceful close is already waiting on its disconnect packet
                this.channel.close();
            }
            return;
        }

        this.blackholeInboundPackets(); // inbound stops either way

        for (BedrockSession session : this.sessions.values()) {
            session.disconnectReason = reason;
        }

        if (force) {
            // If an exception is thrown or the peer is misbehaving, we close it immediately without notification
            this.channel.close();
        } else {
            this.channel.disconnect();
        }
    }

    protected void onClose() {
        if (this.channel.isOpen()) {
            log.warn("Tried to close peer, but channel is open!", new Throwable());
            return;
        }

        this.closeAdmission();
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        try {
            // Sessions remove themselves from the map in onClose(), and the open
            // addressing map must not be mutated mid iteration (skipped entries,
            // iterator NPE), so iterate a snapshot. An escaping exception here
            // could otherwise never be retried: closed is already latched.
            for (BedrockSession session : new ArrayList<>(this.sessions.values())) {
                try {
                    session.onClose();
                } catch (Exception e) {
                    log.error("Exception whilst closing session", e);
                }
            }
        } finally {
            this.sessions.clear();
            this.free();
        }
    }

    public boolean isConnected() {
        return !this.closed.get() && !this.isAdmissionClosed() && this.channel.isOpen();
    }

    public boolean isConnecting() {
        return !this.channel.isActive() && !this.closed.get();
    }

    public SocketAddress getSocketAddress() {
        return this.channel.remoteAddress();
    }

    public Channel getChannel() {
        return this.channel;
    }

    public int getRakVersion() {
        return this.channel.config().getOption(RakChannelOption.RAK_PROTOCOL_VERSION);
    }

    /*
        Netty handler methods
     */

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        this.onClose();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.sessions.put(0, this.sessionFactory.createSession(this, 0));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        this.onClose();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof BedrockPacketWrapper) {
                this.onBedrockPacket((BedrockPacketWrapper) msg);
            } else {
                throw new DecoderException("Unexpected message type: " + msg.getClass().getName());
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof RakDisconnectReason) {
            onRakNetDisconnect(ctx, (RakDisconnectReason) evt);
        }
    }

    private static final class QueuedPacket {
        final BedrockPacketWrapper wrapper;
        final long weight;

        QueuedPacket(BedrockPacketWrapper wrapper, long weight) {
            this.wrapper = wrapper;
            this.weight = weight;
        }
    }

    protected void blackholeInboundPackets() {
        if (this.channel.pipeline().get(BlackholeInboundAdapter.class) == null) {
            this.channel.pipeline().addFirst(BlackholeInboundAdapter.NAME, BlackholeInboundAdapter.INSTANCE);
        }
    }
}
