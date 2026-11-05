"""Build without Gradle or Android Studio. All output lives under KITKAT_WORK (E: by default)."""
from pathlib import Path
import os, subprocess, zipfile, shutil, json, hashlib
import xml.etree.ElementTree as ET

PROJECT=Path(__file__).resolve().parent
WORK=Path(os.environ.get('KITKAT_WORK','E:/JellyfinKitKat'))
TOOLS=Path(os.environ.get('KITKAT_TOOLCHAIN',str(WORK/'toolchain')))
JAVA=next((TOOLS/'jdk').glob('*/bin'))
BT=next((TOOLS/'build-tools').glob('*/aapt.exe')).parent
ANDROID=next((TOOLS/'platform').glob('*/android.jar'))
OUT=WORK/'build'
DIST=WORK/'dist'
OUT.mkdir(parents=True,exist_ok=True); DIST.mkdir(exist_ok=True)
env=dict(os.environ,JAVA_HOME=str(JAVA.parent),TEMP=str(WORK/'tmp'),TMP=str(WORK/'tmp'))
Path(env['TEMP']).mkdir(exist_ok=True)

def run(*args):
    print('Running',Path(str(args[0])).name,flush=True)
    subprocess.run([str(a) for a in args],check=True,cwd=str(OUT),env=env)

def build():
    manifest=ET.parse(PROJECT/'app/AndroidManifest.xml').getroot()
    version=manifest.attrib['{http://schemas.android.com/apk/res/android}versionName']
    gen=OUT/'generated';classes=OUT/'classes';dex=OUT/'dex'
    for path in (gen,classes,dex):
        if path.exists():shutil.rmtree(path)
        path.mkdir()
    app=PROJECT/'app'; unsigned=OUT/'unsigned.apk'; aligned=OUT/'aligned.apk'
    if unsigned.exists():unsigned.unlink()
    run(BT/'aapt.exe','package','-f','-m','-J',gen,'-M',app/'AndroidManifest.xml','-S',app/'res','-A',app/'assets','-I',ANDROID,'-F',unsigned)
    staged=OUT/'src'
    if staged.exists():shutil.rmtree(staged)
    shutil.copytree(app/'src',staged)
    sources=list(staged.rglob('*.java'))+list(gen.rglob('*.java'))
    argfile=OUT/'sources.txt'
    argfile.write_text('\n'.join('"'+str(s).replace('\\','/')+'"' for s in sources),encoding='utf-8')
    run(JAVA/'javac.exe','-encoding','UTF-8','-source','1.7','-target','1.7','-bootclasspath',ANDROID,'-d',classes,'@'+str(argfile))
    jar=OUT/'classes.jar'
    with zipfile.ZipFile(jar,'w') as z:
        for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
    run(JAVA/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','19','--lib',ANDROID,'--output',dex,jar)
    with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as z:z.write(dex/'classes.dex','classes.dex')
    run(BT/'zipalign.exe','-f','4',unsigned,aligned)
    key=WORK/'kitkat-release.keystore'
    if not key.exists():
        run(JAVA/'keytool.exe','-genkeypair','-keystore',key,'-storepass','kitkat-local-build','-keypass','kitkat-local-build',
            '-alias','kitkat','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Jellyfin KitKat Local Build')
    apk=DIST/('jellyfin-kitkat-'+version+'.apk')
    run(JAVA/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',key,'--ks-pass','pass:kitkat-local-build',
        '--key-pass','pass:kitkat-local-build','--ks-key-alias','kitkat','--min-sdk-version','16',
        '--v1-signing-enabled','true','--v2-signing-enabled','false',
        '--v3-signing-enabled','false','--v4-signing-enabled','false','--out',apk,aligned)
    run(JAVA/'java.exe','-jar',BT/'lib/apksigner.jar','verify','--verbose','--min-sdk-version','19',apk)
    run(BT/'aapt.exe','dump','badging',apk)
    digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    shutil.copy2(PROJECT/'README.md',DIST/'README.md')
    source_zip=DIST/('jellyfin-kitkat-'+version+'-source.zip')
    with zipfile.ZipFile(source_zip,'w',zipfile.ZIP_DEFLATED) as z:
        for name in ('README.md','RELEASE_NOTES.md','.gitignore','.gitattributes','bootstrap.py','build.py','test.py','test_seek.py','test_grid.py','test_subtitle.py','bootstrap_emulator.py','smoke_install.py','generate_icons.py','ICON_LICENSE.txt','THIRD_PARTY_NOTICES.md'):
            z.write(PROJECT/name,'jellyfin-kitkat/'+name)
        for folder in ('app','tests'):
            for f in (PROJECT/folder).rglob('*'):
                if f.is_file():z.write(f,'jellyfin-kitkat/'+f.relative_to(PROJECT).as_posix())
    source_digest=hashlib.sha256(source_zip.read_bytes()).hexdigest()
    (DIST/'SHA256SUMS.txt').write_text(digest+'  '+apk.name+'\n'+source_digest+'  '+source_zip.name+'\n',encoding='utf-8')
    print('APK:',apk,'\nBytes:',apk.stat().st_size,'\nSHA256:',digest)

if __name__=='__main__':build()
