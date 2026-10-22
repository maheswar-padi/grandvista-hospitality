package com.grandvista.payment.web;

import com.grandvista.payment.provider.WebhookSignature;
import com.grandvista.payment.service.WebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Provider callback endpoint. The body is read as raw bytes so the HMAC is verified over exactly what was sent. */
@RestController
@RequestMapping("/api/v1/payments/webhooks")
@Tag(name = "Provider webhooks")
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Operation(summary = "SIMULATED provider callback (HMAC-SHA256 signed, timestamped, deduplicated)")
    @PostMapping(path = "/simulated", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> simulated(@RequestBody byte[] body,
            @RequestHeader(value = WebhookSignature.HEADER, required = false) String signature) {
        return Map.of("result", webhooks.handleSimulated(body, signature).name());
    }
}
