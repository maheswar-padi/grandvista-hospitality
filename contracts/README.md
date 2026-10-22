# Inter-service contracts

JSON examples of the internal messages exchanged between services. They are verified from **both** sides:

| Contract | Provider test (produces it) | Consumer test (reads it) |
|---|---|---|
| `room-service/hold-response.json` | `room-service` `RoomInventoryIntegrationTest.holdResponseHonoursPublishedContract` – a real hold response must contain every field of the example | `booking-service` `DownstreamContractTest` – the booking client must parse the example |
| `payment-service/payment-response.json` | `payment-service` `PaymentFlowIntegrationTest.paymentResponseAndEventHonourPublishedContracts` | `booking-service` `DownstreamContractTest` |
| `payment-service/payment-event.json` (RabbitMQ `payment.succeeded` / `payment.failed`) | `payment-service` `OutboxRelayContractTest` + `PaymentFlowIntegrationTest.paymentResponseAndEventHonourPublishedContracts` | `booking-service` `DownstreamContractTest`, `BookingSagaIntegrationTest`, `BookingMessagingIntegrationTest`; `notification-service` `EventContractTest` |
| `booking-service/booking-event.json` (RabbitMQ `booking.created` / `.confirmed` / `.cancelled` / `.hold-expired`) | `booking-service` `BookingSagaIntegrationTest.happyPath…` (outbox payload has every field) | `notification-service` `EventContractTest`, `NotificationFlowIntegrationTest` |

When a provider changes a field that a consumer relies on, one of these tests fails.
Values are illustrative; only the field names and types matter.

## Events on RabbitMQ

All events go to the durable topic exchange `grandvista.events` as persistent JSON messages
(`message-id` = `eventId`, `type` = `eventType`, header `X-Correlation-Id`). Producers write them to a
transactional outbox first and publish with publisher confirms (`mandatory`), so an event is published if and only
if its state change was committed, at least once.

| Event (`eventType`) | Routing key | Producer | Consumers (queue) |
|---|---|---|---|
| `BOOKING_CREATED` (booking accepted, room held, awaiting payment) | `booking.created` | booking-service | notification-service (`notification.events`) |
| `BOOKING_CONFIRMED` | `booking.confirmed` | booking-service | notification-service |
| `BOOKING_CANCELLED` | `booking.cancelled` | booking-service | notification-service |
| `HOLD_EXPIRED` (unpaid booking expired, hold released) | `booking.hold-expired` | booking-service | notification-service |
| `PAYMENT_SUCCEEDED` | `payment.succeeded` | payment-service | booking-service (`booking.payment-events`), notification-service |
| `PAYMENT_FAILED` | `payment.failed` | payment-service | booking-service, notification-service |

Consumers de-duplicate by `eventId` (booking: `processed_events`; notification: unique `(event_id, notification_type)`),
retry failures with exponential backoff and then dead-letter them to `<queue>.dlq` via `grandvista.events.dlx`.
Adding a field is backwards compatible (consumers ignore unknown fields); removing or renaming one requires a new
`schemaVersion`.
