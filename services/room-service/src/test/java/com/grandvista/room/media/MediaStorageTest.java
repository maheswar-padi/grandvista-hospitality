package com.grandvista.room.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

public class MediaStorageTest {

    public static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    @TempDir
    Path dir;

    @Test
    void detectsFormatsByMagicBytesNotByName() {
        assertThat(ImageType.detect(PNG)).contains(ImageType.PNG);
        assertThat(ImageType.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0}))
                .contains(ImageType.JPEG);
        assertThat(ImageType.detect("RIFF0000WEBPVP8 ".getBytes())).contains(ImageType.WEBP);
        assertThat(ImageType.detect("<svg onload=alert(1)>".getBytes())).isEmpty();
        assertThat(ImageType.detect("GIF89a-------".getBytes())).isEmpty();
        assertThat(ImageType.detect(new byte[3])).isEmpty();
    }

    @Test
    void storesUnderGeneratedKeyAndRejectsTraversalKeys() throws Exception {
        var storage = new LocalFileSystemMediaStorage(new MediaProperties(dir.toString(), DataSize.ofMegabytes(1)));
        var stored = storage.store(PNG, ImageType.PNG);

        assertThat(stored.key()).matches("^[0-9a-f-]{36}\\.png$");
        assertThat(Files.exists(dir.resolve(stored.key()))).isTrue();
        try (var loaded = storage.load(stored.key()).orElseThrow().content()) {
            assertThat(loaded.readAllBytes()).isEqualTo(PNG);
        }

        Files.writeString(dir.getParent().resolve("secret.txt"), "top secret");
        for (String evil : new String[] {"../secret.txt", "..%2Fsecret.txt", "/etc/passwd", "a.png", "",
                stored.key().replace(".png", ".svg")}) {
            assertThat(storage.load(evil)).as(evil).isEmpty();
        }

        storage.delete(stored.key());
        assertThat(storage.load(stored.key())).isEmpty();
    }
}
