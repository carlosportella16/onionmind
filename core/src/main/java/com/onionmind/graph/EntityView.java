package com.onionmind.graph;

import java.util.List;

/** Response of {@code GET /api/entities/{type}/{key}}. */
public record EntityView(String type, String key, String display, List<PageMention> pages, List<Related> related) {

    public record PageMention(String url, String host, double confidence, int version) {}

    /** An entity co-occurring with this one, counted by pages in common (design D6). */
    public record Related(String type, String key, String display, int sharedPages) {}
}
