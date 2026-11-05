"""Serve a deterministic 90s HLS video and exercise the actual player on emulator-5580."""
from pathlib import Path
import subprocess, threading, http.server, json, os, zipfile, time, urllib.parse, sys
VERSION='0.1.5'
FALLBACK_TEST='--fallback' in sys.argv
LIVE_TEST='--live' in sys.argv
LIVE_STALL='--live-stall' in sys.argv
LIVE_TEST=LIVE_TEST or LIVE_STALL
from build import JAVA,BT,ANDROID,WORK,PROJECT,run,env

OUT=WORK/'seek-test';OUT.mkdir(exist_ok=True)
MEDIA=OUT/'media';MEDIA.mkdir(exist_ok=True)
ADB=WORK/'diagnostics/sdk/platform-tools/adb.exe'
def adb(*args,timeout=60):
    p=subprocess.run([str(ADB),'-s','emulator-5580',*map(str,args)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=env,timeout=timeout)
    return p.stdout.decode('utf-8',errors='replace')

def prepare():
    if not (MEDIA/'index.m3u8').exists():
        run('ffmpeg','-y','-f','lavfi','-i','testsrc2=size=320x180:rate=10',
            '-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','90',
            '-c:v','libx264','-profile:v','baseline','-level','3.0','-pix_fmt','yuv420p','-g','60','-sc_threshold','0',
            '-b:v','180k','-c:a','aac','-ac','2','-b:a','64k','-hls_time','6','-hls_list_size','0',
            '-hls_playlist_type','vod','-hls_segment_filename',MEDIA/'segment%03d.ts',MEDIA/'index.m3u8')
    if LIVE_TEST and not (MEDIA/'live-original.mp4').exists():
        run('ffmpeg','-y','-loglevel','error','-i',MEDIA/'index.m3u8','-t','2','-c','copy','-movflags','+faststart',MEDIA/'live-original.mp4')
    if LIVE_STALL and not (MEDIA/'audio-only.mp4').exists():
        run('ffmpeg','-y','-loglevel','error','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','30','-c:a','aac','-vn',MEDIA/'audio-only.mp4')
    gen=OUT/'gen';classes=OUT/'classes';dex=OUT/'dex'
    for d in (gen,classes,dex):d.mkdir(exist_ok=True)
    unsigned=OUT/'test-unsigned.apk'
    run(BT/'aapt.exe','package','-f','-m','-J',gen,'-M',PROJECT/'tests/AndroidManifest.xml','-I',ANDROID,'-F',unsigned)
    sources=[]
    for name in ('SeekInstrumentation.java','GridInstrumentation.java'):
        source=OUT/name;source.write_bytes((PROJECT/'tests'/name).read_bytes());sources.append(source)
    run(JAVA/'javac.exe','-encoding','UTF-8','-source','1.7','-target','1.7','-bootclasspath',ANDROID,'-d',classes,*sources)
    jar=OUT/'classes.jar'
    with zipfile.ZipFile(jar,'w') as z:
        for f in classes.rglob('*.class'):z.write(f,f.relative_to(classes).as_posix())
    run(JAVA/'java.exe','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--min-api','19','--lib',ANDROID,'--output',dex,jar)
    with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:z.write(dex/'classes.dex','classes.dex')
    apk=OUT/'test.apk'
    run(JAVA/'java.exe','-jar',BT/'lib/apksigner.jar','sign','--ks',WORK/'kitkat-release.keystore',
        '--ks-pass','pass:kitkat-local-build','--key-pass','pass:kitkat-local-build','--ks-key-alias','kitkat',
        '--min-sdk-version','16','--v1-signing-enabled','true','--v2-signing-enabled','false','--v3-signing-enabled','false',
        '--v4-signing-enabled','false','--out',apk,unsigned)
    return apk

requests=[]
class Fixture(http.server.SimpleHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(MEDIA),**kwargs)
    def log_message(self,*args):pass
    def do_POST(self):
        body=self.rfile.read(int(self.headers.get('Content-Length','0')))
        requests.append((self.path,body.decode()))
        if '/PlaybackInfo' in self.path:
            direct=(FALLBACK_TEST or LIVE_TEST) and json.loads(body).get('EnableDirectPlay',False)
            data=json.dumps({'PlaySessionId':'test-session','MediaSources':[{'Id':'opened-source' if LIVE_TEST else 'test-source','LiveStreamId':'fixture-live' if LIVE_TEST else None,'SupportsDirectPlay':direct,'TranscodingUrl':'/index.m3u8'}]}).encode()
            self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
        else:self.send_response(204);self.end_headers()
    def do_DELETE(self):self.send_response(204);self.end_headers()
    def do_GET(self):
        requests.append((self.path,'Range='+str(self.headers.get('Range'))))
        if '/PlaybackInfo' in self.path:
            data=json.dumps({'MediaSources':[{'Id':'test-source'}]}).encode()
            self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
        if '/Videos/' in self.path and '/stream?' in self.path:
            data=(MEDIA/('audio-only.mp4' if LIVE_STALL else 'live-original.mp4')).read_bytes() if LIVE_TEST else b'fixture deliberately unsupported original codec'
            self.send_response(200);self.send_header('Content-Type','video/mp4');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
        path=MEDIA/Path(urllib.parse.urlsplit(self.path).path).name
        if not path.is_file():self.send_error(404);return
        data=path.read_bytes();start=0;end=len(data)-1;partial=False
        value=self.headers.get('Range','')
        if value.startswith('bytes='):
            a,b=value[6:].split('-',1);start=int(a or 0);end=min(end,int(b)) if b else end;partial=True
        if start> end:self.send_error(416);return
        data=data[start:end+1]
        self.send_response(206 if partial else 200)
        self.send_header('Content-Type','application/vnd.apple.mpegurl' if path.suffix=='.m3u8' else 'video/mp2t')
        self.send_header('Content-Length',str(len(data)));self.send_header('Accept-Ranges','bytes')
        if partial:self.send_header('Content-Range','bytes %d-%d/%d'%(start,end,path.stat().st_size))
        self.end_headers();self.wfile.write(data);self.wfile.flush()

if __name__=='__main__':
    test=prepare()
    deadline=time.monotonic()+240
    while time.monotonic()<deadline:
        if adb('shell','getprop','sys.boot_completed',timeout=5).strip()=='1':break
        time.sleep(1)
    else:raise RuntimeError('Emulator is not ready')
    server=http.server.ThreadingHTTPServer(('0.0.0.0',8771),Fixture)
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:
        print(adb('install','--no-streaming','-r',WORK/('dist/jellyfin-kitkat-'+VERSION+'.apk')),flush=True)
        print(adb('install','--no-streaming','-r',test),flush=True)
        command=['shell','am','instrument','-w']
        if FALLBACK_TEST:command+=['-e','scenario','fallback']
        if LIVE_TEST:command+=['-e','scenario','live']
        command+=['org.jellyfin.kitkat.tests/.SeekInstrumentation']
        output=adb(*command,timeout=180)
        print(output,flush=True)
        (OUT/('live-result.txt' if LIVE_TEST else 'fallback-result.txt' if FALLBACK_TEST else 'result.txt')).write_text(output,encoding='utf-8')
        (OUT/'requests.json').write_text(json.dumps(requests,indent=2),encoding='utf-8')
        if ('PASS live fallback:' if LIVE_TEST else 'PASS original failure -> 1080p fallback:' if FALLBACK_TEST else 'PASS HLS seek:') not in output:raise RuntimeError('Playback regression failed')
        negotiations=[x for x in requests if '/PlaybackInfo' in x[0] and x[1].startswith('{')]
        if len(negotiations)!=2:raise RuntimeError('Seeking unnecessarily reopened playback')
        if FALLBACK_TEST or LIVE_TEST:
            bodies=[json.loads(x[1]) for x in negotiations]
            if not bodies[0]['EnableDirectPlay'] or bodies[1]['EnableDirectPlay']:raise RuntimeError('Incorrect original/fallback order')
            limits=bodies[1]['DeviceProfile']['CodecProfiles'][0]['Conditions']
            if limits[1]['Value']!='1080':raise RuntimeError('Fallback did not request 1080p')
            if LIVE_TEST:
                paths=[x[0] for x in requests]
                close=next(i for i,p in enumerate(paths) if p.startswith('/LiveStreams/Close'))
                second=next(i for i,x in enumerate(requests) if x in negotiations[1:])
                if close>=second:raise RuntimeError('Live source reopened before old source was closed')
    finally:server.shutdown()
