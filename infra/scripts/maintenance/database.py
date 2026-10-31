"""Restricted homolog maintenance, executed inside a short-lived Kubernetes Job."""
import json
import os
import re


def validate(operation, batch, confirmation):
    if operation not in ('rotate_admin', 'preview_seed', 'delete_seed'):
        raise ValueError('Unknown operation')
    if operation != 'rotate_admin' and not re.fullmatch(r'FIAP-DEMO-[a-f0-9]{12}', batch):
        raise ValueError('An exact seed batch is required')
    if operation == 'delete_seed' and confirmation != batch:
        raise ValueError('Deletion requires the same exact batch as confirmation')


def execute(conn, operation, batch, confirmation):
    validate(operation, batch, confirmation)
    with conn.cursor() as cur:
        cur.execute("SET LOCAL lock_timeout='5s'")
        cur.execute("SET LOCAL statement_timeout='20s'")
        cur.execute('SET LOCAL search_path TO oficina_mecanica')
        if operation == 'rotate_admin':
            import bcrypt
            password = os.environ['ROTATION_PASSWORD']
            if len(password) < 8:
                raise ValueError('Password too short')
            cur.execute("SELECT id FROM app_users WHERE username='admin' AND role='ADMIN' AND active=true FOR UPDATE")
            if len(cur.fetchall()) != 1:
                raise ValueError('Expected exactly one active admin')
            encoded = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10, prefix=b'2a')).decode()
            cur.execute("UPDATE app_users SET password=%s WHERE username='admin' AND role='ADMIN' AND active=true", (encoded,))
            if cur.rowcount != 1:
                raise ValueError('Unexpected update count')
            return {'operation': operation, 'updated': 1}
        # A single transaction and exact batch; all unexpected references abort deletion.
        cur.execute('SELECT customer_id, document, first_name, email FROM customer WHERE last_name=%s FOR UPDATE', (batch,))
        rows = cur.fetchall()
        if len(rows) != 1:
            raise ValueError('Expected one seed customer')
        customer, document, first, email = rows[0]
        if first != 'FIAP Demo' or not (email or '').endswith('@example.invalid'):
            raise ValueError('Customer is not a seed fixture')
        if document == os.environ.get('PROTECTED_CUSTOMER_CPF', ''):
            raise ValueError('Authorizer fixture is protected')
        cur.execute('SELECT work_order_id, description FROM work_order WHERE customer_id=%s FOR UPDATE', (customer,))
        orders = cur.fetchall()
        if len(orders) > 150 or any(not (description or '').startswith(batch) for _, description in orders):
            raise ValueError('Non-seed work or oversized batch')
        order_ids = [row[0] for row in orders]
        cur.execute('SELECT part_id FROM parts WHERE name=%s FOR UPDATE', (batch,))
        parts = [row[0] for row in cur.fetchall()]
        if len(parts) != 1:
            raise ValueError('Expected one isolated seed part')
        cur.execute('SELECT count(*) FROM estimate_item i JOIN estimate e ON e.estimate_id=i.estimate_id WHERE e.work_order_id=ANY(%s) AND NOT (i.part_id=ANY(%s))', (order_ids, parts))
        if cur.fetchone()[0]:
            raise ValueError('Batch uses shared stock')
        cur.execute('SELECT count(*) FROM estimate_item i JOIN estimate e ON e.estimate_id=i.estimate_id WHERE i.part_id=ANY(%s) AND NOT (e.work_order_id=ANY(%s))', (parts, order_ids))
        if cur.fetchone()[0]:
            raise ValueError('Seed stock is referenced outside this batch')
        cur.execute('SELECT vehicle_id FROM vehicle WHERE customer_id=%s FOR UPDATE', (customer,))
        vehicle_ids = [row[0] for row in cur.fetchall()]
        vehicles = len(vehicle_ids)
        cur.execute('SELECT count(*) FROM work_order WHERE vehicle_id=ANY(%s) AND customer_id<>%s', (vehicle_ids, customer))
        if cur.fetchone()[0]:
            raise ValueError('Vehicle is referenced outside this batch')
        if vehicles != 1:
            raise ValueError('Unexpected vehicle count')
        counts = {'customers': 1, 'vehicles': vehicles, 'parts': len(parts), 'work_orders': len(orders)}
        if operation == 'delete_seed':
            cur.execute('DELETE FROM estimate_decision_token WHERE work_order_id=ANY(%s)', (order_ids,))
            cur.execute('DELETE FROM estimate_item WHERE estimate_id IN (SELECT estimate_id FROM estimate WHERE work_order_id=ANY(%s))', (order_ids,))
            cur.execute('DELETE FROM estimate WHERE work_order_id=ANY(%s)', (order_ids,))
            cur.execute('DELETE FROM work_order_history WHERE work_order_id=ANY(%s)', (order_ids,))
            cur.execute('DELETE FROM work_order_service WHERE work_order_id=ANY(%s)', (order_ids,))
            cur.execute('DELETE FROM work_order WHERE work_order_id=ANY(%s)', (order_ids,))
            cur.execute('DELETE FROM vehicle WHERE customer_id=%s', (customer,))
            cur.execute('DELETE FROM customer WHERE customer_id=%s', (customer,))
            cur.execute('DELETE FROM parts WHERE part_id=ANY(%s)', (parts,))
        return {'operation': operation, 'batch': batch, 'counts': counts}


def main():
    import psycopg
    operation = os.environ['MAINTENANCE_OPERATION']
    validate(operation, os.environ.get('SEED_BATCH', ''), os.environ.get('DELETE_CONFIRMATION', ''))
    try:
        with psycopg.connect(host=os.environ['INFRA_HOST_POSTGRES'], port=os.environ.get('POSTGRES_PORT', '5432'),
                              dbname=os.environ['POSTGRES_DB'], user=os.environ['POSTGRES_USERNAME'],
                              password=os.environ['POSTGRES_PASSWORD'], sslmode='require', connect_timeout=15) as conn:
            result = execute(conn, operation, os.environ.get('SEED_BATCH', ''), os.environ.get('DELETE_CONFIRMATION', ''))
        print(json.dumps(result))
    except Exception:
        print('Maintenance failed; transaction rolled back. Sensitive database details suppressed.')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
