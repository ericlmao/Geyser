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

import org.geysermc.geyser.GeyserLogger;

import java.util.function.Function;

/**
 * Limits on the outbound work Geyser keeps for Bedrock connections. Values come from system properties
 * ({@code -DGeyser.Outbound.<Name>=<value>}); invalid values are reported and replaced with the default, never
 * treated as "no limit".
 *
 * @param peerMaxPackets packets a single connection may have queued before they are handed to the network
 * @param peerMaxWeight estimated bytes a single connection may have queued
 * @param sharedMaxPackets packets all connections together may have queued
 * @param sharedMaxWeight estimated bytes all connections together may have queued
 * @param drainPackets packets handed to the network per drain before yielding
 * @param rakMaxOutstandingBytes encoded bytes a RakNet connection may have waiting to be sent or acknowledged
 * @param rakMaxTotalOutstandingBytes encoded bytes all RakNet connections together may have outstanding
 * @param rakMaxPendingWrites encoded batches a RakNet connection may have waiting for the shared RakNet thread
 * @param rakMaxQueuedBytes RakNet's own limit on unsent bytes per connection
 * @param monitorIntervalMillis how often connections are checked
 * @param stallMillis how long queued work may wait without any progress before its connection is aborted
 * @param unresponsiveGraceMillis how long past RakNet's session timeout a silent client is kept before it is aborted
 * @param pressureSoft direct memory usage (fraction of the maximum) at which overloaded connections are aborted
 * @param pressureStop direct memory usage at which no further large payloads are admitted
 * @param pressureResume direct memory usage below which pressure measures end
 */
public record OutboundLimits(
    int peerMaxPackets,
    long peerMaxWeight,
    int sharedMaxPackets,
    long sharedMaxWeight,
    int drainPackets,
    long rakMaxOutstandingBytes,
    long rakMaxTotalOutstandingBytes,
    int rakMaxPendingWrites,
    int rakMaxQueuedBytes,
    long monitorIntervalMillis,
    long stallMillis,
    long unresponsiveGraceMillis,
    double pressureSoft,
    double pressureStop,
    double pressureResume
) {
    private static final long MIB = 1024L * 1024;

    public static final OutboundLimits DEFAULTS = new OutboundLimits(
        4096,
        8 * MIB,
        131_072,
        256 * MIB,
        256,
        8 * MIB,
        128 * MIB,
        128,
        (int) (16 * MIB),
        1000,
        30_000,
        20_000,
        0.80,
        0.90,
        0.70
    );

    public static OutboundLimits fromSystemProperties(GeyserLogger logger) {
        OutboundLimits d = DEFAULTS;
        Reader r = new Reader(logger);
        int peerMaxPackets = r.read("PeerMaxPackets", d.peerMaxPackets, Integer::parseInt, 16, 1 << 20);
        long peerMaxWeight = r.read("PeerMaxWeight", d.peerMaxWeight, Long::parseLong, 64 * 1024L, 1L << 34);
        int sharedMaxPackets = r.read("SharedMaxPackets", d.sharedMaxPackets, Integer::parseInt, peerMaxPackets, Integer.MAX_VALUE);
        long sharedMaxWeight = r.read("SharedMaxWeight", d.sharedMaxWeight, Long::parseLong, peerMaxWeight, 1L << 40);
        int drainPackets = r.read("DrainPackets", d.drainPackets, Integer::parseInt, 1, peerMaxPackets);
        long rakMaxOutstandingBytes = r.read("RakMaxOutstandingBytes", d.rakMaxOutstandingBytes, Long::parseLong, 64 * 1024L, 1L << 34);
        long rakMaxTotalOutstandingBytes = r.read("RakMaxTotalOutstandingBytes", d.rakMaxTotalOutstandingBytes, Long::parseLong, rakMaxOutstandingBytes, 1L << 40);
        int rakMaxPendingWrites = r.read("RakMaxPendingWrites", d.rakMaxPendingWrites, Integer::parseInt, 1, 1 << 20);
        // Kept under its original name, which was the first version of this setting
        int rakMaxQueuedBytes = r.readProperty("Geyser.RakMaxQueuedBytes", d.rakMaxQueuedBytes, Integer::parseInt, 64 * 1024, Integer.MAX_VALUE);
        long monitorIntervalMillis = r.read("MonitorIntervalMillis", d.monitorIntervalMillis, Long::parseLong, 100L, 60_000L);
        long stallMillis = r.read("StallMillis", d.stallMillis, Long::parseLong, 1000L, 3_600_000L);
        long unresponsiveGraceMillis = r.read("UnresponsiveGraceMillis", d.unresponsiveGraceMillis, Long::parseLong, 0L, 3_600_000L);
        double pressureSoft = r.read("PressureSoft", d.pressureSoft, Double::parseDouble, 0.1, 1.0);
        double pressureStop = r.read("PressureStop", d.pressureStop, Double::parseDouble, pressureSoft, 1.0);
        double pressureResume = r.read("PressureResume", d.pressureResume, Double::parseDouble, 0.0, pressureSoft);
        // Keep defaults consistent with explicitly configured values
        sharedMaxPackets = Math.max(sharedMaxPackets, peerMaxPackets);
        sharedMaxWeight = Math.max(sharedMaxWeight, peerMaxWeight);
        drainPackets = Math.min(drainPackets, peerMaxPackets);
        rakMaxTotalOutstandingBytes = Math.max(rakMaxTotalOutstandingBytes, rakMaxOutstandingBytes);
        pressureStop = Math.max(pressureStop, pressureSoft);
        pressureResume = Math.min(pressureResume, pressureSoft);
        return new OutboundLimits(peerMaxPackets, peerMaxWeight, sharedMaxPackets, sharedMaxWeight, drainPackets,
            rakMaxOutstandingBytes, rakMaxTotalOutstandingBytes, rakMaxPendingWrites, rakMaxQueuedBytes,
            monitorIntervalMillis, stallMillis, unresponsiveGraceMillis, pressureSoft, pressureStop, pressureResume);
    }

    private record Reader(GeyserLogger logger) {
        <T extends Comparable<T>> T read(String name, T fallback, Function<String, T> parser, T min, T max) {
            return readProperty("Geyser.Outbound." + name, fallback, parser, min, max);
        }

        <T extends Comparable<T>> T readProperty(String property, T fallback, Function<String, T> parser, T min, T max) {
            String raw = System.getProperty(property);
            if (raw == null) {
                return fallback;
            }
            try {
                T value = parser.apply(raw.trim());
                if (value.compareTo(min) >= 0 && value.compareTo(max) <= 0) {
                    return value;
                }
            } catch (RuntimeException ignored) {
            }
            logger.warning("Ignoring invalid value '" + raw + "' for " + property + " (allowed: " + min + " to " + max + "), using " + fallback);
            return fallback;
        }
    }
}
