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

package org.geysermc.geyser.ping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPingPassthroughTest {

    private ServerSocket server;
    private Thread acceptor;
    private final CountDownLatch accepted = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
        if (acceptor != null) {
            acceptor.interrupt();
        }
    }

    /**
     * Accepts one connection and hands it to the given behaviour; the connection is kept open until the test ends.
     */
    private void serve(Consumer<Socket> behaviour) {
        acceptor = new Thread(() -> {
            try (Socket client = server.accept()) {
                accepted.countDown();
                behaviour.accept(client);
                // Keep the connection open without answering
                InputStream in = client.getInputStream();
                while (in.read() != -1) {
                    // drain
                }
            } catch (IOException ignored) {
            }
        }, "LegacyPingPassthroughTest server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private GeyserLegacyPingPassthrough passthrough() {
        // The GeyserImpl is only used by run(); pingOnce does not touch it
        return new GeyserLegacyPingPassthrough(null, 1);
    }

    private String host() {
        return server.getInetAddress().getHostAddress();
    }

    @Test
    void silentServerTimesOut() {
        serve(client -> {});
        GeyserLegacyPingPassthrough passthrough = passthrough();

        long start = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(GeyserLegacyPingPassthrough.TIMEOUT_MILLIS / 1000 + 3), () ->
            assertThrows(SocketTimeoutException.class, () -> passthrough.pingOnce(host(), server.getLocalPort(), false)));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis >= GeyserLegacyPingPassthrough.TIMEOUT_MILLIS - 500, "timed out too early: " + elapsedMillis + " ms");
    }

    @Test
    void oversizedLengthIsRejected() {
        serve(client -> {
            try {
                OutputStream out = client.getOutputStream();
                // packet length, packet id, then a status length of 0x7fffffff
                out.write(new byte[]{0x05, 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x07});
                out.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        GeyserLegacyPingPassthrough passthrough = passthrough();

        IOException e = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
            assertThrows(IOException.class, () -> passthrough.pingOnce(host(), server.getLocalPort(), false)));
        assertEquals(IOException.class, e.getClass());
        assertTrue(e.getMessage().contains("Unreasonable status length"), e.getMessage());
    }

    @Test
    void shutdownUnblocksPendingRead() throws Exception {
        serve(client -> {});
        GeyserLegacyPingPassthrough passthrough = passthrough();

        CompletableFuture<byte[]> ping = CompletableFuture.supplyAsync(() -> {
            try {
                return passthrough.pingOnce(host(), server.getLocalPort(), false);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(accepted.await(3, TimeUnit.SECONDS), "server never accepted");
        // Give the client time to write its request and block on the read
        Thread.sleep(200);

        long start = System.nanoTime();
        passthrough.shutdown();
        ExecutionException e = assertThrows(ExecutionException.class, () -> ping.get(1, TimeUnit.SECONDS));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertInstanceOf(SocketException.class, e.getCause().getCause());
        assertTrue(elapsedMillis < 1000, "shutdown took " + elapsedMillis + " ms");
    }
}
