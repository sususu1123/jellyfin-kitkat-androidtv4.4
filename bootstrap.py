"""Download a private, checksum-verified Android build toolchain."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

import os
ROOT = Path(os.environ.get('KITKAT_TOOLCHAIN', 'E:/JellyfinKitKat/toolchain'))
ROOT.mkdir(parents=True,exist_ok=True)

def download(url, target, checksum=None, algorithm='sha256'):
    if not target.exists():
        print('Downloading', target.name, flush=True)
        urllib.request.urlretrieve(url, target)
    if checksum:
        actual = hashlib.file_digest(target.open('rb'), algorithm).hexdigest()
        if actual != checksum:
            raise RuntimeError('Checksum mismatch: ' + target.name)
    return target

def sdk_package(package):
    tree = ET.parse(ROOT / 'repository.xml')
    entry = next(p for p in tree.getroot() if p.attrib.get('path') == package)
    archive = next(a for a in entry.findall('./archives/archive')
                   if a.findtext('host-os') in (None, 'windows'))
    item = archive.find('complete')
    name = item.findtext('url')
    path = download('https://dl.google.com/android/repository/' + name,
                    ROOT / name, item.findtext('checksum'), 'sha1')
    dest = ROOT / ('platform' if package.startswith('platforms') else 'build-tools')
    if not (dest / '.complete').exists():
        print('Extracting', name, flush=True)
        with zipfile.ZipFile(path) as z:
            z.extractall(dest)
        (dest / '.complete').touch()

def jdk():
    request = urllib.request.Request(
        'https://api.adoptium.net/v3/assets/latest/8/hotspot?architecture=x64&image_type=jdk&os=windows',
        headers={'User-Agent': 'Jellyfin-KitKat-build'})
    with urllib.request.urlopen(request) as response:
        item = json.load(response)[0]['binary']['package']
    path = download(item['link'], ROOT / item['name'], item['checksum'])
    dest = ROOT / 'jdk'
    if not (dest / '.complete').exists():
        print('Extracting JDK', flush=True)
        with zipfile.ZipFile(path) as z:
            z.extractall(dest)
        (dest / '.complete').touch()

if __name__ == '__main__':
    if not (ROOT / 'repository.xml').exists():
        download('https://dl.google.com/android/repository/repository2-1.xml', ROOT / 'repository.xml')
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        jobs = [pool.submit(sdk_package, 'platforms;android-19'),
                pool.submit(sdk_package, 'build-tools;30.0.3'), pool.submit(jdk)]
        for job in jobs:
            job.result()
    print('Toolchain ready', flush=True)
