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

package org.geysermc.geyser.util;

import org.checkerframework.checker.nullness.qual.NonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public class WebUtils {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    /**
     * Suffix of a metadata file written by a forced {@link #downloadRemotePack(String, boolean)} download.
     * It only replaces the actual metadata once the download is adopted via {@link #promoteRemotePack(String, Path)}.
     */
    private static final String PENDING_METADATA_SUFFIX = ".pending";

    private static Path remotePackCache() {
        // Not a static field so this class can be loaded without a running Geyser instance (e.g. in tests)
        return GeyserImpl.getInstance().getBootstrap().getConfigFolder().resolve("cache").resolve("remote_packs");
    }

    /**
     * Opens a connection with Geyser's user agent, connect/read timeouts and redirects enabled.
     * Without explicit timeouts, {@link HttpURLConnection} waits forever on a stalled server.
     */
    private static HttpURLConnection openConnection(String reqURL) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(reqURL).openConnection();
        con.setRequestProperty("User-Agent", getUserAgent());
        con.setConnectTimeout(CONNECT_TIMEOUT_MS);
        con.setReadTimeout(READ_TIMEOUT_MS);
        con.setInstanceFollowRedirects(true);
        return con;
    }

    /**
     * Makes a web request to the given URL and returns the body as a string
     *
     * @param reqURL URL to fetch
     * @return body content or
     * @throws IOException / a wrapped UnknownHostException for nicer errors.
     */
    public static String getBody(String reqURL) throws IOException {
        try {
            URL url = new URL(reqURL);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", getUserAgent()); // Otherwise Java 8 fails on checking updates
            con.setConnectTimeout(10000);
            con.setReadTimeout(10000);
            checkResponseCode(con);
            return connectionToString(con);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Unable to resolve requested url (%s)! Are you offline?".formatted(reqURL), e);
        }
    }

    /**
     * Makes a web request to the given URL and returns the body as a {@link JsonObject}.
     *
     * @param reqURL URL to fetch
     * @return the response as JSON
     */
    public static JsonObject getJson(String reqURL) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(reqURL).openConnection();
        con.setRequestProperty("User-Agent", getUserAgent());
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        checkResponseCode(con);
        try (InputStreamReader isr = new InputStreamReader(con.getInputStream());
             JsonReader reader = GeyserImpl.GSON.newJsonReader(isr)) {
            //noinspection deprecation
            return new JsonParser().parse(reader).getAsJsonObject();
        }
    }

    /**
     * Downloads a file from the given URL and saves it to disk
     *
     * @param reqURL File to fetch
     * @param fileLocation Location to save on disk
     */
    public static void downloadFile(String reqURL, String fileLocation) {
        downloadFile(reqURL, Paths.get(fileLocation));
    }

    /**
     * Downloads a file from the given URL and saves it to disk
     *
     * @param reqURL File to fetch
     * @param path Location to save on disk as a path
     */
    public static void downloadFile(String reqURL, Path path) {
        // Download next to the target and move it into place, so concurrent readers never see a partial file
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            HttpURLConnection con = openConnection(reqURL);
            checkResponseCode(con);
            try (InputStream in = con.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to download and save file: " + path.toAbsolutePath() + " (" + reqURL + ")", e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Checks a remote pack URL to see if it is valid
     * If it is, it will download the pack file and return a path to it
     *
     * @param url The URL to check
     * @param force If true, the pack will be downloaded even if it is cached to a separate location.
     *              The existing cache metadata is left untouched; the download only becomes the cached copy once
     *              {@link #promoteRemotePack(String, Path)} is called, and should be removed with
     *              {@link #discardRemotePack(String, Path)} otherwise.
     * @return Path to the downloaded pack file, or null if it was unable to be loaded
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static @NonNull Path downloadRemotePack(String url, boolean force) throws IOException {
        GeyserLogger logger = GeyserImpl.getInstance().getLogger();
        try {
            HttpURLConnection con = openConnection(url);

            int responseCode = con.getResponseCode();
            if (responseCode >= 400) {
                throw new IllegalStateException(String.format("Invalid response code from remote pack at URL: %s (code: %d)", url, responseCode));
            }

            int size = con.getContentLength();
            String type = con.getContentType();

            if (size <= 0) {
                throw new IllegalArgumentException(String.format("Invalid content length received from remote pack at URL: %s (size: %d)", url, size));
            }

            if (type == null || !type.equals("application/zip")) {
                throw new IllegalArgumentException(String.format("Url %s tries to provide a resource pack using the %s content type, which is not supported by Bedrock edition! " +
                    "Bedrock Edition only supports the application/zip content type.", url, type));
            }

            // Ensure remote pack cache dir exists
            Path remotePackCache = remotePackCache();
            Files.createDirectories(remotePackCache);

            Path packMetadata = remotePackCache.resolve(url.hashCode() + ".metadata");
            Path downloadLocation;

            // If we downloaded this pack before, reuse it if the ETag matches.
            if (Files.exists(packMetadata) && !force) {
                try {
                    List<String> metadata = Files.readAllLines(packMetadata, StandardCharsets.UTF_8);
                    int cachedSize = Integer.parseInt(metadata.get(0));
                    String cachedEtag = metadata.get(1);
                    long cachedLastModified = Long.parseLong(metadata.get(2));
                    downloadLocation = remotePackCache.resolve(metadata.get(3));

                    if (cachedSize == size &&
                            cachedEtag.equals(con.getHeaderField("ETag")) &&
                            cachedLastModified == con.getLastModified() &&
                            downloadLocation.toFile().exists()) {
                        logger.debug("Using cached pack (%s) for %s.".formatted(downloadLocation.getFileName(), url));
                        downloadLocation.toFile().setLastModified(System.currentTimeMillis());
                        packMetadata.toFile().setLastModified(System.currentTimeMillis());
                        // The body is never read, so the connection cannot be reused
                        con.disconnect();
                        return downloadLocation;
                    } else {
                        logger.debug("Deleting cached pack/metadata (%s) as it appears to have changed!".formatted(url));
                        Files.deleteIfExists(packMetadata);
                        Files.deleteIfExists(downloadLocation);
                    }
                } catch (IOException e) {
                    GeyserImpl.getInstance().getLogger().error("Failed to read cached pack metadata! " + e);
                    packMetadata.toFile().deleteOnExit();
                }
            }

            downloadLocation = remotePackCache.resolve(url.hashCode() + "_" + System.currentTimeMillis() + ".zip");
            try (InputStream in = con.getInputStream()) {
                Files.copy(in, downloadLocation, StandardCopyOption.REPLACE_EXISTING);
            }

            // This needs to match as the client fails to download the pack otherwise
            long downloadSize = Files.size(downloadLocation);
            if (downloadSize != size) {
                Files.delete(downloadLocation);
                throw new IllegalStateException("Size mismatch with resource pack at url: %s. Downloaded pack has %s bytes, expected %s bytes!"
                        .formatted(url, downloadSize, size));
            }

            try {
                boolean shouldDeleteEnclosing = false;
                var originalZip = downloadLocation;
                try (ZipFile zip = new ZipFile(downloadLocation.toFile())) {
                    // This can (or should???) contain a zip
                    if (zip.stream().allMatch(name -> name.getName().endsWith(".zip"))) {
                        // Unzip the pack, as that's what we're after
                        downloadLocation = remotePackCache.resolve(url.hashCode() + "_" + System.currentTimeMillis() + "_unzipped.zip");
                        try (InputStream in = zip.getInputStream(zip.entries().nextElement())) {
                            Files.copy(in, downloadLocation, StandardCopyOption.REPLACE_EXISTING);
                        }
                        shouldDeleteEnclosing = true;
                    }
                } finally {
                    if (shouldDeleteEnclosing) {
                        // We don't need the original zip anymore
                        Files.delete(originalZip);
                    }
                }
            } catch (IOException e) {
                Files.deleteIfExists(downloadLocation);
                throw new IllegalArgumentException("Encountered exception while reading downloaded resource pack at url: %s".formatted(url), e);
            }

            // A forced download must not replace the cached copy until it is adopted (see promoteRemotePack);
            // otherwise the metadata would point at a file that the running pack codec is not using.
            Path metadataTarget = force ? pendingMetadata(packMetadata) : packMetadata;
            try {
                Files.write(
                        metadataTarget,
                        Arrays.asList(
                                String.valueOf(size),
                                con.getHeaderField("ETag"),
                                String.valueOf(con.getLastModified()),
                                downloadLocation.getFileName().toString()
                        ),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                metadataTarget.toFile().setLastModified(System.currentTimeMillis());
            } catch (IOException e) {
                Files.deleteIfExists(metadataTarget);
                Files.deleteIfExists(downloadLocation);
                throw new IllegalStateException("Failed to write cached pack metadata: " + e.getMessage());
            }

            downloadLocation.toFile().setLastModified(System.currentTimeMillis());
            logger.debug("Successfully downloaded remote pack! URL: %s (to: %s )".formatted(url, downloadLocation));
            return downloadLocation;
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Unable to download resource pack from malformed URL %s".formatted(url));
        } catch (SocketTimeoutException | ConnectException e) {
            logger.debug(e);
            throw new IllegalArgumentException("Unable to download pack from url %s due to network error ( %s )".formatted(url, e.toString()));
        }
    }

    /**
     * Makes a pack downloaded with {@code force} the cached copy for the given URL, replacing the previous metadata.
     * The previously cached pack file is not deleted here, as it may still be served to clients; it is cleaned up
     * once it is no longer referenced (see {@link org.geysermc.geyser.registry.loader.ResourcePackLoader#cleanupRemotePacks()}).
     *
     * @param url the url of the pack
     * @param candidate the path returned by {@link #downloadRemotePack(String, boolean)}
     * @return whether the metadata now references the candidate
     */
    public static boolean promoteRemotePack(String url, Path candidate) {
        Path packMetadata = remotePackCache().resolve(url.hashCode() + ".metadata");
        Path pending = pendingMetadata(packMetadata);
        try {
            if (!referencesFile(pending, candidate)) {
                GeyserImpl.getInstance().getLogger().debug("No pending cache metadata for %s (%s); not updating the remote pack cache.".formatted(url, candidate.getFileName()));
                return false;
            }
            Files.move(pending, packMetadata, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            GeyserImpl.getInstance().getLogger().error("Failed to update cached pack metadata for " + url, e);
            return false;
        }
    }

    /**
     * Deletes a pack downloaded with {@code force} that was not adopted, together with its pending metadata.
     * Does nothing if the pack file is the currently cached copy for the URL.
     *
     * @param url the url of the pack
     * @param candidate the path returned by {@link #downloadRemotePack(String, boolean)}
     */
    public static void discardRemotePack(String url, Path candidate) {
        Path remotePackCache = remotePackCache();
        Path packMetadata = remotePackCache.resolve(url.hashCode() + ".metadata");
        Path pending = pendingMetadata(packMetadata);
        try {
            if (!candidate.toAbsolutePath().normalize().startsWith(remotePackCache.toAbsolutePath().normalize())
                    || referencesFile(packMetadata, candidate)) {
                return;
            }
            if (referencesFile(pending, candidate)) {
                Files.deleteIfExists(pending);
            }
            Files.deleteIfExists(candidate);
        } catch (IOException e) {
            GeyserImpl.getInstance().getLogger().error("Failed to delete unused remote pack " + candidate, e);
        }
    }

    private static Path pendingMetadata(Path packMetadata) {
        return packMetadata.resolveSibling(packMetadata.getFileName() + PENDING_METADATA_SUFFIX);
    }

    /**
     * @return whether the given metadata file exists and its pack file entry (4th line) is the given file
     */
    private static boolean referencesFile(Path metadata, Path packFile) throws IOException {
        if (!Files.exists(metadata)) {
            return false;
        }
        List<String> lines = Files.readAllLines(metadata, StandardCharsets.UTF_8);
        return lines.size() >= 4 && lines.get(3).equals(packFile.getFileName().toString());
    }


    /**
     * Post a string to the given URL
     *
     * @param reqURL URL to post to
     * @param postContent String data to post
     * @return String returned by the server
     * @throws IOException If the request fails
     */
    public static String post(String reqURL, String postContent) throws IOException {
        HttpURLConnection con = openConnection(reqURL);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "text/plain");
        con.setDoOutput(true);

        try (OutputStream out = con.getOutputStream()) {
            out.write(postContent.getBytes(StandardCharsets.UTF_8));
        }

        return connectionToString(con);
    }

    /**
     * Gets the string output from the passed {@link HttpURLConnection},
     * or logs the error message.
     */
    private static String connectionToString(HttpURLConnection con) throws IOException {
        checkResponseCode(con);
        return inputStreamToString(con.getInputStream(), con::disconnect);
    }

    /**
     * Throws an exception if there is an error stream to avoid further issues
     */
    private static void checkResponseCode(HttpURLConnection con) throws IOException {
        // Send the request (we dont use this but its required for getErrorStream() to work)
        con.getResponseCode();

        // Read the error message if there is one if not just read normally
        InputStream errorStream = con.getErrorStream();
        if (errorStream != null) {
            throw new IOException(inputStreamToString(errorStream, null));
        }
    }

    /**
     * Get the string output from the passed {@link InputStream}
     *
     * @param stream The input stream to get the string from
     * @return The body of the returned page
     * @throws IOException If the request fails
     */
    private static String inputStreamToString(InputStream stream, @Nullable Runnable onFinish) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(stream))) {
            String inputLine;

            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
                content.append("\n");
            }

            if (onFinish != null) {
                onFinish.run();
            }
        }

        return content.toString();
    }

    /**
     * Post fields to a URL as a form
     *
     * @param reqURL URL to post to
     * @param fields Form data to post
     * @return String returned by the server
     * @throws IOException If the request fails
     */
    public static String postForm(String reqURL, Map<String, String> fields) throws IOException {
        HttpURLConnection con = openConnection(reqURL);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setDoOutput(true);

        try (OutputStream out = con.getOutputStream()) {
            // Write the form data to the output. Each part is written separately so that a large value
            // (e.g. a log file) is not copied into yet another concatenated string.
            for (Map.Entry<String, String> field : fields.entrySet()) {
                out.write((field.getKey() + "=").getBytes(StandardCharsets.UTF_8));
                out.write(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
                out.write('&');
            }
        }

        return connectionToString(con);
    }

    /**
     * Find a SRV record for the given address
     *
     * @param geyser Geyser instance
     * @param remoteAddress Address to find the SRV record for
     * @return The SRV record or null if not found
     */
    public static String @Nullable [] findSrvRecord(GeyserImpl geyser, String remoteAddress) {
        InitialDirContext ctx = null;
        try {
            // Searches for a server address and a port from a SRV record of the specified host name
            ctx = new InitialDirContext();
            Attribute attr = ctx.getAttributes("dns:///_minecraft._tcp." + remoteAddress, new String[]{"SRV"}).get("SRV");
            // size > 0 = SRV entry found
            if (attr != null && attr.size() > 0) {
                return ((String) attr.get(0)).split(" ");
            }
        } catch (Exception | NoClassDefFoundError ex) { // Check for a NoClassDefFoundError to prevent Android crashes
            if (geyser.config().debugMode()) {
                geyser.getLogger().debug("Exception while trying to find an SRV record for the remote host.");
                ex.printStackTrace(); // Otherwise we can get a stack trace for any domain that doesn't have an SRV record
            }
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (NamingException ignored) {
                }
            }
        }
        return null;
    }

    /**
     * Get a stream of lines from the given URL
     *
     * @param reqURL URL to fetch
     * @return Stream of lines from the URL or an empty stream if the request fails
     */
    public static Stream<String> getLineStream(String reqURL) {
        try {
            URL url = new URL(reqURL);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", getUserAgent()); // Otherwise Java 8 fails on checking updates
            con.setConnectTimeout(10000);
            con.setReadTimeout(10000);

            return connectionToString(con).lines();
        } catch (Exception e) {
            GeyserImpl.getInstance().getLogger().error("Error while trying to get a stream from " + reqURL, e);
            return Stream.empty();
        }
    }

    public static String getUserAgent() {
        return "Geyser-" + GeyserImpl.getInstance().platformType().platformName() + "/" + GeyserImpl.VERSION;
    }

    public static String toHttps(String url) {
        if (url != null && url.startsWith("http://")) {
            return "https://" + url.substring(7);
        }
        return url;
    }
}
