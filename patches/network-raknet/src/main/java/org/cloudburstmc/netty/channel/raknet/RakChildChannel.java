/*
 * Copyright 2022 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.channel.raknet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.channel.raknet.config.DefaultChannelToServerProxyMetrics;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakSessionConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelConfig;
import org.cloudburstmc.netty.handler.codec.raknet.common.*;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakChildDatagramHandler;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerOnlineInitialHandler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Patched by Geyser (see patches/README.md), derived from netty-transport-raknet 2.0.0.CR5-20261009.162522-2: writes
 * are handed to the parent event loop through a bounded queue with a single drain task, instead of one task per
 * message, and a write that would exceed {@link RakOutboundBudget} fails and closes the channel.
 */
public class RakChildChannel extends AbstractChannel implements RakChannel {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakChildChannel.class);
    private static final ChannelMetadata metadata = new ChannelMetadata(true);
    private static final int MAX_WRITES_PER_DRAIN = 128;

    private final RakChannelConfig config;
    private final InetSocketAddress remoteAddress;
    private final InetSocketAddress localAddress;
    private final InetSocketAddress clientAddress;
    private final DefaultChannelPipeline rakPipeline;
    private final RakSessionCodec sessionCodec;
    private volatile boolean open = true;
    private volatile boolean active;

    // Written on this channel's event loop, drained on the parent's
    private final Queue<PendingWrite> pendingWrites = PlatformDependent.newMpscQueue();
    private final AtomicLong pendingBytes = new AtomicLong();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean overflowed = new AtomicBoolean();
    // Monotonic progress markers (System.nanoTime()) for monitoring from other threads
    private volatile long pendingSinceNanos;
    private volatile long lastParentDrainNanos = System.nanoTime();

    RakChildChannel(InetSocketAddress remoteAddress, InetSocketAddress localAddress, InetSocketAddress clientAddress, RakServerChannel parent, long guid, int mtu, Consumer<RakChannel> childConsumer) {
        super(parent);
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.clientAddress = clientAddress;
        this.config = new DefaultRakSessionConfig(this, new DefaultChannelToServerProxyMetrics(parent, this));
        this.config.setGuid(guid);
        this.config.setMtu(mtu);
        // Allow user to configure the child channel before we initialize pipeline
        // This is not the same as bootstrap.childOption() as Bootstrap does not allow setting options per channel
        if (childConsumer != null) {
            childConsumer.accept(this);
        }
        // Create an internal pipeline for RakNet session logic to take place. We use the parent channel to ensure
        // this all occurs on the parent event loop so the connection is not slowed down by any user code.
        // (compression, encryption, etc.)
        this.rakPipeline = new RakChannelPipeline(parent, this);
        this.rakPipeline.addLast(RakChildDatagramHandler.NAME, new RakChildDatagramHandler(this));

        // Setup session/online phase
        RakSessionCodec sessionCodec = new RakSessionCodec(this);
        this.sessionCodec = sessionCodec;
        this.rakPipeline.addLast(RakDatagramCodec.NAME, new RakDatagramCodec());
        this.rakPipeline.addLast(RakAcknowledgeHandler.NAME, new RakAcknowledgeHandler(sessionCodec));
        this.rakPipeline.addLast(RakSessionCodec.NAME, sessionCodec);
        // This handler auto-removes once ConnectionRequest is received
        this.rakPipeline.addLast(ConnectedPingHandler.NAME, new ConnectedPingHandler());
        this.rakPipeline.addLast(ConnectedPongHandler.NAME, new ConnectedPongHandler(sessionCodec));
        this.rakPipeline.addLast(DisconnectNotificationHandler.NAME, DisconnectNotificationHandler.INSTANCE);
        this.rakPipeline.addLast(RakServerOnlineInitialHandler.NAME, new RakServerOnlineInitialHandler(this));
        this.rakPipeline.addLast(RakUnhandledMessagesQueue.NAME, new RakUnhandledMessagesQueue(this));
        this.rakPipeline.fireChannelRegistered();
        this.rakPipeline.fireChannelActive();
    }

    @Override
    public ChannelPipeline rakPipeline() {
        return rakPipeline;
    }

    @Override
    public SocketAddress localAddress0() {
        return this.localAddress;
    }

    @Override
    public SocketAddress remoteAddress0() {
        return this.clientAddress;
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    public InetSocketAddress remoteOrProxyAddress() {
        return remoteAddress;
    }

    @Override
    public RakChannelConfig config() {
        return this.config;
    }

    @Override
    public ChannelMetadata metadata() {
        return metadata;
    }

    @Override
    protected void doBind(SocketAddress socketAddress) throws Exception {
        throw new UnsupportedOperationException("Can not bind child channel!");
    }

    @Override
    protected void doBeginRead() throws Exception {
        // Ignore
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        if (!this.open) {
            throw new ClosedChannelException();
        } else if (!active) {
            throw new NonWritableChannelException();
        }
        ClosedChannelException exception = null;
        RakOutboundOverflowException overflow = null;
        boolean submitted = false;
        for (; ; ) {
            Object msg = in.current();
            if (msg == null) {
                break;
            }
            try {
                if (!this.parent().isOpen()) {
                    if (exception == null) {
                        exception = new ClosedChannelException();
                    }
                    in.remove(exception);
                } else if (overflow != null || this.overflowed.get()) {
                    if (overflow == null) {
                        overflow = new RakOutboundOverflowException("Channel is closing after exceeding its outbound limits");
                    }
                    in.remove(overflow);
                } else {
                    long size = messageSize(msg);
                    // Retain before reserving, so a failing retain cannot leak a reservation. The retain transfers one
                    // reference to the pending queue; remove() releases the outbound buffer's
                    Object retained = ReferenceCountUtil.retain(msg);
                    if (this.tryReservePending(size)) {
                        this.pendingWrites.add(new PendingWrite(retained, size));
                        in.remove();
                        submitted = true;
                    } else {
                        ReferenceCountUtil.safeRelease(retained);
                        overflow = new RakOutboundOverflowException("Outbound limit exceeded: " + this.pendingBytes.get()
                                + " bytes pending, " + this.sessionCodec.getOutstandingBytes() + " bytes outstanding, "
                                + RakOutboundBudget.totalOutstandingBytes() + " bytes outstanding in total");
                        in.remove(overflow);
                    }
                }
            } catch (Throwable cause) {
                in.remove(cause);
            }
        }
        if (submitted) {
            this.scheduleParentDrain();
        }
        if (overflow != null && this.overflowed.compareAndSet(false, true)) {
            // No address here: this library cannot see whether the application logs addresses
            log.warn("Closing RakNet channel after exceeding its outbound limits: {}", overflow.getMessage());
            // Closing from inside doWrite would run while the outbound buffer is being flushed
            try {
                this.eventLoop().execute(this::close);
            } catch (RejectedExecutionException ignored) {
                // The event loop is shutting down, which closes the channel anyway
            }
        }
    }

    private boolean tryReservePending(long size) {
        if (this.pendingCount.get() >= RakOutboundBudget.maxPendingWrites()) {
            return false;
        }
        long pending = this.pendingBytes.get();
        long outstanding = pending + this.sessionCodec.getOutstandingBytes();
        // A single write larger than the limit is admitted when nothing else is outstanding
        if (outstanding > 0 && outstanding + size > RakOutboundBudget.maxChannelBytes()) {
            return false;
        }
        if (!RakOutboundBudget.tryReserveTotal(size)) {
            return false;
        }
        this.pendingBytes.addAndGet(size);
        if (this.pendingCount.getAndIncrement() == 0) {
            this.pendingSinceNanos = System.nanoTime();
        }
        return true;
    }

    private void releasePending(PendingWrite write) {
        this.pendingBytes.addAndGet(-write.size);
        this.pendingCount.decrementAndGet();
        RakOutboundBudget.adjustTotal(-write.size);
    }

    private void scheduleParentDrain() {
        if (!this.drainScheduled.compareAndSet(false, true)) {
            return;
        }
        EventLoop parentLoop = this.parent().eventLoop();
        try {
            parentLoop.execute(this::drainPendingWrites);
        } catch (RejectedExecutionException rejected) {
            this.drainScheduled.set(false);
            if (parentLoop.isTerminated()) {
                // Nothing consumes the queue anymore, so releasing it here cannot race a drain
                this.releasePendingWrites();
            } else if (this.drainScheduled.compareAndSet(false, true)) {
                GlobalEventExecutor.INSTANCE.schedule(() -> {
                    this.drainScheduled.set(false);
                    this.scheduleParentDrain();
                }, 10, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Runs on the parent event loop, which owns the session pipeline, so writes reach the session codec directly.
     */
    private void drainPendingWrites() {
        this.drainScheduled.set(false);
        if (!this.open) {
            this.releasePendingWrites();
            return;
        }
        int written = 0;
        PendingWrite write;
        while (written < MAX_WRITES_PER_DRAIN && (write = this.pendingWrites.poll()) != null) {
            this.releasePending(write);
            if (!this.open) {
                ReferenceCountUtil.safeRelease(write.message);
                continue;
            }
            this.rakPipeline.write(write.message);
            written++;
        }
        if (written > 0) {
            this.rakPipeline.flush();
        }
        this.lastParentDrainNanos = System.nanoTime();
        if (!this.pendingWrites.isEmpty()) {
            this.scheduleParentDrain();
        }
    }

    private synchronized void releasePendingWrites() {
        PendingWrite write;
        while ((write = this.pendingWrites.poll()) != null) {
            this.releasePending(write);
            ReferenceCountUtil.safeRelease(write.message);
        }
    }

    private static long messageSize(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        } else if (msg instanceof RakMessage) {
            return ((RakMessage) msg).content().readableBytes();
        } else if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return 0;
    }

    /**
     * @return bytes written to this channel that are still waiting for the parent event loop
     */
    public long getPendingBytes() {
        return this.pendingBytes.get();
    }

    /**
     * @return how long writes have been waiting for the parent event loop without it draining any, in nanoseconds,
     * or 0 if nothing is waiting
     */
    public long getPendingWaitNanos(long nowNanos) {
        if (this.pendingCount.get() == 0) {
            return 0;
        }
        return nowNanos - Math.max(this.pendingSinceNanos, this.lastParentDrainNanos);
    }

    /**
     * @return the session codec of this channel
     */
    public RakSessionCodec getSessionCodec() {
        return this.sessionCodec;
    }

    private static final class PendingWrite {
        final Object message;
        final long size;

        PendingWrite(Object message, long size) {
            this.message = message;
            this.size = size;
        }
    }

    public static final class RakOutboundOverflowException extends RuntimeException {
        RakOutboundOverflowException(String message) {
            super(message, null, false, false);
        }
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    protected void doDisconnect() throws Exception {
        RakSessionCodec sessionCodec = this.rakPipeline.get(RakSessionCodec.class);
        if (sessionCodec == null) {
            return; // Already closed and torn down
        }
        try {
            // Notify the peer, after which the session closes this channel
            sessionCodec.disconnect();
        } catch (RejectedExecutionException e) {
            // The server's event loop is shutting down, so close without notifying
            this.close();
        }
    }

    @Override
    protected void doClose() throws Exception {
        // closeForcibly() reaches here without completing the close future, and can follow a close
        if (this.open) {
            this.open = false;
            try {
                ((RakServerChannel) this.parent()).onChildClosed(this);
            } catch (Throwable t) {
                // Thrown here, it would only fail the close promise, which callers rarely check
                log.warn("Failed to release child channel {}", this, t);
            }
            // Release writes that never reached the session; the drain releases them now that the channel is closed
            this.scheduleParentDrain();
        }
    }

    @Override
    public boolean isActive() {
        return this.isOpen() && this.active;
    }

    @Override
    public boolean isOpen() {
        return this.open;
    }

    @Override
    protected boolean isCompatible(EventLoop eventLoop) {
        return true;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress socketAddress, SocketAddress socketAddress1, ChannelPromise channelPromise) {
                throw new UnsupportedOperationException("Can not connect child channel!");
            }
        };
    }
}
