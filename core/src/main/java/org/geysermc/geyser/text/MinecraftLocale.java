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

package org.geysermc.geyser.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.text.MessageTranslator;
import org.geysermc.geyser.util.AssetUtils;
import org.geysermc.geyser.util.FileUtils;
import org.geysermc.geyser.util.JsonUtils;
import org.geysermc.geyser.util.WebUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class MinecraftLocale {

    public static final Map<String, Map<String, String>> LOCALE_MAPPINGS = new ConcurrentHashMap<>();

    private static final Set<String> REMOVED_KEYS = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> REPLACED_KEYS = new ConcurrentHashMap<>();
    // Translation keys are the same in every lang file, so loaded locales share one instance of each
    private static final Map<String, String> KEY_INTERNER = new ConcurrentHashMap<>();

    private static final Map<String, CompletableFuture<Void>> PENDING_LOCALES = new ConcurrentHashMap<>();
    // Last time a loaded locale was requested or seen in use by a session; drives idle eviction
    private static final Map<String, Long> LAST_USED = new ConcurrentHashMap<>();
    private static final long LOCALE_IDLE_MILLIS = TimeUnit.MINUTES.toMillis(Long.getLong("Geyser.LocaleIdleMinutes", 30));
    private static final Object LOADER_LOCK = new Object();
    private static ThreadPoolExecutor loader;

    // Check instance availability to avoid exception during testing
    private static final boolean IN_INSTANCE = GeyserImpl.getInstance() != null;

    private static final Path LOCALE_FOLDER = (IN_INSTANCE) ? GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("locales") : null;
    private static final Path DEPRECATED = LOCALE_FOLDER == null ? null : getPath("deprecated");

    static {
        if (IN_INSTANCE) {
            try {
                // Create the locales folder
                Files.createDirectories(LOCALE_FOLDER);
                Files.createDirectories(LOCALE_FOLDER.resolve("overrides"));
            } catch (IOException exception) {
                throw new RuntimeException("Unable to create locale folders! " + exception.getMessage());
            }
        }
    }

    public static void ensureEN_US() {
        // en_us requires special treatment since it's included in Minecraft's JAR file
        Path localeFile = getPath("en_us");
        AssetUtils.addTask(!Files.exists(localeFile), new AssetUtils.ClientJarTask("assets/minecraft/lang/en_us.json",
                (stream) -> AssetUtils.saveFile(localeFile, stream),
                () -> loadLocale("en_us")));
    }

    public static void downloadDeprecations() {
        if (!loadDeprecations()) {
            AssetUtils.addTask(!Files.exists(DEPRECATED), new AssetUtils.ClientJarTask("assets/minecraft/lang/deprecated.json",
                stream -> AssetUtils.saveFile(DEPRECATED, stream),
                () -> {
                    if (!loadDeprecations()) {
                        GeyserImpl.getInstance().getLogger().warning("Failed to load deprecated locale file: it doesn't exist?");
                    }
                }));
        }
    }

    /**
     * Downloads and loads a locale on the locale loader thread, so the caller (usually an event loop) never waits
     * on the download or the parse. Concurrent requests for the same locale share one load.
     *
     * @param locale Locale to download and load
     * @return a future that completes once the locale is loaded, or failed to load
     */
    public static CompletableFuture<Void> downloadAndLoadLocaleAsync(String locale) {
        String key = locale.toLowerCase(Locale.ROOT);
        if (!GeyserLocale.isValidLocale(key)) {
            // Client-supplied; never let it reach a file path or the tracking maps
            return CompletableFuture.completedFuture(null);
        }
        LAST_USED.put(normalizeLocale(key), System.currentTimeMillis());
        if (isLocaleLoaded(key)) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        CompletableFuture<Void> pending = PENDING_LOCALES.putIfAbsent(key, future);
        if (pending != null) {
            return pending;
        }

        try {
            loader().execute(() -> {
                try {
                    downloadAndLoadLocale(key);
                    PENDING_LOCALES.remove(key, future);
                    future.complete(null);
                } catch (Throwable t) {
                    PENDING_LOCALES.remove(key, future);
                    GeyserImpl.getInstance().getLogger().error("Failed to load locale " + key, t);
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            PENDING_LOCALES.remove(key, future);
            future.completeExceptionally(e);
        }
        return future;
    }

    private static ThreadPoolExecutor loader() {
        synchronized (LOADER_LOCK) {
            if (loader == null || loader.isShutdown()) {
                // One thread serialises the downloads and file writes; it exits when idle
                loader = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                    new DefaultThreadFactory("Geyser Locale Loader", true));
                loader.allowCoreThreadTimeOut(true);
            }
            return loader;
        }
    }

    /**
     * Stops the locale loader thread. Loads still queued are dropped.
     */
    public static void shutdown() {
        synchronized (LOADER_LOCK) {
            if (loader != null) {
                loader.shutdownNow();
                loader = null;
            }
        }
        // Dropped loads would otherwise block later requests for the same locale
        PENDING_LOCALES.values().forEach(future -> future.cancel(false));
        PENDING_LOCALES.clear();
    }

    /**
     * Downloads a locale from Mojang if it's not already loaded. This blocks on the download and parse; prefer
     * {@link #downloadAndLoadLocaleAsync(String)} on network threads.
     *
     * @param locale Locale to download and load
     */
    public static void downloadAndLoadLocale(String locale) {
        locale = locale.toLowerCase(Locale.ROOT);
        if (!GeyserLocale.isValidLocale(locale)) {
            GeyserImpl.getInstance().getLogger().debug("Ignoring malformed locale: " + locale);
            return;
        }
        LAST_USED.put(normalizeLocale(locale), System.currentTimeMillis());

        if (isLocaleLoaded(locale)) {
            GeyserImpl.getInstance().getLogger().debug("Locale already loaded: " + locale);
            return;
        }

        if (locale.equals("nb_no")) {
            // Different locale code - https://minecraft.wiki/w/Language
            locale = "no_no";
        }

        // Check the locale isn't already loaded
        if (!AssetUtils.isAssetKnown("minecraft/lang/" + locale + ".json") && !locale.equals("en_us")) {
            if (loadLocale(locale)) {
                GeyserImpl.getInstance().getLogger().debug("Loaded locale locally while not being in asset map: " + locale);
            } else {
                GeyserImpl.getInstance().getLogger().warning(GeyserLocale.getLocaleStringLog("geyser.locale.fail.invalid", locale));
            }
            return;
        }

        GeyserImpl.getInstance().getLogger().debug("Downloading and loading locale: " + locale);

        downloadLocale(locale);
        if (!loadLocale(locale)) {
            GeyserImpl.getInstance().getLogger().warning(GeyserLocale.getLocaleStringLog("geyser.locale.fail.missing", locale));
        }
    }

    /**
     * Downloads the specified locale if it's not already downloaded
     *
     * @param locale Locale to download
     */
    private static void downloadLocale(String locale) {
        // Don't download en_us, it's included in the JAR file
        if (locale.equals("en_us")) {
            return;
        }
        Path localeFile = getPath(locale);

        // Check if we have already downloaded the locale file
        if (Files.exists(localeFile)) {
            String curHash = byteArrayToHexString(FileUtils.calculateSHA1(localeFile));
            String targetHash = AssetUtils.getAsset("minecraft/lang/" + locale + ".json").getHash();

            if (!curHash.equals(targetHash)) {
                GeyserImpl.getInstance().getLogger().debug("Locale out of date; re-downloading: " + locale);
            } else {
                GeyserImpl.getInstance().getLogger().debug("Locale already downloaded and up-to date: " + locale);
                return;
            }
        }

        try {
            // Get the hash and download the locale
            String hash = AssetUtils.getAsset("minecraft/lang/" + locale + ".json").getHash();
            WebUtils.downloadFile("https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash, localeFile.toString());
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("Unable to download locale file hash", e);
        }
    }

    private static Path getPath(String locale) {
        return LOCALE_FOLDER.resolve(locale + ".json");
    }

    /**
     * Loads a locale already downloaded, if the file doesn't exist it just logs a warning
     *
     * @param locale Bedrock locale to load
     */
    private static boolean loadLocale(String locale) {
        String lowercaseLocale = locale.toLowerCase(Locale.ROOT);

        // Need to grab this before we change the locale - downloaded/override locales are stored under the Java locale name
        Path localeFile = getPath(lowercaseLocale);
        Path localeOverride = getPath("overrides/" + lowercaseLocale);

        if (lowercaseLocale.equals("no_no")) {
            // Store this locale under the Bedrock locale, so we don't need to do this check over and over
            lowercaseLocale = "nb_no";
        }

        // Sized for a full lang file (~8-9k keys) so it isn't rehashed while loading
        Map<String, String> langMap = new HashMap<>(12_000);
        if (Files.exists(localeFile) && Files.isReadable(localeFile)) {
            parseLangFile(localeFile, lowercaseLocale, langMap);
        }

        // Load the locale overwrites
        if (Files.exists(localeOverride) && Files.isReadable(localeOverride)) {
            parseLangFile(localeOverride, lowercaseLocale, langMap);
        }

        if (!langMap.isEmpty()) {
            LOCALE_MAPPINGS.put(lowercaseLocale, langMap);
            LAST_USED.putIfAbsent(lowercaseLocale, System.currentTimeMillis());
            return true;
        } else {
            return false;
        }
    }

    private static boolean loadDeprecations() {
        if (Files.exists(DEPRECATED) && Files.isReadable(DEPRECATED)) {
            try (InputStream localeStream = Files.newInputStream(DEPRECATED, StandardOpenOption.READ)) {
                // Parse the file as json
                JsonObject localeObj = JsonUtils.fromJson(localeStream);
                JsonElement removed = localeObj.get("removed");
                if (removed.isJsonArray()) {
                    for (JsonElement removedElement : removed.getAsJsonArray()) {
                        REMOVED_KEYS.add(removedElement.getAsString());
                    }
                }

                JsonElement renamed = localeObj.get("renamed");
                if (renamed.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> renamedElement : renamed.getAsJsonObject().entrySet()) {
                        REPLACED_KEYS.put(renamedElement.getKey(), renamedElement.getValue().getAsString());
                    }
                }

                return true;
            } catch (FileNotFoundException e){
                throw new AssertionError(GeyserLocale.getLocaleStringLog("geyser.locale.fail.file", DEPRECATED, e.getMessage()));
            } catch (Exception e) {
                throw new AssertionError(GeyserLocale.getLocaleStringLog("geyser.locale.fail.json", DEPRECATED), e);
            }
        }
        return false;
    }

    /**
     * Load and parse a json lang file.
     *
     * @param localeFile Path of locale file
     * @param locale Locale to load
     * @return a Map of the loaded translations
     */
    public static Map<String, String> parseLangFile(Path localeFile, String locale) {
        Map<String, String> langMap = new HashMap<>();
        parseLangFile(localeFile, locale, langMap);
        return langMap;
    }

    /**
     * Load and parse a json lang file into the given map.
     *
     * @param localeFile Path of locale file
     * @param locale Locale to load
     * @param langMap Map the loaded translations are put in
     */
    public static void parseLangFile(Path localeFile, String locale, Map<String, String> langMap) {
        // Read the localefile
        try (InputStream localeStream = Files.newInputStream(localeFile, StandardOpenOption.READ)) {
            // Parse the file as json
            JsonObject localeObj = JsonUtils.fromJson(localeStream);

            // Parse all the locale fields
            for (Map.Entry<String, JsonElement> entry : localeObj.entrySet()) {
                if (REMOVED_KEYS.contains(entry.getKey())) {
                    continue;
                }
                String key = KEY_INTERNER.computeIfAbsent(REPLACED_KEYS.getOrDefault(entry.getKey(), entry.getKey()), Function.identity());
                langMap.put(key, entry.getValue().getAsString());
            }
        } catch (FileNotFoundException e){
            throw new AssertionError(GeyserLocale.getLocaleStringLog("geyser.locale.fail.file", locale, e.getMessage()));
        } catch (Exception e) {
            throw new AssertionError(GeyserLocale.getLocaleStringLog("geyser.locale.fail.json", locale), e);
        }
    }

    /**
     * Translate the given Java language string into the given locale, or falls back to American English.
     *
     * @param messageText Language string to translate
     * @param locale Locale to translate to
     * @return Translated string or the original message if it was not found in the given locale
     */
    public static String getLocaleString(String messageText, String locale) {
        String translated = getLocaleStringIfPresent(messageText, locale);
        if (translated == null) {
            // Don't cause a NPE if the string is missing
            GeyserImpl.getInstance().getLogger().debug("MISSING JAVA TRANSLATION STRING: " + messageText);
            return messageText;
        }
        return translated;
    }

    /**
     * Translate the given Java language string into the given locale, falls back to American English, or returns null.
     *
     * @param messageText Language string to translate
     * @param locale Locale to translate to
     * @return Translated string or null if it was not found in the given locale
     */
    public static @Nullable String getLocaleStringIfPresent(String messageText, String locale) {
        Map<String, String> localeStrings = LOCALE_MAPPINGS.get(locale.toLowerCase(Locale.ROOT));
        if (localeStrings != null) {
            String translated = localeStrings.get(messageText);
            if (translated != null) {
                return translated;
            }
        }
        localeStrings = LOCALE_MAPPINGS.get("en_us");
        if (localeStrings != null) {
            return localeStrings.get(messageText);
        }
        return null;
    }

    /**
     * Checks if a locale has been loaded.
     *
     * @param locale Locale to check
     * @return true if the locale has been loaded
     */
    public static boolean isLocaleLoaded(String locale) {
        return LOCALE_MAPPINGS.containsKey(locale.toLowerCase(Locale.ROOT));
    }

    /**
     * Unloads Java locales no session has used for a while, so the loaded set stays proportional to the
     * locales of recent players. en_us and the default locale are never unloaded. A player joining with an
     * unloaded locale loads it again from disk.
     */
    public static void evictIdleLocales() {
        GeyserImpl geyser = GeyserImpl.getInstance();
        if (!IN_INSTANCE || geyser == null) {
            return;
        }
        try {
            Set<String> active = new HashSet<>();
            for (GeyserSession session : geyser.getSessionManager().getAllSessions()) {
                active.add(normalizeLocale(session.locale().toLowerCase(Locale.ROOT)));
            }
            Set<String> pinned = new HashSet<>();
            pinned.add("en_us");
            pinned.add(normalizeLocale(GeyserLocale.getDefaultLocale().toLowerCase(Locale.ROOT)));
            String configLocale = geyser.config().defaultLocale();
            if (configLocale != null) {
                pinned.add(normalizeLocale(configLocale.toLowerCase(Locale.ROOT)));
            }

            long now = System.currentTimeMillis();
            for (String locale : LOCALE_MAPPINGS.keySet()) {
                if (active.contains(locale)) {
                    LAST_USED.put(locale, now);
                    continue;
                }
                Long lastUsed = LAST_USED.putIfAbsent(locale, now);
                // The conditional remove fails if a player asked for the locale since lastUsed was read
                if (shouldEvict(locale, active, pinned, lastUsed, now, LOCALE_IDLE_MILLIS) && LAST_USED.remove(locale, lastUsed)) {
                    LOCALE_MAPPINGS.remove(locale);
                    MessageTranslator.invalidateTranslations(locale);
                    geyser.getLogger().debug("Unloaded idle locale: " + locale);
                    if (LAST_USED.containsKey(locale)) {
                        // A player asked for it while it was being removed and saw it as loaded
                        downloadAndLoadLocaleAsync(locale);
                    }
                }
            }
            // Requests for locales that never loaded (unknown codes, failed downloads) would otherwise pile up
            LAST_USED.entrySet().removeIf(entry -> !LOCALE_MAPPINGS.containsKey(entry.getKey())
                && !PENDING_LOCALES.containsKey(entry.getKey()) && now - entry.getValue() > LOCALE_IDLE_MILLIS);
            MessageTranslator.pruneTranslations();
        } catch (Throwable t) {
            // Don't let an exception cancel the scheduled task
            geyser.getLogger().error("Failed to unload idle locales", t);
        }
    }

    /**
     * Decides whether a loaded locale can be unloaded.
     *
     * @param locale loaded locale, lower case
     * @param active locales of connected sessions, lower case
     * @param pinned locales that are never unloaded, lower case
     * @param lastUsed last time the locale was used, or null if unknown
     * @param now current time in milliseconds
     * @param idleMillis how long a locale must be unused before it is unloaded
     */
    static boolean shouldEvict(String locale, Set<String> active, Set<String> pinned, @Nullable Long lastUsed, long now, long idleMillis) {
        if (pinned.contains(locale) || active.contains(locale)) {
            return false;
        }
        // Unknown means it was loaded outside downloadAndLoadLocale; start its idle clock instead of unloading it now
        return lastUsed != null && now - lastUsed > idleMillis;
    }

    /**
     * Maps a lower case locale to the key it is stored under in {@link #LOCALE_MAPPINGS}.
     */
    static String normalizeLocale(String locale) {
        // Norwegian is downloaded as no_no but stored under the Bedrock code, see loadLocale
        return locale.equals("no_no") ? "nb_no" : locale;
    }

    /**
     * Convert a byte array into a hex string
     *
     * @param b Byte array to convert
     * @return The hex representation of the given byte array
     */
    private static String byteArrayToHexString(byte[] b) {
        StringBuilder result = new StringBuilder();
        for (byte value : b) {
            result.append(Integer.toString((value & 0xff) + 0x100, 16).substring(1));
        }
        return result.toString();
    }
}
