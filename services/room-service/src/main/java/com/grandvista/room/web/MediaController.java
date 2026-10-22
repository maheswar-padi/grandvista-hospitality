package com.grandvista.room.web;

import com.grandvista.platform.error.ApiException;
import com.grandvista.room.media.MediaStorage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves stored room images. Keys are immutable, so responses are cacheable for a long time. */
@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "Media")
public class MediaController {

    private final MediaStorage storage;

    public MediaController(MediaStorage storage) {
        this.storage = storage;
    }

    @Operation(summary = "Download a room image")
    @GetMapping("/{key}")
    public ResponseEntity<InputStreamResource> get(@PathVariable String key) {
        MediaStorage.MediaResource media = storage.load(key)
                .orElseThrow(() -> ApiException.notFound("MEDIA_NOT_FOUND", "Image not found."));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(media.type().mediaType()))
                .contentLength(media.sizeBytes())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'")
                .body(new InputStreamResource(media.content()));
    }
}
