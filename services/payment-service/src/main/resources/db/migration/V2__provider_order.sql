-- Providers that use orders (Razorpay) create one before the customer pays; webhooks reference it.
ALTER TABLE payments ADD COLUMN provider_order_id VARCHAR(80);
CREATE UNIQUE INDEX ux_payments_provider_order ON payments (provider, provider_order_id)
    WHERE provider_order_id IS NOT NULL;
