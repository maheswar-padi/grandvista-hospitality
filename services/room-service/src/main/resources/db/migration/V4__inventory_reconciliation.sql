-- Stock reconciliation: one row per run per property. Findings are kept as a JSON report so the latest run
-- can be shown as-is and the next run can tell new findings from ones already logged.
CREATE TABLE inventory_reconciliation_runs (
    id                  UUID PRIMARY KEY,
    property_id         UUID         NOT NULL REFERENCES properties (id),
    trigger_type        VARCHAR(12)  NOT NULL CHECK (trigger_type IN ('SCHEDULED', 'MANUAL')),
    status              VARCHAR(12)  NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
    started_at          TIMESTAMPTZ  NOT NULL,
    finished_at         TIMESTAMPTZ  NOT NULL,
    allocations_checked INTEGER      NOT NULL DEFAULT 0,
    bookings_checked    INTEGER      NOT NULL DEFAULT 0,
    finding_count       INTEGER      NOT NULL DEFAULT 0,
    released_count      INTEGER      NOT NULL DEFAULT 0,
    findings            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    error               VARCHAR(300),
    actor               VARCHAR(120) NOT NULL
);

CREATE INDEX ix_reconciliation_runs_property_started ON inventory_reconciliation_runs (property_id, started_at DESC);
