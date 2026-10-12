/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
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

import io.netty.channel.Channel;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.BedrockOutboundPolicy;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockSessionFactory;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class GeyserBedrockPeer extends BedrockPeer {
    private SocketAddress proxiedAddress;

    // Guarded by this
    private boolean terminalSignalled;
    private boolean terminalDelivered;
    private String terminalReason;
    private @Nullable Consumer<String> terminalListener;

    // Highest queue seen since the monitor last collected them; calibration data for the outbound limits
    private final AtomicLong peakQueuedPackets = new AtomicLong();
    private final AtomicLong peakQueuedWeight = new AtomicLong();

    public GeyserBedrockPeer(Channel channel, BedrockSessionFactory sessionFactory) {
        this(channel, sessionFactory, BedrockOutboundPolicy.UNLIMITED, null);
    }

    /**
     * @param monitor watches this connection until it has been closed and cleaned up; may be null
     */
    public GeyserBedrockPeer(Channel channel, BedrockSessionFactory sessionFactory, BedrockOutboundPolicy outboundPolicy,
                             @Nullable TransportMonitor monitor) {
        super(channel, sessionFactory, outboundPolicy);
        if (monitor != null) {
            monitor.register(this);
        }
    }

    public SocketAddress getRealAddress() {
        SocketAddress proxied = this.proxiedAddress;
        return proxied == null ? this.getSocketAddress() : proxied;
    }

    public void setProxiedAddress(SocketAddress proxiedAddress) {
        this.proxiedAddress = proxiedAddress;
    }

    @Override
    public void sendPacket(int senderClientId, int targetClientId, BedrockPacket packet) {
        super.sendPacket(senderClientId, targetClientId, packet);
        this.recordQueued();
    }

    @Override
    public void sendPacket(BedrockPacketWrapper wrapper) {
        super.sendPacket(wrapper);
        this.recordQueued();
    }

    @Override
    public void sendPacketImmediately(int senderClientId, int targetClientId, BedrockPacket packet) {
        super.sendPacketImmediately(senderClientId, targetClientId, packet);
        this.recordQueued();
    }

    @Override
    public void sendPacketsImmediately(int senderClientId, int targetClientId, BedrockPacket... packets) {
        super.sendPacketsImmediately(senderClientId, targetClientId, packets);
        this.recordQueued();
    }

    private void recordQueued() {
        raise(this.peakQueuedPackets, this.getQueuedPacketCount());
        raise(this.peakQueuedWeight, this.getQueuedPacketWeight());
    }

    private static void raise(AtomicLong peak, long value) {
        long current;
        while (value > (current = peak.get()) && !peak.compareAndSet(current, value)) {
            // Lost to a concurrent sender; retry against its value
        }
    }

    /**
     * @return the most packets queued at once since the last call, which restarts the count from what is queued now
     */
    long takePeakQueuedPackets() {
        long queued = this.getQueuedPacketCount();
        return Math.max(this.peakQueuedPackets.getAndSet(queued), queued);
    }

    /**
     * @return the highest queued weight since the last call, which restarts the count from what is queued now
     */
    long takePeakQueuedWeight() {
        long queued = this.getQueuedPacketWeight();
        return Math.max(this.peakQueuedWeight.getAndSet(queued), queued);
    }

    /**
     * Aborts this connection because its transport stopped making progress or exceeded its limits. Outbound
     * admission closes right away on the calling thread; closing the channel and releasing queued packets happens
     * on the network thread. The session is told once, so it stops producing and can clean up on its own thread.
     * Safe to call from any thread, any number of times.
     */
    public void abortTransport(String reason) {
        this.abort(reason);
        this.signalTerminal(reason);
    }

    /**
     * Called by {@link GeyserOutboundPolicy} after this peer was aborted for exceeding an outbound limit.
     */
    void onOutboundOverload() {
        this.signalTerminal(BedrockPeer.OUTBOUND_OVERLOAD_REASON);
    }

    /**
     * Sets who is told, once, when this connection is aborted. If that already happened, the listener is called
     * right away. The listener is not kept once it was told, so a connection that lingers until it is cleaned up
     * doesn't keep it (and the session behind it) reachable.
     */
    public synchronized void setTerminalListener(Consumer<String> listener) {
        if (this.terminalDelivered) {
            // Someone was already told, and nothing will be signalled again
            return;
        }
        if (this.terminalSignalled) {
            this.terminalDelivered = true;
            listener.accept(this.terminalReason);
            return;
        }
        this.terminalListener = listener;
    }

    private synchronized void signalTerminal(String reason) {
        if (this.terminalSignalled) {
            return;
        }
        this.terminalSignalled = true;
        this.terminalReason = reason;
        Consumer<String> listener = this.terminalListener;
        if (listener != null) {
            this.terminalListener = null;
            this.terminalDelivered = true;
            listener.accept(reason);
        }
    }

    /**
     * @return whether this peer has been closed, its queue released and its channel closed
     */
    public boolean isDisposed() {
        return this.isClosed() && !this.channel.isOpen();
    }
}
