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

package org.geysermc.geyser.registry.type;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;

/**
 * @param soundEvent the resolved Bedrock level sound event, or null if neither {@code bedrock} nor {@code java} maps to one
 * @param levelEventType the resolved Bedrock level event if {@code levelEvent} is set, or null if it is unset or {@code bedrock} is not a valid level event
 */
public record SoundMapping(String java, String bedrock, String playsound, int extraData, String identifier,
                           boolean levelEvent, float pitchAdjust, @Nullable SoundEvent soundEvent, @Nullable LevelEvent levelEventType) {
    public SoundMapping(String java, String bedrock, String playsound, int extraData, String identifier, boolean levelEvent, float pitchAdjust,
                        @Nullable SoundEvent soundEvent, @Nullable LevelEvent levelEventType) {
        this.java = java;
        this.bedrock = bedrock == null || bedrock.isEmpty() ? null : bedrock;
        this.playsound = playsound == null || playsound.isEmpty() ? null : playsound;
        this.extraData = extraData;
        this.identifier = identifier == null || identifier.isEmpty() ? ":" : identifier;
        this.levelEvent = levelEvent;
        this.pitchAdjust = pitchAdjust;
        this.soundEvent = soundEvent;
        this.levelEventType = levelEventType;
    }

    /**
     * Creates a mapping without resolved Bedrock events.
     */
    public SoundMapping(String java, String bedrock, String playsound, int extraData, String identifier, boolean levelEvent, float pitchAdjust) {
        this(java, bedrock, playsound, extraData, identifier, levelEvent, pitchAdjust, null, null);
    }

    /**
     * @return a copy of this mapping with the given resolved Bedrock events
     */
    public SoundMapping withResolvedEvents(@Nullable SoundEvent soundEvent, @Nullable LevelEvent levelEventType) {
        return new SoundMapping(java, bedrock, playsound, extraData, identifier, levelEvent, pitchAdjust, soundEvent, levelEventType);
    }
}
