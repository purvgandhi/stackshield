package com.stacktrial.trial;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Copies a fixture project into a fresh local Git repository so tests can clone it via file://. */
final class FixtureRepository {

    private FixtureRepository() {
    }

    static String create(String fixture, Path target) throws Exception {
        Path source = Path.of("..", "fixtures", fixture).toAbsolutePath().normalize();
        try (var files = Files.walk(source)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path rel = source.relativize(p);
                if (rel.toString().startsWith("target")) continue;
                Path dest = target.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest);
            }
        }
        run(target, "git", "init", "-q", "-b", "main");
        run(target, "git", "add", "-A");
        run(target, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "baseline");
        return "file://" + target.toAbsolutePath();
    }

    private static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(List.of(cmd)).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(out);
    }
}
