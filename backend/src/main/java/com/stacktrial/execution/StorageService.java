package com.stacktrial.execution;

import java.util.Optional;

/**
 * Where execution artifacts (logs, dependency trees) live. Keys look like {@code trials/{id}/BASELINE/build.log}.
 * The local implementation writes to disk; an object-store implementation could replace it without touching callers.
 */
public interface StorageService {

    /** @return the key the content was stored under */
    String storeText(String key, String content);

    Optional<String> readText(String key);
}
