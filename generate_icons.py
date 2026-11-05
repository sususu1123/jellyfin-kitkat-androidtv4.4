"""Fetch unchanged official Jellyfin Android TV PNG icons, pinned and checksum-verified."""
from pathlib import Path
import hashlib, urllib.request

ROOT=Path(__file__).resolve().parent
COMMIT='69a2b7b1237732a134dd501ff55b9404cf2d1601'
BASE='https://raw.githubusercontent.com/jellyfin/jellyfin-ux/'+COMMIT+'/'
HASHES={
    'mdpi':'a9f76083d9c269616dfaaae518b797bbc5083f7e0c4ef2d37873ad05712b63c2',
    'hdpi':'8575bbb0f1c82d0708d0c7e46d2508e21056d152efaa862ecf540987a21fabd8',
    'xhdpi':'b906be14c5d1d73605c09d3ecaac3e97ad3467cedd31f6c0b06c4548c367ee8a',
    'xxhdpi':'e5e99707a80e8fcf5a99da147768b200890b10edac6a93cb2f7ccfaf8a5910fb',
    'xxxhdpi':'0c134ad585cb4c6eb829e15d130fa248dfd6933dd28cdecca2c0fb7a5b92c71a'}

if __name__=='__main__':
    for density,digest in HASHES.items():
        data=urllib.request.urlopen(BASE+'branding/android/tv/app/mipmap-'+density+'/app_icon.png').read()
        if hashlib.sha256(data).hexdigest()!=digest:raise RuntimeError('Official icon checksum mismatch')
        directory=ROOT/'app/res'/('drawable-'+density);directory.mkdir(exist_ok=True)
        (directory/'ic_launcher.png').write_bytes(data)
        print('Official icon',density)
