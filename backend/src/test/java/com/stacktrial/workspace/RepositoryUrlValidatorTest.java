package com.stacktrial.workspace;

import com.stacktrial.common.StackTrialProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryUrlValidatorTest {

    static RepositoryUrlValidator validator(boolean allowLocal) {
        StackTrialProperties props = new StackTrialProperties(Path.of("data"),
                new StackTrialProperties.Workspace(allowLocal, Duration.ofMinutes(1), false), null, null, null, null);
        return new RepositoryUrlValidator(props);
    }

    @Test
    void acceptsHttpsAndLocalFileRepositories() {
        assertThatCode(() -> validator(true).validate("https://github.com/acme/shop.git", "main")).doesNotThrowAnyException();
        assertThatCode(() -> validator(true).validate("file:///tmp/repos/shop", "release/1.2")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"--upload-pack=touch /tmp/x", "ext::sh -c id", "git@github.com:acme/shop.git",
            "http://github.com/acme/shop", "https://github.com/acme/../../etc", "file:///tmp/x"})
    void rejectsDangerousOrUnsupportedUrls(String url) {
        assertThatThrownBy(() -> validator(false).validate(url, "main")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-b", "--orphan", "main..dev", "a b"})
    void rejectsSuspiciousRefs(String ref) {
        assertThatThrownBy(() -> validator(true).validate("https://github.com/acme/shop", ref))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
