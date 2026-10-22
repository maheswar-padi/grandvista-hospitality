package com.grandvista.room.media;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * @param localRoot     directory used by the local file-system store (mounted as a volume in Docker)
 * @param maxImageSize  per-upload limit, also enforced by the multipart configuration and the gateway
 */
@ConfigurationProperties("gv.media")
public record MediaProperties(String localRoot, DataSize maxImageSize) {

    public MediaProperties {
        localRoot = localRoot == null || localRoot.isBlank() ? "./var/media" : localRoot;
        maxImageSize = maxImageSize == null ? DataSize.ofMegabytes(5) : maxImageSize;
    }
}
