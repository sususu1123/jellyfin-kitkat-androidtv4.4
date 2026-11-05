"""Validate embedded MKV subtitle stream indexes and server-style burn-in using generated media.

This tests FFmpeg burn-in, not the user's Jellyfin server or subtitle fonts on their media.
The actual API/source-selection contract is exercised by ApiTest.java.
"""
from pathlib import Path
import subprocess,json,os

ROOT=Path('E:/JellyfinKitKat/subtitle-test');ROOT.mkdir(parents=True,exist_ok=True)
env=dict(os.environ,TEMP='E:/JellyfinKitKat/tmp',TMP='E:/JellyfinKitKat/tmp')
def ffmpeg(*args,capture=False):
    command=['ffmpeg','-hide_banner','-loglevel','error',*map(str,args)]
    return subprocess.check_output(command,cwd=ROOT,env=env) if capture else subprocess.run(command,cwd=ROOT,env=env,check=True)

for name,text in [('first.srt','WRONG TRACK'),('selected.srt','SELECTED SUBTITLE')]:
    (ROOT/name).write_text('1\n00:00:00,100 --> 00:00:05,800\n'+text+'\n',encoding='utf-8')
ffmpeg('-y','-f','lavfi','-i','color=c=black:size=320x180:rate=10','-f','lavfi','-i','anullsrc=r=44100:cl=stereo',
       '-i','first.srt','-i','selected.srt','-t','6','-map','0:v','-map','1:a','-map','2','-map','3',
       '-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac','-c:s','srt','fixture.mkv')
probe=subprocess.check_output(['ffprobe','-v','error','-show_streams','-of','json','fixture.mkv'],cwd=ROOT,env=env)
streams=json.loads(probe)['streams'];subs=[s for s in streams if s['codec_type']=='subtitle']
assert [s['index'] for s in subs]==[2,3]
assert all(s['codec_name']=='subrip' for s in subs)
ffmpeg('-y','-i','fixture.mkv','-ss','2','-vf','subtitles=fixture.mkv:si=1','-frames:v','1','-an','-sn','burned.png')
burned=ffmpeg('-i','burned.png','-f','rawvideo','-pix_fmt','gray','-frames:v','1','pipe:1',capture=True)
plain=ffmpeg('-i','fixture.mkv','-ss','2','-an','-sn','-frames:v','1','-f','rawvideo','-pix_fmt','gray','pipe:1',capture=True)
visible=sum(p>180 for p in burned);baseline=sum(p>180 for p in plain)
assert baseline<5 and visible>50,(baseline,visible)
(ROOT/'result.txt').write_text('PASS embedded MKV SubRip: global stream index 3 maps to subtitle ordinal 1; '
    +'burn-in visible white pixels='+str(visible)+', original black frame='+str(baseline)+'\n',encoding='utf-8')
print((ROOT/'result.txt').read_text(),flush=True)
