package com.pyosechang.agent.core.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified memory entry — stores all types of agent knowledge.
 * Categories: storage, facility, area, event, preference, skill
 */
public class MemoryEntry {
    private String id;
    private String title;
    private String description;
    private String content;
    private String category;
    private List<String> tags;
    private MemoryLocation location;
    private String scope; // kept for backward compat (derived from visibleTo)
    private List<String> visibleTo; // empty = global (all agents), non-empty = specific agent names
    private String createdAt;
    private String updatedAt;
    private String loadedAt;

    public MemoryEntry() {
        this.tags = new ArrayList<>();
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

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }

    public MemoryLocation getLocation() { return location; }
    public void setLocation(MemoryLocation location) { this.location = location; }

    public String getScope() {
        if (visibleTo == null || visibleTo.isEmpty()) return "global";
        if (visibleTo.size() == 1) return "agent:" + visibleTo.get(0);
        return "agents:" + String.join(",", visibleTo);
    }

    /** Backward-compatible setter — converts old scope format to visibleTo */
    public void setScope(String scope) {
        this.scope = scope;
        if (scope == null || "global".equals(scope)) {
            this.visibleTo = new ArrayList<>();
        } else if (scope.startsWith("agents:")) {
            String names = scope.substring("agents:".length());
            this.visibleTo = new ArrayList<>(List.of(names.split(",")));
        } else if (scope.startsWith("agent:")) {
            this.visibleTo = new ArrayList<>(List.of(scope.substring("agent:".length())));
        } else {
            this.visibleTo = new ArrayList<>();
        }
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
     * Searches title, description, and tags (case-insensitive).
     */
    public boolean matchesQuery(String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.toLowerCase();
        if (title != null && title.toLowerCase().contains(q)) return true;
        if (description != null && description.toLowerCase().contains(q)) return true;
        if (tags != null) {
            for (String tag : tags) {
                if (tag.toLowerCase().contains(q)) return true;
            }
        }
        return false;
    }
}
