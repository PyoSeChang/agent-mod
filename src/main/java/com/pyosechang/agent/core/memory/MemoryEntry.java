package com.pyosechang.agent.core.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Base memory entry — stores all types of agent knowledge.
 * Subclasses add category-specific fields (location, schedule config, etc.).
 */
public class MemoryEntry {
    private String id;
    private String title;
    private String description;
    private String content;
    private String category;
    private List<String> visibleTo;
    private String createdAt;
    private String updatedAt;
    private String loadedAt;

    public MemoryEntry() {
        this.visibleTo = new ArrayList<>();
        String now = Instant.now().toString();
        this.createdAt = now;
        this.updatedAt = now;
    }

    // Getters & setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getScope() {
        if (visibleTo == null || visibleTo.isEmpty()) return "global";
        if (visibleTo.size() == 1) return "agent:" + visibleTo.get(0);
        return "agents:" + String.join(",", visibleTo);
    }

    public List<String> getVisibleTo() { return visibleTo != null ? visibleTo : List.of(); }

    public void setVisibleTo(List<String> visibleTo) {
        this.visibleTo = visibleTo != null ? new ArrayList<>(visibleTo) : new ArrayList<>();
    }

    public boolean isVisibleTo(String agentName) {
        if (visibleTo == null || visibleTo.isEmpty()) return true;
        return visibleTo.contains(agentName);
    }

    public boolean isGlobal() { return visibleTo == null || visibleTo.isEmpty(); }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public String getLoadedAt() { return loadedAt; }
    public void setLoadedAt(String loadedAt) { this.loadedAt = loadedAt; }

    public void markUpdated() {
        this.updatedAt = Instant.now().toString();
    }

    public void markLoaded() {
        this.loadedAt = Instant.now().toString();
    }

    /**
     * Check if this entry matches a keyword search query.
     * Searches title and description (case-insensitive).
     */
    public boolean matchesQuery(String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.toLowerCase();
        if (title != null && title.toLowerCase().contains(q)) return true;
        if (description != null && description.toLowerCase().contains(q)) return true;
        return false;
    }
}
