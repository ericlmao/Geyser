/*
 * Copyright (c) 2026 GeyserMC. Added to CloudburstMC Protocol by Geyser's patched build (see patches/README.md).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cloudburstmc.protocol.bedrock;

import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

/**
 * Limits how much outbound work a {@link BedrockPeer} may hold before it has been handed to its channel.
 * <p>
 * Every send, including immediate sends, reserves capacity before it is queued. When a reservation fails, the peer
 * stops accepting packets for good and is aborted: a connection that cannot keep up must not be allowed to buffer
 * without limit, and dropping individual packets would desynchronize the client.
 * <p>
 * All methods may be called from any thread and must not block.
 */
public interface BedrockOutboundPolicy {

    /**
     * No limits, which matches the behavior of an unpatched peer.
     */
    BedrockOutboundPolicy UNLIMITED = new BedrockOutboundPolicy() {
    };

    /**
     * @return the maximum number of packets a peer may have queued, or a value {@code <= 0} for no limit
     */
    default int maxQueuedPackets() {
        return 0;
    }

    /**
     * @return the maximum estimated weight a peer may have queued, or a value {@code <= 0} for no limit.
     * A single packet heavier than this is still admitted when nothing else is queued.
     */
    default long maxQueuedWeight() {
        return 0;
    }

    /**
     * @return how many packets one drain hands to the channel before yielding the event loop
     */
    default int maxDrainPackets() {
        return Integer.MAX_VALUE;
    }

    /**
     * Estimates the memory retained by a queued packet. Must be cheap and must not allocate.
     */
    default long estimateWeight(BedrockPacket packet) {
        return 0;
    }

    /**
     * Estimates the memory retained by a queued wrapper. Must be cheap and must not allocate.
     */
    default long estimateWeight(BedrockPacketWrapper wrapper) {
        if (wrapper.getPacket() != null) {
            return this.estimateWeight(wrapper.getPacket());
        }
        return wrapper.getPacketBuffer() == null ? 0 : wrapper.getPacketBuffer().readableBytes();
    }

    /**
     * Reserves capacity shared by all peers using this policy.
     *
     * @return {@code false} if the shared budget is exhausted
     */
    default boolean tryReserveShared(int packets, long weight) {
        return true;
    }

    /**
     * Returns capacity previously reserved with {@link #tryReserveShared(int, long)}. Called exactly once per reservation.
     */
    default void releaseShared(int packets, long weight) {
    }

    /**
     * Called once when the peer stopped accepting packets because a limit was reached. The peer has already been
     * aborted when this is called.
     */
    default void onOverload(BedrockPeer peer, Rejection rejection) {
    }

    enum Rejection {
        /**
         * The peer no longer accepts packets because it was closed or aborted.
         */
        CLOSED,
        PEER_PACKET_LIMIT,
        PEER_WEIGHT_LIMIT,
        SHARED_LIMIT
    }
}
