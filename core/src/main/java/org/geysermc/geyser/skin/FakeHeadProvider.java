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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.skin.Skin;
import org.geysermc.geyser.api.skin.SkinData;
import org.geysermc.geyser.api.skin.SkinGeometry;
import org.geysermc.geyser.entity.type.LivingEntity;
import org.geysermc.geyser.entity.type.player.AvatarEntity;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.mcprotocollib.auth.texture.Texture;
import org.geysermc.mcprotocollib.auth.texture.TextureType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.ResolvableProfile;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Responsible for modifying a player's skin when wearing a player head
 * <p>
 * {@link GeyserSession#getPlayerWithCustomHeads()} is only touched on the session event loop; every completion hops
 * back there and drops its result if the session closed, the entity despawned or the head changed in the meantime.
 */
public class FakeHeadProvider {
    static final Cache<FakeHeadEntry, SkinData> MERGED_SKINS = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumWeight(32L * 1024 * 1024)
            .weigher((FakeHeadEntry key, SkinData value) -> value.skin().skinData().length + value.cape().capeData().length + 128)
            .build();
    // Merges in progress; only ever written through mergedSkin
    static final Map<FakeHeadEntry, CompletableFuture<SkinData>> IN_FLIGHT = new ConcurrentHashMap<>();

    public static void setHead(GeyserSession session, AvatarEntity entity, @Nullable ResolvableProfile profile) {
        if (profile == null) {
            return;
        }

        // Claimed before resolving, so repeated equipment updates don't redo the work and a slower earlier request
        // can tell that it was superseded
        ResolvableProfile previous = session.getPlayerWithCustomHeads().put(entity.uuid(), profile);
        if (profile.equals(previous)) {
            // We already did this, no need to re-compute
            return;
        }
        boolean headShown = previous != null;

        SkinManager.resolveProfile(profile).whenComplete((resolved, throwable) -> whileCurrent(session, entity, profile, () -> {
            Texture skinTexture = throwable == null ? SkinManager.getTextureDataFromProfile(resolved, TextureType.SKIN) : null;
            if (skinTexture == null) {
                if (throwable != null) {
                    debug("Couldn't resolve the head profile worn by " + entity.getUsername(), throwable);
                }
                abandonHead(session, entity, headShown);
                return;
            }

            String headUrl = skinTexture.getURL();
            mergedSkin(new FakeHeadEntry(entity.getSkinId(), headUrl),
                    () -> SkinProvider.requestSkinData(entity, session),
                    () -> SkinProvider.requestSkin(entity.uuid(), headUrl, true))
                .whenComplete((skinData, mergeThrowable) -> whileCurrent(session, entity, profile, () -> {
                    if (mergeThrowable != null) {
                        debug("Couldn't merge skin of " + entity.getUsername() + " with head skin " + headUrl, mergeThrowable);
                        abandonHead(session, entity, headShown);
                        return;
                    }
                    SkinManager.sendSkinPacket(session, entity, skinData);
                }));
        }));
    }

    public static void restoreOriginalSkin(GeyserSession session, LivingEntity livingEntity) {
        if (!(livingEntity instanceof AvatarEntity entity)) {
            return;
        }

        if (session.getPlayerWithCustomHeads().remove(entity.uuid()) == null) {
            return;
        }

        CompletableFuture<SkinData> skinDataFuture;
        try {
            skinDataFuture = SkinProvider.requestSkinData(entity, session);
        } catch (RuntimeException e) {
            // The skin executor was shut down, e.g. by a reload
            debug("Couldn't restore the skin of " + entity.getUsername(), e);
            return;
        }

        skinDataFuture.whenComplete((skinData, throwable) -> session.ensureInEventLoop(() -> {
            if (throwable != null) {
                debug("Couldn't restore the skin of " + entity.getUsername(), throwable);
                return;
            }
            // Skip if a new head went on while the original skin loaded; that head's own request sends the skin
            if (session.isClosed() || !entity.isValid() || session.getPlayerWithCustomHeads().containsKey(entity.uuid())
                    || skinData.geometry() == null) {
                return;
            }
            SkinManager.sendSkinPacket(session, entity, skinData);
        }));
    }

    /**
     * Returns the cached merge for {@code key}, or starts it, or joins the one already in flight. No thread blocks on
     * another skin task: both inputs are requested asynchronously and the merge itself runs on the common pool.
     */
    static CompletableFuture<SkinData> mergedSkin(FakeHeadEntry key, Supplier<CompletableFuture<SkinData>> originalSkin,
                                                  Supplier<CompletableFuture<Skin>> headSkin) {
        SkinData cached = MERGED_SKINS.getIfPresent(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        try {
            return IN_FLIGHT.computeIfAbsent(key, k -> originalSkin.get()
                    .thenCombineAsync(headSkin.get(), (original, head) -> merge(original, head, k.fakeHeadSkinUrl()))
                    // Async for the same reason as SkinProvider#requestInFlight: removing inside computeIfAbsent's mapping
                    // function would throw "Recursive update"
                    .whenCompleteAsync((result, throwable) -> {
                        try {
                            if (throwable == null) {
                                MERGED_SKINS.put(k, result);
                            }
                        } finally {
                            IN_FLIGHT.remove(k);
                        }
                    }));
        } catch (RuntimeException e) {
            // The skin executor was shut down, e.g. by a reload; nothing was registered
            return CompletableFuture.failedFuture(e);
        }
    }

    static SkinData merge(SkinData skinData, Skin headSkin, String headUrl) {
        Skin skin = skinData.skin();
        if (skin.failed()) {
            // Don't cache a head on the fallback texture for an hour
            throw new IllegalStateException("Couldn't load player's original skin");
        }
        if (headSkin.failed()) {
            throw new IllegalStateException("Couldn't load head skin " + headUrl);
        }

        SkinGeometry geometry = skinData.geometry() != null && SkinGeometry.SLIM.geometryName().equals(skinData.geometry().geometryName())
                ? SkinProvider.WEARING_CUSTOM_SKULL_SLIM : SkinProvider.WEARING_CUSTOM_SKULL;

        BufferedImage originalSkinImage = SkinProvider.imageDataToBufferedImage(skin.skinData(), 64, skin.skinData().length / 4 / 64);
        BufferedImage headSkinImage = SkinProvider.imageDataToBufferedImage(headSkin.skinData(), 64, headSkin.skinData().length / 4 / 64);

        Graphics2D graphics2D = originalSkinImage.createGraphics();
        graphics2D.setComposite(AlphaComposite.Clear);
        graphics2D.fillRect(0, 0, 64, 16);
        graphics2D.setComposite(AlphaComposite.SrcOver);
        graphics2D.drawImage(headSkinImage, 0, 0, 64, 16, 0, 0, 64, 16, null);
        graphics2D.dispose();

        // Make the skin key a combination of the current skin data and the new skin data
        // Don't tie it to a player - that player *can* change skins in-game
        String skinKey = "customPlayerHead_" + headUrl + "_" + skin.textureUrl();
        byte[] targetSkinData = SkinProvider.bufferedImageToImageData(originalSkinImage);
        return new SkinData(new Skin(skinKey, targetSkinData), skinData.cape(), geometry);
    }

    private static void whileCurrent(GeyserSession session, AvatarEntity entity, ResolvableProfile profile, Runnable action) {
        session.ensureInEventLoop(() -> {
            // The session closed, the player despawned, or the head was taken off or swapped while we waited
            if (session.isClosed() || !entity.isValid() || !profile.equals(session.getPlayerWithCustomHeads().get(entity.uuid()))) {
                return;
            }
            action.run();
        });
    }

    /**
     * Releases the claim made by {@link #setHead} so a later equipment update can retry, and puts the original skin
     * back if a previous head was showing.
     */
    private static void abandonHead(GeyserSession session, AvatarEntity entity, boolean headShown) {
        if (headShown) {
            restoreOriginalSkin(session, entity);
        } else {
            session.getPlayerWithCustomHeads().remove(entity.uuid());
        }
    }

    private static void debug(String message, Throwable throwable) {
        // One line per failure: these fire per player whenever the textures CDN has a hiccup
        GeyserImpl.getInstance().getLogger().debug(message + ": " + throwable);
    }

    record FakeHeadEntry(@Nullable String originalSkinId, String fakeHeadSkinUrl) {
    }
}
