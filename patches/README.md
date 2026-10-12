# Patched Cloudburst networking artifacts

Geyser builds two of its networking dependencies from the upstream artifacts pinned in
`gradle/libs.versions.toml`, with a few classes replaced. Every Geyser module resolves the upstream
coordinates to these projects through dependency substitution (see `geyser.base-conventions`), so the
shaded jars contain the patched classes and nothing else changes.

The replaced classes are derived from the upstream sources of `netty-transport-raknet`
`2.0.0.CR5-20261009.162522-2` and `bedrock-connection` `3.0.0.Beta14-20261009.172637-6` (the `network` and
`protocol-connection` versions in `gradle/libs.versions.toml`). They must be re-derived from the new upstream
sources whenever either catalog version changes. Each project records the version its classes were derived from
in `upstream-base.txt`, and `verifyUpstreamBase` (run by `check` and `compileJava`, so every jar build) fails while that differs from the catalog;
update the file only after re-deriving.

Each project's jar is the upstream jar with the classes under its `src/main/java` replaced (nested classes
included) and any new classes added. Both upstream projects are licensed under the Apache License 2.0; the
modified files keep their original headers and describe their changes in their class documentation.

## Why

Without these changes, outbound packets for a Bedrock client are queued without any limit at three points:
the per-connection packet queue in `BedrockPeer`, the task queue of the connection's event loop (immediate
sends), and the task queue of the shared RakNet server event loop (one task per encoded batch). A client that
stops acknowledging, or an event loop that stalls, makes these queues grow until direct memory and then the
heap are exhausted, which then stalls every other connection. See the incident report for the heap dump analysis.

## `patched-protocol-connection` (`org.cloudburstmc.protocol:bedrock-connection`)

- `BedrockOutboundPolicy` (new): limits and a weight estimate for outbound packets, supplied by Geyser.
- `BedrockPeer`:
  - Every send, including `sendPacketImmediately` and `sendPacketsImmediately`, reserves capacity before it is
    queued. Admission and closing admission share one atomic state, so no packet is admitted after close.
  - Immediate sends go through the same FIFO queue and drain it right away, so packet order is preserved.
  - Exceeding a limit aborts the connection once: admission closes immediately and the channel is closed
    without a disconnect packet (which would need a new allocation under memory pressure).
  - The queue is only consumed on the channel's event loop (or once that loop has terminated). A drain hands
    over at most `maxDrainPackets()` packets before yielding. At most one flush, drain and free task is pending.
  - Progress timestamps and queue counters are published for monitoring.
- `BedrockBatchEncoder`: besides upstream's transport-derived size limit, a batch holds at most 256 packets / 1 MiB
  (system properties `org.cloudburstmc.protocol.bedrock.maxBatchPackets` / `maxBatchBytes`), reaching either
  limit while writing emits a batch right away, and a failure while building a batch releases every packet waiting
  for it.

## `patched-network-raknet` (`org.cloudburstmc.netty:netty-transport-raknet`)

- `RakOutboundBudget` (new): per-channel and shared limits on encoded bytes that are pending for the parent
  event loop, unsent, or sent reliably but unacknowledged, plus a limit on pending writes per channel.
- `RakChildChannel`: writes reach the parent event loop through a bounded queue with a single drain task,
  instead of one task per message. A write over the budget fails and closes the channel.
- `RakSessionCodec`:
  - `RAK_MAX_QUEUED_BYTES` is also enforced when a message is queued, not only on the next tick.
  - A session stuck in `DISCONNECTING` is closed after the session timeout. (Upstream already closes a session
    whose disconnect notification cannot be allocated.)
  - `close()` is idempotent but still closes an open channel when called again.
  - Outstanding bytes and receive activity are published for monitoring.

## Tests

`./gradlew :patched-protocol-connection:test :patched-network-raknet:test` runs the patch tests with Netty's
paranoid leak detection. The RakNet tests run a real server and client over loopback.
