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

import io.netty.buffer.ByteBuf;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.BedrockOutboundPolicy;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.data.skin.ImageData;
import org.cloudburstmc.protocol.bedrock.data.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.data.SubChunkData;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerSkinPacket;
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackChunkDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Geyser's {@link BedrockOutboundPolicy}: per-connection and shared limits from {@link OutboundLimits}, a weight
 * estimate for each packet, and a direct memory pressure gate that the {@link TransportMonitor} sets.
 */
public final class GeyserOutboundPolicy implements BedrockOutboundPolicy {
    /**
     * Rough cost of any queued packet: the wrapper, the queue node and the packet object itself.
     */
    static final long BASE_WEIGHT = 128;
    /**
     * Packets estimated at least this heavy are not admitted while direct memory pressure is at the stop level and
     * the shared queue is more than half full.
     */
    static final long LARGE_PAYLOAD_WEIGHT = 64 * 1024;
    private static final long PER_ELEMENT_WEIGHT = 512;
    private static final long LOGIN_PACKET_WEIGHT = 256 * 1024;

    private final OutboundLimits limits;
    private final GeyserLogger logger;
    private final BooleanSupplier logAddresses;
    private final AtomicLong sharedPackets = new AtomicLong();
    private final AtomicLong sharedWeight = new AtomicLong();
    private volatile Pressure pressure = Pressure.NORMAL;

    public GeyserOutboundPolicy(OutboundLimits limits, GeyserLogger logger) {
        this(limits, logger, configuredLogAddresses());
    }

    /**
     * @param logAddresses whether warnings may include the connection's address
     */
    public GeyserOutboundPolicy(OutboundLimits limits, GeyserLogger logger, BooleanSupplier logAddresses) {
        this.limits = limits;
        this.logger = logger;
        this.logAddresses = logAddresses;
    }

    public OutboundLimits limits() {
        return this.limits;
    }

    @Override
    public int maxQueuedPackets() {
        return this.limits.peerMaxPackets();
    }

    @Override
    public long maxQueuedWeight() {
        return this.limits.peerMaxWeight();
    }

    @Override
    public int maxDrainPackets() {
        return this.limits.drainPackets();
    }

    @Override
    public long estimateWeight(BedrockPacket packet) {
        return BASE_WEIGHT + payloadWeight(packet);
    }

    @Override
    public boolean tryReserveShared(int packets, long weight) {
        // Only while the queues are actually holding a lot: memory used elsewhere must not get every connection
        // that receives a chunk aborted. Connections with large backlogs are shed by the TransportMonitor anyway.
        if (this.pressure == Pressure.STOP && weight >= LARGE_PAYLOAD_WEIGHT
            && this.sharedWeight.get() > this.limits.sharedMaxWeight() / 2) {
            return false;
        }
        if (!tryAdd(this.sharedPackets, packets, this.limits.sharedMaxPackets())) {
            return false;
        }
        if (!tryAdd(this.sharedWeight, weight, this.limits.sharedMaxWeight())) {
            this.sharedPackets.addAndGet(-packets);
            return false;
        }
        return true;
    }

    @Override
    public void releaseShared(int packets, long weight) {
        this.sharedPackets.addAndGet(-packets);
        this.sharedWeight.addAndGet(-weight);
    }

    @Override
    public void onOverload(BedrockPeer peer, Rejection rejection) {
        String reason = switch (rejection) {
            case PEER_PACKET_LIMIT -> "more than " + this.limits.peerMaxPackets() + " packets were waiting to be sent";
            case PEER_WEIGHT_LIMIT -> "more than " + this.limits.peerMaxWeight() + " bytes were waiting to be sent";
            case SHARED_LIMIT -> this.pressure == Pressure.STOP
                ? "a large packet was refused because direct memory is running low"
                : "the server-wide outbound limit was reached";
            case CLOSED -> "the connection is closed";
        };
        this.logger.warning("Closing Bedrock connection " + loggableAddress(peer, this.logAddresses) + ": " + reason);
        if (peer instanceof GeyserBedrockPeer geyserPeer) {
            geyserPeer.onOutboundOverload();
        }
    }

    public long sharedQueuedPackets() {
        return this.sharedPackets.get();
    }

    public long sharedQueuedWeight() {
        return this.sharedWeight.get();
    }

    public Pressure pressure() {
        return this.pressure;
    }

    void setPressure(Pressure pressure) {
        this.pressure = pressure;
    }

    /**
     * Follows {@code log-player-ip-addresses}, read when a warning is logged so a reload is picked up.
     */
    static BooleanSupplier configuredLogAddresses() {
        return () -> {
            GeyserImpl geyser = GeyserImpl.getInstance();
            return geyser == null || geyser.config() == null || geyser.config().logPlayerIpAddresses();
        };
    }

    /**
     * @return the peer's address, or a placeholder if addresses must not be logged
     */
    static String loggableAddress(BedrockPeer peer, BooleanSupplier logAddresses) {
        if (!logAddresses.getAsBoolean()) {
            return "<IP address withheld>";
        }
        return String.valueOf(peer instanceof GeyserBedrockPeer geyserPeer ? geyserPeer.getRealAddress() : peer.getSocketAddress());
    }

    /**
     * Adds {@code amount} unless that would exceed {@code max}. A single amount is always admitted when nothing is
     * reserved, so one legitimately large packet cannot be refused outright.
     */
    private static boolean tryAdd(AtomicLong counter, long amount, long max) {
        for (; ; ) {
            long current = counter.get();
            if (current > 0 && current + amount > max) {
                return false;
            }
            if (counter.compareAndSet(current, current + amount)) {
                return true;
            }
        }
    }

    /**
     * Estimates the memory retained by packet fields that can be large. Everything else is covered by
     * {@link #BASE_WEIGHT}. Must not allocate.
     */
    static long payloadWeight(BedrockPacket packet) {
        if (packet instanceof LevelChunkPacket chunk) {
            return readable(chunk.getData());
        } else if (packet instanceof SubChunkPacket subChunk) {
            long weight = 0;
            for (SubChunkData data : subChunk.getSubChunks()) {
                weight += readable(data.getData()) + readable(data.getHeightMapData()) + readable(data.getRenderHeightMapData());
            }
            return weight;
        } else if (packet instanceof ResourcePackChunkDataPacket chunkData) {
            return readable(chunkData.getData());
        } else if (packet instanceof PlayerListPacket playerList) {
            long weight = 0;
            for (PlayerListPacket.Entry entry : playerList.getEntries()) {
                weight += PER_ELEMENT_WEIGHT + skinWeight(entry.getSkin());
            }
            return weight;
        } else if (packet instanceof PlayerSkinPacket skin) {
            return skinWeight(skin.getSkin());
        } else if (packet instanceof InventoryContentPacket inventory) {
            return (long) inventory.getContents().size() * PER_ELEMENT_WEIGHT;
        } else if (packet instanceof CraftingDataPacket crafting) {
            return (long) (crafting.getCraftingData().size() + crafting.getShapedData().size() + crafting.getShapelessData().size()
                + crafting.getMultiData().size() + crafting.getSmithingTransformData().size() + crafting.getSmithingTrimData().size()
                + crafting.getPotionMixData().size() + crafting.getContainerMixData().size()) * PER_ELEMENT_WEIGHT;
        } else if (packet instanceof CreativeContentPacket creative) {
            return (long) (creative.getContents().size() + creative.getGroups().size()) * PER_ELEMENT_WEIGHT;
        } else if (packet instanceof AvailableCommandsPacket commands) {
            return (long) commands.getCommands().size() * PER_ELEMENT_WEIGHT * 2;
        } else if (packet instanceof ItemComponentPacket components) {
            return (long) components.getItems().size() * PER_ELEMENT_WEIGHT * 2;
        } else if (packet instanceof StartGamePacket || packet instanceof BiomeDefinitionListPacket) {
            return LOGIN_PACKET_WEIGHT;
        }
        return 0;
    }

    private static long skinWeight(@Nullable SerializedSkin skin) {
        if (skin == null) {
            return 0;
        }
        long weight = imageWeight(skin.getSkinData()) + imageWeight(skin.getCapeData());
        String geometry = skin.getGeometryData();
        if (geometry != null) {
            weight += geometry.length() * 2L;
        }
        return weight;
    }

    private static long imageWeight(@Nullable ImageData image) {
        return image == null ? 0 : image.getImage().length;
    }

    private static long readable(@Nullable ByteBuf buf) {
        return buf == null || buf.refCnt() == 0 ? 0 : buf.readableBytes();
    }

    public enum Pressure {
        NORMAL,
        /**
         * Connections holding more than half of their limits are aborted.
         */
        SOFT,
        /**
         * Additionally, no further large payloads are admitted while the shared queue is more than half full.
         */
        STOP
    }
}
