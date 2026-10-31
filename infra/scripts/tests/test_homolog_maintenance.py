import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location('maintenance', Path(__file__).parents[1] / 'maintenance/database.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MaintenanceTests(unittest.TestCase):
    def test_deletion_requires_exact_batch_twice(self):
        module.validate('preview_seed', 'FIAP-DEMO-012345abcdef', '')
        module.validate('delete_seed', 'FIAP-DEMO-012345abcdef', 'FIAP-DEMO-012345abcdef')
        for operation, batch, confirmation in [('delete_seed', 'FIAP-DEMO-012345abcdef', ''), ('preview_seed', '%', ''), ('preview_seed', 'FIAP-CUSTOMER-x', ''), ('arbitrary', '', '')]:
            with self.assertRaises(ValueError):
                module.validate(operation, batch, confirmation)

    def test_protected_authorizer_customer_aborts_before_writes(self):
        conn = MagicMock()
        cur = conn.cursor.return_value.__enter__.return_value
        cur.fetchall.return_value = [('id', 'protected-fixture', 'FIAP Demo', 'demo@example.invalid')]
        with patch.dict(os.environ, {'PROTECTED_CUSTOMER_CPF': 'protected-fixture'}), self.assertRaises(ValueError):
            module.execute(conn, 'delete_seed', 'FIAP-DEMO-012345abcdef', 'FIAP-DEMO-012345abcdef')
        self.assertFalse(any(call.args[0].startswith(('DELETE', 'UPDATE')) for call in cur.execute.call_args_list))

    def test_preview_returns_only_counts_and_does_not_delete(self):
        conn = MagicMock()
        cur = conn.cursor.return_value.__enter__.return_value
        cur.fetchall.side_effect = [[('customer', 'synthetic', 'FIAP Demo', 'demo@example.invalid')], [('order', 'FIAP-DEMO-012345abcdef / synthetic order 1')], [('part',)], [('vehicle',)]]
        cur.fetchone.side_effect = [(0,), (0,), (0,)]
        result = module.execute(conn, 'preview_seed', 'FIAP-DEMO-012345abcdef', '')
        self.assertEqual(1, result['counts']['work_orders'])
        self.assertNotIn('synthetic', str(result))
        self.assertFalse(any(call.args[0].startswith(('DELETE', 'UPDATE')) for call in cur.execute.call_args_list))

    def test_shared_stock_blocks_cleanup(self):
        conn = MagicMock()
        cur = conn.cursor.return_value.__enter__.return_value
        cur.fetchall.side_effect = [[('customer', 'synthetic', 'FIAP Demo', 'demo@example.invalid')], [('order', 'FIAP-DEMO-012345abcdef / synthetic order 1')], [('part',)], [('vehicle',)]]
        cur.fetchone.return_value = (1,)
        with self.assertRaises(ValueError):
            module.execute(conn, 'delete_seed', 'FIAP-DEMO-012345abcdef', 'FIAP-DEMO-012345abcdef')
        self.assertFalse(any(call.args[0].startswith('DELETE') for call in cur.execute.call_args_list))


if __name__ == '__main__':
    unittest.main()
