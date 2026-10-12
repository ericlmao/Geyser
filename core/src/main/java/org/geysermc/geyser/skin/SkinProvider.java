/*
 * Copyright (c) 2019-2024 GeyserMC. http://geysermc.org
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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.bytes.ByteArrays;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.skin.ImageData;
import org.cloudburstmc.protocol.bedrock.data.skin.SerializedSkin;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.api.event.bedrock.SessionSkinApplyEvent;
import org.geysermc.geyser.api.network.AuthType;
import org.geysermc.geyser.api.skin.Cape;
import org.geysermc.geyser.api.skin.Skin;
import org.geysermc.geyser.api.skin.SkinData;
import org.geysermc.geyser.api.skin.SkinGeometry;
import org.geysermc.geyser.entity.type.player.AvatarEntity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.text.GeyserLocale;
import org.geysermc.geyser.util.FileUtils;
import org.geysermc.geyser.util.WebUtils;
import org.geysermc.mcprotocollib.auth.util.TextureUrlChecker;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class SkinProvider {
    /**
     * Only used when there is no Geyser instance to take the blocking executor from (tests).
     */
    private static ExecutorService FALLBACK_EXECUTOR;

    static final Skin EMPTY_SKIN;
    /**
     * Returned for any skin that could not be downloaded. Never stored in {@link #CACHED_JAVA_SKINS}.
     */
    static final Skin FAILED_SKIN;
    static final BufferedImage EMPTY_SKIN_IMAGE;
    static final Cape EMPTY_CAPE = new Cape("", "no-cape", ByteArrays.EMPTY_ARRAY, true);

    /*
     * The texture caches below are JVM-global (they survive reloads) and bounded by bytes, so a burst of unique skins
     * cannot grow them without limit: about 96 MiB in total at most. A Bedrock player's own skin, cape and geometry
     * stay cached while they are online, as determineFallbackSkinData touches them on every lookup.
     */
    private static final Cache<String, Cape> CACHED_JAVA_CAPES = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumWeight(8L * 1024 * 1024)
            .weigher((String url, Cape cape) -> url.length() + cape.capeData().length + 64)
            .build();
    private static final Cache<String, Skin> CACHED_JAVA_SKINS = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumWeight(32L * 1024 * 1024)
            .weigher((String url, Skin skin) -> url.length() + skin.skinData().length + 64)
            .build();

    private static final Cache<String, Cape> CACHED_BEDROCK_CAPES = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumWeight(8L * 1024 * 1024)
            .weigher((String capeId, Cape cape) -> capeId.length() + cape.capeData().length + 64)
            .build();
    private static final Cache<String, Skin> CACHED_BEDROCK_SKINS = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumWeight(32L * 1024 * 1024)
            .weigher((String skinId, Skin skin) -> skinId.length() + skin.skinData().length + 64)
            .build();

    /*
     * URLs that recently failed to download. Kept apart from the caches above so a transient failure is retried once
     * the entry expires, instead of pinning the fallback skin to that player.
     */
    private static final Cache<String, Boolean> FAILED_SKIN_URLS = CacheBuilder.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .maximumSize(4096)
            .build();
    private static final Cache<String, Boolean> FAILED_CAPE_URLS = CacheBuilder.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .maximumSize(4096)
            .build();

    // In-flight downloads; only ever written through requestInFlight
    static final Map<String, CompletableFuture<Cape>> requestedCapes = new ConcurrentHashMap<>();
    static final Map<String, CompletableFuture<Skin>> requestedSkins = new ConcurrentHashMap<>();

    private static final Cache<UUID, SkinGeometry> cachedGeometry = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumWeight(16L * 1024 * 1024)
            .weigher((UUID uuid, SkinGeometry geometry) -> geometry.geometryName().length() + geometry.geometryData().length() + 64)
            .build();

    /**
     * Citizens NPCs use UUID version 2, while legitimate Minecraft players use version 4, and
     * offline mode players use version 3.
     */
    private static final Predicate<UUID> IS_NPC = uuid -> uuid.version() == 2;

    static final SkinGeometry SKULL_GEOMETRY;
    static final SkinGeometry WEARING_CUSTOM_SKULL;
    static final SkinGeometry WEARING_CUSTOM_SKULL_SLIM;
    public static final SerializedSkin EMPTY_SERIALIZED_SKIN;

    private static final byte[] SKIN_MASK = Base64.getDecoder().decode(
        "AP//AAAAAAAA//8AAAAAAAD//wAAAAAAAP//AAAAAAAA//8AAAAAAAD//wAAAAAAAP//AAAAAAAA//8AAAAAAP////8AAAAA/////wAAAAD/////AAAAAP////8AAAAA/////wAAAAD/////AAAAAP////8AAAAA/////wAAAADwD/D/D/8AAPAP8P8P/wAA8A/w/w//AADwD/D/D/8AAP///////w8A////////DwD///////8PAP///////w8A////////DwD///////8PAP///////w8A////////DwD///////8PAP///////w8A////////DwD///////8PAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADwD/APAAAAAPAP8A8AAAAA8A/wDwAAAADwD/APAAAAAP////8AAAAA/////wAAAAD/////AAAAAP////8AAAAA/////wAAAAD/////AAAAAP////8AAAAA/////wAAAAD/////AAAAAP////8AAAAA/////wAAAAD/////AAA="
    );
    private static final byte[] SKIN_MASK_LEGACY = Base64.getDecoder().decode(
        "AP//AAAAAAAA//8AAAAAAAD//wAAAAAAAP//AAAAAAAA//8AAAAAAAD//wAAAAAAAP//AAAAAAAA//8AAAAAAP////8AAAAA/////wAAAAD/////AAAAAP////8AAAAA/////wAAAAD/////AAAAAP////8AAAAA/////wAAAADwD/D/D/APAPAP8P8P8A8A8A/w/w/wDwDwD/D/D/APAP////////8A/////////wD/////////AP////////8A/////////wD/////////AP////////8A/////////wD/////////AP////////8A/////////wD/////////AA=="
    );

    static {
        // Skins and capes are tiny; decode them in memory rather than through a temporary file per image
        ImageIO.setUseCache(false);

        // Generate the empty texture to use as an emergency fallback
        final int pink = 0xFFF800F8;
        final int black = 0xFF000000;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(64 * 64 * 4);
        EMPTY_SKIN_IMAGE = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int rgba;
                if (y > 32) {
                    rgba = x >= 32 ? pink : black;
                } else {
                    rgba = x >= 32 ? black : pink;
                }
                
                outputStream.write((rgba >> 16) & 0xFF); // Red
                outputStream.write((rgba >> 8) & 0xFF); // Green
                outputStream.write(rgba & 0xFF); // Blue
                outputStream.write((rgba >> 24) & 0xFF); // Alpha
                
                EMPTY_SKIN_IMAGE.setRGB(x, y, rgba);
            }
        }
        EMPTY_SKIN = new Skin("geysermc:empty", outputStream.toByteArray(), true);
        FAILED_SKIN = new Skin("empty", EMPTY_SKIN.skinData(), true);

        /* Load in the custom skull geometry */
        String skullData = new String(FileUtils.readAllBytes("bedrock/skin/geometry.humanoid.customskull.json"), StandardCharsets.UTF_8);
        SKULL_GEOMETRY = new SkinGeometry("{\"geometry\" :{\"default\" :\"geometry.humanoid.customskull\"}}", skullData);

        /* Load in the player head skull geometry */
        String wearingCustomSkull = new String(FileUtils.readAllBytes("bedrock/skin/geometry.humanoid.wearingCustomSkull.json"), StandardCharsets.UTF_8);
        WEARING_CUSTOM_SKULL = new SkinGeometry("{\"geometry\" :{\"default\" :\"geometry.humanoid.wearingCustomSkull\"}}", wearingCustomSkull);
        String wearingCustomSkullSlim = new String(FileUtils.readAllBytes("bedrock/skin/geometry.humanoid.wearingCustomSkullSlim.json"), StandardCharsets.UTF_8);
        WEARING_CUSTOM_SKULL_SLIM = new SkinGeometry("{\"geometry\" :{\"default\" :\"geometry.humanoid.wearingCustomSkullSlim\"}}", wearingCustomSkullSlim);

        /* Used for non-player waypoints... Bedrock requires a skin being sent. Lovely. */
        EMPTY_SERIALIZED_SKIN = SerializedSkin.builder()
            .fullSkinId("emptyFullSkinId")
            .skinId("skinId")
            .skinData(ImageData.of(EMPTY_SKIN.skinData()))
            .capeData(ImageData.EMPTY)
            .geometryName(SkinGeometry.SLIM.geometryName())
            .geometryData(SkinGeometry.SLIM.geometryData())
            .premium(true)
            .color(new Color(0, true))
            .trusted(true)
            .profileHash("")
            .build();
    }

    /**
     * @return Geyser's shared blocking executor, which {@link GeyserImpl} owns and shuts down
     */
    public static ExecutorService getExecutorService() {
        GeyserImpl geyser = GeyserImpl.getInstance();
        ExecutorService executor = geyser == null ? null : geyser.blockingExecutor();
        return executor != null ? executor : fallbackExecutor();
    }

    private static synchronized ExecutorService fallbackExecutor() {
        if (FALLBACK_EXECUTOR == null) {
            FALLBACK_EXECUTOR = GeyserImpl.newBlockingExecutor();
        }
        return FALLBACK_EXECUTOR;
    }

    /**
     * Shuts down only the fallback executor, if one was ever created; the shared executor is left to {@link GeyserImpl}.
     */
    public static synchronized void shutdown() {
        if (FALLBACK_EXECUTOR != null) {
            FALLBACK_EXECUTOR.shutdown();
            FALLBACK_EXECUTOR = null;
        }
    }

    public static void registerCacheImageTask(GeyserImpl geyser) {
        // Schedule Daily Image Expiry if we are caching them
        if (geyser.config().advanced().cacheImages() > 0) {
            geyser.getScheduledThread().scheduleAtFixedRate(() -> {
                // Anything escaping a scheduleAtFixedRate task silently cancels all future runs, so nothing may escape
                try {
                    Path cacheFolder = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("cache").resolve("images");
                    if (!Files.isDirectory(cacheFolder)) {
                        return;
                    }

                    int count = 0;
                    final long expireTime = ((long) GeyserImpl.getInstance().config().advanced().cacheImages()) * ((long)1000 * 60 * 60 * 24);
                    final long cutoff = System.currentTimeMillis() - expireTime;
                    // Stream the listing rather than materialising every entry of a large directory at once
                    try (Stream<Path> files = Files.list(cacheFolder)) {
                        Iterator<Path> iterator = files.iterator();
                        while (iterator.hasNext()) {
                            File imageFile = iterator.next().toFile();
                            if (imageFile.lastModified() < cutoff) {
                                //noinspection ResultOfMethodCallIgnored
                                imageFile.delete();
                                count++;
                            }
                        }
                    }

                    if (count > 0) {
                        GeyserImpl.getInstance().getLogger().debug(String.format("Removed %d cached image files as they have expired", count));
                    }
                } catch (Throwable t) {
                    GeyserImpl.getInstance().getLogger().error("Failed to clean image cache", t);
                }
            }, 10, 1, TimeUnit.DAYS);
        }
    }

    /**
     * Search our cached database for an already existing, translated skin of this Java URL.
     */
    static Skin getCachedSkin(String skinUrl) {
        return CACHED_JAVA_SKINS.getIfPresent(skinUrl);
    }

    /**
     * If skin data fails to apply, or there is no skin data to apply, determine what skin we should give as a fallback.
     */
    public static SkinData determineFallbackSkinData(UUID uuid) {
        Skin skin = null;
        Cape cape = null;
        SkinGeometry geometry = SkinGeometry.WIDE;

        if (GeyserImpl.getInstance().config().java().authType() != AuthType.ONLINE) {
            // Let's see if this player is a Bedrock player, and if so, let's pull their skin.
            GeyserSession session = GeyserImpl.getInstance().connectionByUuid(uuid);
            if (session != null) {
                String skinId = session.getClientData().getSkinId();
                skin = CACHED_BEDROCK_SKINS.getIfPresent(skinId);
                String capeId = session.getClientData().getCapeId();
                cape = CACHED_BEDROCK_CAPES.getIfPresent(capeId);
                geometry = Objects.requireNonNullElse(cachedGeometry.getIfPresent(uuid), geometry);
            }
        }

        if (skin == null) {
            // We don't have a skin for the player right now. Fall back to a default.
            ProvidedSkins.ProvidedSkin providedSkin = ProvidedSkins.getDefaultPlayerSkin(uuid);
            skin = providedSkin.getData();
            geometry = providedSkin.isSlim() ? SkinGeometry.SLIM : SkinGeometry.WIDE;
        }

        if (cape == null) {
            cape = EMPTY_CAPE;
        }

        return new SkinData(skin, cape, geometry);
    }

    /**
     * Used as a fallback if an official Java cape doesn't exist for this user.
     */
    @NonNull
    private static Cape getCachedBedrockCape(UUID uuid) {
        GeyserSession session = GeyserImpl.getInstance().connectionByUuid(uuid);
        if (session != null) {
            String capeId = session.getClientData().getCapeId();
            Cape bedrockCape = CACHED_BEDROCK_CAPES.getIfPresent(capeId);
            if (bedrockCape != null) {
                return bedrockCape;
            }
        }
        return EMPTY_CAPE;
    }

    @Nullable
    static Cape getCachedCape(String capeUrl) {
        if (capeUrl == null) {
            return null;
        }
        return CACHED_JAVA_CAPES.getIfPresent(capeUrl);
    }

    static CompletableFuture<SkinData> requestSkinData(AvatarEntity entity, GeyserSession session) {
        SkinManager.GameProfileData data = SkinManager.GameProfileData.from(entity);
        if (data == null) {
            // This player likely does not have a textures property
            return CompletableFuture.completedFuture(determineFallbackSkinData(entity.uuid()));
        }

        return requestSkinAndCape(entity.uuid(), data.skinUrl(), data.capeUrl())
                .thenApplyAsync(skinAndCape -> {
                    try {
                        Skin skin = skinAndCape.skin();
                        Cape cape = skinAndCape.cape();
                        SkinGeometry geometry = data.isSlim() ? SkinGeometry.SLIM : SkinGeometry.WIDE;

                        // Whether we should see if this player has a Bedrock skin we should check for on failure of
                        // any skin property
                        boolean checkForBedrock = entity.uuid().version() != 4;

                        if (cape.failed() && checkForBedrock) {
                            cape = getCachedBedrockCape(entity.uuid());
                        }

                        // Call event to allow extensions to modify the skin, cape and geo
                        boolean isBedrock = GeyserImpl.getInstance().connectionByUuid(entity.uuid()) != null;
                        SkinData skinData = new SkinData(skin, cape, geometry);
                        final EventSkinData eventSkinData = new EventSkinData(skinData);
                        GeyserImpl.getInstance().eventBus().fire(new SessionSkinApplyEvent(session, entity.getUsername(), entity.uuid(), data.isSlim(), isBedrock, skinData) {
                            @Override
                            public @NonNull SkinData skinData() {
                                return eventSkinData.skinData();
                            }

                            @Override
                            public void skin(@NonNull Skin newSkin) {
                                eventSkinData.skinData(new SkinData(Objects.requireNonNull(newSkin), eventSkinData.skinData().cape(), eventSkinData.skinData().geometry()));
                            }

                            @Override
                            public void cape(@NonNull Cape newCape) {
                                eventSkinData.skinData(new SkinData(eventSkinData.skinData().skin(), Objects.requireNonNull(newCape), eventSkinData.skinData().geometry()));
                            }

                            @Override
                            public void geometry(@NonNull SkinGeometry newGeometry) {
                                eventSkinData.skinData(new SkinData(eventSkinData.skinData().skin(), eventSkinData.skinData().cape(), Objects.requireNonNull(newGeometry)));
                            }
                        });

                        return eventSkinData.skinData();
                    } catch (Exception e) {
                        // One line per player rather than a stack trace each, unless debugging
                        GeyserLogger logger = GeyserImpl.getInstance().getLogger();
                        String message = GeyserLocale.getLocaleStringLog("geyser.skin.fail", entity.uuid());
                        if (logger.isDebug()) {
                            logger.error(message, e);
                        } else {
                            logger.warning(message + " (" + e + ")");
                        }
                    }

                    return new SkinData(skinAndCape.skin(), skinAndCape.cape(), null);
                });
    }

    private static CompletableFuture<SkinAndCape> requestSkinAndCape(UUID playerId, String skinUrl, String capeUrl) {
        return CompletableFuture.supplyAsync(() -> {
            long time = System.currentTimeMillis();

            SkinAndCape skinAndCape = new SkinAndCape(
                    getOrDefault(requestSkin(playerId, skinUrl, false), EMPTY_SKIN, 5),
                    getOrDefault(requestCape(capeUrl, false), EMPTY_CAPE, 5)
            );

            GeyserImpl.getInstance().getLogger().debug("Took " + (System.currentTimeMillis() - time) + "ms for " + playerId);
            return skinAndCape;
        }, getExecutorService());
    }

    /**
     * The returned future never completes exceptionally; a failed download yields {@link #FAILED_SKIN}.
     */
    static CompletableFuture<Skin> requestSkin(UUID playerId, String textureUrl, boolean newThread) {
        if (textureUrl == null || textureUrl.isEmpty()) return CompletableFuture.completedFuture(EMPTY_SKIN);
        CompletableFuture<Skin> requestedSkin = requestedSkins.get(textureUrl);
        if (requestedSkin != null) {
            // already requested
            return requestedSkin;
        }

        Skin cachedSkin = CACHED_JAVA_SKINS.getIfPresent(textureUrl);
        if (cachedSkin != null) {
            return CompletableFuture.completedFuture(cachedSkin);
        }

        if (FAILED_SKIN_URLS.getIfPresent(textureUrl) != null) {
            // Failed recently; don't retry until the negative entry expires
            return CompletableFuture.completedFuture(FAILED_SKIN);
        }

        if (newThread) {
            return requestInFlight(requestedSkins, textureUrl, url -> supplySkin(playerId, url), url -> FAILED_SKIN, SkinProvider::cacheSkinResult);
        }

        Skin skin = supplySkin(playerId, textureUrl); // blocking
        cacheSkinResult(textureUrl, skin);
        return CompletableFuture.completedFuture(skin);
    }

    /**
     * The returned future never completes exceptionally; a failed download yields a cape with {@link Cape#failed()} set.
     */
    private static CompletableFuture<Cape> requestCape(String capeUrl, boolean newThread) {
        if (capeUrl == null || capeUrl.isEmpty()) return CompletableFuture.completedFuture(EMPTY_CAPE);
        CompletableFuture<Cape> requestedCape = requestedCapes.get(capeUrl);
        if (requestedCape != null) {
            return requestedCape;
        }

        Cape cachedCape = CACHED_JAVA_CAPES.getIfPresent(capeUrl);
        if (cachedCape != null) {
            return CompletableFuture.completedFuture(cachedCape);
        }

        if (FAILED_CAPE_URLS.getIfPresent(capeUrl) != null) {
            // Failed recently; don't retry until the negative entry expires
            return CompletableFuture.completedFuture(failedCape(capeUrl));
        }

        if (newThread) {
            return requestInFlight(requestedCapes, capeUrl, SkinProvider::supplyCape, SkinProvider::failedCape, SkinProvider::cacheCapeResult);
        }

        Cape cape = supplyCape(capeUrl); // blocking
        cacheCapeResult(capeUrl, cape);
        return CompletableFuture.completedFuture(cape);
    }

    /**
     * Starts the download of {@code url} on the skin executor, or joins the one already in flight. {@code computeIfAbsent}
     * guarantees a single in-flight future per URL. The returned future never completes exceptionally, and it completes
     * only after the result has been cached and the in-flight entry removed.
     */
    static <T> CompletableFuture<T> requestInFlight(Map<String, CompletableFuture<T>> inFlight, String url, Function<String, T> loader,
                                                    Function<String, T> onFailure, BiConsumer<String, T> cacheResult) {
        try {
            return inFlight.computeIfAbsent(url, key -> CompletableFuture.supplyAsync(() -> loader.apply(key), getExecutorService())
                    .exceptionally(throwable -> {
                        debug("Failed to load texture from " + key + ": " + throwable);
                        return onFailure.apply(key);
                    })
                    // This must be async: a synchronous whenComplete could run inside computeIfAbsent's mapping function,
                    // where removing from the map throws "Recursive update". Running it elsewhere, the removal waits for the
                    // insert to be published and then removes exactly this entry. The common pool is used instead of the
                    // skin executor so that a pool shut down between the two stages can't reject this stage.
                    .whenCompleteAsync((result, throwable) -> {
                        try {
                            cacheResult.accept(key, result);
                        } finally {
                            inFlight.remove(key);
                        }
                    }));
        } catch (RejectedExecutionException e) {
            // The skin executor was shut down (e.g. during a reload) after we got it; nothing was registered
            return CompletableFuture.completedFuture(onFailure.apply(url));
        }
    }

    /**
     * The only place a downloaded Java skin is cached. Failures go to the short-lived negative cache instead, so the
     * positive cache only ever holds skins with {@code failed() == false}.
     */
    static void cacheSkinResult(String url, @Nullable Skin skin) {
        if (skin == null || skin.failed()) {
            FAILED_SKIN_URLS.put(url, Boolean.TRUE);
        } else {
            CACHED_JAVA_SKINS.put(url, skin);
        }
    }

    /**
     * The only place a downloaded Java cape is cached; see {@link #cacheSkinResult(String, Skin)}.
     */
    static void cacheCapeResult(String url, @Nullable Cape cape) {
        if (cape == null || cape.failed()) {
            FAILED_CAPE_URLS.put(url, Boolean.TRUE);
        } else {
            CACHED_JAVA_CAPES.put(url, cape);
        }
    }

    private static void debug(String message) {
        // Null-safe so the failure paths above can never throw themselves
        GeyserImpl geyser = GeyserImpl.getInstance();
        if (geyser != null) {
            geyser.getLogger().debug(message);
        }
    }

    static void storeBedrockSkin(UUID playerID, String skinId, byte[] skinData) {
        Skin skin = new Skin(skinId, skinData);
        CACHED_BEDROCK_SKINS.put(skin.textureUrl(), skin);
    }

    static void storeBedrockCape(String capeId, byte[] capeData) {
        Cape cape = new Cape(capeId, capeId, capeData);
        CACHED_BEDROCK_CAPES.put(capeId, cape);
    }

    static void storeBedrockGeometry(UUID playerID, byte[] geometryName, byte[] geometryData) {
        SkinGeometry geometry = new SkinGeometry(new String(geometryName), new String(geometryData));
        cachedGeometry.put(playerID, geometry);
    }

    private static Skin supplySkin(UUID uuid, String textureUrl) {
        try {
            byte[] skin = requestImageData(textureUrl, false);
            return new Skin(textureUrl, skin);
        } catch (Exception e) {
            debug("Failed to download skin from " + textureUrl + ": " + e);
        }

        return FAILED_SKIN;
    }

    private static Cape supplyCape(String capeUrl) {
        byte[] cape = EMPTY_CAPE.capeData();
        try {
            cape = requestImageData(capeUrl, true);
        } catch (Exception e) {
            debug("Failed to download cape from " + capeUrl + ": " + e);
        }

        return new Cape(
                capeUrl,
                capeIdFromUrl(capeUrl),
                cape,
                cape.length == 0
        );
    }

    private static Cape failedCape(String capeUrl) {
        return new Cape(capeUrl, capeIdFromUrl(capeUrl), EMPTY_CAPE.capeData(), true);
    }

    private static String capeIdFromUrl(String capeUrl) {
        String[] urlSection = capeUrl.split("/"); // A real url is expected at this stage
        return urlSection[urlSection.length - 1]; // get the texture id and use it as cape id
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static BufferedImage requestImage(String imageUrl, boolean isCape) throws IOException {
        BufferedImage image = null;

        // First see if we have a cached file. We also update the modification stamp so we know when the file was last used
        File imageFile = GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("cache").resolve("images").resolve(UUID.nameUUIDFromBytes(imageUrl.getBytes()) + ".png").toFile();
        if (imageFile.exists()) {
            try {
                GeyserImpl.getInstance().getLogger().debug("Reading cached image from file " + imageFile.getPath() + " for " + imageUrl);
                imageFile.setLastModified(System.currentTimeMillis());
                image = ImageIO.read(imageFile);
            } catch (IOException ignored) {}
        }

        // If no image we download it
        if (image == null) {
            image = downloadImage(imageUrl);
            GeyserImpl.getInstance().getLogger().debug("Downloaded " + imageUrl);

            // Write to cache if we are allowed
            if (GeyserImpl.getInstance().config().advanced().cacheImages() > 0) {
                imageFile.getParentFile().mkdirs();
                try {
                    ImageIO.write(image, "png", imageFile);
                    GeyserImpl.getInstance().getLogger().debug("Writing cached skin to file " + imageFile.getPath() + " for " + imageUrl);
                } catch (IOException e) {
                    GeyserImpl.getInstance().getLogger().error("Failed to write cached skin to file " + imageFile.getPath() + " for " + imageUrl);
                }
            }
        }

        // if the requested image is a cape
        if (isCape) {
            if (image.getWidth() > 64 || image.getHeight() > 32) {
                // Prevent weirdly-scaled capes from being cut off
                BufferedImage newImage = new BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB);
                Graphics g = newImage.createGraphics();
                g.drawImage(image, 0, 0, image.getWidth(), image.getHeight(), null);
                g.dispose();
                image.flush();
                image = scale(newImage, 64, 32);
            } else if (image.getWidth() < 64 || image.getHeight() < 32) {
                // Bedrock doesn't like smaller-sized capes, either.
                BufferedImage newImage = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
                Graphics g = newImage.createGraphics();
                g.drawImage(image, 0, 0, image.getWidth(), image.getHeight(), null);
                g.dispose();
                image.flush();
                image = newImage;
            }
        } else {
            int height = image.getHeight();
            int width = image.getWidth();
            if (width == 64 && (height == 32 || height == 64)) {
                boolean isLegacy = height == 32;
                int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
                byte[] mask = isLegacy ? SKIN_MASK_LEGACY : SKIN_MASK;
                for (int i = 0; i < pixels.length; i++) {
                    if ((((mask[i >> 3] & 0xFF) >> (i & 0x7)) & 1) != 0) {
                        pixels[i] |= 0xFF000000;
                    }
                }
                image.setRGB(0, 0, width, height, pixels, 0, width);
            } else {
                throw new IllegalStateException("Discarding incorrectly sized (" + width + "x" + height + ") skin texture from " + imageUrl);
            }
        }

        return image;
    }

    private static byte[] requestImageData(String imageUrl, boolean isCape) throws Exception {
        BufferedImage image = requestImage(imageUrl, isCape);
        byte[] data = bufferedImageToImageData(image);
        image.flush();
        return data;
    }

    public static @Nullable String shorthandUUID(@Nullable UUID uuid) {
        if (uuid == null) {
            return null;
        }

        return uuid.toString().replace("-", "");
    }

    public static @Nullable UUID expandUUID(@Nullable String uuid) {
        if (uuid == null) {
            return null;
        }

        long mostSignificant = Long.parseUnsignedLong(uuid.substring(0, 16), 16);
        long leastSignificant = Long.parseUnsignedLong(uuid.substring(16), 16);
        return new UUID(mostSignificant, leastSignificant);
    }

    /**
     * Request a player's username from their UUID
     *
     * @param uuid the player's UUID
     * @return a completable username of the player
     */
    public static CompletableFuture<@Nullable String> requestUsernameFromUUID(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject node = WebUtils.getJson("https://api.minecraftservices.com/minecraft/profile/lookup/" + shorthandUUID(uuid));
                JsonElement name = node.get("name");
                if (name == null) {
                    GeyserImpl.getInstance().getLogger().debug("No username found in Mojang response for " + uuid);
                    return null;
                }
                return name.getAsString();
            } catch (Exception e) {
                if (GeyserImpl.getInstance().config().debugMode()) {
                    e.printStackTrace();
                }
                return null;
            }
        }, getExecutorService());
    }

    /**
     * Request a player's UUID from their username
     *
     * @param username the player's username
     * @return a completable UUID of the player
     */
    public static CompletableFuture<@Nullable UUID> requestUUIDFromUsername(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject node = WebUtils.getJson("https://api.mojang.com/users/profiles/minecraft/" + username);
                JsonElement id = node.get("id");
                if (id == null) {
                    GeyserImpl.getInstance().getLogger().debug("No UUID found in Mojang response for " + username);
                    return null;
                }
                return expandUUID(id.getAsString());
            } catch (Exception e) {
                if (GeyserImpl.getInstance().config().debugMode()) {
                    e.printStackTrace();
                }
                return null;
            }
        }, getExecutorService());
    }

    /**
     * Request textures from a player's UUID
     *
     * @param uuid the player's UUID
     * @return a completable GameProfile with textures included
     */
    public static CompletableFuture<@Nullable String> requestTexturesFromUUID(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject node = WebUtils.getJson("https://sessionserver.mojang.com/session/minecraft/profile/" + shorthandUUID(uuid));
                JsonArray properties = node.getAsJsonArray("properties");
                if (properties == null) {
                    GeyserImpl.getInstance().getLogger().debug("No properties found in Mojang response for " + uuid);
                    return null;
                }
                return properties.get(0).getAsJsonObject().get("value").getAsString();
            } catch (Exception e) {
                GeyserImpl.getInstance().getLogger().debug("Unable to request textures for " + uuid);
                if (GeyserImpl.getInstance().config().debugMode()) {
                    e.printStackTrace();
                }
                return null;
            }
        }, getExecutorService());
    }

    /**
     * Request textures from a player's username
     *
     * @param username the player's username
     * @return a completable GameProfile with textures included
     */
    public static CompletableFuture<@Nullable String> requestTexturesFromUsername(String username) {
        return requestUUIDFromUsername(username)
            .thenCompose(uuid -> {
                if (uuid == null) {
                    return CompletableFuture.completedFuture(null);
                }
                return requestTexturesFromUUID(uuid);
            });
    }

    private static BufferedImage downloadImage(String imageUrl) throws IOException {
        if (!TextureUrlChecker.isAllowedTextureDomain(imageUrl)) {
            return EMPTY_SKIN_IMAGE;
        }
        HttpURLConnection con = (HttpURLConnection) new URL(imageUrl).openConnection();
        con.setRequestProperty("User-Agent", WebUtils.getUserAgent());
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);

        int code = con.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            // Close the error body so the connection can go back to the keep-alive pool instead of lingering until GC
            InputStream errorStream = con.getErrorStream();
            if (errorStream != null) {
                errorStream.close();
            }
            throw new IOException("HTTP " + code + " from " + imageUrl);
        }

        BufferedImage image;
        // ImageIO.read(InputStream) does not close the stream it is given
        try (InputStream stream = con.getInputStream()) {
            image = ImageIO.read(stream);
        }

        if (image == null) {
            throw new IllegalArgumentException("Failed to read image from: %s".formatted(imageUrl));
        }
        return image;
    }

    public static BufferedImage scale(BufferedImage bufferedImage, int newWidth, int newHeight) {
        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = resized.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(bufferedImage, 0, 0, newWidth, newHeight, null);
        g2.dispose();
        bufferedImage.flush();
        return resized;
    }

    /**
     * Get the RGBA int for a given index in some image data
     *
     * @param index Index to get
     * @param data Image data to find in
     * @return An int representing RGBA
     */
    private static int getRGBA(int index, byte[] data) {
        return (data[index] & 0xFF) << 16 | (data[index + 1] & 0xFF) << 8 |
                data[index + 2] & 0xFF | (data[index + 3] & 0xFF) << 24;
    }

    /**
     * Convert a byte[] to a BufferedImage
     *
     * @param imageData The byte[] to convert
     * @param imageWidth The width of the target image
     * @param imageHeight The height of the target image
     * @return The converted BufferedImage
     */
    public static BufferedImage imageDataToBufferedImage(byte[] imageData, int imageWidth, int imageHeight) {
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        int index = 0;
        for (int y = 0; y < imageHeight; y++) {
            for (int x = 0; x < imageWidth; x++) {
                image.setRGB(x, y, getRGBA(index, imageData));
                index += 4;
            }
        }

        return image;
    }

    /**
     * Convert a BufferedImage to a byte[]
     *
     * @param image The BufferedImage to convert
     * @return The converted byte[]
     */
    public static byte[] bufferedImageToImageData(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        byte[] data = new byte[pixels.length * 4];
        int index = 0;
        for (int argb : pixels) {
            data[index++] = (byte) (argb >> 16); // Red
            data[index++] = (byte) (argb >> 8); // Green
            data[index++] = (byte) argb; // Blue
            data[index++] = (byte) (argb >>> 24); // Alpha
        }
        return data;
    }

    public static <T> T getOrDefault(CompletableFuture<T> future, T defaultValue, int timeoutInSeconds) {
        try {
            return future.get(timeoutInSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return defaultValue;
    }

    public record SkinAndCape(Skin skin, Cape cape) {
    }

    public static class EventSkinData {
        private SkinData skinData;

        public EventSkinData(SkinData skinData) {
            this.skinData = skinData;
        }

        public SkinData skinData() {
            return skinData;
        }

        public void skinData(SkinData skinData) {
            this.skinData = skinData;
        }
    }
}
