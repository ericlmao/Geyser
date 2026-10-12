/*
 * Copyright (c) 2019-2026 GeyserMC. http://geysermc.org
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

package org.geysermc.geyser.extension;

import org.geysermc.geyser.GeyserBootstrap;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class GeyserExtensionClassLoaderTest {
    @TempDir
    Path tempDir;

    private MockedStatic<GeyserImpl> geyserMock;
    private GeyserExtensionLoader extensionLoader;
    private Path emptyJar;
    private Path ownerJar;

    @BeforeEach
    void setUp() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "requires a JDK");

        GeyserImpl geyser = mock(GeyserImpl.class);
        GeyserBootstrap bootstrap = mock(GeyserBootstrap.class);
        when(bootstrap.getConfigFolder()).thenReturn(tempDir);
        when(geyser.getBootstrap()).thenReturn(bootstrap);
        when(geyser.getLogger()).thenReturn(mock(GeyserLogger.class));
        geyserMock = mockStatic(GeyserImpl.class);
        geyserMock.when(GeyserImpl::getInstance).thenReturn(geyser);

        extensionLoader = new GeyserExtensionLoader();

        Path src = Files.createDirectories(tempDir.resolve("src/fixture"));
        Files.writeString(src.resolve("Shared.java"), "package fixture; public class Shared {}");
        Files.writeString(src.resolve("Extra.java"), "package fixture; public class Extra {}");
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        assertEquals(0, compiler.run(null, null, null, "-d", classes.toString(),
            src.resolve("Shared.java").toString(), src.resolve("Extra.java").toString()));

        emptyJar = jar(tempDir.resolve("empty.jar"), classes, List.of());
        ownerJar = jar(tempDir.resolve("owner.jar"), classes, List.of("fixture/Shared.class", "fixture/Extra.class"));
    }

    @AfterEach
    void tearDown() {
        if (geyserMock != null) {
            geyserMock.close();
        }
    }

    @Test
    void lookupContinuesPastLoadersWithoutTheClass() throws Exception {
        GeyserExtensionClassLoader empty = classLoader(emptyJar, "a");
        GeyserExtensionClassLoader owner = classLoader(ownerJar, "b");
        GeyserExtensionClassLoader requester = classLoader(emptyJar, "c");
        Map<String, GeyserExtensionClassLoader> loaders = classLoaders();
        loaders.put("a", empty);
        loaders.put("b", owner);
        loaders.put("c", requester);
        // The loader lacking the class must be visited before the owner for this test to mean anything
        assertEquals("a", loaders.keySet().iterator().next());

        Class<?> shared = extensionLoader.classByName("fixture.Shared");
        assertNotNull(shared);
        assertSame(owner, shared.getClassLoader());

        assertSame(shared, requester.loadClass("fixture.Shared"));
        assertNull(extensionLoader.classByName("fixture.Missing"));
        assertThrows(ClassNotFoundException.class, () -> requester.loadClass("fixture.Missing"));
    }

    @Test
    void shutdownClosesLoaders() throws Exception {
        GeyserExtensionClassLoader owner = classLoader(ownerJar, "owner");
        classLoaders().put("owner", owner);
        assertNotNull(extensionLoader.classByName("fixture.Shared"));

        extensionLoader.shutdown();

        assertTrue(classLoaders().isEmpty());
        assertNull(extensionLoader.classByName("fixture.Shared"));
        // A closed URLClassLoader can no longer read its jar
        assertNull(owner.findResource("fixture/Extra.class"));
        assertThrows(ClassNotFoundException.class, () -> owner.loadClass("fixture.Extra"));
    }

    @Test
    void shutdownLetsLoadersBeCollected() throws Exception {
        WeakReference<ClassLoader> ref = registerAndLoad();

        extensionLoader.shutdown();

        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(ref.get(), "extension classloader still reachable after shutdown");
    }

    private WeakReference<ClassLoader> registerAndLoad() throws Exception {
        GeyserExtensionClassLoader owner = classLoader(ownerJar, "owner");
        GeyserExtensionClassLoader other = classLoader(emptyJar, "other");
        classLoaders().put("owner", owner);
        classLoaders().put("other", other);
        // Populates both the global cache and the requester's cache with the owner's class
        assertSame(owner, other.loadClass("fixture.Shared").getClassLoader());
        return new WeakReference<>(owner);
    }

    private GeyserExtensionClassLoader classLoader(Path jar, String id) throws IOException {
        GeyserExtensionDescription description = new GeyserExtensionDescription(id, id, "fixture.Main", 2, 9, 0, "1.0.0", List.of(), Map.of());
        return new GeyserExtensionClassLoader(extensionLoader, getClass().getClassLoader(), jar, description);
    }

    @SuppressWarnings("unchecked")
    private Map<String, GeyserExtensionClassLoader> classLoaders() throws Exception {
        Field field = GeyserExtensionLoader.class.getDeclaredField("classLoaders");
        field.setAccessible(true);
        return (Map<String, GeyserExtensionClassLoader>) field.get(extensionLoader);
    }

    private static Path jar(Path jar, Path classes, List<String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out)) {
            jarOut.putNextEntry(new JarEntry("extension.yml"));
            jarOut.write("id: test".getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
            for (String entry : entries) {
                jarOut.putNextEntry(new JarEntry(entry));
                jarOut.write(Files.readAllBytes(classes.resolve(entry)));
                jarOut.closeEntry();
            }
        }
        return jar;
    }
}
