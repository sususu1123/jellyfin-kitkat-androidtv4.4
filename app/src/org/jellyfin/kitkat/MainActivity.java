package org.jellyfin.kitkat;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.util.LruCache;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final ExecutorService network=Executors.newFixedThreadPool(2);
    private final ExecutorService pictures=Executors.newFixedThreadPool(2);
    private final Handler ui=new Handler();
    private final ArrayList<JSONObject> rows=new ArrayList<JSONObject>();
    private final ArrayDeque<Page> history=new ArrayDeque<Page>();
    private final LruCache<String,Bitmap> images=new LruCache<String,Bitmap>(4*1024*1024) {
        protected int sizeOf(String key,Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private android.content.SharedPreferences prefs;
    private Api api;
    private LinearLayout root, nav, footer;
    private TextView heading, status;
    private GridView list;
    private Page page;
    private int generation=0;
    private boolean busy=false, dead=false;
    private int columns=6;
    private static final int SIZE=40;
    private static final class Page {
        String title,path; int offset=0,selected=0,total=-1;
        boolean hasMore=true,failed=false;
        ArrayList<JSONObject> items=new ArrayList<JSONObject>();
        Page(String t,String p) {title=t;path=p;}
    }
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs=getSharedPreferences("jellyfin",MODE_PRIVATE);
        if(prefs.getInt("qualityPolicy",0)<1)prefs.edit().putInt("qualityPolicy",1).putBoolean("force",false).putInt("height",1080).putInt("bitrate",12000000).commit();
        if (!prefs.contains("device")) prefs.edit().putString("device",UUID.randomUUID().toString()).commit();
        reloadApi();
        if (api == null) login(); else browser();
    }
    private void reloadApi() {
        String token=prefs.getString("token","");
        api=token.length()==0 ? null : new Api(prefs.getString("server",""),token,prefs.getString("user",""),prefs.getString("device",""));
    }
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density*n+0.5f); }
    private TextView label(String text,int sp) {
        TextView v=new TextView(this); v.setText(text); v.setTextColor(0xffedf5fa); v.setTextSize(sp); return v;
    }
    private Button button(String text,View.OnClickListener action) {
        Button b=new Button(this); b.setText(text); b.setTextColor(0xffedf5fa); b.setTextSize(17);
        b.setBackgroundResource(R.drawable.focus); b.setPadding(dp(16),dp(8),dp(16),dp(8));
        b.setOnClickListener(action); return b;
    }
    private void initRoot() {
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(30),dp(18),dp(30),dp(14));
        root.setBackgroundColor(0xff0b141d); setContentView(root);
    }
    private void login() {
        generation++; history.clear(); page=null; initRoot();
        root.addView(label("Jellyfin · 精简版",30));
        root.addView(label("Android 4.4.2  ·  媒体库与电视直播",17));
        final EditText server=new EditText(this); server.setSingleLine(true); server.setTextSize(20);
        server.setHint("服务器，例如 http://192.168.1.10:8096"); server.setText(prefs.getString("server",""));
        server.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        final EditText user=new EditText(this); user.setSingleLine(true); user.setHint("用户名"); user.setText(prefs.getString("name",""));
        final EditText password=new EditText(this); password.setSingleLine(true); password.setHint("密码（不保存）");
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(server); root.addView(user); root.addView(password);
        final TextView message=label("请填写 Jellyfin 服务器和账号。局域网可使用 HTTP。",16); root.addView(message);
        final Button connect=button("连接并登录",null); root.addView(connect);
        connect.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            final String address=server.getText().toString(), name=user.getText().toString(), secret=password.getText().toString();
            if(name.trim().length()==0){message.setText("请输入用户名");return;}
            final Api pending;
            try {pending=new Api(address,"","",prefs.getString("device",""));}
            catch(Exception e){message.setText(e.getMessage());return;}
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(password.getWindowToken(),0);
            connect.setEnabled(false); message.setText("正在连接服务器…"); final int ticket=++generation;
            network.execute(new Runnable(){public void run(){try{
                final JSONObject result=pending.login(name,secret);
                final String token=result.getString("AccessToken"), uid=result.getJSONObject("User").getString("Id");
                ui.post(new Runnable(){public void run(){if(dead||ticket!=generation)return;
                    prefs.edit().putString("server",pending.base).putString("name",name).putString("token",token).putString("user",uid).commit();
                    password.setText(""); reloadApi(); browser();
                }});
            }catch(final Exception e){ui.post(new Runnable(){public void run(){if(dead||ticket!=generation)return; connect.setEnabled(true); message.setText(friendly(e));}});}}});
        }});
        server.requestFocus();
    }
    static String friendly(Exception e) {
        if(e instanceof javax.net.ssl.SSLException) return "HTTPS 连接失败：旧电视可能不支持服务器 TLS／证书；请检查服务端配置或使用可信局域网 HTTP。";
        if(e instanceof java.net.SocketTimeoutException) return "连接超时，请检查服务器地址、网络和服务端状态。";
        if(e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException) return "无法连接服务器，请检查地址、端口及网络。";
        return e.getMessage()==null ? "请求失败，请重试。" : e.getMessage();
    }
    private void browser() {
        generation++; initRoot();
        nav=new LinearLayout(this); root.addView(nav);
        addNav("媒体库",new Runnable(){public void run(){home();}});
        addNav("继续观看",new Runnable(){public void run(){open(new Page("继续观看","/Users/"+Api.id(api.userId)+"/Items/Resume?MediaTypes=Video&Fields=UserData,PrimaryImageAspectRatio"),false);}});
        addNav("电视直播",new Runnable(){public void run(){open(new Page("电视直播","/LiveTv/Channels?UserId="+Api.enc(api.userId)+"&AddCurrentProgram=true&SortBy=SortName"),false);}});
        addNav("搜索",new Runnable(){public void run(){search();}});
        addNav("设置",new Runnable(){public void run(){settings();}});
        heading=label("媒体库",24); heading.setPadding(0,dp(12),0,dp(8)); root.addView(heading);
        status=label("",15); root.addView(status);
        columns=Math.max(3,Math.min(7,(getResources().getDisplayMetrics().widthPixels-dp(60))/dp(190)));
        list=new GridView(this);list.setNumColumns(columns);list.setHorizontalSpacing(dp(10));list.setVerticalSpacing(dp(10));
        list.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);list.setSelector(android.R.color.transparent);list.setCacheColorHint(0);
        list.setAdapter(new MediaAdapter()); root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener(){
            public void onItemClick(AdapterView<?> parent,View view,int position,long id){if(!busy&&position<rows.size())select(rows.get(position),position);}
        });
        footer=new LinearLayout(this); root.addView(footer);
        footer.addView(button("加载更多",new View.OnClickListener(){public void onClick(View v){loadMore();}}));
        footer.addView(button("回到顶部",new View.OnClickListener(){public void onClick(View v){list.requestFocus();list.setSelection(0);}}));
        footer.addView(button("刷新／重试",new View.OnClickListener(){public void onClick(View v){if(!busy)load();}}));
        list.setOnScrollListener(new AbsListView.OnScrollListener(){
            public void onScrollStateChanged(AbsListView v,int state){}
            public void onScroll(AbsListView v,int first,int visible,int total){
                if(page!=null&&!page.failed&&!busy&&visible>0&&total>0&&first+visible>=total-columns*2)loadMore();
            }
        });
        home();
    }
    private void addNav(String text, final Runnable action) {
        Button b=button(text,new View.OnClickListener(){public void onClick(View v){action.run();}});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1); lp.setMargins(0,0,dp(6),0); nav.addView(b,lp);
    }
    private void home() {open(new Page("媒体库","/Users/"+Api.id(api.userId)+"/Views?IncludeExternalContent=false"),false);}
    private void open(Page next,boolean push) {
        if(push&&page!=null){int selected=list.getSelectedItemPosition();if(selected>=0)page.selected=selected;history.push(page);} else if(!push)history.clear();
        display(next);
    }
    private void display(Page next){
        generation++;busy=false;page=next;rows.clear();rows.addAll(page.items);
        ((BaseAdapter)list.getAdapter()).notifyDataSetChanged();heading.setText(page.title);
        if(rows.isEmpty())load();else{updateStatus();list.requestFocus();list.setSelection(Math.max(0,page.selected));}
    }
    private void load() {
        if(api==null||page==null)return;
        page.offset=0;page.total=-1;page.hasMore=true;page.failed=false;page.items.clear();
        rows.clear();((BaseAdapter)list.getAdapter()).notifyDataSetChanged();loadBatch(false);
    }
    private void loadMore(){if(!busy&&page!=null&&page.hasMore)loadBatch(true);}
    private void updateStatus(){
        if(page==null)return;
        status.setText(rows.isEmpty()?"没有内容。直播需要先在 Jellyfin 服务端配置，并向此账号开放。":
            "已加载 "+rows.size()+(page.total>=0?" / "+page.total:"")+" 项  ·  "+(page.hasMore?"向下浏览自动加载":"已全部加载")+"  ·  确认键打开，返回键回上层");
    }
    private void loadBatch(final boolean append) {
        if(api==null||page==null)return;
        busy=true; final Page requested=page; final int ticket=++generation; final Api client=api;
        requested.failed=false;heading.setText(page.title);status.setText(append?"正在加载更多…":"正在加载…");
        final String path=page.path+(page.path.contains("?")?"&":"?")+"StartIndex="+page.offset+"&Limit="+SIZE;
        network.execute(new Runnable(){public void run(){try{
            final JSONObject response=client.request("GET",path,null);
            ui.post(new Runnable(){public void run(){if(dead||ticket!=generation)return;
                JSONArray items=response.optJSONArray("Items");
                if(items!=null)for(int i=0;i<items.length()&&i<SIZE;i++){JSONObject item=items.optJSONObject(i);if(item!=null){JSONObject slim=compact(item);rows.add(slim);requested.items.add(slim);}}
                int received=items==null?0:items.length();requested.offset+=received;
                requested.total=response.optInt("TotalRecordCount",-1);
                requested.hasMore=received>0&&(requested.total>=0?requested.offset<requested.total:received>=SIZE);
                busy=false; ((BaseAdapter)list.getAdapter()).notifyDataSetChanged();
                updateStatus();
                if(!append&&!rows.isEmpty()){list.requestFocus();list.setSelection(Math.max(0,Math.min(requested.selected,rows.size()-1)));}
            }});
        }catch(final Exception e){ui.post(new Runnable(){public void run(){if(dead||ticket!=generation)return;busy=false;requested.failed=true;status.setText(friendly(e)+"  选择“加载更多”重试，或刷新。");}});}}});
    }
    private JSONObject compact(JSONObject full){
        // Retain only card/navigation metadata while scrolling large libraries on a 1 GB TV.
        JSONObject slim=new JSONObject();String[] keys={"Id","Name","Type","IsFolder","MediaType","ProductionYear","IndexNumber","ParentIndexNumber","ChannelNumber","ImageTags","SeriesId","SeriesPrimaryImageTag","CurrentProgram"};
        for(String key:keys)try{if(!full.isNull(key))slim.put(key,full.get(key));}catch(Exception ignored){}
        return slim;
    }
    private void select(final JSONObject item,final int position) {
        if(page!=null)page.selected=position;
        String type=item.optString("Type"), itemId=item.optString("Id");
        if(item.optBoolean("IsFolder")||type.equals("Series")||type.equals("Season")||type.equals("CollectionFolder")||type.equals("BoxSet")){
            String sort=type.equals("Series")||type.equals("Season")?"ParentIndexNumber,IndexNumber,SortName":"SortName";
            open(new Page(item.optString("Name"),"/Users/"+Api.id(api.userId)+"/Items?ParentId="+Api.enc(itemId)+"&SortBy="+sort+
                "&SortOrder=Ascending&Fields=UserData,PrimaryImageAspectRatio"),true);return;
        }
        if(type.equals("TvChannel")){launch(item,0,-1,-1,position);return;}
        if(!type.equals("Movie")&&!type.equals("Episode")&&!type.equals("Video")&&!item.optString("MediaType").equals("Video")){
            Toast.makeText(this,"精简版暂只支持视频和电视直播",Toast.LENGTH_LONG).show();return;
        }
        final int ticket=++generation; busy=true; status.setText("读取影片信息…"); final Api client=api;
        network.execute(new Runnable(){public void run(){try{
            final JSONObject full=client.item(item.optString("Id"));
            ui.post(new Runnable(){public void run(){if(dead||ticket!=generation)return;busy=false;status.setText("选择播放方式");details(full,position);}});
        }catch(final Exception e){ui.post(new Runnable(){public void run(){if(dead||ticket!=generation)return;busy=false;status.setText(friendly(e));}});}}});
    }
    private void details(final JSONObject item,final int position) {
        final long resume=item.optJSONObject("UserData")==null?0:item.optJSONObject("UserData").optLong("PlaybackPositionTicks");
        final int[] audio={-1}, subtitle={-1};
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),dp(8),dp(18),dp(8));
        TextView overview=label(item.optString("Overview",""),17);overview.setMaxLines(5);content.addView(overview);
        final JSONArray streams=new JSONArray(); JSONArray sources=item.optJSONArray("MediaSources");
        if(sources!=null&&sources.length()>0&&sources.optJSONObject(0)!=null){JSONArray tracks=sources.optJSONObject(0).optJSONArray("MediaStreams");
            if(tracks!=null)for(int i=0;i<tracks.length();i++)streams.put(tracks.optJSONObject(i));}
        final Button audioButton=button("音轨：服务端默认",null), subtitleButton=button("字幕：关闭",null);
        audioButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){chooseTrack(streams,"Audio",audio,audioButton);}});
        subtitleButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){chooseTrack(streams,"Subtitle",subtitle,subtitleButton);}});
        content.addView(audioButton);content.addView(subtitleButton);
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(item.optString("Name")).setView(content)
            .setPositiveButton("从头播放",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){launch(item,0,audio[0],subtitle[0],position);}})
            .setNegativeButton("返回",null);
        if(resume>0)builder.setNeutralButton("继续观看 "+(resume/600000000)+" 分钟",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){launch(item,resume,audio[0],subtitle[0],position);}});
        builder.show();
    }
    private void chooseTrack(JSONArray streams,String type,final int[] choice,final Button target) {
        final ArrayList<Integer> indexes=new ArrayList<Integer>();final ArrayList<String> names=new ArrayList<String>();
        indexes.add(-1);names.add(type.equals("Audio")?"服务端默认":"关闭字幕");
        for(int i=0;i<streams.length();i++){JSONObject s=streams.optJSONObject(i);if(s!=null&&type.equals(s.optString("Type"))){
            indexes.add(s.optInt("Index"));names.add(s.optString("DisplayTitle",s.optString("Language","未知语言")+" "+s.optString("Codec")));}}
        new AlertDialog.Builder(this).setTitle(type.equals("Audio")?"选择音轨":"选择字幕（服务端烧录）")
            .setItems(names.toArray(new String[0]),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){choice[0]=indexes.get(which);target.setText(names.get(which));}}).show();
    }
    private void launch(JSONObject item,long start,int audio,int subtitle,int position) {
        String source="";JSONArray sources=item.optJSONArray("MediaSources");
        if(sources!=null&&sources.length()>0&&sources.optJSONObject(0)!=null)source=sources.optJSONObject(0).optString("Id","");
        Intent intent=new Intent(this,PlayerActivity.class).putExtra("id",item.optString("Id")).putExtra("title",item.optString("Name"))
            .putExtra("live",item.optString("Type").equals("TvChannel")).putExtra("start",start).putExtra("audio",audio).putExtra("subtitle",subtitle).putExtra("source",source);
        if(item.optString("Type").equals("TvChannel")){
            ArrayList<String> ids=new ArrayList<String>(),names=new ArrayList<String>();int selected=0;
            for(JSONObject row:rows)if(row.optString("Type").equals("TvChannel")){if(row.optString("Id").equals(item.optString("Id")))selected=ids.size();ids.add(row.optString("Id"));names.add(row.optString("Name"));}
            intent.putStringArrayListExtra("channelIds",ids).putStringArrayListExtra("channelNames",names).putExtra("channelIndex",selected);
        }
        page.selected=position; startActivityForResult(intent,1);
    }
    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==1&&api!=null&&page!=null){list.requestFocus();list.setSelection(Math.max(0,page.selected));updateStatus();}}
    private void search(){
        final EditText input=new EditText(this);input.setSingleLine(true);input.setHint("电影、剧集或视频名称");
        new AlertDialog.Builder(this).setTitle("搜索媒体库").setView(input).setPositiveButton("搜索",new DialogInterface.OnClickListener(){
            public void onClick(DialogInterface d,int which){String term=input.getText().toString().trim();if(term.length()>0)open(new Page("搜索："+term,
                "/Users/"+Api.id(api.userId)+"/Items?Recursive=true&IncludeItemTypes=Movie,Series,Episode,Video&Fields=UserData&SearchTerm="+Api.enc(term)),true);}
        }).setNegativeButton("取消",null).show();
    }
    private void settings(){
        final String[] options={"播放：优先原画直放，失败后转码（默认）","播放：始终转码",
            "转码上限：1080p / 12 Mbps（默认）","转码上限：4K / 40 Mbps（需设备支持）",
            "转码上限：720p / 6 Mbps","转码上限：480p / 2 Mbps","退出登录"};
        new AlertDialog.Builder(this).setTitle("设置 · "+(prefs.getBoolean("force",false)?"始终转码":"优先原画")+" · 转码上限 "+prefs.getInt("height",1080)+"p")
            .setItems(options,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){
                if(w<2)prefs.edit().putBoolean("force",w==1).commit();
                else if(w<6){int[] heights={1080,2160,720,480},rates={12000000,40000000,6000000,2000000};prefs.edit().putInt("height",heights[w-2]).putInt("bitrate",rates[w-2]).commit();}
                else new AlertDialog.Builder(MainActivity.this).setMessage("退出此账号？").setPositiveButton("退出",new DialogInterface.OnClickListener(){public void onClick(DialogInterface x,int y){prefs.edit().remove("token").remove("user").commit();api=null;images.evictAll();login();}}).setNegativeButton("取消",null).show();
            }}).show();
    }
    public void onBackPressed(){if(api!=null&&!history.isEmpty())display(history.pop());else new AlertDialog.Builder(this).setMessage("退出 Jellyfin 精简版？").setPositiveButton("退出",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){finish();}}).setNegativeButton("取消",null).show();}
    private final class MediaAdapter extends BaseAdapter {
        public int getCount(){return rows.size();} public Object getItem(int p){return rows.get(p);} public long getItemId(int p){return p;}
        public View getView(int p,View reused,android.view.ViewGroup parent){
            LinearLayout row;
            if(reused==null){row=new LinearLayout(MainActivity.this);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(7),dp(7),dp(7),dp(7));row.setBackgroundResource(R.drawable.focus);
                ImageView image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);image.setBackgroundColor(0xff101e29);row.addView(image,new LinearLayout.LayoutParams(-1,dp(136)));
                LinearLayout text=new LinearLayout(MainActivity.this);text.setOrientation(LinearLayout.VERTICAL);text.setPadding(dp(3),dp(4),dp(3),0);
                TextView name=label("",17);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);text.addView(name,new LinearLayout.LayoutParams(-1,dp(44)));
                TextView sub=label("",13);sub.setTextColor(0xffa8becc);sub.setSingleLine(true);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);text.addView(sub);
                row.addView(text,new LinearLayout.LayoutParams(-1,-2));
            }else row=(LinearLayout)reused;
            JSONObject item=rows.get(p);ImageView image=(ImageView)row.getChildAt(0);LinearLayout text=(LinearLayout)row.getChildAt(1);
            String name=item.optString("Name"), type=item.optString("Type");
            if(type.equals("Episode"))name="第 "+item.optInt("ParentIndexNumber")+" 季 · 第 "+item.optInt("IndexNumber")+" 集  "+name;
            if(type.equals("TvChannel"))name=item.optString("ChannelNumber","")+"  "+name;
            ((TextView)text.getChildAt(0)).setText(name);
            String sub="";JSONObject program=item.optJSONObject("CurrentProgram");
            if(program!=null)sub="正在播出："+program.optString("Name");
            else if(item.optInt("ProductionYear")>0)sub=item.optInt("ProductionYear")+"  ·  "+sub;
            if(sub.length()==0)sub=item.optBoolean("IsFolder")?"确认键浏览":"确认键播放";
            ((TextView)text.getChildAt(1)).setText(sub);
            loadImage(item,image);return row;
        }
    }
    private void loadImage(JSONObject item,final ImageView view){
        view.setImageDrawable(null);view.setTag(null);
        JSONObject tags=item.optJSONObject("ImageTags");String imageId=item.optString("Id"),tag=tags==null?"":tags.optString("Primary");
        if(tag.length()==0){imageId=item.optString("SeriesId","");tag=item.optString("SeriesPrimaryImageTag","");}
        if(imageId.length()==0||tag.length()==0)return;
        final String key=imageId+":"+tag;view.setTag(key);Bitmap cached=images.get(key);if(cached!=null){view.setImageBitmap(cached);return;}
        final Api client=api;final String path="/Items/"+Api.id(imageId)+"/Images/Primary?MaxWidth=240&MaxHeight=272&Quality=75&Tag="+Api.enc(tag);
        // Bound queued image work while holding a remote key down.
        if(((ThreadPoolExecutor)pictures).getQueue().size()>12)return;
        pictures.execute(new Runnable(){public void run(){HttpURLConnection c=null;try{
            c=(HttpURLConnection)new URL(client.base+path).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(false);for(Map.Entry<String,String> h:client.headers().entrySet())c.setRequestProperty(h.getKey(),h.getValue());
            if(c.getResponseCode()!=200)return;
            InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();
            try{byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>512*1024)return;out.write(buf,0,n);}}finally{in.close();}
            byte[] bytes=out.toByteArray();BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            options.inSampleSize=1;while(options.outWidth/options.inSampleSize>304||options.outHeight/options.inSampleSize>280)options.inSampleSize*=2;
            options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.RGB_565;
            final Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)return;images.put(key,bitmap);
            ui.post(new Runnable(){public void run(){if(!dead&&key.equals(view.getTag()))view.setImageBitmap(bitmap);}});
        }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}}});
    }
    protected void onDestroy(){dead=true;generation++;ui.removeCallbacksAndMessages(null);network.shutdownNow();pictures.shutdownNow();images.evictAll();super.onDestroy();}
}
