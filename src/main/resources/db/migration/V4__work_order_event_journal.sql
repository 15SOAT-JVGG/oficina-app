-- Hibernate creates work_order after Flyway on a fresh installation. Work-order
-- references are validated by the application within the enclosing transaction.
CREATE TABLE oficina_mecanica.work_order_outbox (
    sequence_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    work_order_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    traceparent VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX ix_work_order_outbox_pending
    ON oficina_mecanica.work_order_outbox (work_order_id, sequence_id)
    WHERE published_at IS NULL;

CREATE TABLE oficina_mecanica.work_order_estimate_projection (
    work_order_id UUID PRIMARY KEY,
    opening_event_id UUID NOT NULL UNIQUE REFERENCES oficina_mecanica.work_order_outbox(event_id),
    saga_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    status VARCHAR(20),
    estimate_id UUID,
    estimate_data JSONB,
    failure_code TEXT,
    failure_message TEXT,
    CONSTRAINT ck_estimate_projection_status CHECK (status IS NULL OR status IN ('AGUARDANDO', 'CRIADO', 'FALHOU')),
    CONSTRAINT ck_estimate_projection_result CHECK (COALESCE((
        (status IS NULL AND estimate_id IS NULL AND estimate_data IS NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'AGUARDANDO' AND estimate_id IS NULL AND estimate_data IS NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'CRIADO' AND estimate_id IS NOT NULL AND estimate_data IS NOT NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'FALHOU' AND estimate_id IS NULL AND estimate_data IS NULL AND failure_code IS NOT NULL AND failure_message IS NOT NULL)
    ), false))
);

CREATE TABLE oficina_mecanica.work_order_inbox (
    event_id UUID PRIMARY KEY,
    work_order_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
