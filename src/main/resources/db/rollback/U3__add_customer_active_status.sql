ALTER TABLE IF EXISTS oficina_mecanica.customer
    DROP COLUMN IF EXISTS active;

DO $$
BEGIN
    IF to_regclass('oficina_mecanica.flyway_schema_history') IS NOT NULL THEN
        DELETE FROM oficina_mecanica.flyway_schema_history
        WHERE version = '3'
          AND script = 'V3__add_customer_active_status.sql';
    END IF;
END $$;
