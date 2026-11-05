package org.jellyfin.kitkat.tests;

import android.app.*;
import android.os.*;
import android.content.*;
import android.media.MediaPlayer;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import java.lang.reflect.*;

/** Runs against the production player on a real API-19 virtual device with an HTTP HLS fixture. */
public final class SeekInstrumentation extends Instrumentation {
    private Activity activity;
    private String steps="";
    private boolean fallbackTest=false;
    private boolean liveTest=false;
    public void onCreate(Bundle args){super.onCreate(args);liveTest=args!=null&&"live".equals(args.getString("scenario"));fallbackTest=liveTest||args!=null&&"fallback".equals(args.getString("scenario"));start();}
    private Object field(String name)throws Exception{
        Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(activity);
    }
    private int position()throws Exception{
        final int[] result={-1};final Exception[] error={null};
        runOnMainSync(new Runnable(){public void run(){try{
            MediaPlayer p=(MediaPlayer)field("player");
            if(Boolean.TRUE.equals(field("ready"))&&!Boolean.TRUE.equals(field("seeking"))&&p!=null)result[0]=p.getCurrentPosition();
        }catch(Exception e){error[0]=e;}}});
        if(error[0]!=null)throw error[0];return result[0];
    }
    private int waitPosition(int minimum,int maximum)throws Exception{
        long deadline=System.currentTimeMillis()+45000;
        while(System.currentTimeMillis()<deadline){int p=position();if(p>=minimum&&p<=maximum)return p;Thread.sleep(200);}
        throw new Exception("Expected position "+minimum+".."+maximum+", actual "+position());
    }
    private void launch(long ticks)throws Exception{
        Intent i=new Intent().setClassName("org.jellyfin.kitkat","org.jellyfin.kitkat.PlayerActivity")
            .putExtra("id","movie-1").putExtra("title","HLS seek regression")
            .putExtra("start",ticks).putExtra("live",liveTest).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity=startActivitySync(i);
    }
    private void close(){if(activity!=null)runOnMainSync(new Runnable(){public void run(){activity.finish();}});}
    private void holdSeek(final int key)throws Exception{
        final int before=position();
        runOnMainSync(new Runnable(){public void run(){activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));}});
        Thread.sleep(650);
        final boolean[] preview={false};runOnMainSync(new Runnable(){public void run(){try{preview[0]=Boolean.TRUE.equals(field("scrubbing"))&&!Boolean.TRUE.equals(field("seeking"));}catch(Exception e){throw new RuntimeException(e);}}});
        // KitKat may settle a completed HLS seek to the preceding six-second segment boundary.
        if(!preview[0]||Math.abs(position()-before)>8000)throw new Exception("Playback sought while key was held");
        steps+="hold=preview-only; ";
        runOnMainSync(new Runnable(){public void run(){activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));}});
    }
    public void onStart(){
        Bundle result=new Bundle();
        try{
            setInTouchMode(false);
            getTargetContext().getSharedPreferences("jellyfin",0).edit().putString("server","http://10.0.2.2:8771")
                .putString("token","fixture-token").putString("user","fixture-user").putString("device","fixture-device")
                .putBoolean("force",!fallbackTest).putInt("height",fallbackTest?1080:480).putInt("bitrate",fallbackTest?12000000:2000000).commit();
            launch(0);
            if(liveTest){
                long deadline=System.currentTimeMillis()+45000;boolean transcoded=false;
                while(System.currentTimeMillis()<deadline){final boolean[] done={false};runOnMainSync(new Runnable(){public void run(){try{
                    Object session=field("session");done[0]=session!=null&&"Transcode".equals(session.getClass().getField("method").get(session));
                }catch(Exception e){throw new RuntimeException(e);}}});if(done[0]){transcoded=true;break;}Thread.sleep(200);}
                if(!transcoded)throw new Exception("Live playback did not automatically transcode");
            }
            steps+="start="+waitPosition(300,10000)+"; ";
            if(fallbackTest){close();result.putString("stream",(liveTest?"PASS live fallback: ":"PASS original failure -> 1080p fallback: ")+steps);finish(Activity.RESULT_OK,result);return;}
            holdSeek(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);steps+="forward="+waitPosition(25000,50000)+"; ";
            Thread.sleep(1200);
            runOnMainSync(new Runnable(){public void run(){try{
                ((LinearLayout)((LinearLayout)field("controls")).getChildAt(1)).getChildAt(1).requestFocus();
            }catch(Exception e){throw new RuntimeException(e);}}});
            holdSeek(KeyEvent.KEYCODE_DPAD_CENTER);steps+="backward="+waitPosition(0,12000)+"; ";
            close();Thread.sleep(1500);
            launch(600000000L);steps+="resume="+waitPosition(55000,72000)+"; ";close();
            result.putString("stream","PASS HLS seek: "+steps);finish(Activity.RESULT_OK,result);
        }catch(Exception e){close();result.putString("stream","FAIL HLS seek: "+steps+e.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
