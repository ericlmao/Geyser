/*
 * Copyright (c) 2026 GeyserMC. Added to CloudburstMC Network by Geyser's patched build (see patches/README.md).
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

package org.cloudburstmc.netty.channel.raknet;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Limits the encoded payload a server child channel may have outstanding: handed to the channel but waiting for the
 * parent event loop, queued in the session but not yet sent, or sent reliably but not yet acknowledged.
 * <p>
 * A child channel checks these limits when data is written to it, on its own event loop, so a stalled parent event
 * loop or an unresponsive client cannot make it buffer without bound. A write over the limit fails and closes the
 * channel.
 */
public final class RakOutboundBudget {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakOutboundBudget.class);

    private static volatile long maxChannelBytes = Long.getLong("org.cloudburstmc.netty.raknet.maxOutstandingBytes", 8L * 1024 * 1024);
    private static volatile long maxTotalBytes = Long.getLong("org.cloudburstmc.netty.raknet.maxTotalOutstandingBytes", 128L * 1024 * 1024);
    private static volatile int maxPendingWrites = Integer.getInteger("org.cloudburstmc.netty.raknet.maxPendingWrites", 128);

    // Outstanding bytes of all child channels: pending parent submissions plus each session's published outstanding bytes
    private static final AtomicLong TOTAL = new AtomicLong();

    private RakOutboundBudget() {
    }

    /**
     * @param channelBytes maximum outstanding bytes per channel
     * @param totalBytes maximum outstanding bytes over all channels
     * @param pendingWrites maximum number of writes per channel waiting for the parent event loop
     */
    public static void configure(long channelBytes, long totalBytes, int pendingWrites) {
        if (channelBytes <= 0 || totalBytes <= 0 || pendingWrites <= 0) {
            throw new IllegalArgumentException("RakNet outbound limits must be positive: " + channelBytes + ", " + totalBytes + ", " + pendingWrites);
        }
        long total = TOTAL.get();
        if (total != 0) {
            // Not reset: sessions that are still open would release their bytes later and push the total negative
            log.warn("RakNet outbound budget reconfigured with {} bytes still accounted; the previous instance did not release everything", total);
        }
        maxChannelBytes = channelBytes;
        maxTotalBytes = totalBytes;
        maxPendingWrites = pendingWrites;
    }

    public static long maxChannelBytes() {
        return maxChannelBytes;
    }

    public static long maxTotalBytes() {
        return maxTotalBytes;
    }

    public static int maxPendingWrites() {
        return maxPendingWrites;
    }

    /**
     * @return the outstanding bytes of all child channels
     */
    public static long totalOutstandingBytes() {
        return TOTAL.get();
    }

    /**
     * Reserves {@code bytes} of the shared budget. A single write is admitted when nothing else is outstanding.
     */
    static boolean tryReserveTotal(long bytes) {
        long max = maxTotalBytes;
        for (; ; ) {
            long current = TOTAL.get();
            if (current > 0 && current + bytes > max) {
                return false;
            }
            if (TOTAL.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    /**
     * Adjusts the shared total without checking the limit, e.g. when a session publishes its outstanding bytes or
     * a reservation is returned.
     */
    public static void adjustTotal(long delta) {
        if (delta != 0) {
            TOTAL.addAndGet(delta);
        }
    }
}
