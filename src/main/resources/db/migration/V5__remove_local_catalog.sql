DO $$
DECLARE
    referencing_constraint RECORD;
BEGIN
    FOR referencing_constraint IN
        SELECT constraint_row.conrelid::regclass AS table_name,
               constraint_row.conname AS constraint_name
        FROM pg_constraint constraint_row
        WHERE constraint_row.contype = 'f'
          AND constraint_row.confrelid IN (
              to_regclass('oficina_mecanica.parts'),
              to_regclass('oficina_mecanica.service_item')
          )
    LOOP
        EXECUTE format(
            'ALTER TABLE %s DROP CONSTRAINT %I',
            referencing_constraint.table_name,
            referencing_constraint.constraint_name
        );
    END LOOP;
END
$$;

DROP TABLE IF EXISTS oficina_mecanica.parts;
DROP TABLE IF EXISTS oficina_mecanica.service_item;
