/*
 * Copyright (c) 2025 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.event.bedrock.SessionAcceptCodeOfConductEvent;
import org.geysermc.geyser.api.event.java.ServerCodeOfConductEvent;
import org.geysermc.geyser.session.GeyserSession;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class CodeOfConductManager {
    private static final Path SAVE_PATH = Path.of("cache/codeofconducts.json");
    private static volatile CodeOfConductManager loaded = null;

    private final Map<String, Integer> playerAcceptedCodeOfConducts = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;
    private ScheduledFuture<?> saveTask;

    private CodeOfConductManager() {
        Path savePath = getSavePath();
        if (Files.exists(savePath) && Files.isRegularFile(savePath)) {
            GeyserImpl.getInstance().getLogger().debug("Loading codeofconducts.json");

            try (Reader reader = new FileReader(savePath.toFile())) {
                //noinspection deprecation - otherwise 1.16.5 doesn't work
                JsonObject object = new JsonParser().parse(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    playerAcceptedCodeOfConducts.put(entry.getKey(), entry.getValue().getAsInt());
                }
            } catch (IOException exception) {
                GeyserImpl.getInstance().getLogger().error("Failed to read code of conduct cache!", exception);
            }
        } else {
            GeyserImpl.getInstance().getLogger().debug("codeofconducts.json not found, not loading");
        }
    }

    /**
     * Schedules the periodic save on the current scheduled thread. The previous scheduled thread (if any) is shut down
     * on disable, which cancels its periodic task, so this must run on every enable, including reloads.
     */
    private synchronized void scheduleSaves() {
        if (saveTask != null) {
            saveTask.cancel(false);
        }
        saveTask = GeyserImpl.getInstance().getScheduledThread().scheduleAtFixedRate(this::save, 5L, 5L, TimeUnit.MINUTES);
    }

    public boolean hasAcceptedCodeOfConduct(GeyserSession session, String codeOfConduct) {
        ServerCodeOfConductEvent event = new ServerCodeOfConductEvent(session, codeOfConduct);
        session.getGeyser().getEventBus().fire(event);
        return event.accepted() || playerAcceptedCodeOfConducts.getOrDefault(session.xuid(), 0) == codeOfConduct.hashCode();
    }

    public void saveCodeOfConductAccepted(GeyserSession session, String codeOfConduct) {
        SessionAcceptCodeOfConductEvent event = new SessionAcceptCodeOfConductEvent(session, codeOfConduct);
        session.getGeyser().getEventBus().fire(event);
        if (!event.shouldSkipSaving()) {
            playerAcceptedCodeOfConducts.put(session.xuid(), codeOfConduct.hashCode());
            dirty = true;
        }
    }

    // Fixes https://github.com/GeyserMC/Geyser/issues/6139
    public static void trySave() {
        if (loaded == null) {
            return;
        }
        getInstance().save();
    }

    public synchronized void save() {
        if (dirty) {
            GeyserImpl.getInstance().getLogger().debug("Saving codeofconducts.json");

            // Cleared before the snapshot so an acceptance that lands mid-save marks the map dirty again
            dirty = false;
            JsonObject saved = new JsonObject();
            playerAcceptedCodeOfConducts.forEach(saved::addProperty);
            try {
                Files.writeString(getSavePath(), saved.toString());
            } catch (IOException exception) {
                dirty = true;
                GeyserImpl.getInstance().getLogger().error("Failed to write code of conduct cache!", exception);
            }
        }
    }

    private static Path getSavePath() {
        return GeyserImpl.getInstance().configDirectory().resolve(SAVE_PATH);
    }

    /**
     * Called on every enable (initial start and reload): loads the cache once, then (re)schedules the periodic save.
     */
    public static synchronized void load() {
        if (loaded == null) {
            loaded = new CodeOfConductManager();
        }
        loaded.scheduleSaves();
    }

    public static CodeOfConductManager getInstance() {
        if (loaded == null) {
            throw new IllegalStateException("CodeOfConductManager was accessed before loaded");
        }
        return loaded;
    }
}
