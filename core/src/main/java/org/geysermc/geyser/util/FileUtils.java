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

package org.geysermc.geyser.util;

import org.geysermc.geyser.GeyserBootstrap;
import org.geysermc.geyser.GeyserImpl;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Stream;

public final class FileUtils {
    public static <T> T loadConfig(File src, Class<T> valueType) throws IOException {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
            .file(src)
            .build();
        ConfigurationNode node = loader.load();
        return node.get(valueType);
    }

    public static <T> T loadJson(InputStream src, Class<T> valueType) {
        // Read specifically with UTF-8 to allow any non-UTF-encoded JSON to read
        return GeyserImpl.GSON.fromJson(new InputStreamReader(src, StandardCharsets.UTF_8), valueType);
    }

    /**
     * Open the specified file or copy if from resources
     *
     * @param file File to open
     * @param name Name of the resource get if needed
     * @param format Formatting callback
     * @return File handle of the specified file
     * @throws IOException if the file failed to copy from resource
     */
    public static File fileOrCopiedFromResource(File file, String name, Function<String, String> format, GeyserBootstrap bootstrap) throws IOException {
        if (!file.exists()) {
            try (InputStream input = bootstrap.getResourceOrThrow(name)) {
                String text = format.apply(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
            }
        }

        return file;
    }

    /**
     * Open the specified file or copy if from resources
     *
     * @param file File to open
     * @param name Name of the resource get if needed
     * @return File handle of the specified file
     * @throws IOException if the file failed to copy from resource
     */
    public static File fileOrCopiedFromResource(File file, String name, GeyserBootstrap bootstrap) throws IOException {
        return fileOrCopiedFromResource(file, name, Function.identity(), bootstrap);
    }

    /**
     * Writes the given data to the specified file on disk
     *
     * @param file File to write to
     * @param data Data to write to the file
     * @throws IOException if the file failed to write
     */
    public static void writeFile(File file, char[] data) throws IOException {
        Files.writeString(file.toPath(), new String(data), StandardCharsets.UTF_8);
    }

    /**
     * Writes the given data to the specified file on disk
     *
     * @param name File path to write to
     * @param data Data to write to the file
     * @throws IOException if the file failed to write
     */
    public static void writeFile(String name, char[] data) throws IOException {
        writeFile(new File(name), data);
    }

    /**
     * Calculate the SHA256 hash of a file
     *
     * @param path Path to calculate the hash for
     * @return A byte[] representation of the hash
     */
    public static byte[] calculateSHA256(Path path) {
        return calculateHash(path, "SHA-256");
    }

    /**
     * Calculate the SHA1 hash of a file
     *
     * @param path Path to calculate the hash for
     * @return A byte[] representation of the hash
     */
    public static byte[] calculateSHA1(Path path) {
        return calculateHash(path, "SHA-1");
    }

    /**
     * Hashes the file in small chunks. Reading it into one large array would make the JDK cache an equally large
     * direct buffer on the calling thread, which for big resource packs adds up across threads.
     */
    private static byte[] calculateHash(Path path, String algorithm) {
        try (InputStream stream = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (Exception e) {
            throw new RuntimeException("Could not calculate pack hash", e);
        }
    }

    /**
     * @param resource the internal resource to read off from
     * @return the byte array of an InputStream
     */
    public static byte[] readAllBytes(String resource) {
        try (InputStream stream = GeyserImpl.getInstance().getBootstrap().getResourceOrThrow(resource)) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Error while trying to read internal input stream!", e);
        }
    }

    /**
     * @param resource the internal resource to read off from
     * 
     * @return the contents decoded as a UTF-8 String
     */
    public static String readToString(String resource) {
        return new String(readAllBytes(resource), StandardCharsets.UTF_8);
    }

    /**
     * Read the lines of a file and return it as a stream
     *
     * @param path File path to read
     * @return The lines as a stream
     */
    public static Stream<String> readAllLines(Path path) {
        try {
            return new BufferedReader(new InputStreamReader(Files.newInputStream(path))).lines();
        } catch (IOException e) {
            throw new RuntimeException("Error while trying to read file!", e);
        }
    }

    /**
     * Reads the end of a file without loading the whole file into memory.
     * At most the last {@code maxBytes} bytes are read; if the file is larger than that, the (likely partial)
     * first line of the read region is dropped. The result is then limited to the last {@code maxLines} lines.
     * Line separators are kept as they are in the file.
     *
     * @param path the file to read
     * @param maxBytes the maximum number of bytes to read from the end of the file
     * @param maxLines the maximum number of lines to return
     * @return the tail of the file, decoded as UTF-8; at most {@code maxBytes} bytes (encoded) and {@code maxLines} lines
     * @throws IOException if the file could not be read
     */
    public static String readTail(Path path, long maxBytes, int maxLines) throws IOException {
        if (maxBytes < 0 || maxLines < 0) {
            throw new IllegalArgumentException("maxBytes and maxLines must not be negative");
        }

        byte[] bytes;
        boolean truncated;
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            long size = channel.size();
            long start = Math.max(0, size - Math.min(maxBytes, Integer.MAX_VALUE - 8));
            truncated = start > 0;
            channel.position(start);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) {
                    break;
                }
            }
            bytes = buffer.array();
            if (buffer.hasRemaining()) {
                // File shrank while reading
                bytes = Arrays.copyOf(bytes, buffer.position());
            }
        }

        int from = 0;
        int end = bytes.length;
        if (truncated) {
            // Skip the partial first line
            int newline = indexOf(bytes, (byte) '\n', 0, end);
            if (newline != -1) {
                from = newline + 1;
            } else {
                // One very long line; at least don't start in the middle of a UTF-8 sequence
                while (from < end && (bytes[from] & 0xC0) == 0x80) {
                    from++;
                }
            }
        }

        if (maxLines == 0) {
            return "";
        }

        // Count lines backwards; a trailing line separator terminates the last line rather than starting a new one
        int lines = 0;
        int scanEnd = end > from && bytes[end - 1] == '\n' ? end - 1 : end;
        for (int i = scanEnd - 1; i >= from; i--) {
            if (bytes[i] == '\n' && ++lines == maxLines) {
                from = i + 1;
                break;
            }
        }

        return new String(bytes, from, end - from, StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] bytes, byte value, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private FileUtils() {
    }
}
