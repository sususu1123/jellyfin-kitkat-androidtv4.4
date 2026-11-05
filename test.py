"""Compile the real API class and run HTTP contract tests on the private JDK."""
from pathlib import Path
import os, shutil, urllib.request, zipfile
from build import JAVA, WORK, run, PROJECT

out=WORK/'tests'
out.mkdir(parents=True,exist_ok=True)
json_source=out/'JSON-java-20240303/src/main/java'
if not json_source.exists():
    archive=out/'json-source.zip'
    urllib.request.urlretrieve('https://codeload.github.com/stleary/JSON-java/zip/refs/tags/20240303',archive)
    with zipfile.ZipFile(archive) as z:z.extractall(out)
for src in [PROJECT/'app/src/org/jellyfin/kitkat/Api.java',PROJECT/'tests/ApiTest.java']:
    shutil.copy2(src,out/src.name)
sources=list(json_source.rglob('*.java'))+[out/'Api.java',out/'ApiTest.java']
argfile=out/'test-sources.txt'
argfile.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources),encoding='utf-8')
run(JAVA/'javac.exe','-encoding','UTF-8','-d',out,'@'+str(argfile))
run(JAVA/'java.exe','-cp',out,'org.jellyfin.kitkat.ApiTest')
