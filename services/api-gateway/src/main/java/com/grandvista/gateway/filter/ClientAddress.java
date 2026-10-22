package com.grandvista.gateway.filter;

import java.net.InetSocketAddress;
import org.springframework.http.server.reactive.ServerHttpRequest;

/** Client IP for rate limiting. Forwarded headers are honoured only when the gateway sits behind a trusted proxy. */
final class ClientAddress {

    private ClientAddress() {
    }

    static String of(ServerHttpRequest request, boolean trustForwardedFor) {
        if (trustForwardedFor) {
            String xff = request.getHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // the right-most entry was added by our own trusted proxy; left entries are client-controlled
                String[] parts = xff.split(",");
                return parts[parts.length - 1].trim();
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
