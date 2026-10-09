#!/usr/bin/env python3
"""Read-only workload sample; records measurements, not production capacity."""
import argparse, concurrent.futures, json, math, time, urllib.request
from collections import Counter
p=argparse.ArgumentParser()
p.add_argument('--url',default='http://localhost:8082/api/countries?page=0&size=50')
p.add_argument('--requests',type=int,default=200)
p.add_argument('--concurrency',type=int,default=8)
p.add_argument('--output')
a=p.parse_args()
if not 1 <= a.concurrency <= 32 or not 1 <= a.requests <= 10000: p.error('concurrency 1..32, requests 1..10000')
def get(_):
 start=time.perf_counter()
 try:
  with urllib.request.urlopen(a.url,timeout=15) as r:
   json.loads(r.read())
   return r.status,time.perf_counter()-start,r.headers.get('X-Instance-ID','unknown')
 except Exception as e:return type(e).__name__,time.perf_counter()-start,'unknown'
for _ in range(3):get(_)
start=time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=a.concurrency) as ex:results=list(ex.map(get,range(a.requests)))
elapsed=time.perf_counter()-start
latencies=sorted(r[1]*1000 for r in results)
quantile=lambda q:round(latencies[min(len(latencies)-1,math.ceil(len(latencies)*q)-1)],3)
result={'requests':a.requests,'concurrency':a.concurrency,'seconds':round(elapsed,3),
 'requests_per_second':round(a.requests/elapsed,3),'statuses':dict(Counter(str(r[0]) for r in results)),
 'latency_ms':{'p50':quantile(.5),'p95':quantile(.95),'max':round(max(latencies),3)},
 'instances':dict(Counter(r[2] for r in results)),
 'scope':'Local read workload; does not establish maximum throughput, HA or production capacity.'}
text=json.dumps(result,indent=2)+'\n'
print(text,end='')
if a.output:
 from pathlib import Path
 Path(a.output).write_text(text)
if any(r[0]!=200 for r in results):raise SystemExit(1)
