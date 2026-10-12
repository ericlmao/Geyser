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

package org.geysermc.geyser.session;

import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.api.network.AuthType;
import org.geysermc.geyser.api.network.RemoteServer;
import org.geysermc.geyser.erosion.AbstractGeyserboundPacketHandler;
import org.geysermc.geyser.text.GeyserLocale;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.geysermc.geyser.scoreboard.network.util.GeyserMockContext.mockContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the real {@link GeyserSession#disconnect(Component)}, {@link GeyserSession#tick()} and login-path code on a
 * session whose constructor did not run: only the fields those methods use are set.
 */
class GeyserSessionDisconnectTest {
    private GeyserSession session;
    private GeyserImpl geyser;
    private GeyserLogger logger;
    private SessionManager sessionManager;
    private UpstreamSession upstream;
    private DownstreamSession downstream;
    private ScheduledFuture<?> tickThread;
    private BedrockPeer peer;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        session = mock(GeyserSession.class, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        doReturn("en_us").when(session).locale();

        geyser = mock(GeyserImpl.class);
        logger = mock(GeyserLogger.class);
        sessionManager = mock(SessionManager.class);
        when(geyser.getLogger()).thenReturn(logger);
        when(geyser.getSessionManager()).thenReturn(sessionManager);

        upstream = mock(UpstreamSession.class);
        BedrockServerSession bedrockSession = mock(BedrockServerSession.class);
        peer = mock(BedrockPeer.class);
        when(upstream.getSession()).thenReturn(bedrockSession);
        when(bedrockSession.getPeer()).thenReturn(peer);

        downstream = mock(DownstreamSession.class);
        tickThread = mock(ScheduledFuture.class);

        set("geyser", geyser);
        set("upstream", upstream);
        set("downstream", downstream);
        set("tickThread", tickThread);
        set("erosionHandler", mock(AbstractGeyserboundPacketHandler.class));
        set("closing", new AtomicBoolean());
        set("queuedImmediatelyPackets", new ArrayList<BedrockPacket>());
    }

    private void set(String name, Object value) throws ReflectiveOperationException {
        Field field = GeyserSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    private void assertTornDown() {
        assertTrue(session.isClosed());
        verify(tickThread).cancel(false);
        verify(sessionManager).removeSession(session);
        verify(upstream).release();
    }

    @Test
    void disconnectClosesBothSidesAndRemovesTheSession() {
        session.disconnect("bye");

        verify(downstream).disconnect(any(Component.class));
        verify(upstream).disconnect(anyString());
        assertTornDown();
        verify(logger, never()).error(anyString(), any(Throwable.class));
    }

    @Test
    void concurrentDisconnectsTearDownOnce() throws Exception {
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Thread> started = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(() -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                session.disconnect("bye");
            });
            thread.start();
            started.add(thread);
        }
        for (Thread thread : started) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        }

        verify(downstream, times(1)).disconnect(any(Component.class));
        verify(upstream, times(1)).disconnect(anyString());
        verify(sessionManager, times(1)).removeSession(session);
        verify(tickThread, times(1)).cancel(false);
        assertTrue(session.isClosed());
    }

    @Test
    void disconnectFinishesWhenTheBedrockSessionAlreadyClosed() {
        doThrow(new IllegalStateException("Session has been closed")).when(upstream).disconnect(anyString());

        session.disconnect("bye");

        assertTornDown();
    }

    @Test
    void disconnectFinishesWhenTheJavaDisconnectThrows() {
        doThrow(new RuntimeException("listener failed")).when(downstream).disconnect(any(Component.class));

        session.disconnect("bye");

        assertTornDown();
        verify(logger).error(anyString(), any(RuntimeException.class));
    }

    @Test
    void disconnectLeavesAJavaConnectionInProgressAlone() {
        // While the Java connection is being made, MCProtocolLib reports it closed; disconnecting it then would mark it
        // disconnected without ever closing it once it connects, so GeyserSessionAdapter#connected closes it instead
        when(downstream.isClosed()).thenReturn(true);

        session.disconnect("bye");

        verify(downstream, never()).disconnect(any(Component.class));
        assertTornDown();
    }

    @Test
    void javaConnectionCompletingAfterDisconnectIsClosed() {
        mockContext(context -> {
            GeyserSession adapterSession = mock(GeyserSession.class);
            RemoteServer remoteServer = mock(RemoteServer.class);
            when(remoteServer.authType()).thenReturn(AuthType.OFFLINE);
            when(adapterSession.remoteServer()).thenReturn(remoteServer);
            when(adapterSession.isClosed()).thenReturn(true);

            Session javaSession = mock(Session.class);
            Channel channel = mock(Channel.class);
            when(channel.isOpen()).thenReturn(true);
            when(javaSession.getChannel()).thenReturn(channel);

            new GeyserSessionAdapter(adapterSession).connected(new ConnectedEvent(javaSession));

            verify(javaSession).disconnect(any(Component.class));
            // disconnect() can't close the channel if it was called before the channel existed, so it is closed directly
            verify(channel).close();
            assertFalse(adapterSession.loggedIn);
            verify(adapterSession, never()).getPlayerEntity();
        });
    }

    @Test
    void closedSessionDoesNotConnectToTheJavaServer() {
        mockContext(context -> {
            session.disconnect("bye");
            GeyserImpl instance = GeyserImpl.getInstance();

            session.authenticate("Player");

            verify(instance, never()).eventBus();
            verify(logger, never()).error(anyString(), any(Throwable.class));
        });
    }

    @Test
    void failedJavaConnectDisconnectsTheSession() {
        mockContext(context -> {
            GeyserImpl instance = GeyserImpl.getInstance();
            when(instance.eventBus()).thenThrow(new RuntimeException("extension failed"));

            try (var locale = mockStatic(GeyserLocale.class)) {
                // No locales are loaded in tests
                locale.when(() -> GeyserLocale.getPlayerLocaleString(anyString(), anyString())).thenReturn("Server offline");

                session.authenticate("Player");
            }

            verify(logger).error(anyString(), any(RuntimeException.class));
            verify(downstream).disconnect(Component.text("Server offline"));
            assertTornDown();
        });
    }

    @Test
    void tickSendsQueuedPacketsWhenPartOfTheTickFails() {
        // Nothing else the tick needs is set, so it fails on its first step
        TextPacket packet = new TextPacket();
        session.getQueuedImmediatelyPackets().add(packet);

        session.tick();

        verify(peer).sendPacketsImmediately(eq(0), eq(0), eq(packet));
        assertTrue(session.getQueuedImmediatelyPackets().isEmpty());
        verify(logger, times(1)).error(anyString(), any(Throwable.class));

        // Failures on the following ticks aren't logged again right away
        session.tick();
        session.tick();
        verify(logger, times(1)).error(anyString(), any(Throwable.class));
        assertEquals(3, session.getTicks());
    }

    @Test
    void tickDoesNotSendWhenNothingIsQueued() {
        session.tick();

        verify(peer, never()).sendPacketsImmediately(Mockito.anyInt(), Mockito.anyInt(), any(BedrockPacket[].class));
    }
}
