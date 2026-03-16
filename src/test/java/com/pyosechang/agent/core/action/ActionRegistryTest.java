package com.pyosechang.agent.core.action;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ActionRegistryTest {

    private ActionRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = ActionRegistry.getInstance();

        // Clear internal maps via reflection so each test starts clean
        clearField("syncActions");
        clearField("asyncFactories");
        clearField("allNames");
    }

    private void clearField(String fieldName) throws Exception {
        Field f = ActionRegistry.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        Object value = f.get(registry);
        if (value instanceof Map<?, ?>) {
            ((Map<?, ?>) value).clear();
        } else if (value instanceof List<?>) {
            ((List<?>) value).clear();
        }
    }

    // ----- mock implementations -----

    static class MockSyncAction implements Action {
        @Override public String getName() { return "mock_sync"; }
        @Override public JsonObject execute(ServerPlayer agent, JsonObject params) {
            return new JsonObject();
        }
    }

    public static class MockAsyncAction implements AsyncAction {
        @Override public String getName() { return "mock_async"; }
        @Override public CompletableFuture<JsonObject> start(ServerPlayer agent, JsonObject params) {
            return CompletableFuture.completedFuture(new JsonObject());
        }
        @Override public void tick(ServerPlayer agent) {}
        @Override public void cancel() {}
        @Override public boolean isActive() { return false; }
    }

    // ----- tests -----

    @Test
    void registerSync_get_returnsSameInstance() {
        MockSyncAction action = new MockSyncAction();
        registry.register(action);

        assertSame(action, registry.get("mock_sync"));
    }

    @Test
    void registerAsync_createAsync_returnsNewInstanceEachCall() {
        registry.registerAsync(MockAsyncAction.class);

        AsyncAction first = registry.createAsync("mock_async");
        AsyncAction second = registry.createAsync("mock_async");

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second);
    }

    @Test
    void isAsync_syncAction_returnsFalse() {
        registry.register(new MockSyncAction());
        assertFalse(registry.isAsync("mock_sync"));
    }

    @Test
    void isAsync_asyncAction_returnsTrue() {
        registry.registerAsync(MockAsyncAction.class);
        assertTrue(registry.isAsync("mock_async"));
    }

    @Test
    void get_unknownName_returnsNull() {
        assertNull(registry.get("nonexistent"));
    }

    @Test
    void createAsync_unknownName_returnsNull() {
        assertNull(registry.createAsync("nonexistent"));
    }

    @Test
    void listNames_returnsAllRegistered() {
        registry.register(new MockSyncAction());
        registry.registerAsync(MockAsyncAction.class);

        List<String> names = registry.listNames();
        assertTrue(names.contains("mock_sync"));
        assertTrue(names.contains("mock_async"));
        assertEquals(2, names.size());
    }

    @Test
    void get_asyncAction_createsFreshInstance() {
        registry.registerAsync(MockAsyncAction.class);

        // get() for an async name delegates to createAsync(), so two calls return different objects
        Action first = registry.get("mock_async");
        Action second = registry.get("mock_async");

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second);
    }

    @Test
    void registerSync_duplicateName_doesNotDuplicateInListNames() {
        MockSyncAction action = new MockSyncAction();
        registry.register(action);
        registry.register(action);

        List<String> names = registry.listNames();
        assertEquals(1, names.size());
    }
}
