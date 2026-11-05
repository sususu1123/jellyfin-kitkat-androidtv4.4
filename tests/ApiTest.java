package org.jellyfin.kitkat;

import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Contract tests against a local HTTP server; no account credentials or real streams required. */
public final class ApiTest {
    private static HttpServer server;
    private static String base;
    private static String lastPath,lastMethod,lastAuth,lastToken,lastQuery;
    private static JSONObject lastBody;
    private static int responseCode=200,checks=0;
    private static String response="{}";
    private static final ArrayList<String> calls=new ArrayList<String>();
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static JSONObject obj(String json)throws Exception{return new JSONObject(json);}
    private static void answer(int code,String json){responseCode=code;response=json;calls.clear();}
    private static void expectError(Api api,int code,String fragment)throws Exception{
        answer(code,"{}");boolean failed=false;
        try{api.request("GET","/denied",null);}catch(IOException e){failed=e.getMessage().contains(fragment);}
        check(failed,"HTTP "+code+" should be actionable");
    }
    public static void main(String[] args)throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",new HttpHandler(){public void handle(HttpExchange e)throws IOException{
            lastPath=e.getRequestURI().getPath();lastMethod=e.getRequestMethod();lastQuery=e.getRequestURI().getRawQuery();
            lastAuth=e.getRequestHeaders().getFirst("X-Emby-Authorization");lastToken=e.getRequestHeaders().getFirst("X-Emby-Token");
            ByteArrayOutputStream body=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
            while((n=e.getRequestBody().read(buf))!=-1)body.write(buf,0,n);
            try{lastBody=body.size()==0?null:new JSONObject(body.toString("UTF-8"));}catch(Exception x){throw new IOException(x);}
            calls.add(lastMethod+" "+lastPath);
            byte[] bytes=response.getBytes("UTF-8");
            e.getResponseHeaders().set("Content-Type","application/json");
            e.sendResponseHeaders(responseCode,responseCode==204?-1:bytes.length);
            if(responseCode!=204)e.getResponseBody().write(bytes);e.close();
        }});
        server.start();base="http://127.0.0.1:"+server.getAddress().getPort();
        try{run();System.out.println("PASS: "+checks+" assertions against HTTP mock server");}finally{server.stop(0);}
    }
    private static void run()throws Exception{
        check(Api.normalize(" 192.168.1.10:8096/ ").equals("http://192.168.1.10:8096"),"address normalization");
        for(String bad:new String[]{"", "file:///etc/passwd", "http://user:pw@server", "http://server/?token=x", "http://server/#frag"}){
            boolean rejected=false;try{Api.normalize(bad);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"reject unsafe address: "+bad);
        }
        check(Api.enc("中文 &+").equals("%E4%B8%AD%E6%96%87+%26%2B"),"UTF8 query escaping");
        Api anonymous=new Api(base,"","","device-test");
        answer(200,"{\"AccessToken\":\"sample-token\",\"User\":{\"Id\":\"user-1\"}}");
        JSONObject login=anonymous.login("测试 用户","sample-secret");
        check(lastMethod.equals("POST")&&lastPath.equals("/Users/AuthenticateByName"),"login endpoint");
        check(lastBody.getString("Username").equals("测试 用户")&&lastBody.getString("Pw").equals("sample-secret"),"login JSON UTF8");
        check(lastAuth.contains("DeviceId=\"device-test\"")&&!lastAuth.contains("Token="),"unauthenticated client identity");
        Api api=new Api(base,login.getString("AccessToken"),login.getJSONObject("User").getString("Id"),"device-test");
        answer(200,"{\"Items\":[],\"TotalRecordCount\":0}");api.request("GET","/LiveTv/Channels?UserId=user-1&StartIndex=40&Limit=40",null);
        check(lastToken.equals("sample-token")&&lastAuth.contains("Token=\"sample-token\""),"authentication headers");
        check(lastQuery.contains("StartIndex=40"),"paging request preserved");
        expectError(api,401,"401");expectError(api,403,"403");expectError(api,500,"500");
        answer(204,"");check(api.request("POST","/empty",new JSONObject()).length()==0,"204 responses accepted");

        JSONObject normal=Api.profile(true,720,4000000);
        check(normal.getJSONArray("DirectPlayProfiles").length()==1,"original direct play trial enabled");
        check(normal.getInt("MaxStreamingBitrate")==Integer.MAX_VALUE,"original bitrate independent of fallback quality");
        check(normal.getJSONArray("CodecProfiles").length()==0,"original resolution and codec level are not capped");
        check(!normal.getJSONArray("DirectPlayProfiles").getJSONObject(0).has("Container"),"original trial not limited to MP4");
        JSONObject hd=Api.profile(false,1080,12000000),uhd=Api.profile(false,2160,40000000);
        JSONArray hdLimits=hd.getJSONArray("CodecProfiles").getJSONObject(0).getJSONArray("Conditions");
        JSONArray uhdLimits=uhd.getJSONArray("CodecProfiles").getJSONObject(0).getJSONArray("Conditions");
        check(hdLimits.getJSONObject(0).getString("Value").equals("1920")&&hdLimits.getJSONObject(1).getString("Value").equals("1080"),"1080p transcode dimensions");
        check(uhdLimits.getJSONObject(0).getString("Value").equals("3840")&&uhdLimits.getJSONObject(1).getString("Value").equals("2160"),"4K transcode dimensions");
        check(uhdLimits.getJSONObject(3).getString("Value").equals("51"),"4K H264 level is not limited to 720p level 3.1");
        JSONObject transcode=normal.getJSONArray("TranscodingProfiles").getJSONObject(0);
        check(transcode.getString("Container").equals("ts")&&transcode.getString("Protocol").equals("hls"),"KitKat TS HLS, not fMP4");
        check(transcode.getString("VideoCodec").equals("h264")&&transcode.getString("AudioCodec").equals("aac"),"compatible transcode codecs");
        check(transcode.getString("MaxAudioChannels").equals("2"),"stereo limit");
        check(Api.profile(false,480,2000000).getJSONArray("DirectPlayProfiles").length()==0,"compatibility mode prohibits direct play");

        answer(200,"{\"PlaySessionId\":\"session-1\",\"MediaSources\":[{\"Id\":\"source-1\",\"LiveStreamId\":null,\"SupportsDirectPlay\":true}]}");
        Api.Playback direct=api.playback("movie-1",false,900000000L,false,720,4000000,-1,-1);
        check(lastPath.equals("/Items/movie-1/PlaybackInfo")&&lastMethod.equals("POST"),"playback negotiation endpoint");
        check(lastBody.getLong("StartTimeTicks")==900000000L,"resume offset sent");
        check(lastBody.getInt("SubtitleStreamIndex")==-1,"no unintended default subtitles");
        check(lastBody.getInt("MaxStreamingBitrate")==Integer.MAX_VALUE&&!lastBody.has("MaxAudioChannels"),"original attempt does not impose fallback bitrate or stereo");
        check(direct.method.equals("DirectPlay")&&direct.url.contains("Static=true")&&direct.url.contains("MediaSourceId=source-1"),"direct URL uses selected media source");
        check(direct.liveStreamId.equals(""),"JSON null live source is not the string null on KitKat");
        calls.clear();api.cleanup(direct);check(calls.isEmpty(),"VOD direct play does not close a nonexistent live source");
        check(direct.url.contains("/stream?")&&!direct.url.contains("stream.mp4"),"original stream does not pretend every source is MP4");
        answer(200,"{\"PlaySessionId\":\"fallback-session\",\"MediaSources\":[{\"Id\":\"source-hd\",\"SupportsDirectPlay\":false,\"TranscodingUrl\":\"/videos/movie-1/master.m3u8\"}]}");
        Api.Playback fallback=api.playback("movie-1",false,0,false,1080,12000000,-1,-1);
        check(fallback.method.equals("Transcode")&&!lastBody.getBoolean("EnableDirectPlay"),"server refusal triggers a separate transcode negotiation");
        check(lastBody.getInt("MaxStreamingBitrate")==12000000,"fallback uses selected bitrate instead of unlimited direct bitrate");
        check(lastBody.getJSONObject("DeviceProfile").getJSONArray("CodecProfiles").getJSONObject(0).getJSONArray("Conditions").getJSONObject(1).getString("Value").equals("1080"),"fallback retains selected 1080p resolution");
        int negotiations=0;for(String call:calls)if(call.equals("POST /Items/movie-1/PlaybackInfo"))negotiations++;
        check(negotiations==2,"original probe and fallback are negotiated exactly once each");

        answer(200,"{\"PlaySessionId\":\"session-2\",\"MediaSources\":[{\"Id\":\"source-2\",\"LiveStreamId\":\"live-1\",\"TranscodingUrl\":\"/videos/channel-1/master.m3u8?VideoCodec=h264\"}]}");
        Api.Playback live=api.playback("channel-1",true,999L,false,480,2000000,-1,-1);
        check(lastBody.getBoolean("AutoOpenLiveStream"),"server opens live sources");
        check(!lastBody.getBoolean("EnableDirectPlay")&&!lastBody.getBoolean("EnableDirectStream"),"live stream uses conservative transcode");
        check(!lastBody.getBoolean("AllowVideoStreamCopy")&&!lastBody.getBoolean("AllowAudioStreamCopy"),"incompatible source codecs not copied");
        check(lastBody.getLong("StartTimeTicks")==0,"live ignores VOD resume offset");
        check(live.url.equals(base+"/videos/channel-1/master.m3u8?VideoCodec=h264&api_key=sample-token"),"authenticated HLS address");
        check(live.liveStreamId.equals("live-1")&&live.method.equals("Transcode"),"live session identity retained");

        answer(204,"");api.report("/Progress",direct,1200000000L,true);
        check(lastPath.equals("/Sessions/Playing/Progress"),"progress endpoint");
        check(lastBody.getLong("PositionTicks")==1200000000L&&lastBody.getBoolean("IsPaused"),"progress state");
        api.report("",live,0,false);check(!lastBody.getBoolean("CanSeek"),"live cannot seek");
        api.cleanup(live);
        check(calls.contains("POST /LiveStreams/Close")&&calls.contains("DELETE /Videos/ActiveEncodings"),"cleanup closes tuner and transcode");
        check(lastQuery.contains("playSessionId=session-2"),"cleanup restricted to own playback session");

        Api subpath=new Api(base+"/jellyfin","sample-token","user-1","device-test");
        check(subpath.resolveStream("/videos/x/master.m3u8").startsWith(base+"/jellyfin/videos/"),"reverse proxy BaseUrl appended");
        check(subpath.resolveStream("/jellyfin/videos/x/master.m3u8").startsWith(base+"/jellyfin/videos/"),"BaseUrl not duplicated");
        boolean rejected=false;try{api.resolveStream("http://different-host/video.m3u8");}catch(IOException e){rejected=true;}
        check(rejected,"credentials never sent to another origin");

        answer(200,"{\"ErrorCode\":\"NotAllowed\",\"MediaSources\":[]}");
        rejected=false;try{api.playback("movie-1",false,0,true,720,4000000,-1,-1);}catch(IOException e){rejected=e.getMessage().contains("NotAllowed");}
        check(rejected,"playback permission failure explained");
        answer(200,"{\"MediaSources\":[]}");rejected=false;
        try{api.playback("movie-1",false,0,true,720,4000000,-1,-1);}catch(IOException e){rejected=true;}
        check(rejected,"missing media handled");
        answer(200,"{\"PlaySessionId\":\"session-3\",\"MediaSources\":[{\"Id\":\"s\",\"TranscodingUrl\":\"/videos/x/master.m3u8\"}]}");
        api.playback("movie-1",false,0,false,720,4000000,2,4);
        check(lastBody.getInt("AudioStreamIndex")==2&&lastBody.getInt("SubtitleStreamIndex")==4,"track selection");
        check(lastBody.getString("MediaSourceId").equals("s"),"selected tracks are pinned to their source, required by Jellyfin 10.10");
        check(!lastBody.getBoolean("EnableDirectPlay")&&lastBody.getBoolean("AlwaysBurnInSubtitleWhenTranscoding"),"subtitles require burn-in");
        answer(200,"{\"PlaySessionId\":\"subtitle-session\",\"MediaSources\":[{\"Id\":\"wrong-version\",\"TranscodingUrl\":\"/wrong.m3u8\"},{\"Id\":\"chosen-version\",\"TranscodingUrl\":\"/videos/movie-1/master.m3u8?subtitleStreamIndex=0&SubtitleMethod=Embed&AllowVideoStreamCopy=true\"}]}");
        Api.Playback subtitles=api.playback("movie-1",false,0,true,1080,12000000,-1,4,"chosen-version");
        check(lastBody.getString("MediaSourceId").equals("chosen-version"),"explicit media version sent with subtitle index");
        check(subtitles.sourceId.equals("chosen-version")&&!subtitles.url.contains("wrong.m3u8"),"server sorting cannot switch selected subtitle version");
        check(subtitles.url.contains("SubtitleStreamIndex=4")&&!subtitles.url.contains("subtitleStreamIndex=0"),"selected subtitle index replaces stale URL default");
        check(subtitles.url.contains("SubtitleMethod=Encode")&&!subtitles.url.contains("SubtitleMethod=Embed"),"selected subtitles burn into video rather than muxing into HLS");
        check(subtitles.url.contains("AllowVideoStreamCopy=false")&&!subtitles.url.contains("AllowVideoStreamCopy=true"),"subtitle burn-in cannot copy undecorated video");
        boolean subrip=false;JSONArray subtitleProfiles=lastBody.getJSONObject("DeviceProfile").getJSONArray("SubtitleProfiles");
        for(int i=0;i<subtitleProfiles.length();i++)if(subtitleProfiles.getJSONObject(i).getString("Format").equals("subrip"))subrip=true;
        check(subrip,"MKV SubRip codec is explicitly supported for burn-in");
        answer(200,"{\"PlaySessionId\":\"tuner-session\",\"MediaSources\":[{\"Id\":\"opened-source\",\"LiveStreamId\":\"opened-live\",\"SupportsDirectPlay\":true}]}");
        Api.Playback opened=api.playback("channel-1",true,0,false,1080,12000000,-1,-1,"provisional-source");
        check(opened.sourceId.equals("opened-source"),"tuner can replace provisional source ID when opening the requested channel");
        check(opened.url.contains("LiveStreamId=opened-live")&&opened.url.contains("MediaSourceId=opened-source"),"live playback uses actual opened source and stream IDs");
    }
}
