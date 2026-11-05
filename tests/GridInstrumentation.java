package org.jellyfin.kitkat.tests;

import android.app.*;
import android.os.*;
import android.content.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** Tests real grid pagination and folder-back focus with 125 fixture movies. */
public final class GridInstrumentation extends Instrumentation {
    private Activity activity;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private Object field(String name)throws Exception{Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private int count()throws Exception{final int[] n={0};runOnMainSync(new Runnable(){public void run(){try{n[0]=((ArrayList<?>)field("rows")).size();}catch(Exception e){throw new RuntimeException(e);}}});return n[0];}
    private void waitCount(int minimum,int maximum)throws Exception{
        long deadline=System.currentTimeMillis()+20000;
        while(System.currentTimeMillis()<deadline){int n=count();if(n>=minimum&&n<=maximum)return;Thread.sleep(150);}
        throw new Exception("Grid expected "+minimum+".."+maximum+", actual "+count());
    }
    private void select(final int index,final boolean click){runOnMainSync(new Runnable(){public void run(){try{
        GridView grid=(GridView)field("list");grid.requestFocus();grid.setSelection(index);
        if(click)grid.performItemClick(null,index,index);
    }catch(Exception e){throw new RuntimeException(e);}}});}
    public void onStart(){Bundle result=new Bundle();try{
        setInTouchMode(false);
        getTargetContext().getSharedPreferences("jellyfin",0).edit().putString("server","http://10.0.2.2:8771")
            .putString("token","fixture-token").putString("user","fixture-user").putString("device","fixture-device").commit();
        activity=startActivitySync(new Intent().setClassName("org.jellyfin.kitkat","org.jellyfin.kitkat.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitCount(1,1);select(0,true);waitCount(40,40);
        select(38,false);waitCount(80,80);
        select(20,false);Thread.sleep(300);select(20,true);waitCount(1,1);
        runOnMainSync(new Runnable(){public void run(){activity.onBackPressed();}});waitCount(80,80);Thread.sleep(300);
        final int[] selected={-1};runOnMainSync(new Runnable(){public void run(){try{selected[0]=((GridView)field("list")).getSelectedItemPosition();}catch(Exception e){throw new RuntimeException(e);}}});
        if(selected[0]!=20)throw new Exception("Folder back lost selection: "+selected[0]);
        select(0,false);Thread.sleep(1500);
        Bundle ready=new Bundle();ready.putString("stream","GRID_READY");sendStatus(0,ready);Thread.sleep(6000);
        result.putString("stream","PASS grid: 40 -> 80 items automatically; folder back retains 80 items and selection 20");
        finish(Activity.RESULT_OK,result);
    }catch(Exception e){result.putString("stream","FAIL grid: "+e.toString());finish(Activity.RESULT_CANCELED,result);}}
}
