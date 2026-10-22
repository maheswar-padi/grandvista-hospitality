package com.grandvista.room.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Development/single-node media store. Files are named {@code <uuid>.<ext>} by the server; any key that
 * does not match that exact shape is rejected before touching the file system, and resolved paths are
 * checked to stay inside the root directory, which rules out path traversal.
 */
@Component
public class LocalFileSystemMediaStorage implements MediaStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileSystemMediaStorage.class);
    private static final Pattern KEY = Pattern.compile("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.(jpg|png|webp)$");

    private final Path root;

    public LocalFileSystemMediaStorage(MediaProperties properties) {
        this.root = Path.of(properties.localRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot create media directory " + root, ex);
        }
        log.info("Local media storage at {}", root);
    }

    @Override
    public StoredMedia store(byte[] content, ImageType type) {
        String key = UUID.randomUUID() + "." + type.extension();
        Path target = resolve(key).orElseThrow();
        try {
            Path temp = Files.createTempFile(root, ".upload-", ".tmp");
            Files.write(temp, content, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, target);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to store media", ex);
        }
        return new StoredMedia(key, type, content.length);
    }

    @Override
    public Optional<MediaResource> load(String key) {
        Matcher matcher = KEY.matcher(key == null ? "" : key);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return resolve(key).filter(Files::isRegularFile).map(path -> {
            try {
                return new MediaResource(key, ImageType.fromExtension(matcher.group(2)).orElseThrow(), Files.size(path),
                        Files.newInputStream(path));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    @Override
    public void delete(String key) {
        resolve(key).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ex) {
                log.warn("Could not delete media {}: {}", key, ex.getMessage());
            }
        });
    }

    Optional<Path> resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        Path path = root.resolve(key).normalize();
        return path.startsWith(root) ? Optional.of(path) : Optional.empty();
    }
}
