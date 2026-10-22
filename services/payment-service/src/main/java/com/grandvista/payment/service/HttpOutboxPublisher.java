package com.grandvista.payment.service;

import com.grandvista.payment.domain.OutboxEvent;
import com.grandvista.platform.web.CorrelationId;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Posts payment events straight to the booking-service (used when no message broker is configured). */
public class HttpOutboxPublisher implements OutboxPublisher {

    private final RestClient bookingClient;

    public HttpOutboxPublisher(RestClient bookingServiceClient) {
        this.bookingClient = bookingServiceClient;
    }

    @Override
    public String name() {
        return "booking-service (HTTP)";
    }

    @Override
    public void publish(OutboxEvent event) throws RetryableDeliveryException, RejectedDeliveryException {
        try {
            bookingClient.post().uri("/internal/v1/payment-events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(CorrelationId.HEADER, event.getCorrelationId() == null ? CorrelationId.newId() : event.getCorrelationId())
                    .body(event.getPayload())
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401 || status == 429) {
                throw new RetryableDeliveryException(ex.getStatusCode().toString(), ex);
            }
            // Any other 4xx means the consumer rejected the contract: retrying the same message cannot succeed.
            throw new RejectedDeliveryException(ex.getStatusCode() + " " + ex.getResponseBodyAsString());
        } catch (RestClientException | IllegalStateException ex) {
            throw new RetryableDeliveryException(ex.getMessage(), ex);
        }
    }
}
