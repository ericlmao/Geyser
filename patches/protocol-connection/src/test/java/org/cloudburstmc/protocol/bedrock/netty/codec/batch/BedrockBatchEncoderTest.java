package org.cloudburstmc.protocol.bedrock.netty.codec.batch;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockBatchEncoderTest {

    private static BedrockPacketWrapper encoded(ByteBufAllocator alloc, int size) {
        ByteBuf buffer = alloc.buffer(size).writeZero(size);
        return BedrockPacketWrapper.create(1, 0, 0, null, buffer);
    }

    @Test
    void batchesAreSplitAtThePacketLimit() {
        EmbeddedChannel channel = new EmbeddedChannel(new BedrockBatchEncoder());
        int packets = BedrockBatchEncoder.MAX_BATCH_PACKETS + 44;
        for (int i = 0; i < packets; i++) {
            channel.write(encoded(channel.alloc(), 8));
        }
        channel.flush();
        List<Integer> sizes = new ArrayList<>();
        BedrockBatchWrapper batch;
        while ((batch = channel.readOutbound()) != null) {
            sizes.add(batch.getPackets().size());
            batch.release();
        }
        assertEquals(List.of(BedrockBatchEncoder.MAX_BATCH_PACKETS, 44), sizes);
        channel.finishAndReleaseAll();
    }

    @Test
    void batchesAreSplitAtTheByteLimit() {
        EmbeddedChannel channel = new EmbeddedChannel(new BedrockBatchEncoder());
        int size = BedrockBatchEncoder.MAX_BATCH_BYTES / 2;
        for (int i = 0; i < 3; i++) {
            channel.write(encoded(channel.alloc(), size));
        }
        channel.flush();
        int batches = 0;
        BedrockBatchWrapper batch;
        while ((batch = channel.readOutbound()) != null) {
            batches++;
            batch.release();
        }
        assertEquals(2, batches);
        channel.finishAndReleaseAll();
    }

    @Test
    void failedBatchReleasesEveryWaitingPacket() {
        ByteBufAllocator failing = new AbstractByteBufAllocator() {
            @Override
            protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
                return UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity);
            }

            @Override
            protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
                throw new OutOfMemoryError("Cannot reserve direct buffer memory (test)");
            }

            @Override
            public CompositeByteBuf compositeDirectBuffer(int maxNumComponents) {
                throw new OutOfMemoryError("Cannot reserve direct buffer memory (test)");
            }

            @Override
            public boolean isDirectBufferPooled() {
                return false;
            }
        };
        EmbeddedChannel channel = new EmbeddedChannel(new BedrockBatchEncoder());
        channel.config().setAllocator(failing);
        List<BedrockPacketWrapper> wrappers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            BedrockPacketWrapper wrapper = encoded(UnpooledByteBufAllocator.DEFAULT, 8);
            wrappers.add(wrapper);
            channel.write(wrapper);
        }
        channel.flush();
        // EmbeddedChannel records the exception the pipeline saw instead of throwing it from flush()
        assertThrows(OutOfMemoryError.class, channel::checkException);
        assertTrue(wrappers.stream().allMatch(w -> w.refCnt() == 0), "waiting packets released");
        channel.finishAndReleaseAll();
    }
}
