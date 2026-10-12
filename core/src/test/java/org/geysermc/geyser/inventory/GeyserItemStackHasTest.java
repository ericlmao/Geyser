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

package org.geysermc.geyser.inventory;

import org.geysermc.geyser.item.components.resolvable.ResolvableComponent;
import org.geysermc.geyser.item.type.Item;
import org.geysermc.geyser.scoreboard.network.util.GeyserMockContext;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.ComponentCache;
import org.geysermc.geyser.session.cache.registry.JavaRegistryProvider;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

/**
 * {@link GeyserItemStack#has(DataComponentType)} must agree with {@code getAllComponents().contains(type)}.
 */
class GeyserItemStackHasTest {
    private static final List<DataComponentType<?>> TYPES = List.of(
        DataComponentTypes.MAX_DAMAGE, // base
        DataComponentTypes.MAX_STACK_SIZE, // resolvable base
        DataComponentTypes.DAMAGE, // patch-added
        DataComponentTypes.REPAIR_COST, // base type removed by a patch marker
        DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER, // patch removal marker for a type not in the base
        DataComponentTypes.MAP_ID, // raw null patch value for a type not in the base
        DataComponentTypes.CUSTOM_NAME // absent
    );

    private static final ResolvableComponent<Integer> RESOLVABLE_STACK_SIZE = new ResolvableComponent<>() {
        @Override
        public DataComponentType<Integer> type() {
            return DataComponentTypes.MAX_STACK_SIZE;
        }

        @Override
        public Integer resolve(JavaRegistryProvider registries) {
            return 16;
        }
    };

    @Test
    void hasMatchesGatheredComponents() {
        GeyserMockContext.mockContext(context -> {
            Item item = testItem();

            DataComponents resolved = new DataComponents(new HashMap<>());
            resolved.put(DataComponentTypes.MAX_STACK_SIZE, 16);
            ComponentCache cache = context.mock(ComponentCache.class);
            when(cache.getResolvedComponents(item)).thenReturn(resolved);
            GeyserSession session = context.mock(GeyserSession.class);
            when(session.getComponentCache()).thenReturn(cache);

            DataComponents patch = new DataComponents(new HashMap<>());
            patch.put(DataComponentTypes.DAMAGE, 3);
            // DataComponents#put(type, null) stores a removal marker (the type's null component)
            patch.put(DataComponentTypes.REPAIR_COST, null);
            patch.put(DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER, null);
            // A raw null map value is also a present key
            patch.getDataComponents().put(DataComponentTypes.MAP_ID, null);

            GeyserItemStack withPatch = stack(session, item, patch);
            assertEquivalent(withPatch);
            assertExpected(withPatch, true, true, true, true, true, true, false);

            GeyserItemStack baseOnly = stack(session, item, null);
            assertEquivalent(baseOnly);
            assertExpected(baseOnly, true, true, false, true, false, false, false);
        });
    }

    @Test
    void unresolvedComponentsAreAbsent() {
        GeyserMockContext.mockContext(context -> {
            Item item = testItem();

            // The getter has nothing resolved for this item (e.g. outside a session context)
            ComponentCache cache = context.mock(ComponentCache.class);
            when(cache.getResolvedComponents(item)).thenReturn(null);
            GeyserSession session = context.mock(GeyserSession.class);
            when(session.getComponentCache()).thenReturn(cache);

            GeyserItemStack stack = stack(session, item, null);
            assertEquivalent(stack);
            assertExpected(stack, true, false, false, true, false, false, false);
        });
    }

    @Test
    void emptyStackHasNothing() {
        GeyserMockContext.mockContext(() -> {
            for (DataComponentType<?> type : TYPES) {
                assertFalse(GeyserItemStack.EMPTY.has(type), type::toString);
            }
        });
    }

    private static Item testItem() {
        DataComponents base = new DataComponents(new HashMap<>());
        base.put(DataComponentTypes.MAX_DAMAGE, 100);
        base.put(DataComponentTypes.REPAIR_COST, 0);
        return new Item("geyser_test:has_item", Item.builder()
            .components(base)
            .resolvableComponents(List.of(RESOLVABLE_STACK_SIZE)));
    }

    private static GeyserItemStack stack(GeyserSession session, Item item, DataComponents patch) {
        // javaId 1 is not air; the item is injected directly so no Java item registry lookup is needed
        GeyserItemStack stack = GeyserItemStack.of(session, 1, 1, patch);
        try {
            Field field = GeyserItemStack.class.getDeclaredField("item");
            field.setAccessible(true);
            field.set(stack, item);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return stack;
    }

    private static void assertEquivalent(GeyserItemStack stack) {
        DataComponents all = stack.getAllComponents();
        for (DataComponentType<?> type : TYPES) {
            assertEquals(all.contains(type), stack.has(type), type::toString);
        }
    }

    private static void assertExpected(GeyserItemStack stack, boolean... expected) {
        for (int i = 0; i < TYPES.size(); i++) {
            DataComponentType<?> type = TYPES.get(i);
            assertEquals(expected[i], stack.has(type), type::toString);
        }
    }
}
