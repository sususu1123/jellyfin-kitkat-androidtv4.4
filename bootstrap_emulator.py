"""Private, headless Android 4.4 emulator for installation regression checks, stored on E:."""
from pathlib import Path
import urllib.request, zipfile, shutil, hashlib, os

ROOT=Path('E:/JellyfinKitKat/diagnostics')
SDK=ROOT/'sdk'
AVD=ROOT/'avd'
ROOT.mkdir(parents=True,exist_ok=True)
SDK.mkdir(exist_ok=True)
AVD.mkdir(exist_ok=True)
for name in ('platforms','tools'):(SDK/name).mkdir(exist_ok=True)

def unpack(url,dest,sha1=None):
    target=ROOT/url.rsplit('/',1)[-1]
    if not target.exists():
        print('Downloading',target.name,flush=True)
        urllib.request.urlretrieve(url,target)
    if sha1 and hashlib.file_digest(target.open('rb'),'sha1').hexdigest()!=sha1:
        raise RuntimeError('Checksum mismatch')
    if not (dest/'.complete').exists():
        print('Extracting',target.name,flush=True)
        dest.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(target) as z:z.extractall(dest)
        (dest/'.complete').touch()

unpack('https://dl.google.com/android/repository/emulator-windows-4266726.zip',SDK)
unpack('https://dl.google.com/android/repository/sys-img/android/x86-19_r06.zip',SDK/'system-images/android-19/default',
       '2ac82153aae97f7eae4c5a0761224fe04321d03d')
tools=SDK/'platform-tools';tools.mkdir(exist_ok=True)
for name in ('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll'):
    shutil.copy2(Path('C:/platform-tools')/name,tools/name)
avd=AVD/'kitkat.avd';avd.mkdir(exist_ok=True)
(AVD/'kitkat.ini').write_text('avd.ini.encoding=UTF-8\npath='+str(avd)+'\ntarget=android-19\n',encoding='utf-8')
(avd/'config.ini').write_text('''avd.ini.encoding=UTF-8
abi.type=x86
hw.cpu.arch=x86
hw.ramSize=512
hw.lcd.width=1280
hw.lcd.height=720
hw.lcd.density=160
hw.mainKeys=yes
hw.keyboard=yes
hw.gpu.enabled=no
disk.dataPartition.size=256M
tag.id=default
skin.name=1280x720
image.sysdir.1='''+str(SDK/'system-images/android-19/default/x86')+'\n',encoding='utf-8')
print('Emulator ready:',SDK/'emulator/emulator.exe')
