/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.session;

import io.netty.util.ReferenceCountUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.geysermc.geyser.network.bedrock.GeyserBedrockPeer;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;

@RequiredArgsConstructor
public class UpstreamSession {
    @Getter private final BedrockServerSession session;
    @Getter @Setter
    private boolean initialized = false;
    /**
     * Null once sent or released. Guarded by this, as the session may be disconnected from another thread.
     */
    private Queue<BedrockPacket> postStartGamePackets = new ArrayDeque<>();
    /**
     * Whether {@link #release()} was called. Guarded by this.
     */
    private boolean released;

    /**
     * Sends a packet, or releases it if the session is closed. Either way, the caller no longer owns the packet.
     */
    public void sendPacket(@NonNull BedrockPacket packet) {
        if (isClosed()) {
            ReferenceCountUtil.safeRelease(packet);
            return;
        }
        session.sendPacket(packet);
    }

    /**
     * Sends a packet immediately, or releases it if the session is closed. Either way, the caller no longer owns the
     * packet.
     */
    public void sendPacketImmediately(@NonNull BedrockPacket packet) {
        if (isClosed()) {
            ReferenceCountUtil.safeRelease(packet);
            return;
        }
        session.sendPacketImmediately(packet);
    }

    public void disconnect(String reason) {
        this.session.disconnect(reason);
    }

    /**
     * Queue a packet that must be delayed until after login.
     */
    public synchronized void queuePostStartGamePacket(BedrockPacket packet) {
        if (released) {
            ReferenceCountUtil.safeRelease(packet);
            return;
        }
        if (postStartGamePackets == null) {
            // Already sent, so there is no reason to wait
            sendPacket(packet);
            return;
        }
        postStartGamePackets.add(packet);
    }

    public synchronized void sendPostStartGamePackets() {
        if (isClosed()) {
            release();
            return;
        }
        if (postStartGamePackets == null) {
            return;
        }

        BedrockPacket packet;
        while ((packet = postStartGamePackets.poll()) != null) {
            sendPacket(packet);
        }
        postStartGamePackets = null;
    }

    /**
     * Releases the packets that were queued for after the start game packet and not sent yet. Called when the
     * session is disconnected; packets queued afterwards are dropped.
     */
    public synchronized void release() {
        released = true;
        if (postStartGamePackets == null) {
            return;
        }
        BedrockPacket packet;
        while ((packet = postStartGamePackets.poll()) != null) {
            ReferenceCountUtil.safeRelease(packet);
        }
        postStartGamePackets = null;
    }

    public boolean isClosed() {
        return !session.getPeer().isConnected() && !session.getPeer().isConnecting();
    }

    public InetSocketAddress getAddress() {
        // Will always be an InetSocketAddress. See ProxyChannel#remoteAddress
        return (InetSocketAddress) ((GeyserBedrockPeer) session.getPeer()).getRealAddress();
    }

    public void setInetAddress(InetSocketAddress address) {
        ((GeyserBedrockPeer) session.getPeer()).setProxiedAddress(address);
    }

    /**
     * Gets the session's protocol version.
     *
     * @return the session's protocol version.
     */
    public int getProtocolVersion() {
        return this.session.getCodec().getProtocolVersion();
    }

    /**
     * Gets the codec helper for this session.
     *
     * @return the codec helper for this session
     */
    public BedrockCodecHelper getCodecHelper() {
        return this.session.getPeer().getCodecHelper();
    }

    public void forciblyClose() {
        this.session.getPeer().getChannel().close();
    }
}
