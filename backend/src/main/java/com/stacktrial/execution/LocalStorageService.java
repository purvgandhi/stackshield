package com.stacktrial.execution;

import com.stacktrial.common.StackTrialProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class LocalStorageService implements StorageService {

    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9_\\-]+(/[A-Za-z0-9_.\\-]+)*$");

    private final Path root;

    public LocalStorageService(StackTrialProperties properties) {
        this.root = properties.dataDir().resolve("artifacts").toAbsolutePath().normalize();
    }

    @Override
    public String storeText(String key, String content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store artifact " + key, e);
        }
    }

    @Override
    public Optional<String> readText(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read artifact " + key, e);
        }
    }

    private Path resolve(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches() || key.contains("..")) {
            throw new IllegalArgumentException("Invalid artifact key: " + key);
        }
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Invalid artifact key: " + key);
        }
        return p;
    }
}
