package org.jellyfin.kitkat;

import android.app.*;
import android.os.*;
import android.content.*;
import android.media.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Native MediaPlayer H264/AAC + MPEG-TS HLS, supported by Android 4.4. */
public final class PlayerActivity extends Activity implements SurfaceHolder.Callback {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final ThreadPoolExecutor reports=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>());
    static { reports.allowCoreThreadTimeOut(true); }
    private final Handler ui=new Handler();
    private Api api;
    private Api.Playback session;
    private MediaPlayer player;
    private SurfaceView surface;
    private FrameLayout frame;
    private LinearLayout controls;
    private TextView title,status,time;
    private Button pause;
    private android.content.SharedPreferences prefs;
    private String itemId,itemTitle,selectedSourceId="";
    private boolean videoRendered=false;
    private boolean live,ready=false,closed=false,forced=false,retried=false,surfaceReady=false,started=false;
    private boolean seeking=false,playAfterSeek=false;
    private boolean scrubbing=false,scrubWasPlaying=false;
    private int scrubDirection=0;
    private long scrubTarget=0,scrubStarted=0;
    private long resumeTicks,lastPosition;
    private long initialSeek=0;
    private int audioIndex,subtitleIndex,generation=0,channelIndex=0;
    private ArrayList<String> channelIds,channelNames;
    private final Runnable hide=new Runnable(){public void run(){if(ready&&!scrubbing&&!seeking){controls.setVisibility(View.GONE);title.setVisibility(View.GONE);}}};
    private final Runnable advanceScrub=new Runnable(){public void run(){
        if(!scrubbing||closed)return;
        nudgeScrub(SystemClock.uptimeMillis()-scrubStarted>=2000?30000:10000);
        ui.postDelayed(this,200);
    }};
    private final Runnable timeout=new Runnable(){public void run(){if(!closed&&!ready)failure("视频加载超时，请检查服务端转码和直播源。",true);}};
    private final Runnable firstFrameTimeout=new Runnable(){public void run(){
        if(ready&&(!isPlaying()||scrubbing)){if(!closed&&session!=null&&"DirectPlay".equals(session.method))ui.postDelayed(this,15000);return;}
        if(!closed&&!videoRendered&&session!=null&&"DirectPlay".equals(session.method))failure("直放未能显示画面，正在尝试转码。",true);
    }};
    private final Runnable startInitialSeek=new Runnable(){public void run(){
        if(closed||!ready||player==null||initialSeek<=0)return;
        long target=initialSeek;initialSeek=0;seekToTicks(target,true);
    }};
    private final Runnable seekTimeout=new Runnable(){public void run(){if(!closed&&seeking)failure("播放器未完成跳转，请选择转码重试。",false);}};
    private final Runnable heartbeat=new Runnable(){public void run(){if(closed)return;if(ready&&started&&!seeking&&!scrubbing&&session!=null){
        lastPosition=positionTicks();report("/Progress",session,lastPosition,!isPlaying());updateTime();
    }ui.postDelayed(this,10000);}};
    public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs=getSharedPreferences("jellyfin",MODE_PRIVATE);
        try{api=new Api(prefs.getString("server",""),prefs.getString("token",""),prefs.getString("user",""),prefs.getString("device",""));}
        catch(Exception e){finish();return;}
        Intent intent=getIntent();itemId=intent.getStringExtra("id");itemTitle=intent.getStringExtra("title");live=intent.getBooleanExtra("live",false);
        resumeTicks=intent.getLongExtra("start",0);audioIndex=intent.getIntExtra("audio",-1);subtitleIndex=intent.getIntExtra("subtitle",-1);
        selectedSourceId=intent.getStringExtra("source");if(selectedSourceId==null)selectedSourceId="";
        channelIds=intent.getStringArrayListExtra("channelIds");channelNames=intent.getStringArrayListExtra("channelNames");channelIndex=intent.getIntExtra("channelIndex",0);
        forced=prefs.getBoolean("force",false)||subtitleIndex>=0;
        frame=new FrameLayout(this);frame.setBackgroundColor(0xff000000);setContentView(frame);
        surface=new SurfaceView(this);frame.addView(surface,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));surface.getHolder().addCallback(this);
        title=text(itemTitle,24);title.setPadding(dp(24),dp(16),dp(24),dp(16));title.setBackgroundColor(0xb0000000);
        frame.addView(title,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        status=text("正在准备播放…",21);status.setGravity(Gravity.CENTER);frame.addView(status,new FrameLayout.LayoutParams(-1,-1));
        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(dp(20),dp(10),dp(20),dp(14));controls.setBackgroundColor(0xd0101d28);
        time=text("",17);controls.addView(time);LinearLayout buttons=new LinearLayout(this);controls.addView(buttons);
        pause=button("暂停／播放",new Runnable(){public void run(){toggle();}});buttons.addView(pause);
        if(live){buttons.addView(button("上一台",new Runnable(){public void run(){switchChannel(-1);}}));buttons.addView(button("下一台",new Runnable(){public void run(){switchChannel(1);}}));}
        else{buttons.addView(seekButton("快退",-1));buttons.addView(seekButton("快进",1));}
        buttons.addView(button("转码重试",new Runnable(){public void run(){retryTranscode();}}));
        buttons.addView(button("返回",new Runnable(){public void run(){finish();}}));
        frame.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        ui.postDelayed(heartbeat,10000);
    }
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(0xfff2f8fb);t.setTextSize(size);return t;}
    private Button button(String text,final Runnable action){Button b=new Button(this);b.setText(text);b.setTextSize(17);b.setTextColor(0xffedf5fa);b.setBackgroundResource(R.drawable.focus);b.setPadding(dp(15),dp(6),dp(15),dp(6));b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();showControls();}});return b;}
    private Button seekButton(String text,final int direction){
        Button b=button(text,new Runnable(){public void run(){beginScrub(direction);commitScrub();}});
        b.setOnKeyListener(new View.OnKeyListener(){public boolean onKey(View view,int key,KeyEvent event){
            if(key!=KeyEvent.KEYCODE_DPAD_CENTER&&key!=KeyEvent.KEYCODE_ENTER&&key!=KeyEvent.KEYCODE_SPACE)return false;
            if(event.getAction()==KeyEvent.ACTION_DOWN)beginScrub(direction);
            else if(event.getAction()==KeyEvent.ACTION_UP)commitScrub();return true;
        }});
        b.setOnTouchListener(new View.OnTouchListener(){public boolean onTouch(View view,MotionEvent event){
            if(event.getAction()==MotionEvent.ACTION_DOWN)beginScrub(direction);
            else if(event.getAction()==MotionEvent.ACTION_UP)commitScrub();
            else if(event.getAction()==MotionEvent.ACTION_CANCEL)cancelScrub();return true;
        }});return b;
    }
    public void surfaceCreated(SurfaceHolder holder){surfaceReady=true;if(!closed&&player==null)prepare();}
    public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){}
    public void surfaceDestroyed(SurfaceHolder holder){surfaceReady=false;releaseCurrent();}
    private void prepare(){
        if(closed||!surfaceReady)return;ready=false;started=false;status.setVisibility(View.VISIBLE);status.setText("正在协商视频格式…");showControls();
        final int ticket=++generation;final String requested=itemId;final long start=live?0:resumeTicks;final boolean transcode=forced;
        lastPosition=start;
        ui.removeCallbacks(timeout);ui.postDelayed(timeout,60000);
        worker.execute(new Runnable(){public void run(){try{
            final Api.Playback result=api.playback(requested,live,start,transcode,prefs.getInt("height",1080),prefs.getInt("bitrate",12000000),audioIndex,subtitleIndex,selectedSourceId);
            ui.post(new Runnable(){public void run(){if(closed||ticket!=generation||!surfaceReady){cleanup(result);return;}session=result;selectedSourceId=result.sourceId;if("Transcode".equals(result.method))forced=true;openPlayer(ticket);}});
        }catch(final Exception e){ui.post(new Runnable(){public void run(){if(closed||ticket!=generation)return;failure(MainActivity.friendly(e),!transcode);}});}}});
    }
    private void openPlayer(final int ticket){
        try{
            videoRendered=false;
            if("DirectPlay".equals(session.method))ui.postDelayed(firstFrameTimeout,15000);
            player=new MediaPlayer();player.setAudioStreamType(AudioManager.STREAM_MUSIC);player.setDisplay(surface.getHolder());
            player.setScreenOnWhilePlaying(true);
            player.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener(){public void onVideoSizeChanged(MediaPlayer p,int w,int h){fit(w,h);}});
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener(){public void onPrepared(MediaPlayer p){
                if(closed||ticket!=generation)return;ready=true;ui.removeCallbacks(timeout);status.setVisibility(View.GONE);
                if(!live&&session.startTicks>0){
                    initialSeek=session.startTicks;lastPosition=initialSeek;player.setVolume(0,0);
                    status.setText("正在恢复播放位置…");status.setBackgroundColor(0xff000000);status.setVisibility(View.VISIBLE);
                    player.start();ui.postDelayed(startInitialSeek,500);
                }
                else beginPlayback(true);
                pause.requestFocus();
            }});
            player.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener(){public void onSeekComplete(MediaPlayer p){
                if(closed||ticket!=generation||!seeking)return;
                seeking=false;ui.removeCallbacks(seekTimeout);p.setVolume(1,1);status.setBackgroundColor(0x00000000);status.setVisibility(View.GONE);lastPosition=(long)p.getCurrentPosition()*10000;
                beginPlayback(playAfterSeek);
            }});
            player.setOnInfoListener(new MediaPlayer.OnInfoListener(){public boolean onInfo(MediaPlayer p,int what,int extra){
                if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START){videoRendered=true;ui.removeCallbacks(firstFrameTimeout);}
                if(scrubbing)return false;
                if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START&&initialSeek>0){ui.removeCallbacks(startInitialSeek);startInitialSeek.run();}
                if(what==MediaPlayer.MEDIA_INFO_BUFFERING_START){status.setText("正在缓冲…");status.setVisibility(View.VISIBLE);}
                else if(what==MediaPlayer.MEDIA_INFO_BUFFERING_END&&!seeking&&initialSeek==0)status.setVisibility(View.GONE);return false;
            }});
            player.setOnErrorListener(new MediaPlayer.OnErrorListener(){public boolean onError(MediaPlayer p,int what,int extra){
                if(ticket==generation&&!closed)failure("电视播放器无法播放（"+what+" / "+extra+"）。请尝试转码重试，并检查服务端 FFmpeg。",true);return true;
            }});
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){public void onCompletion(MediaPlayer p){if(ticket!=generation||closed)return;
                if(live)failure("直播流已结束，正在尝试转码。",true);else{lastPosition=positionTicks();finish();}
            }});
            player.setDataSource(this,Uri.parse(session.url),api.headers());status.setText("正在加载视频…");player.prepareAsync();
        }catch(Exception e){failure(MainActivity.friendly(e),true);}
    }
    private void fit(final int width,final int height){if(width<=0||height<=0)return;frame.post(new Runnable(){public void run(){
        int w=frame.getWidth(),h=frame.getHeight();if(w==0||h==0)return;
        if((long)w*height>(long)h*width)w=(int)((long)h*width/height);else h=(int)((long)w*height/width);
        surface.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));
    }});}
    private boolean isPlaying(){try{return ready&&player!=null&&player.isPlaying();}catch(Exception e){return false;}}
    private long positionTicks(){
        if(live)return 0;
        if(seeking||initialSeek>0)return lastPosition;
        try{if(ready&&player!=null)return (long)player.getCurrentPosition()*10000;}catch(Exception ignored){}
        return lastPosition;
    }
    private void updateTime(){
        long position=positionTicks()/10000000;long duration=0;try{if(player!=null)duration=player.getDuration()/1000;}catch(Exception ignored){}
        time.setText(live?"直播 · 上／下键切换本页频道 · 确认键显示控制栏":
            stamp(position)+" / "+stamp(duration)+"  ·  左／右键快退快进 · "+(session==null?"":session.method.equals("DirectPlay")?"直接播放":"兼容转码"));
    }
    private String stamp(long n){return String.format(java.util.Locale.US,"%d:%02d:%02d",n/3600,(n/60)%60,n%60);}
    private void toggle(){if(!ready||player==null||seeking||scrubbing)return;try{if(player.isPlaying())player.pause();else player.start();pause.setText(player.isPlaying()?"暂停":"播放");report("/Progress",session,positionTicks(),!player.isPlaying());}catch(Exception ignored){}}
    private void beginPlayback(boolean play){
        if(play)player.start();
        if(!started){started=true;report("",session,positionTicks(),!play);}
        else report("/Progress",session,positionTicks(),!play);
        pause.setText(play?"暂停":"播放");updateTime();showControls();
    }
    private void seekToTicks(long target,boolean play){
        int duration=player.getDuration();
        long millis=Math.max(0,target/10000);
        if(duration>0)millis=Math.min(millis,Math.max(0,duration-1000));
        lastPosition=millis*10000;playAfterSeek=play;seeking=true;
        ui.removeCallbacks(seekTimeout);ui.postDelayed(seekTimeout,20000);
        status.setText("正在跳转到 "+stamp(millis/1000)+"…");status.setVisibility(View.VISIBLE);
        player.seekTo((int)Math.min(Integer.MAX_VALUE,millis));
    }
    private void beginScrub(int direction){
        if(live||!ready||player==null||seeking||initialSeek>0||scrubbing)return;
        scrubTarget=positionTicks();scrubDirection=direction;scrubWasPlaying=isPlaying();scrubStarted=SystemClock.uptimeMillis();scrubbing=true;
        try{if(scrubWasPlaying)player.pause();}catch(Exception ignored){}
        ui.removeCallbacks(hide);nudgeScrub(10000);ui.postDelayed(advanceScrub,200);
    }
    private void nudgeScrub(int stepMillis){
        scrubTarget=Math.max(0,scrubTarget+scrubDirection*stepMillis*10000L);
        long duration=0;try{duration=player.getDuration();}catch(Exception ignored){}
        if(duration>0)scrubTarget=Math.min(scrubTarget,Math.max(0,duration-1000)*10000L);
        status.setBackgroundColor(0xd0000000);status.setVisibility(View.VISIBLE);
        status.setText((scrubDirection>0?"快进":"快退")+"预览  "+stamp(scrubTarget/10000000)+" / "+stamp(duration/1000)+"\n松开后跳转播放 · 返回取消");
        controls.setVisibility(View.VISIBLE);title.setVisibility(View.VISIBLE);time.setText("目标时间："+stamp(scrubTarget/10000000));
    }
    private void commitScrub(){
        if(!scrubbing)return;scrubbing=false;ui.removeCallbacks(advanceScrub);status.setBackgroundColor(0x00000000);
        try{seekToTicks(scrubTarget,scrubWasPlaying);}catch(Exception e){failure("跳转失败，请重试。",false);}
    }
    private void cancelScrub(){
        if(!scrubbing)return;scrubbing=false;ui.removeCallbacks(advanceScrub);status.setBackgroundColor(0x00000000);status.setVisibility(View.GONE);
        try{if(scrubWasPlaying&&player!=null)player.start();}catch(Exception ignored){}showControls();
    }
    private void prepareAfterCleanup(){
        final int ticket=generation;
        reports.execute(new Runnable(){public void run(){ui.post(new Runnable(){public void run(){if(!closed&&ticket==generation)prepare();}});}});
    }
    private void retryTranscode(){long position=positionTicks();releaseCurrent();if(live)selectedSourceId="";resumeTicks=live?0:position;forced=true;retried=true;prepareAfterCleanup();}
    private void switchChannel(int direction){if(channelIds==null||channelIds.size()<2){Toast.makeText(this,"本页没有其他频道，请返回频道列表",Toast.LENGTH_SHORT).show();return;}
        releaseCurrent();channelIndex=(channelIndex+direction+channelIds.size())%channelIds.size();itemId=channelIds.get(channelIndex);itemTitle=channelNames.get(channelIndex);
        title.setText(itemTitle);selectedSourceId="";resumeTicks=lastPosition=0;retried=false;forced=prefs.getBoolean("force",false);prepareAfterCleanup();
    }
    private void failure(String message,boolean allowFallback){
        boolean fallback=allowFallback&&!forced&&!retried&&(session==null||"DirectPlay".equals(session.method));long position=positionTicks();releaseCurrent();
        if(fallback){forced=true;retried=true;if(live)selectedSourceId="";resumeTicks=position;status.setText("直放失败，正在改用兼容转码…");prepareAfterCleanup();return;}
        status.setVisibility(View.VISIBLE);status.setText(message);controls.setVisibility(View.VISIBLE);title.setVisibility(View.VISIBLE);pause.setText("暂停／播放");
        ui.removeCallbacks(hide);
    }
    private void report(final String event,final Api.Playback p,final long position,final boolean paused){
        if(p==null||reports.isShutdown())return;
        if(event.equals("/Progress")&&reports.getQueue().size()>0)return;
        reports.execute(new Runnable(){public void run(){try{api.report(event,p,position,paused);}catch(Exception ignored){}}});
    }
    private void cleanup(final Api.Playback p){if(p!=null&&!reports.isShutdown())reports.execute(new Runnable(){public void run(){api.cleanup(p);}});}
    private void releaseCurrent(){
        generation++;ui.removeCallbacks(timeout);ui.removeCallbacks(firstFrameTimeout);ui.removeCallbacks(hide);ui.removeCallbacks(startInitialSeek);ui.removeCallbacks(seekTimeout);ui.removeCallbacks(advanceScrub);scrubbing=false;
        long position=positionTicks();if(player!=null){try{player.setOnErrorListener(null);player.setOnPreparedListener(null);player.setOnCompletionListener(null);player.reset();player.release();}catch(Exception ignored){}player=null;}
        if(session!=null){if(started)report("/Stopped",session,position,false);cleanup(session);session=null;}
        lastPosition=position;ready=false;started=false;seeking=false;initialSeek=0;
    }
    private void showControls(){controls.setVisibility(View.VISIBLE);title.setVisibility(View.VISIBLE);ui.removeCallbacks(hide);if(ready&&!scrubbing&&isPlaying())ui.postDelayed(hide,7000);if(!scrubbing)updateTime();}
    public boolean onKeyDown(int key,KeyEvent event){
        if(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||key==KeyEvent.KEYCODE_MEDIA_REWIND){beginScrub(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD?1:-1);return true;}
        if(scrubbing&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT))return true;
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||key==KeyEvent.KEYCODE_MEDIA_PLAY||key==KeyEvent.KEYCODE_MEDIA_PAUSE){toggle();showControls();return true;}
        if(controls.getVisibility()!=View.VISIBLE){
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){showControls();pause.requestFocus();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_LEFT){beginScrub(-1);return true;}if(key==KeyEvent.KEYCODE_DPAD_RIGHT){beginScrub(1);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(live&&event.getRepeatCount()==0)switchChannel(key==KeyEvent.KEYCODE_DPAD_UP?-1:1);else showControls();return true;}
        }else if(key>=KeyEvent.KEYCODE_DPAD_UP&&key<=KeyEvent.KEYCODE_DPAD_CENTER){ui.removeCallbacks(hide);if(ready&&isPlaying())ui.postDelayed(hide,12000);}
        return super.onKeyDown(key,event);
    }
    public boolean onKeyUp(int key,KeyEvent event){
        if(scrubbing&&(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||key==KeyEvent.KEYCODE_MEDIA_REWIND||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)){commitScrub();return true;}
        return super.onKeyUp(key,event);
    }
    public void onBackPressed(){if(scrubbing)cancelScrub();else finish();}
    protected void onStop(){closed=true;releaseCurrent();ui.removeCallbacks(heartbeat);super.onStop();finish();}
    protected void onDestroy(){closed=true;releaseCurrent();ui.removeCallbacks(heartbeat);worker.shutdown();
        // Reports use one process-wide ordered queue. Late negotiations still close their live source.
        super.onDestroy();}
}
