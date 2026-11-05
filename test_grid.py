"""Validate poster grid, incremental loading and folder return focus on API 19."""
import json, http.server, threading, time, urllib.parse, zlib, struct, subprocess
from test_seek import prepare, adb, OUT, WORK, ADB, env

def png(color):
    w,h=120,160
    raw=b''.join(b'\0'+bytes(color)*w for _ in range(h))
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')

requests=[]
class Fixture(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
        url=urllib.parse.urlsplit(self.path);q=urllib.parse.parse_qs(url.query);requests.append(url.path+'?'+url.query)
        if '/Images/Primary' in url.path:
            colors=[(24,95,125),(82,52,116),(32,108,89),(135,74,45),(69,85,116),(120,52,77)]
            data=png(colors[sum(url.path.encode())%len(colors)]);mime='image/png'
        else:
            if url.path.endswith('/Views'):
                items=[{'Id':'library','Name':'电影 · 网格测试','Type':'CollectionFolder','IsFolder':True,'ImageTags':{'Primary':'fixture'}}]
            elif q.get('ParentId')==['movie20']:
                items=[{'Id':'episode1','Name':'测试剧集','Type':'Episode','MediaType':'Video','ImageTags':{'Primary':'fixture'}}]
            else:
                items=[{'Id':'movie'+str(i),'Name':'影片 %03d'%i,'Type':'Series' if i==20 else 'Movie',
                    'IsFolder':i==20,'MediaType':'Video','ProductionYear':2000+i%25,'ImageTags':{'Primary':'fixture'}} for i in range(125)]
            start=int(q.get('StartIndex',['0'])[0]);limit=int(q.get('Limit',['40'])[0])
            data=json.dumps({'Items':items[start:start+limit],'TotalRecordCount':len(items)}).encode();mime='application/json'
        self.send_response(200);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)

if __name__=='__main__':
    test=prepare();server=http.server.ThreadingHTTPServer(('0.0.0.0',8771),Fixture)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    try:
        print(adb('install','--no-streaming','-r',test),flush=True)
        process=subprocess.Popen([str(ADB),'-s','emulator-5580','shell','am','instrument','-r','-w','org.jellyfin.kitkat.tests/.GridInstrumentation'],
            stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=env)
        lines=[]
        for raw in process.stdout:
            line=raw.decode('utf-8',errors='replace');lines.append(line)
            if 'GRID_READY' in line:
                adb('shell','screencap','-p','/data/local/tmp/grid-preview.png')
                adb('pull','/data/local/tmp/grid-preview.png',WORK/'diagnostics/grid.png')
        process.wait(timeout=100);result=''.join(lines)
        print(result,flush=True);(OUT/'grid-result.txt').write_text(result,encoding='utf-8')
        (OUT/'grid-requests.json').write_text(json.dumps(requests,indent=2),encoding='utf-8')
        if 'PASS grid:' not in result:raise RuntimeError('Grid regression failed')
    finally:server.shutdown()
