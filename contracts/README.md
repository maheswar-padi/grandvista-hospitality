# Inter-service contracts

JSON examples of the internal messages exchanged between services. They are verified from **both** sides:

| Contract | Provider test (produces it) | Consumer test (reads it) |
|---|---|---|
| `room-service/hold-response.json` | `room-service` `RoomInventoryIntegrationTest.holdResponseHonoursPublishedContract` – a real hold response must contain every field of the example | `booking-service` `DownstreamContractTest` – the booking client must parse the example |
| `payment-service/payment-response.json` | `payment-service` `PaymentFlowIntegrationTest.paymentResponseAndEventHonourPublishedContracts` | `booking-service` `DownstreamContractTest` |
| `payment-service/payment-event.json` | `payment-service` `OutboxRelayContractTest` + `PaymentFlowIntegrationTest.paymentResponseAndEventHonourPublishedContracts` | `booking-service` `DownstreamContractTest` and `BookingSagaIntegrationTest` |

When a provider changes a field that a consumer relies on, one of these tests fails.
Values are illustrative; only the field names and types matter.
