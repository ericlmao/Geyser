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

import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is used for mapping a translation key with the already loaded Java locale data
 * Used in MessageTranslator.java as part of the KyoriPowered/Adventure library
 */
public class MinecraftTranslationRegistry extends TranslatableComponentRenderer<String> {
    private final Pattern stringReplacement = Pattern.compile("%s");
    private final Pattern positionalStringReplacement = Pattern.compile("%([0-9]+)\\$s");
    private final Pattern escapeBraces = Pattern.compile("\\{+['{]+\\{+|\\{+");

    /**
     * Converted MessageFormat patterns per lower case locale. Each entry belongs to the locale map it was built
     * from, so a reloaded locale never serves patterns of the map it replaced.
     */
    private final Map<String, PatternCache> patternCache = new ConcurrentHashMap<>();

    private record PatternCache(Map<String, String> source, Map<String, String> patterns) {
    }

    // Exists to maintain compatibility with Velocity's older Adventure version
    @Override
    public @Nullable MessageFormat translate(@NonNull String key, @NonNull String locale) {
        return this.translate(key, null, locale);
    }

    @Override
    protected @Nullable MessageFormat translate(@NonNull String key, @Nullable String fallback, @NonNull String locale) {
        String lowercaseLocale = locale.toLowerCase(Locale.ROOT);
        // Read before the string, so the cache entry can't be tied to a newer map than the string came from
        Map<String, String> source = MinecraftLocale.LOCALE_MAPPINGS.get(lowercaseLocale);

        // Get the locale string
        String localeString = MinecraftLocale.getLocaleStringIfPresent(key, lowercaseLocale);
        if (localeString == null) {
            if (fallback != null) {
                // Fallback strings will still have their params inserted
                // They come from the component, so they aren't cached
                return new MessageFormat(convert(fallback), Locale.ROOT);
            } else {
                // The original translation will be translated
                // Can be tested with 1.19.4: {"translate":"%s","with":[{"text":"weeeeeee"}]}
                return null;
            }
        }

        // MessageFormat isn't thread-safe and this renderer is shared, so only the converted pattern is cached
        if (source == null) {
            // Locale not loaded (yet): this is the en_us string, which must not be cached under this locale
            return new MessageFormat(convert(localeString), Locale.ROOT);
        }
        PatternCache cache = patternCache.get(lowercaseLocale);
        if (cache == null || cache.source() != source) {
            cache = new PatternCache(source, new ConcurrentHashMap<>());
            patternCache.put(lowercaseLocale, cache);
        }
        String pattern = cache.patterns().get(key);
        if (pattern == null) {
            pattern = convert(localeString);
            cache.patterns().put(key, pattern);
        }
        return new MessageFormat(pattern, Locale.ROOT);
    }

    /**
     * Drops the cached patterns of a locale, e.g. when it is unloaded.
     *
     * @param locale the locale
     */
    public void invalidate(String locale) {
        patternCache.remove(locale.toLowerCase(Locale.ROOT));
    }

    /**
     * Drops cached patterns of locales that are no longer loaded, or were loaded again since.
     */
    public void prune() {
        patternCache.entrySet().removeIf(entry -> MinecraftLocale.LOCALE_MAPPINGS.get(entry.getKey()) != entry.getValue().source());
    }

    /**
     * Converts a Java translation string into a MessageFormat pattern.
     */
    private String convert(String localeString) {
        // replace single quote instances which get lost in MessageFormat otherwise
        localeString = localeString.replace("'", "''");

        // Escape all left curly brackets with single quote - fixes https://github.com/GeyserMC/Geyser/issues/4662
        Pattern p = escapeBraces;
        Matcher m = p.matcher(localeString);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, "'" + m.group() + "'");
        }
        m.appendTail(sb);

        // Replace the `%s` with numbered inserts `{0}`
        p = stringReplacement;
        m = p.matcher(sb.toString());
        sb = new StringBuilder();
        int i = 0;
        while (m.find()) {
            m.appendReplacement(sb, "{" + (i++) + "}");
        }
        m.appendTail(sb);

        // Replace the `%x$s` with numbered inserts `{x}`
        p = positionalStringReplacement;
        m = p.matcher(sb.toString());
        sb = new StringBuilder();
        while (m.find()) {
            i = Integer.parseInt(m.group(1)) - 1;
            m.appendReplacement(sb, "{" + i + "}");
        }
        m.appendTail(sb);

        // Locale shouldn't need to be specific - dates for example will not be handled
        return sb.toString();
    }

    /**
     * @return the number of cached patterns for a locale, for tests
     */
    int cachedPatterns(String locale) {
        PatternCache cache = patternCache.get(locale.toLowerCase(Locale.ROOT));
        return cache == null ? 0 : cache.patterns().size();
    }
}
