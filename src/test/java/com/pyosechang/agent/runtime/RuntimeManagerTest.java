package com.pyosechang.agent.runtime;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeManagerTest {

    @TempDir Path tempDir;
    private Path userHome;
    private Path gameDir;

    @BeforeEach
    void setUp() {
        userHome = tempDir.resolve("home");
        gameDir = tempDir.resolve("game");
        gameDir.toFile().mkdirs();
    }

    @Nested
    class Production {

        @Test
        void resolvesUserHome_whenDistIndexJsExists() throws IOException {
            Path rt = userHome.resolve(".agent-mod/agent-runtime/dist");
            Files.createDirectories(rt);
            Files.createFile(rt.resolve("index.js"));

            Path result = RuntimeManager.resolveRuntimePath(userHome, gameDir, true);

            assertEquals(userHome.resolve(".agent-mod/agent-runtime"), result);
        }

        @Test
        void throws_whenUserHomeHasNoRuntime() {
            var ex = assertThrows(IllegalStateException.class,
                () -> RuntimeManager.resolveRuntimePath(userHome, gameDir, true));

            assertTrue(ex.getMessage().contains("agent-runtime not found"));
            assertTrue(ex.getMessage().contains("deploy"));
        }

        @Test
        void throws_whenDistExistsButNoIndexJs() throws IOException {
            Path rt = userHome.resolve(".agent-mod/agent-runtime/dist");
            Files.createDirectories(rt);
            // no index.js

            assertThrows(IllegalStateException.class,
                () -> RuntimeManager.resolveRuntimePath(userHome, gameDir, true));
        }

        @Test
        void ignoresDevPath_evenIfExists() throws IOException {
            // dev path exists but should NOT be used in prod
            Path dev = gameDir.resolve("../agent-runtime/dist");
            Files.createDirectories(dev);
            Files.createFile(dev.resolve("index.js"));

            assertThrows(IllegalStateException.class,
                () -> RuntimeManager.resolveRuntimePath(userHome, gameDir, true));
        }
    }

    @Nested
    class Dev {

        @Test
        void resolvesDevPath_whenDistIndexJsExists() throws IOException {
            Path dev = gameDir.resolve("../agent-runtime/dist");
            Files.createDirectories(dev);
            Files.createFile(dev.resolve("index.js"));

            Path result = RuntimeManager.resolveRuntimePath(userHome, gameDir, false);

            assertEquals(gameDir.resolve("../agent-runtime").normalize(), result);
        }

        @Test
        void throws_whenDevPathHasNoRuntime() {
            var ex = assertThrows(IllegalStateException.class,
                () -> RuntimeManager.resolveRuntimePath(userHome, gameDir, false));

            assertTrue(ex.getMessage().contains("agent-runtime not found"));
            assertTrue(ex.getMessage().contains("npm run build"));
        }

        @Test
        void ignoresUserHome_evenIfExists() throws IOException {
            // user home exists but should NOT be used in dev
            Path userRt = userHome.resolve(".agent-mod/agent-runtime/dist");
            Files.createDirectories(userRt);
            Files.createFile(userRt.resolve("index.js"));

            assertThrows(IllegalStateException.class,
                () -> RuntimeManager.resolveRuntimePath(userHome, gameDir, false));
        }
    }
}
