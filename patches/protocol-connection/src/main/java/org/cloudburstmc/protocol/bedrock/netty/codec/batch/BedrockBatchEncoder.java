package org.cloudburstmc.protocol.bedrock.netty.codec.batch;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.SystemPropertyUtil;
import org.cloudburstmc.netty.channel.TransportChannel;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Patched by Geyser (see patches/README.md), derived from bedrock-connection 3.0.0.Beta14-20261009.172637-6: a batch
 * holds at most {@link #MAX_BATCH_PACKETS} packets and, besides the transport's limit, {@link #MAX_BATCH_BYTES}
 * serialized bytes. Reaching either limit while packets are written emits the pending packets as a batch right away,
 * and a failure while building a batch releases every packet that was waiting for it.
 */
public class BedrockBatchEncoder extends ChannelOutboundHandlerAdapter {

    public static final String NAME = "bedrock-batch-encoder";

    static final int MAX_BATCH_PACKETS = Math.max(1, SystemPropertyUtil.getInt("org.cloudburstmc.protocol.bedrock.maxBatchPackets", 256));
    static final int MAX_BATCH_BYTES = Math.max(1, SystemPropertyUtil.getInt("org.cloudburstmc.protocol.bedrock.maxBatchBytes", 1024 * 1024));

    private final Queue<BedrockPacketWrapper> messages = new ArrayDeque<>();
    private long pendingBytes;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof BedrockPacketWrapper)) {
            super.write(ctx, msg, promise);
            return;
        }

        BedrockPacketWrapper packet = (BedrockPacketWrapper) msg;
        ByteBuf buffer = packet.getPacketBuffer();
        // Accumulate messages to batch
        this.messages.add(packet);
        this.pendingBytes += buffer == null ? 0 : buffer.readableBytes();
        promise.trySuccess(); // complete write promise here

        if (this.messages.size() >= MAX_BATCH_PACKETS || this.pendingBytes >= MAX_BATCH_BYTES) {
            // Hand the batch on now; the next flush flushes it together with the rest
            this.writeBatch(ctx, maxBatchSize(ctx));
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (!messages.isEmpty()) {
            int maxBatchSize = Math.min(MAX_BATCH_BYTES, maxBatchSize(ctx));
            while (!messages.isEmpty()) {
                this.writeBatch(ctx, maxBatchSize);
            }
        }
        super.flush(ctx);
    }

    /**
     * Uncompressed bytes a batch may hold before the remaining packets go out in another, or {@link Integer#MAX_VALUE}
     * for no limit. A batch travels as one message, which may be no larger than the transport allows, so this leaves
     * a little room for compression and encryption to grow it. A larger single packet still goes alone.
     */
    private static int maxBatchSize(ChannelHandlerContext ctx) {
        if (!(ctx.channel() instanceof TransportChannel)) {
            return Integer.MAX_VALUE;
        }
        int maxMessageSize = ((TransportChannel) ctx.channel()).maxMessageSize();
        return maxMessageSize - maxMessageSize / 64;
    }

    /**
     * Writes queued packets as one batch, leaving the rest queued once it would grow past {@code maxBatchSize} or
     * {@link #MAX_BATCH_PACKETS} packets.
     */
    private void writeBatch(ChannelHandlerContext ctx, int maxBatchSize) {
        CompositeByteBuf buf = null;
        BedrockBatchWrapper batch = null;
        boolean written = false;
        try {
            buf = ctx.alloc().compositeDirectBuffer(messages.size() * 2);
            batch = BedrockBatchWrapper.newInstance();

            BedrockPacketWrapper packet;
            int batchPackets = 0;
            while (batchPackets < MAX_BATCH_PACKETS && (packet = messages.peek()) != null) {
                ByteBuf message = packet.getPacketBuffer();
                if (message != null && buf.isReadable()) {
                    int length = message.readableBytes();
                    if (buf.readableBytes() + VarInts.sizeOfUnsignedInt(length) + length > maxBatchSize) {
                        break;
                    }
                }
                messages.poll();
                batchPackets++;
                this.pendingBytes -= message == null ? 0 : message.readableBytes();
                try {
                    if (message == null) {
                        throw new IllegalArgumentException("BedrockPacket is not encoded");
                    }

                    addPrefixed(ctx, buf, message, packet.getReservedPrefixBytes());
                    batch.addPacket(packet.retain());
                } finally {
                    packet.release();
                }
            }

            batch.setUncompressed(buf.retain());
            ctx.write(batch.retain());
            written = true;
        } finally {
            if (!written) {
                // On failure, packets that did not make it into the batch are not written at all; release them
                // rather than keeping them around for a flush that may never come. After a successful batch the rest
                // stays queued for the next one.
                this.releasePending();
            }
            if (buf != null) {
                buf.release();
            }
            if (batch != null) {
                batch.release();
            }
        }
    }

    private void releasePending() {
        BedrockPacketWrapper message;
        while ((message = this.messages.poll()) != null) {
            ReferenceCountUtil.safeRelease(message);
        }
        this.pendingBytes = 0;
    }

    /**
     * Appends {@code message} to {@code buf} behind its VarInt length.
     *
     * <p>When the encoder that produced the message reserved room ahead of it, the prefix is
     * written into that gap and the whole thing goes in as a single component. Otherwise the
     * prefix needs a buffer of its own, costing an allocation and a second component.
     */
    public static void addPrefixed(ChannelHandlerContext ctx, CompositeByteBuf buf, ByteBuf message,
                                    int reservedPrefixBytes) {
        int length = message.readableBytes();
        int prefixSize = VarInts.sizeOfUnsignedInt(length);
        // The gap is only intact while the buffer still starts where the encoder left it.
        if (reservedPrefixBytes >= prefixSize && message.readerIndex() == reservedPrefixBytes) {
            int prefixIndex = reservedPrefixBytes - prefixSize;
            VarInts.setUnsignedInt(message, prefixIndex, length);
            // Absolute-range slice: a cached packet's wrapper is broadcast to many channels, so
            // this buffer's indices must never move, even briefly.
            buf.addComponent(true, message.retainedSlice(prefixIndex, prefixSize + length));
            return;
        }

        ByteBuf header = ctx.alloc().ioBuffer(prefixSize);
        VarInts.writeUnsignedInt(header, length);
        buf.addComponent(true, header);
        buf.addComponent(true, message.retain());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        this.releasePending();
        super.handlerRemoved(ctx);
    }
}
