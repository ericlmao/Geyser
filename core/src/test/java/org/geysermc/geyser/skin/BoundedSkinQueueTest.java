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

package org.geysermc.geyser.skin;

import org.geysermc.geyser.skin.FloodgateSkinUploader.BoundedSkinQueue;
import org.geysermc.geyser.skin.FloodgateSkinUploader.QueuedSkin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BoundedSkinQueueTest {

    private static QueuedSkin skin(String xuid, int length) {
        return new QueuedSkin(xuid, "x".repeat(length));
    }

    @Test
    void evictsOldestByCount() {
        BoundedSkinQueue queue = new BoundedSkinQueue(3, 1_000);
        for (int i = 0; i < 5; i++) {
            assertTrue(queue.add(skin(String.valueOf(i), 10)));
        }
        assertEquals(3, queue.size());
        assertEquals(30, queue.queuedChars());
        assertEquals("2", queue.peekFirst().xuid());
    }

    @Test
    void evictsOldestByChars() {
        BoundedSkinQueue queue = new BoundedSkinQueue(100, 100);
        queue.add(skin("a", 40));
        queue.add(skin("b", 40));
        // 40 + 40 + 30 > 100, so "a" has to go
        queue.add(skin("c", 30));
        assertEquals(2, queue.size());
        assertEquals(70, queue.queuedChars());
        assertEquals("b", queue.peekFirst().xuid());

        // Needs both "b" and "c" evicted
        queue.add(skin("d", 100));
        assertEquals(1, queue.size());
        assertEquals(100, queue.queuedChars());
        assertEquals("d", queue.peekFirst().xuid());
    }

    @Test
    void dropsEntryLargerThanBudget() {
        BoundedSkinQueue queue = new BoundedSkinQueue(100, 100);
        queue.add(skin("a", 50));
        assertFalse(queue.add(skin("huge", 101)));
        // The existing entry is kept rather than evicted for an entry that can never fit
        assertEquals(1, queue.size());
        assertEquals("a", queue.peekFirst().xuid());
    }

    @Test
    void removeFirstAndClearTrackChars() {
        BoundedSkinQueue queue = new BoundedSkinQueue(100, 1_000);
        queue.add(skin("a", 10));
        queue.add(skin("b", 20));

        queue.removeFirst();
        assertEquals(20, queue.queuedChars());
        assertEquals("b", queue.peekFirst().xuid());

        queue.clear();
        assertEquals(0, queue.size());
        assertEquals(0, queue.queuedChars());
        assertNull(queue.peekFirst());

        // Removing from an empty queue is a no-op
        queue.removeFirst();
        assertEquals(0, queue.queuedChars());
    }
}
