#!/usr/bin/env python3
"""Bounded synthetic API seed/load. Reports contain aggregates, never response bodies."""
import collections
import concurrent.futures
import csv
import json
import math
import os
from pathlib import Path
import re
import secrets
import signal
import string
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass

STATES = ('RECEIVED', 'DIAGNOSIS', 'WAITING_APPROVAL', 'IN_PROGRESS', 'COMPLETED', 'DELIVERED')


class SafeFailure(Exception):
    """Only deliberately sanitized messages may reach workflow logs."""


@dataclass(frozen=True)
class Settings:
    mode: str = 'seed_and_load'
    orders: int = 12
    duration: int = 180
    concurrency: int = 12
    cooldown: int = 240

    def validate(self):
        if self.mode not in ('seed_only', 'load_only', 'seed_and_load'):
            raise SafeFailure('Invalid operation.')
        for name, value, low, high in [('orders', self.orders, 1, 100),
                                       ('duration', self.duration, 30, 300),
                                       ('concurrency', self.concurrency, 1, 30),
                                       ('cooldown', self.cooldown, 60, 300)]:
            if not isinstance(value, int) or isinstance(value, bool) or not low <= value <= high:
                raise SafeFailure('Input outside allowed limits: ' + name)
        return self


def validate_gateway(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != 'https' or parsed.username or parsed.password or parsed.port
            or parsed.path not in ('', '/') or parsed.query or parsed.fragment
            or not re.fullmatch(r'[a-z0-9]+\.execute-api\.us-east-1\.amazonaws\.com', parsed.hostname or '')):
        raise SafeFailure('Use the verified homolog HTTP API endpoint.')
    return url.rstrip('/')


def document():
    digits = [secrets.randbelow(10) for _ in range(9)]
    if len(set(digits)) == 1:
        digits[0] = (digits[0] + 1) % 10
    for weight in (10, 11):
        remainder = sum(digit * (weight - i) for i, digit in enumerate(digits)) % 11
        digits.append(0 if remainder < 2 else 11 - remainder)
    return ''.join(map(str, digits))


def plate():
    letters = string.ascii_uppercase
    return ''.join(secrets.choice(letters) for _ in range(3)) + str(secrets.randbelow(10)) + secrets.choice(letters) + f'{secrets.randbelow(100):02d}'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class Client:
    def __init__(self, base, username, password):
        self.base = validate_gateway(base)
        self.username, self.password, self.token = username, password, ''
        self.counts = collections.Counter()
        self.latencies = []
        self.lock = threading.Lock()

    def request(self, method, path, payload=None, expected=200, authenticated=True):
        headers = {'Accept': 'application/json', 'X-Request-ID': 'fiap-demo-' + secrets.token_hex(8)}
        if authenticated:
            headers['Authorization'] = 'Bearer ' + self.token
        data = None
        if payload is not None:
            headers['Content-Type'] = 'application/json'
            data = json.dumps(payload).encode()
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        start, status = time.monotonic(), 0
        try:
            # An opener per request avoids mutable transport state across workers.
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=20) as response:
                status = response.status
                body = response.read(1024 * 1024)
            if status != expected:
                raise SafeFailure(f'Unexpected API response (HTTP {status}).')
            try:
                return json.loads(body)
            except (ValueError, UnicodeError):
                raise SafeFailure('Invalid API JSON response.') from None
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            raise SafeFailure(f'Unexpected API response (HTTP {status}).') from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise SafeFailure('API request did not complete within the request budget.') from None
        finally:
            with self.lock:
                self.counts[str(status)] += 1
                self.latencies.append(time.monotonic() - start)

    def login(self):
        return self.request('POST', '/v1/auth/login', {'username': self.username, 'password': self.password}, authenticated=False)

    def authenticate(self):
        self.token = self.login().get('token', '')
        if not self.token:
            raise SafeFailure('Admin login returned no token.')


def required_id(body, key):
    value = body.get(key)
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-fA-F-]{36}', value):
        raise SafeFailure('API response does not contain the required resource identifier.')
    return value


def seed(api, count, result, pause=time.sleep):
    """Creates a new isolated batch; never retries a write or edits existing fixtures."""
    tag = 'FIAP-DEMO-' + secrets.token_hex(6)
    result['batch'] = tag
    customer = api.request('POST', '/v1/customer', {
        'firstName': 'FIAP Demo', 'lastName': tag,
        'email': tag.lower() + '@example.invalid', 'phoneNumber': '+5511000000000',
        'document': document()}, expected=201)
    customer_id = required_id(customer, 'customerId')
    result['customers'] = 1
    vehicle = api.request('POST', '/v1/vehicle', {
        'customerId': customer_id, 'licensePlate': plate(), 'manufacturer': 'FIAP',
        'model': 'Synthetic Demo', 'color': 'Prata', 'year': 2024, 'kmDriven': 1000, 'type': 'CAR'}, expected=201)
    vehicle_id = required_id(vehicle, 'id')
    result['vehicles'] = 1
    part = api.request('POST', '/admin/parts', {
        'name': tag, 'description': 'Synthetic simulation inventory', 'unitPrice': 50,
        'stockQuantity': count + 10, 'unit': 'UN', 'minimumStock': 0, 'partType': 'PART'}, expected=201)
    part_id = required_id(part, 'id')
    result['parts'] = 1
    result['orders_created'], result['final_states'] = 0, {}
    for index in range(count):
        target = index % len(STATES)
        response = api.request('POST', '/v1/work-orders', {
            'customerId': customer_id, 'vehicleId': vehicle_id,
            'description': f'{tag} / synthetic order {index + 1}', 'priority': 'MEDIUM'}, expected=201)
        order_id = required_id(response.get('workOrder', {}), 'workOrderId')
        result['orders_created'] += 1
        order_path = '/v1/work-orders/' + order_id
        if target >= 1:
            api.request('PATCH', order_path + '/status', {'status': 'DIAGNOSIS'})
        if target >= 2:
            estimate = api.request('POST', order_path + '/estimate', {'items': [{'partId': part_id, 'quantity': 1}]}, expected=201)
            estimate_id = required_id(estimate, 'estimateId')
        if target >= 3:
            api.request('PATCH', order_path + '/estimate/' + estimate_id + '/approve')
        if target >= 4:
            api.request('PATCH', order_path + '/close', {'finalValue': 50})
        if target >= 5:
            api.request('PATCH', order_path + '/status', {'status': 'DELIVERED'})
        observed = api.request('GET', order_path)
        if observed.get('status') != STATES[target]:
            raise SafeFailure('Synthetic work order did not reach its intended lifecycle state.')
        result['final_states'][STATES[target]] = result['final_states'].get(STATES[target], 0) + 1
        pause(.25)
    print('Seed completed: resource counts and final states will appear in the summary.')


def kubectl(*args):
    try:
        result = subprocess.run(['kubectl', *args, '--namespace', 'homolog', '-o', 'json'], check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20)
        return json.loads(result.stdout)
    except (subprocess.SubprocessError, OSError, ValueError):
        raise SafeFailure('Read-only homolog Kubernetes inspection failed.') from None


def validate_mailer(config, deployment):
    effective = dict(config.get('data', {}))
    for container in deployment.get('spec', {}).get('template', {}).get('spec', {}).get('containers', []):
        for item in container.get('env', []):
            if item.get('name') in ('MAIL_MOCK', 'MAIL_HOST'):
                if 'value' not in item:
                    raise SafeFailure('Mailer override cannot be verified safely.')
                effective[item['name']] = item['value']
    if effective.get('MAIL_MOCK', 'true').lower() != 'true' and effective.get('MAIL_HOST') != 'sandbox.smtp.mailtrap.io':
        raise SafeFailure('Synthetic seed requires mock mailer or the Mailtrap sandbox.')


def snapshot():
    resources = kubectl('get', 'hpa/oficina-mecanica', 'deployment/oficina-mecanica')
    hpa = next(item for item in resources['items'] if item['kind'] == 'HorizontalPodAutoscaler')
    deployment = next(item for item in resources['items'] if item['kind'] == 'Deployment')
    status = hpa.get('status', {})
    row = {'time_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
           'current': status.get('currentReplicas', 0), 'desired': status.get('desiredReplicas', 0),
           'ready': deployment.get('status', {}).get('readyReplicas', 0),
           'minimum': hpa['spec'].get('minReplicas', 1), 'maximum': hpa['spec']['maxReplicas']}
    for metric in status.get('currentMetrics', []):
        resource = metric.get('resource', {})
        if resource.get('name') in ('cpu', 'memory'):
            row[resource['name'] + '_percent'] = resource.get('current', {}).get('averageUtilization')
    return row


def run_load(api, settings, stop, result):
    """Login work is bounded by concurrency, deadline and a global 20 requests/sec cap."""
    deadline = time.monotonic() + settings.duration
    lock, next_slot = threading.Lock(), time.monotonic()
    result.update({'attempts': 0, 'errors': 0, 'aborted': False})

    def worker():
        nonlocal next_slot
        while not stop.is_set():
            with lock:
                slot = next_slot
                next_slot = max(next_slot, time.monotonic()) + .05
            delay = max(0, slot - time.monotonic())
            if slot >= deadline or stop.wait(delay):
                return
            failed = False
            try:
                api.login()  # Responses, including tokens, are never printed or persisted.
            except SafeFailure:
                failed = True
            with lock:
                result['attempts'] += 1
                result['errors'] += int(failed)
                if failed and (result['errors'] >= 5 or (result['attempts'] >= 20 and result['errors'] / result['attempts'] > .05)):
                    result['aborted'] = True
                    stop.set()
    with concurrent.futures.ThreadPoolExecutor(max_workers=settings.concurrency) as pool:
        list(pool.map(lambda _: worker(), range(settings.concurrency)))
    if result['errors']:
        raise SafeFailure('Load contained failed requests; see aggregate counts. No further traffic was scheduled after the error budget.')
    if stop.is_set():
        raise SafeFailure('Load interrupted by the Kubernetes health guard.')


def scale_evidence(rows):
    baseline = rows[0]
    return {'scale_up_observed': max(row['ready'] for row in rows) > baseline['ready'],
            'scale_down_observed': rows[-1]['current'] <= baseline['current']}


def write_report(report, api):
    latencies = sorted(api.latencies) if api else []
    report['http_status_counts'] = dict(api.counts) if api else {}
    report['client_p95_seconds'] = round(latencies[max(0, math.ceil(.95 * len(latencies)) - 1)], 3) if latencies else None
    target = Path(os.environ.get('RESULTS_DIR', '/tmp/fiap-simulation-public'))
    target.mkdir(parents=True, exist_ok=True)
    (target / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    fields = ['time_utc', 'current', 'desired', 'ready', 'minimum', 'maximum', 'cpu_percent', 'memory_percent']
    with (target / 'hpa.csv').open('w') as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(report['hpa'])
    summary = '\n'.join(['## Homolog: synthetic seed and load', '', '```json', json.dumps(report, indent=2), '```', '',
                          'Only aggregate evidence is exported. Seed records remain in homolog; each execution creates a new batch.',
                          'HPA behavior is observed, never forced. Seed traffic does not implement missing application business metrics.'])
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        Path(os.environ['GITHUB_STEP_SUMMARY']).write_text(summary + '\n')
    print(json.dumps(report, indent=2))


def main():
    os.umask(0o077)
    report = {'outcome': 'failed', 'seed': {}, 'load': {}, 'hpa': []}
    api, monitor, monitor_stop = None, None, threading.Event()
    stop = threading.Event()
    monitor_errors = []
    try:
        try:
            settings = Settings(os.environ.get('SIMULATION_MODE', 'seed_and_load'),
                                int(os.environ.get('SEED_WORK_ORDERS', '12')),
                                int(os.environ.get('LOAD_DURATION_SECONDS', '180')),
                                int(os.environ.get('LOAD_CONCURRENCY', '12'))).validate()
        except ValueError:
            raise SafeFailure('Simulation numeric inputs must be integers.') from None
        if os.environ.get('TARGET_ENVIRONMENT') != 'homolog':
            raise SafeFailure('This simulation is restricted to homolog.')
        password = os.environ.get('APP_SEED_ADMIN_PASSWORD', '')
        if not password:
            raise SafeFailure('APP_SEED_ADMIN_PASSWORD is required.')
        api = Client(os.environ.get('GATEWAY_URL', ''), os.environ.get('APP_SEED_ADMIN_USERNAME', 'admin'), password)
        baseline = snapshot()
        report['hpa'].append(baseline)
        if baseline['ready'] < 1:
            raise SafeFailure('No ready application replicas before simulation.')
        loading = settings.mode != 'seed_only'
        if loading and (baseline.get('cpu_percent') is None or baseline['current'] >= baseline['maximum']):
            raise SafeFailure('HPA needs available CPU metrics and room to scale above its baseline.')
        api.authenticate()
        if settings.mode != 'load_only':
            validate_mailer(kubectl('get', 'configmap', 'oficina-mecanica-config'), kubectl('get', 'deployment', 'oficina-mecanica'))
            seed(api, settings.orders, report['seed'])
        if loading:
            def watch():
                while not monitor_stop.wait(15):
                    try:
                        row = snapshot()
                        report['hpa'].append(row)
                        print('HPA sample:', json.dumps(row), flush=True)
                        if row['ready'] < 1:
                            raise SafeFailure('Application lost all ready replicas during simulation.')
                    except SafeFailure as error:
                        monitor_errors.append(str(error))
                        stop.set()
                        return
            monitor = threading.Thread(target=watch, daemon=True)
            monitor.start()
            for signum in (signal.SIGTERM, signal.SIGINT):
                signal.signal(signum, lambda *_: stop.set())
            run_load(api, settings, stop, report['load'])
            print('Traffic finished. Observing HPA recovery without changing replicas.', flush=True)
            recovery_deadline = time.monotonic() + settings.cooldown
            recovery_start = time.monotonic()
            while time.monotonic() < recovery_deadline and not stop.wait(15):
                last = report['hpa'][-1]
                if time.monotonic() - recovery_start >= 60 and last['current'] <= baseline['current']:
                    break
            if monitor_errors:
                raise SafeFailure(monitor_errors[0])
            report['hpa'].append(snapshot())
            report.update(scale_evidence(report['hpa']))
            if not report['scale_up_observed']:
                raise SafeFailure('No additional ready replica was observed. Adjust the bounded load profile; no manual scaling was performed.')
            if not report['scale_down_observed']:
                raise SafeFailure('HPA did not return to its baseline during the recovery window.')
        else:
            report['hpa'].append(snapshot())
        report['outcome'] = 'success'
    except SafeFailure as error:
        report['reason'] = str(error)
    except Exception:
        report['reason'] = 'Unexpected simulation failure; sensitive request/response details were suppressed.'
    finally:
        monitor_stop.set()
        if monitor:
            monitor.join(timeout=25)
        write_report(report, api)
    return 0 if report['outcome'] == 'success' else 1


if __name__ == '__main__':
    raise SystemExit(main())
