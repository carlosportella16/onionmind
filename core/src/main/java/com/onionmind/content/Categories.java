package com.onionmind.content;

import java.util.Set;

/**
 * The classifier's fixed taxonomy (fase3-sdd 7.6). Public because the change query
 * (intelligence) validates its {@code category} filter against it.
 */
public final class Categories {

    public static final Set<String> TAXONOMY =
        Set.of("marketplace", "forum", "blog", "service", "institutional", "other");

    private Categories() {
    }
}
