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

package org.geysermc.geyser.entity;

import org.geysermc.geyser.entity.spawn.EntitySpawnContext;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.living.monster.EnderDragonEntity;
import org.geysermc.geyser.entity.type.living.monster.EnderDragonPartEntity;
import org.geysermc.geyser.scoreboard.network.util.GeyserMockContext;
import org.geysermc.geyser.session.cache.EntityCache;
import org.geysermc.geyser.session.cache.WorldBorder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.geysermc.geyser.scoreboard.network.util.GeyserMockContextScoreboard.mockContextScoreboard;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * The Ender Dragon's hitbox parts are separate entities in the {@link EntityCache}, with Java ids
 * {@code dragonId + 1..8}. They must leave the cache together with the dragon, otherwise the parts of a
 * dragon that is spawned again with the same Java id are rejected as duplicates and never spawn.
 */
public class EnderDragonPartsTest {
    private static final int DRAGON_JAVA_ID = 1000;
    private static final int PART_COUNT = 8;

    @Test
    void partsAreRemovedWithTheDragon() {
        mockContextScoreboard(context -> {
            EntityCache entityCache = context.session().getEntityCache();

            EnderDragonEntity dragon = spawnDragon(context, 100);
            assertEquals(1 + PART_COUNT, entityCache.getEntitiesUnsafe().size());
            assertPartsCached(entityCache, true);

            entityCache.removeEntity(dragon);
            assertEquals(0, entityCache.getEntitiesUnsafe().size());
            for (int i = 0; i <= PART_COUNT; i++) {
                assertNull(entityCache.getEntityByJavaId(DRAGON_JAVA_ID + i));
            }

            drainPackets(context);
        });
    }

    @Test
    void respawnedDragonGetsValidParts() {
        mockContextScoreboard(context -> {
            EntityCache entityCache = context.session().getEntityCache();

            EnderDragonEntity first = spawnDragon(context, 100);
            List<Entity> firstParts = parts(entityCache);
            entityCache.removeEntity(first);

            EnderDragonEntity second = spawnDragon(context, 200);
            assertEquals(1 + PART_COUNT, entityCache.getEntitiesUnsafe().size());
            assertSame(second, entityCache.getEntityByJavaId(DRAGON_JAVA_ID));
            assertPartsCached(entityCache, true);

            List<Entity> secondParts = parts(entityCache);
            for (int i = 0; i < PART_COUNT; i++) {
                assertNotSame(firstParts.get(i), secondParts.get(i), "part " + (i + 1) + " is a stale part of the first dragon");
                assertTrue(secondParts.get(i).isValid(), "part " + (i + 1) + " of the respawned dragon was never spawned");
            }

            drainPackets(context);
        });
    }

    @Test
    void removeAllEntitiesWithDragon() {
        mockContextScoreboard(context -> {
            EntityCache entityCache = context.session().getEntityCache();
            // removeAllEntities() clears the world border collision first; not under test here
            when(context.session().getWorldBorder()).thenReturn(context.mock(WorldBorder.class));

            spawnDragon(context, 100);
            // Iterates a copy containing the dragon and its parts; parts removed via the dragon are visited again
            entityCache.removeAllEntities();
            assertEquals(0, entityCache.getEntitiesUnsafe().size());
            assertPartsCached(entityCache, false);

            drainPackets(context);
        });
    }

    private static EnderDragonEntity spawnDragon(GeyserMockContext context, long geyserId) {
        EntitySpawnContext spawnContext = EntitySpawnContext.DUMMY_CONTEXT.apply(context.session(), UUID.randomUUID(), VanillaEntities.ENDER_DRAGON);
        spawnContext.geyserId(geyserId);
        spawnContext.javaId(DRAGON_JAVA_ID);
        EnderDragonEntity dragon = new EnderDragonEntity(spawnContext);
        context.session().getEntityCache().spawnEntity(dragon);
        assertTrue(dragon.isValid());
        return dragon;
    }

    private static List<Entity> parts(EntityCache entityCache) {
        List<Entity> parts = new ArrayList<>();
        for (int i = 1; i <= PART_COUNT; i++) {
            parts.add(entityCache.getEntityByJavaId(DRAGON_JAVA_ID + i));
        }
        return parts;
    }

    private static void assertPartsCached(EntityCache entityCache, boolean cached) {
        for (int i = 1; i <= PART_COUNT; i++) {
            Entity part = entityCache.getEntityByJavaId(DRAGON_JAVA_ID + i);
            if (cached) {
                assertInstanceOf(EnderDragonPartEntity.class, part, "part " + i);
            } else {
                assertNull(part, "part " + i);
            }
        }
    }

    private static void drainPackets(GeyserMockContext context) {
        // Spawn/despawn packets are not under test here; mockContextScoreboard asserts none are left over
        while (context.nextPacket() != null) {
            // discard
        }
    }
}
