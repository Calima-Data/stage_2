package com.calima.search.query;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU cache of search answers, shared by the request threads of one process.
 *
 * <p>Each entry remembers the index version it was computed from, and a lookup only hits for the
 * version the current snapshot reads. Publishing a new version therefore invalidates the cache
 * with no notification that could be lost, in every replica independently.
 */
public final class ResultCache {

    private final int capacity;
    private final Map<SearchRequest, SearchEngine.Answer> entries;

    public ResultCache(int capacity) {
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<SearchRequest, SearchEngine.Answer> eldest) {
                return size() > ResultCache.this.capacity;
            }
        };
    }

    public synchronized SearchEngine.Answer get(SearchRequest request, long version) {
        SearchEngine.Answer answer = entries.get(request);
        return answer != null && answer.version() == version ? answer : null;
    }

    public synchronized void put(SearchRequest request, SearchEngine.Answer answer) {
        if (capacity > 0) {
            entries.put(request, answer);
        }
    }
}
