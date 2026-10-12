package org.cloudburstmc.netty.channel.raknet;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakServerThrottle;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real RakNet server and client over loopback, so writes cross from the child channel's event loop to the
 * server's parent event loop exactly as they do in production.
 */
class RakChildChannelOutboundTest {
    private static final int MESSAGE_SIZE = 64 * 1024;

    private final AtomicBoolean failAllocations = new AtomicBoolean();
    private final LinkedBlockingQueue<RakChildChannel> accepted = new LinkedBlockingQueue<>();
    private final List<Channel> extraClients = new ArrayList<>();
    // The first disconnect reason the client's pipeline reported
    private final CompletableFuture<RakDisconnectReason> clientDisconnectReason = new CompletableFuture<>();
    private EventLoopGroup parentGroup;
    private EventLoopGroup childGroup;
    private EventLoopGroup clientGroup;
    private Channel server;
    private Channel client;
    private RakChildChannel child;

    @BeforeEach
    void setUp() throws Exception {
        this.parentGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.childGroup = new DefaultEventLoopGroup(1);
        this.clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        this.server = new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(this.parentGroup, this.childGroup)
                // The session pipeline allocates from the server channel; failures can be switched on per test
                .option(ChannelOption.ALLOCATOR, new ToggleAllocator(this.failAllocations))
                // One connection per address, so a child that is not released on close blocks the next client
                .option(RakChannelOption.RAK_THROTTLE, new DefaultRakServerThrottle(1, 60_000, 100))
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        RakChildChannelOutboundTest.this.accepted.add((RakChildChannel) channel);
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();

        this.client = this.connectClient(new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof RakDisconnectReason reason) {
                    RakChildChannelOutboundTest.this.clientDisconnectReason.complete(reason);
                }
                super.userEventTriggered(ctx, evt);
            }
        }).sync().channel();
        this.child = this.awaitChild(this.client);
        // The budget is static and shared with the client's session: start every test with the handshake acknowledged
        // on both sides, or a client left with unacknowledged bytes after the server drops it holds them until it
        // times out
        waitFor(() -> RakOutboundBudget.totalOutstandingBytes() == 0, "handshake acknowledged by both peers");
    }

    private ChannelFuture connectClient(ChannelInboundHandlerAdapter handler) {
        return new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(this.clientGroup)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .option(RakChannelOption.RAK_CONNECT_TIMEOUT, 5_000L)
                .handler(handler)
                .connect(this.server.localAddress());
    }

    private RakChildChannel awaitChild(Channel client) throws Exception {
        RakChildChannel child = this.accepted.poll(10, TimeUnit.SECONDS);
        assertNotNull(child, "server accepted " + client.localAddress());
        assertEquals(((InetSocketAddress) client.localAddress()).getPort(), ((InetSocketAddress) child.remoteAddress()).getPort());
        waitFor(child::isActive, "child channel active");
        return child;
    }

    @AfterEach
    void tearDown() throws Exception {
        this.failAllocations.set(false);
        for (Channel extra : this.extraClients) {
            extra.close().await(5, TimeUnit.SECONDS);
        }
        if (this.client != null) {
            this.client.close().await(5, TimeUnit.SECONDS);
        }
        if (this.server != null) {
            this.server.close().await(5, TimeUnit.SECONDS);
        }
        this.clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
        this.childGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
        this.parentGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
        // After the sessions are gone, so their bytes are released first
        RakOutboundBudget.configure(8L * 1024 * 1024, 128L * 1024 * 1024, 128);
    }

    @Test
    void pausedParentLoopKeepsPendingWritesBounded() throws Exception {
        long maxBytes = 2L * 1024 * 1024;
        int maxWrites = 16;
        RakOutboundBudget.configure(maxBytes, 64L * 1024 * 1024, maxWrites);

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch paused = new CountDownLatch(1);
        this.server.eventLoop().execute(() -> {
            paused.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
        });
        assertTrue(paused.await(5, TimeUnit.SECONDS));

        // 100x more than the child may hand over while the parent loop is blocked
        List<ByteBuf> buffers = new ArrayList<>();
        List<ChannelFuture> futures = new ArrayList<>();
        this.child.eventLoop().submit(() -> {
            for (int i = 0; i < maxWrites * 100; i++) {
                ByteBuf buffer = Unpooled.buffer(MESSAGE_SIZE).writeByte(0xFE).writeZero(MESSAGE_SIZE - 1);
                buffers.add(buffer);
                futures.add(this.child.write(buffer));
            }
            this.child.flush();
        }).get(10, TimeUnit.SECONDS);

        // Checked while the parent loop is still blocked
        long pending = this.child.getPendingBytes();
        assertTrue(pending <= maxBytes, "pending " + pending);
        assertTrue(RakOutboundBudget.totalOutstandingBytes() <= maxBytes + MESSAGE_SIZE, "total " + RakOutboundBudget.totalOutstandingBytes());
        long accepted = futures.stream().filter(f -> f.isDone() && f.isSuccess()).count();
        long failed = futures.stream().filter(f -> f.isDone() && !f.isSuccess()).count();
        assertTrue(accepted <= maxWrites, "accepted " + accepted);
        assertEquals(futures.size(), accepted + failed, "every write was resolved without waiting for the parent loop");
        assertTrue(futures.stream().filter(f -> !f.isSuccess()).allMatch(f -> f.cause() instanceof RakChildChannel.RakOutboundOverflowException));
        waitFor(() -> !this.child.isOpen(), "overflowing channel closed");

        release.countDown();
        // The parent loop resumes: pending writes of the closed channel are released and the session is cleaned up
        waitFor(() -> this.child.getPendingBytes() == 0, "pending writes released");
        waitFor(() -> RakOutboundBudget.totalOutstandingBytes() == 0, "shared budget returned, now " + RakOutboundBudget.totalOutstandingBytes());
        waitFor(() -> buffers.stream().allMatch(b -> b.refCnt() == 0), "every buffer released exactly once");
    }

    @Test
    void healthyWritesStillFlowThroughTheBoundedQueue() throws Exception {
        List<ChannelFuture> futures = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            futures.add(this.child.writeAndFlush(Unpooled.buffer(1024).writeByte(0xFE).writeZero(1023)));
        }
        for (ChannelFuture future : futures) {
            assertTrue(future.await(5, TimeUnit.SECONDS));
            assertTrue(future.isSuccess(), String.valueOf(future.cause()));
        }
        assertTrue(this.child.isOpen());
        waitFor(() -> this.child.getPendingBytes() == 0, "pending writes handed to the session");
    }

    @Test
    void failedDisconnectAllocationStillClosesTheSession() throws Exception {
        RakSessionCodec codec = this.child.getSessionCodec();
        this.failAllocations.set(true);
        codec.disconnect(RakDisconnectReason.DISCONNECTED);
        waitFor(() -> !this.child.isOpen(), "session closed instead of waiting on a notification it could not send");
        this.failAllocations.set(false);
        waitFor(() -> codec.getState() == RakState.DISCONNECTED, "session cleaned up, state " + codec.getState());
    }

    @Test
    void closeIsEffectiveWhenAlreadyDisconnecting() throws Exception {
        RakSessionCodec codec = this.child.getSessionCodec();
        CountDownLatch closed = new CountDownLatch(1);
        this.child.closeFuture().addListener(f -> closed.countDown());
        this.server.eventLoop().submit(() -> {
            codec.close(RakDisconnectReason.TIMED_OUT);
            codec.close(RakDisconnectReason.TIMED_OUT);
        }).get(5, TimeUnit.SECONDS);
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertFalse(this.child.isOpen());
    }

    @Test
    void childCloseUnmapsCancelsTickAndRestoresBudget() throws Exception {
        RakOutboundBudget.configure(8L * 1024 * 1024, 128L * 1024 * 1024, 128);
        RakServerChannel serverChannel = (RakServerChannel) this.server;
        SocketAddress address = this.child.remoteOrProxyAddress();
        RakSessionCodec codec = this.child.getSessionCodec();
        assertSame(this.child, serverChannel.getChildChannel(address));

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch paused = new CountDownLatch(1);
        this.server.eventLoop().execute(() -> {
            paused.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
        });
        try {
            assertTrue(paused.await(5, TimeUnit.SECONDS));

            // About 1 MiB, within every limit, held back by the blocked parent loop
            List<ByteBuf> buffers = new ArrayList<>();
            this.child.eventLoop().submit(() -> {
                for (int i = 0; i < 16; i++) {
                    ByteBuf buffer = Unpooled.buffer(MESSAGE_SIZE).writeByte(0xFE).writeZero(MESSAGE_SIZE - 1);
                    buffers.add(buffer);
                    this.child.write(buffer);
                }
                this.child.flush();
            }).get(10, TimeUnit.SECONDS);
            assertTrue(RakOutboundBudget.totalOutstandingBytes() > 0, "writes are accounted while the parent loop is blocked");

            this.child.close();
            release.countDown();
            assertTrue(this.child.closeFuture().await(5, TimeUnit.SECONDS), "child closed");

            waitFor(() -> serverChannel.getChildChannel(address) == null, "child unmapped from the server");
            waitFor(() -> codec.getState() == RakState.DISCONNECTED, "session deinitialized, so its tick is cancelled");
            waitFor(() -> RakOutboundBudget.totalOutstandingBytes() == 0, "shared budget returned, now " + RakOutboundBudget.totalOutstandingBytes());
            waitFor(() -> buffers.stream().allMatch(b -> b.refCnt() == 0), "every buffer released");
        } finally {
            release.countDown();
        }

        // The throttle allows one connection per address: a second client only gets in if the first was released
        Channel second = this.connectClient(new ChannelInboundHandlerAdapter()).sync().channel();
        this.extraClients.add(second);
        RakChildChannel secondChild = this.awaitChild(second);
        assertSame(secondChild, serverChannel.getChildChannel(secondChild.remoteOrProxyAddress()));
    }

    @Test
    void disconnectSendsNotificationBeforeClose() throws Exception {
        this.child.disconnect().sync();
        assertEquals(RakDisconnectReason.CLOSED_BY_REMOTE_PEER, this.clientDisconnectReason.get(5, TimeUnit.SECONDS),
                "client was told about the disconnect instead of timing out");
        waitFor(() -> !this.child.isOpen(), "child closed after the notification");
        waitFor(() -> RakOutboundBudget.totalOutstandingBytes() == 0, "shared budget returned, now " + RakOutboundBudget.totalOutstandingBytes());
    }

    @Test
    void maxMessageSizeIsAnswered() {
        // RakChannel.maxMessageSize() (unpatched) calls into the patched RakSessionCodec; a drift is a NoSuchMethodError
        int size = this.child.maxMessageSize();
        assertTrue(size > 0, "max message size " + size);
    }

    private static void waitFor(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for: " + description);
            }
            Thread.sleep(10);
        }
    }

    /**
     * Allocates normally until told to fail, like a pool that has run out of direct memory.
     */
    private static final class ToggleAllocator extends AbstractByteBufAllocator {
        private final AtomicBoolean fail;

        ToggleAllocator(AtomicBoolean fail) {
            super(false);
            this.fail = fail;
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            if (this.fail.get()) {
                throw new OutOfMemoryError("Cannot reserve direct buffer memory (test)");
            }
            return ByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            if (this.fail.get()) {
                throw new OutOfMemoryError("Cannot reserve direct buffer memory (test)");
            }
            return ByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }
    }
}
