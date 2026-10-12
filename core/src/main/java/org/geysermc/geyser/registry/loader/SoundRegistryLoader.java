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

package org.geysermc.geyser.registry.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.registry.type.SoundMapping;
import org.geysermc.geyser.util.SoundUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads sounds from the given input.
 */
public class SoundRegistryLoader implements RegistryLoader<String, Map<String, SoundMapping>> {
    @Override
    public Map<String, SoundMapping> load(String input) {
        JsonObject soundsJson;
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResourceOrThrow(input);
              InputStreamReader isr = new InputStreamReader(stream)) {
            //noinspection deprecation
            soundsJson = new JsonParser().parse(isr).getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError("Unable to load sound mappings", e);
        }

        Map<String, SoundMapping> soundMappings = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : soundsJson.entrySet()) {
            JsonObject brMap = entry.getValue().getAsJsonObject();
            String javaSound = entry.getKey();
            SoundMapping mapping = new SoundMapping(
                    javaSound,
                    brMap.has("bedrock_mapping") ? brMap.get("bedrock_mapping").getAsString() : null,
                    brMap.has("playsound_mapping") ? brMap.get("playsound_mapping").getAsString() : null,
                    brMap.has("extra_data") ? brMap.get("extra_data").getAsInt() : -1,
                    brMap.has("identifier") ? brMap.get("identifier").getAsString() : null,
                    brMap.has("level_event") && brMap.get("level_event").getAsBoolean(),
                    brMap.has("pitch_adjust") ? brMap.get("pitch_adjust").getAsFloat() : 1.0f
            );

            // Resolve the Bedrock events once here instead of per played sound.
            // Same fallback order as SoundUtils#playSound used: the Bedrock mapping first, then the Java sound name (the map key)
            SoundEvent soundEvent = null;
            if (mapping.bedrock() != null) {
                soundEvent = SoundUtils.toSoundEvent(mapping.bedrock());
            }
            if (soundEvent == null) {
                soundEvent = SoundUtils.toSoundEvent(javaSound);
            }

            LevelEvent levelEventType = null;
            if (mapping.levelEvent()) {
                levelEventType = mapping.bedrock() == null ? null : SoundUtils.toLevelEvent(mapping.bedrock());
                if (levelEventType == null) {
                    GeyserImpl.getInstance().getLogger().warning("Sound mapping " + javaSound + " is marked as a level event but '"
                        + mapping.bedrock() + "' is not a valid LevelEvent");
                }
            }

            soundMappings.put(javaSound, mapping.withResolvedEvents(soundEvent, levelEventType));
        }
        return soundMappings;
    }
}
