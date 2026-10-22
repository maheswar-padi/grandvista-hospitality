package com.grandvista.booking.client;

import com.grandvista.platform.client.RemoteProblem;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import com.grandvista.platform.client.ServiceCallException;
import java.util.function.Supplier;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Runs a remote call through the service's Resilience4j retry and circuit breaker, then translates errors:
 * 4xx → {@link DownstreamException} (a business answer); transport/5xx/open circuit → {@link ServiceCallException},
 * which the API reports as 503 DEPENDENCY_UNAVAILABLE (the fallback: fail fast, nothing half-done).
 */
final class RemoteCalls {

    private RemoteCalls() {
    }

    static <T> T call(String service, String operation, Resilience resilience, Supplier<T> action) {
        try {
            return resilience.call(service, action);
        } catch (CallNotPermittedException ex) {
            throw new ServiceCallException(service, operation + " not attempted: circuit breaker is open", ex);
        } catch (HttpClientErrorException ex) {
            throw new DownstreamException(service, RemoteProblem.from(ex));
        } catch (RestClientException | IllegalStateException ex) {
            // IllegalStateException: load balancer found no instance of the service
            throw new ServiceCallException(service, operation + " failed: " + ex.getMessage(), ex);
        }
    }
}
