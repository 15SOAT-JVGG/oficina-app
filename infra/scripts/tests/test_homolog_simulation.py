import collections
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import uuid

SPEC = importlib.util.spec_from_file_location('simulation', Path(__file__).parents[1] / 'homolog-simulation.py')
sim = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = sim
SPEC.loader.exec_module(sim)


class FakeAPI:
    def __init__(self):
        self.calls = []
        self.state = {}
        self.next_id = 0

    def request(self, method, path, payload=None, expected=200, authenticated=True):
        self.calls.append((method, path, payload))
        self.next_id += 1
        identifier = str(uuid.UUID(int=self.next_id))
        if method == 'POST' and path == '/v1/customer':
            self.customer_id = identifier
            return {}
        if method == 'GET' and path.startswith('/v1/customer/by-document/'):
            return {'customerId': self.customer_id}
        if method == 'POST' and path == '/v1/vehicle':
            self.vehicle_id = identifier
            return {}
        if method == 'GET' and path.startswith('/v1/vehicle/by-license-plate/'):
            return {'id': self.vehicle_id}
        if method == 'POST' and path == '/admin/parts':
            return {'id': identifier}
        if method == 'POST' and path == '/v1/work-orders':
            self.state[identifier] = 'RECEIVED'
            return {'workOrder': {'workOrderId': identifier}}
        order_id = path.split('/')[3]
        if method == 'GET':
            return {'status': self.state[order_id]}
        if path.endswith('/status'):
            self.state[order_id] = payload['status']
        elif path.endswith('/estimate'):
            self.state[order_id] = 'WAITING_APPROVAL'
            return {'estimateId': identifier}
        elif path.endswith('/approve'):
            self.state[order_id] = 'IN_PROGRESS'
        elif path.endswith('/close'):
            self.state[order_id] = 'COMPLETED'
        return {}


class SimulationTests(unittest.TestCase):
    def test_rejects_out_of_budget_inputs(self):
        for field, value in [('orders', 101), ('orders', 0), ('duration', 301), ('concurrency', 31), ('cooldown', 301)]:
            with self.subTest(field=field, value=value), self.assertRaises(sim.SafeFailure):
                sim.Settings(**{field: value}).validate()
        with self.assertRaises(sim.SafeFailure):
            sim.Settings(mode='production').validate()

    def test_rejects_redirect_and_untrusted_endpoint_configuration(self):
        valid = 'https://abc123.execute-api.us-east-1.amazonaws.com'
        self.assertEqual(valid, sim.validate_gateway(valid))
        for url in ['http://example.com', valid + '/v1', valid + '?token=secret', valid + ':443', 'https://a.execute-api.eu-west-1.amazonaws.com']:
            with self.subTest(url=url), self.assertRaises(sim.SafeFailure):
                sim.validate_gateway(url)
        self.assertIsNone(sim.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example'))

    def test_generated_documents_and_plates_pass_public_format_rules(self):
        for _ in range(50):
            document = sim.document()
            self.assertEqual(11, len(document))
            self.assertGreater(len(set(document)), 1)
            digits = list(map(int, document))
            for size in (9, 10):
                remainder = sum(digit * (size + 1 - i) for i, digit in enumerate(digits[:size])) % 11
                self.assertEqual(0 if remainder < 2 else 11 - remainder, digits[size])
            self.assertRegex(sim.plate(), r'^[A-Z]{3}[0-9][A-Z][0-9]{2}$')

    def test_seed_reaches_all_six_states_without_updating_existing_fixtures(self):
        api, result = FakeAPI(), {}
        with patch('sys.stdout', new=io.StringIO()):
            sim.seed(api, 6, result, pause=lambda _: None)
        self.assertEqual(dict.fromkeys(sim.STATES, 1), result['final_states'])
        self.assertEqual(6, result['orders_created'])
        self.assertEqual(1, result['customers'])
        self.assertEqual(1, result['vehicles'])
        self.assertEqual(1, result['parts'])
        customer_payload = api.calls[0][2]
        self.assertTrue(customer_payload['email'].endswith('@example.invalid'))
        close_calls = [payload for method, path, payload in api.calls if path.endswith('/close')]
        self.assertEqual([{'finalValue': 50}, {'finalValue': 50}], close_calls)
        self.assertFalse(any(method == 'DELETE' for method, _, _ in api.calls))

    def test_failed_write_is_not_retried(self):
        api = FakeAPI()
        with patch.object(api, 'request', side_effect=sim.SafeFailure('write failed')) as request:
            with self.assertRaises(sim.SafeFailure):
                sim.seed(api, 6, {}, pause=lambda _: None)
        self.assertEqual(1, request.call_count)

    def test_mailer_accepts_only_mock_or_known_capture_sandbox(self):
        for config in [{}, {'MAIL_MOCK': 'true'}, {'MAIL_MOCK': 'false', 'MAIL_HOST': 'sandbox.smtp.mailtrap.io'}]:
            sim.validate_mailer({'data': config}, {})
        with self.assertRaises(sim.SafeFailure):
            sim.validate_mailer({'data': {'MAIL_MOCK': 'false', 'MAIL_HOST': 'delivery.example.com'}}, {})
        deployment = {'spec': {'template': {'spec': {'containers': [{'env': [{'name': 'MAIL_MOCK', 'value': 'false'}]}]}}}}
        with self.assertRaises(sim.SafeFailure):
            sim.validate_mailer({'data': {'MAIL_MOCK': 'true'}}, deployment)

    def test_empty_201_is_valid_but_empty_200_is_not_json(self):
        client = sim.Client('https://abc123.execute-api.us-east-1.amazonaws.com', 'fixture', 'fixture')
        with patch('urllib.request.build_opener') as opener:
            response = opener.return_value.open.return_value.__enter__.return_value
            response.status = 201
            response.read.return_value = b''
            self.assertEqual({}, client.request('POST', '/v1/customer', {}, expected=201))
            response.status = 200
            with self.assertRaises(sim.SafeFailure):
                client.request('GET', '/v1/customer/by-document/fixture')

    def test_cancelled_client_does_not_schedule_another_write(self):
        client = sim.Client('https://abc123.execute-api.us-east-1.amazonaws.com', 'fixture', 'fixture')
        client.stop.set()
        with patch('urllib.request.build_opener') as opener, self.assertRaises(sim.SafeFailure):
            client.request('POST', '/v1/customer', {}, expected=201)
        opener.assert_not_called()

    def test_patch_without_body_still_declares_json_content_type(self):
        client = sim.Client('https://abc123.execute-api.us-east-1.amazonaws.com', 'fixture', 'fixture')
        with patch('urllib.request.build_opener') as opener:
            response = opener.return_value.open.return_value.__enter__.return_value
            response.status, response.read.return_value = 200, b'{}'
            client.request('PATCH', '/v1/work-orders/fixture/estimate/fixture/approve')
            request = opener.return_value.open.call_args.args[0]
            self.assertEqual('application/json', request.get_header('Content-type'))

    def test_ramp_starts_with_one_worker_and_reaches_the_requested_ceiling(self):
        self.assertEqual(1, sim.active_workers(0, 180, 4))
        self.assertEqual(2, sim.active_workers(30, 180, 4))
        self.assertEqual(4, sim.active_workers(60, 180, 4))
        self.assertEqual(4, sim.active_workers(180, 180, 4))
        self.assertEqual(4, sim.active_workers(15, 30, 4))

    def test_api_error_does_not_echo_response_or_credentials(self):
        client = sim.Client('https://abc123.execute-api.us-east-1.amazonaws.com', 'CANARY_USER', 'CANARY_PASSWORD')
        error = urllib.error.HTTPError(client.base, 500, 'private detail', {}, io.BytesIO(b'CANARY_RESPONSE'))
        with patch('urllib.request.build_opener') as opener:
            opener.return_value.open.side_effect = error
            with self.assertRaises(sim.SafeFailure) as raised:
                client.login()
        self.assertEqual({'500': 1}, dict(client.counts))
        self.assertNotIn('CANARY', str(raised.exception))
        self.assertNotIn('private detail', str(raised.exception))

    def test_load_stops_on_the_global_error_budget(self):
        api = type('FailingAPI', (), {'login': lambda self: (_ for _ in ()).throw(sim.SafeFailure('failed'))})()
        result, stop = {}, threading.Event()
        with self.assertRaises(sim.SafeFailure):
            sim.run_load(api, sim.Settings(duration=30, concurrency=1), stop, result)
        self.assertEqual(5, result['attempts'])
        self.assertTrue(stop.is_set())
        self.assertTrue(result['aborted'])

    def test_scale_requires_additional_ready_pods_not_only_desired_replicas(self):
        baseline = {'ready': 1, 'current': 1, 'desired': 1}
        pending = {'ready': 1, 'current': 3, 'desired': 3}
        self.assertFalse(sim.scale_evidence([baseline, pending])['scale_up_observed'])
        ready = {'ready': 3, 'current': 3, 'desired': 3}
        self.assertEqual({'scale_up_observed': True, 'scale_down_observed': True},
                         sim.scale_evidence([baseline, ready, baseline]))
        self.assertFalse(sim.scale_evidence([baseline, ready])['scale_down_observed'])

    def test_snapshot_reads_hpa_and_deployment_together(self):
        resources = {'items': [
            {'kind': 'HorizontalPodAutoscaler', 'spec': {'minReplicas': 1, 'maxReplicas': 6},
             'status': {'currentReplicas': 2, 'desiredReplicas': 3,
                        'currentMetrics': [{'resource': {'name': 'cpu', 'current': {'averageUtilization': 70}}}]}},
            {'kind': 'Deployment', 'status': {'readyReplicas': 2}}]}
        with patch.object(sim, 'kubectl', return_value=resources) as read:
            row = sim.snapshot()
        read.assert_called_once_with('get', 'hpa/oficina-mecanica', 'deployment/oficina-mecanica')
        self.assertEqual(70, row['cpu_percent'])
        self.assertEqual(2, row['ready'])
        self.assertEqual(3, row['desired'])

    def test_report_contains_only_aggregates_and_no_token(self):
        client = sim.Client('https://abc123.execute-api.us-east-1.amazonaws.com', 'CANARY_USER', 'CANARY_PASSWORD')
        client.token = 'CANARY_TOKEN'
        client.counts = collections.Counter({'200': 1})
        client.latencies = [.25]
        report = {'outcome': 'success', 'seed': {'orders_created': 6}, 'hpa': [], 'load': {}}
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'RESULTS_DIR': directory}, clear=True), patch('sys.stdout', new=io.StringIO()):
            sim.write_report(report, client)
            text = Path(directory, 'summary.json').read_text()
            self.assertNotIn('CANARY', text)
            self.assertEqual(.25, json.loads(text)['client_p95_seconds'])


if __name__ == '__main__':
    unittest.main()
