package com.grandvista.booking.client;

import com.grandvista.platform.client.RemoteProblem;
import com.grandvista.platform.client.Retrier;
import com.grandvista.platform.client.ServiceCallException;
import java.util.function.Supplier;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/** Shared error translation: 4xx → {@link DownstreamException}; transport/5xx → {@link ServiceCallException}. */
final class RemoteCalls {

    private RemoteCalls() {
    }

    static <T> T call(String service, String operation, Retrier retrier, Supplier<T> action) {
        try {
            return retrier.call(service + " " + operation, action, Retrier.TRANSIENT_HTTP_FAILURE);
        } catch (HttpClientErrorException ex) {
            throw new DownstreamException(service, RemoteProblem.from(ex));
        } catch (RestClientException | IllegalStateException ex) {
            // IllegalStateException: load balancer found no instance of the service
            throw new ServiceCallException(service, operation + " failed: " + ex.getMessage(), ex);
        }
    }
}
