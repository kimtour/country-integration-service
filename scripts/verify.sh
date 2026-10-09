#!/usr/bin/env bash
set -euo pipefail
base="${1:-http://localhost:8082}"
python3 - "$base" <<'PYCODE'
import json, sys, urllib.request, urllib.error
base = sys.argv[1].rstrip('/')
for route in ['/actuator/health','/actuator/health/readiness','/actuator/health/liveness','/api/countries']:
    with urllib.request.urlopen(base+route, timeout=30) as response:
        data=json.load(response)
        assert response.status == 200
        if route.startswith('/actuator'): assert data['status'] == 'UP', data
        else: assert isinstance(data,list), data
        print(route, response.status, json.dumps(data))
try:
    urllib.request.urlopen(urllib.request.Request(base+'/api/countries', data=b'kenya',
        headers={'Content-Type':'text/plain'}, method='POST'), timeout=30)
    raise AssertionError('Unsupported content type was accepted')
except urllib.error.HTTPError as error:
    assert error.code == 415, (error.code,error.read())
    print('Unsupported content type', error.code)
try:
    urllib.request.urlopen(urllib.request.Request(base+'/api/countries',
        headers={'Accept':'application/pdf'}), timeout=30)
    raise AssertionError('Unsupported response format was accepted')
except urllib.error.HTTPError as error:
    assert error.code == 406, (error.code,error.read())
    print('Unsupported response format', error.code)
PYCODE
