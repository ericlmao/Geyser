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

package org.geysermc.geyser.translator.protocol.java;

import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.geysermc.geyser.translator.protocol.java.JavaCommandsTranslator.CommandBuilderContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JavaCommandsEnumCacheTest {

    // CommandBuilderContext only stores the session; enumFor never touches it.
    private static CommandBuilderContext newContext() {
        return new CommandBuilderContext(null);
    }

    @Test
    void staticArraySameNameIsSharedAcrossContexts() {
        CommandEnumData a = newContext().enumFor("bool", JavaCommandsTranslator.ENUM_BOOLEAN);
        CommandEnumData b = newContext().enumFor("bool", JavaCommandsTranslator.ENUM_BOOLEAN);
        assertSame(a, b);
        assertEquals("bool", a.getName());
        assertFalse(a.isSoft());
        assertEquals(List.of("true", "false"), List.copyOf(a.getValues().keySet()));
        assertEquals(Set.of(), a.getValues().get("true"));
    }

    @Test
    void staticArrayDifferentNameIsDistinct() {
        CommandEnumData a = newContext().enumFor("bool", JavaCommandsTranslator.ENUM_BOOLEAN);
        CommandEnumData b = newContext().enumFor("value", JavaCommandsTranslator.ENUM_BOOLEAN);
        assertNotSame(a, b);
        assertEquals("value", b.getName());
        assertNotEquals(a, b);
    }

    @Test
    void cachedValuesAreUnmodifiable() {
        CommandEnumData shared = newContext().enumFor("bool", JavaCommandsTranslator.ENUM_BOOLEAN);
        assertThrows(UnsupportedOperationException.class, () -> shared.getValues().put("maybe", Set.of()));

        CommandEnumData perPacket = newContext().enumFor("levels", new String[] {"minecraft:overworld"});
        assertThrows(UnsupportedOperationException.class, () -> perPacket.getValues().put("x", Set.of()));
    }

    @Test
    void nonStaticArrayIsCachedPerContextOnly() {
        String[] levels = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
        CommandBuilderContext first = newContext();
        CommandBuilderContext second = newContext();

        CommandEnumData a1 = first.enumFor("dimension", levels);
        CommandEnumData a2 = first.enumFor("dimension", levels);
        CommandEnumData b = second.enumFor("dimension", levels);

        assertSame(a1, a2);
        assertNotSame(a1, b);
        // Content (and therefore the serialized packet) is identical
        assertEquals(a1, b);
        assertEquals(List.of(levels), List.copyOf(a1.getValues().keySet()));
    }

    @Test
    void nonStaticArrayWithEqualContentIsKeyedByIdentity() {
        CommandBuilderContext context = newContext();
        CommandEnumData a = context.enumFor("dimension", new String[] {"minecraft:overworld"});
        CommandEnumData b = context.enumFor("dimension", new String[] {"minecraft:overworld"});
        assertNotSame(a, b);
        assertEquals(a, b);
    }
}
