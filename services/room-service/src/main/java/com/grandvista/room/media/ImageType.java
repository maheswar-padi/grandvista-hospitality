package com.grandvista.room.media;

import java.util.Arrays;
import java.util.Optional;

/**
 * Accepted image formats, identified by their file signature ("magic bytes") rather than by the file name
 * or the client-supplied Content-Type, both of which an attacker controls. SVG is deliberately not
 * accepted because it can carry script.
 */
public enum ImageType {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private final String mediaType;
    private final String extension;

    ImageType(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<ImageType> detect(byte[] data) {
        if (data == null || data.length < 12) {
            return Optional.empty();
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (Arrays.equals(Arrays.copyOf(data, 8), png)) {
            return Optional.of(PNG);
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    public static Optional<ImageType> fromExtension(String extension) {
        return Arrays.stream(values()).filter(t -> t.extension.equals(extension)).findFirst();
    }
}
