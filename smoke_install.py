"""Install old and new packages on the private API-19 emulator, then launch and capture it."""
from pathlib import Path
import subprocess, time, os
ROOT=Path('E:/JellyfinKitKat')
ADB=ROOT/'diagnostics/sdk/platform-tools/adb.exe'
env=dict(os.environ,TEMP=str(ROOT/'tmp'),TMP=str(ROOT/'tmp'),ANDROID_SDK_HOME=str(ROOT/'diagnostics/home'))
def adb(*args,timeout=30):
    p=subprocess.run([str(ADB),'-s','emulator-5580',*args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout,env=env)
    return p.stdout.decode('utf-8',errors='replace')
for i in range(90):
    try:
        if adb('shell','getprop','sys.boot_completed',timeout=5).strip()=='1':break
    except subprocess.TimeoutExpired:pass
    time.sleep(1)
else:raise RuntimeError('Emulator boot timed out')
print('Android release:',adb('shell','getprop','ro.build.version.release'),flush=True)
for version in ('0.1.0','0.1.1'):
    result=adb('install','--no-streaming','-r',str(ROOT/('dist/jellyfin-kitkat-'+version+'.apk')),timeout=60)
    print('Install',version,':',result,flush=True)
    if 'Success' not in result:raise RuntimeError('Installation failed: '+version)
print(adb('shell','am','start','-W','-n','org.jellyfin.kitkat/.MainActivity'),flush=True)
time.sleep(3)
adb('shell','screencap','-p','/data/local/tmp/jellyfin-login.png')
adb('pull','/data/local/tmp/jellyfin-login.png',str(ROOT/'diagnostics/login.png'))
if not (ROOT/'diagnostics/login.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n'):
    raise RuntimeError('Screenshot capture failed')
logs=adb('logcat','-d','-s','AndroidRuntime:E','ActivityManager:I')
(ROOT/'diagnostics/smoke-log.txt').write_text(logs,encoding='utf-8')
if 'FATAL EXCEPTION' in logs:raise RuntimeError('Application crash detected; inspect smoke-log.txt')
print('Launch check passed; screenshot:',ROOT/'diagnostics/login.png',flush=True)
