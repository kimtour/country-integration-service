#!/usr/bin/env python3
"""Restore a private dump in disposable MySQL; never touches the application DB."""
import argparse, gzip, json, secrets, subprocess, time
from datetime import datetime, timezone
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('backup',type=Path)
p.add_argument('--expected-iso',default='')
p.add_argument('--output',type=Path)
a=p.parse_args()
with gzip.open(a.backup,'rb') as f:dump=f.read()
name='country-restore-check-'+secrets.token_hex(5)
password=secrets.token_hex(20)
def run(*cmd,**kw):
 result=subprocess.run(cmd,capture_output=True,**kw)
 if result.returncode:
  error=result.stderr.decode(errors='replace') if isinstance(result.stderr,bytes) else result.stderr
  raise RuntimeError(error.replace(password,'[redacted]')[-600:])
 return result
def mysql(sql):
 return run('docker','exec','-e','MYSQL_PWD='+password,name,'mysql','--protocol=TCP','-h','127.0.0.1','-uroot','-N','countrydb','-e',sql,text=True).stdout.strip()
try:
 run('docker','run','-d','--name',name,'--memory=512m','-e','MYSQL_ROOT_PASSWORD='+password,'-e','MYSQL_DATABASE=countrydb','mysql:8.4','--innodb-buffer-pool-size=64M','--max-connections=30')
 deadline=time.monotonic()+180
 while True:
  try:mysql('SELECT 1');break
  except RuntimeError:
   if time.monotonic()>deadline:raise RuntimeError('Disposable MySQL did not become ready') from None
   time.sleep(2)
 run('docker','exec','-i','-e','MYSQL_PWD='+password,name,'mysql','--protocol=TCP','-h','127.0.0.1','-uroot','countrydb',input=dump)
 codes=mysql('SELECT iso_code FROM countries ORDER BY iso_code').splitlines()
 if a.expected_iso:assert codes==sorted(a.expected_iso.split(',')),codes
 counts=mysql('SELECT COUNT(*) FROM countries; SELECT COUNT(*) FROM languages; SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;').splitlines()
 result={'date':datetime.now(timezone.utc).isoformat(),'database':'Disposable MySQL 8.4; container removed after check','gzip_valid':True,'restored_iso_codes':codes,'countries':int(counts[0]),'languages':int(counts[1]),'successful_schema_history_entries':int(counts[2]),'limits':'Local dump/import verification; does not establish production RTO/RPO, storage recovery or HA.'}
 assert result['countries']>0 and result['languages']>0 and result['successful_schema_history_entries']>0
 text=json.dumps(result,indent=2)+'\n';print(text,end='')
 if a.output:a.output.write_text(text)
finally:
 subprocess.run(['docker','rm','-f',name],capture_output=True)
