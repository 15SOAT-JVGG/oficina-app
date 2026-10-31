"""Create and remove a bounded maintenance Job; never print secrets or pod logs on errors."""
import base64
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request


def kube(*args, document=None):
    return subprocess.run(['kubectl', '--namespace', 'homolog', *args], input=json.dumps(document) if document else None,
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=30).stdout


def main():
    operation = os.environ['MAINTENANCE_OPERATION']
    name = 'homolog-maint-' + os.environ['GITHUB_RUN_ID']
    password = os.environ.get('APP_SEED_ADMIN_PASSWORD', '')
    try:
        data = {'PROTECTED_CUSTOMER_CPF': os.environ.get('AUTHORIZER_TEST_CUSTOMER_CPF', '')}
        if operation == 'rotate_admin':
            if not password:
                raise ValueError('Missing password')
            data['ROTATION_PASSWORD'] = password
        kube('apply', '-f', '-', document={'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': name},
              'data': {k: base64.b64encode(v.encode()).decode() for k, v in data.items()}})
        kube('apply', '-f', '-', document={'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': name},
              'data': {'database.py': Path('infra/scripts/maintenance/database.py').read_text()}})
        container = {'name': 'maintenance', 'image': 'python:3.12-slim',
          'command': ['sh', '-c', 'python -m pip install --disable-pip-version-check --quiet --target /tmp/deps "psycopg[binary]==3.2.10" "bcrypt==4.3.0" >/dev/null 2>&1 && python /ops/database.py'],
          'envFrom': [{'configMapRef': {'name': 'oficina-mecanica-config'}}, {'secretRef': {'name': 'oficina-mecanica-env'}}, {'secretRef': {'name': name}}],
          'env': [{'name': k, 'value': v} for k, v in {'PYTHONPATH': '/tmp/deps', 'PYTHONDONTWRITEBYTECODE': '1', 'HOME': '/tmp', 'MAINTENANCE_OPERATION': operation, 'SEED_BATCH': os.environ.get('SEED_BATCH', ''), 'DELETE_CONFIRMATION': os.environ.get('DELETE_CONFIRMATION', '')}.items()],
          'resources': {'requests': {'cpu': '100m', 'memory': '128Mi'}, 'limits': {'cpu': '1', 'memory': '256Mi'}},
          'securityContext': {'runAsNonRoot': True, 'runAsUser': 1000, 'allowPrivilegeEscalation': False, 'readOnlyRootFilesystem': True, 'capabilities': {'drop': ['ALL']}},
          'volumeMounts': [{'name': 'ops', 'mountPath': '/ops', 'readOnly': True}, {'name': 'tmp', 'mountPath': '/tmp'}]}
        kube('create', '-f', '-', document={'apiVersion': 'batch/v1', 'kind': 'Job', 'metadata': {'name': name},
          'spec': {'activeDeadlineSeconds': 240, 'backoffLimit': 0, 'ttlSecondsAfterFinished': 300,
            'template': {'spec': {'restartPolicy': 'Never', 'automountServiceAccountToken': False,
              'securityContext': {'fsGroup': 1000, 'seccompProfile': {'type': 'RuntimeDefault'}}, 'containers': [container],
              'volumes': [{'name': 'ops', 'configMap': {'name': name}}, {'name': 'tmp', 'emptyDir': {}}]}}}})
        for _ in range(48):
            status = json.loads(kube('get', 'job', name, '-o', 'json')).get('status', {})
            if status.get('succeeded'):
                break
            if status.get('failed'):
                raise RuntimeError('Job failed')
            time.sleep(5)
        else:
            raise RuntimeError('Job timeout')
        # Successful job logs contain only the explicitly constructed count report.
        report = json.loads(kube('logs', 'job/' + name).strip())
        print(json.dumps(report))
        if operation == 'rotate_admin':
            kube('patch', 'secret', 'oficina-mecanica-env', '--type=merge', '-p', json.dumps({'data': {'APP_SEED_ADMIN_PASSWORD': base64.b64encode(password.encode()).decode()}}))
            endpoint = os.environ['GATEWAY_URL']
            request = urllib.request.Request(endpoint + '/v1/auth/login', data=json.dumps({'username': 'admin', 'password': password}).encode(), headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(request, timeout=30) as response:
                body = json.load(response)
                if response.status != 200 or not body.get('token'):
                    raise ValueError('Login verification failed')
            print('Gateway admin login: HTTP 200; token suppressed.')
        return 0
    except Exception:
        print('Maintenance or verification failed. Credentials and database details suppressed; do not retry deletion blindly.')
        return 1
    finally:
        for kind in ['job', 'secret', 'configmap']:
            try:
                kube('delete', kind, name, '--ignore-not-found=true', '--wait=false')
            except Exception:
                print('Temporary resource cleanup needs inspection: ' + kind)


if __name__ == '__main__':
    raise SystemExit(main())
