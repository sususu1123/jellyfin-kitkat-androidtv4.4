package org.jellyfin.kitkat;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;

/** Jellyfin 10.10 API. No Android dependencies, so transport is tested on the JVM. */
public final class Api {
    public final String base, token, userId, deviceId;
    public Api(String base, String token, String userId, String deviceId) {
        this.base = normalize(base); this.token = token; this.userId = userId; this.deviceId = deviceId;
    }
    public static String normalize(String input) {
        String s = input.trim();
        if (!s.contains("://")) s = "http://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        try {
            URL u = new URL(s);
            if (!(u.getProtocol().equals("http") || u.getProtocol().equals("https")) ||
                u.getHost().length() == 0 || u.getUserInfo() != null || u.getQuery() != null || u.getRef() != null)
                throw new Exception();
        } catch (Exception e) { throw new IllegalArgumentException("服务器地址无效，例如 http://192.168.1.10:8096"); }
        return s;
    }
    public static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    private static String text(JSONObject object, String key) {
        return object.isNull(key) ? "" : object.optString(key, "");
    }
    public static String id(String s) {
        if (s == null || !s.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("无效的媒体 ID");
        return s;
    }
    public String authorization() {
        return "MediaBrowser Client=\"Jellyfin KitKat\", Device=\"Sharp TV\", DeviceId=\"" + deviceId +
            "\", Version=\"0.1.5\"" + (token.length() == 0 ? "" : ", Token=\"" + token + "\"");
    }
    public Map<String,String> headers() {
        Map<String,String> h = new HashMap<String,String>();
        h.put("X-Emby-Authorization", authorization());
        if (token.length() > 0) h.put("X-Emby-Token", token);
        return h;
    }
    public JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(30000); c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        for (Map.Entry<String,String> h : headers().entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        c.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes("UTF-8");
                c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
                OutputStream out = c.getOutputStream(); try { out.write(bytes); } finally { out.close(); }
            }
            int status = c.getResponseCode();
            if (status == 401) throw new IOException("登录失效或用户名、密码错误（401）");
            if (status == 403) throw new IOException("账号没有访问或转码权限（403）");
            if (status < 200 || status >= 300) throw new IOException("服务器请求失败（HTTP " + status + "）");
            if (status == 204) return new JSONObject();
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 4 * 1024 * 1024) throw new IOException("服务器返回的数据过大");
                    out.write(buf, 0, n);
                }
            } finally { in.close(); }
            String text = out.toString("UTF-8");
            return text.trim().length() == 0 ? new JSONObject() : new JSONObject(text);
        } finally { c.disconnect(); }
    }
    public JSONObject login(String name, String password) throws Exception {
        return request("POST", "/Users/AuthenticateByName", new JSONObject().put("Username",name).put("Pw",password));
    }
    public JSONObject item(String itemId) throws Exception {
        return request("GET", "/Users/" + id(userId) + "/Items/" + id(itemId), null);
    }
    public static JSONObject condition(String property, String value) throws Exception {
        return new JSONObject().put("Condition", "LessThanEqual").put("Property",property)
            .put("Value",value).put("IsRequired",true);
    }
    public static JSONObject profile(boolean direct, int height, int bitrate) throws Exception {
        JSONObject p = new JSONObject().put("Name",direct?"Original quality trial":"Selected quality H264 AAC transcode")
            .put("MaxStreamingBitrate",direct?Integer.MAX_VALUE:bitrate).put("MaxStaticBitrate",Integer.MAX_VALUE);
        JSONArray directProfiles = new JSONArray();
        // This is an optimistic original-file trial, not a claim that every codec is supported.
        // Let the actual TV decoder try the original container/codec, then retry transcoding on failure.
        if (direct) directProfiles.put(new JSONObject().put("Type","Video"));
        p.put("DirectPlayProfiles",directProfiles);
        p.put("TranscodingProfiles",new JSONArray().put(new JSONObject()
            .put("Container","ts").put("Type","Video").put("VideoCodec","h264").put("AudioCodec","aac")
            .put("Protocol","hls").put("Context","Streaming").put("MaxAudioChannels","2")
            .put("MinSegments",2).put("SegmentLength",6).put("BreakOnNonKeyFrames",false)));
        int width = height>=2160?3840:height>=1080?1920:height>=720?1280:854;
        JSONArray conditions = new JSONArray().put(condition("Width",String.valueOf(width)))
            .put(condition("Height",String.valueOf(height))).put(condition("VideoBitDepth","8"))
            .put(condition("VideoLevel",height>=2160?"51":height>=1080?"41":"31")).put(condition("VideoFramerate","30"));
        p.put("CodecProfiles", direct?new JSONArray():new JSONArray()
            .put(new JSONObject().put("Type","Video").put("Codec","h264").put("Conditions",conditions))
            .put(new JSONObject().put("Type","VideoAudio").put("Codec","aac,mp3")
                .put("Conditions",new JSONArray().put(condition("AudioChannels","2")))));
        // Embedded subtitles require server-side burn-in; never claim native subtitle support.
        JSONArray subtitles=new JSONArray();
        for(String codec:new String[]{"srt","subrip","ass","ssa","pgssub","dvdsub","dvbsub","webvtt","mov_text"})
            subtitles.put(new JSONObject().put("Format",codec).put("Method","Encode"));
        p.put("SubtitleProfiles",subtitles);
        return p;
    }
    public static final class Playback {
        public String itemId, sourceId, sessionId, liveStreamId, url, method;
        public long startTicks;
        public boolean live;
    }
    public Playback playback(String itemId, boolean live, long start, boolean forceTranscode,
                             int height, int bitrate, int audioIndex, int subtitleIndex) throws Exception {
        return playback(itemId,live,start,forceTranscode,height,bitrate,audioIndex,subtitleIndex,"");
    }
    public Playback playback(String itemId, boolean live, long start, boolean forceTranscode,
                             int height, int bitrate, int audioIndex, int subtitleIndex,String selectedSourceId) throws Exception {
        start = live ? 0 : Math.max(0, start);
        if(selectedSourceId==null||selectedSourceId.length()==0){
            JSONObject metadata=request("GET","/Items/"+id(itemId)+"/PlaybackInfo?UserId="+enc(userId),null);
            if(metadata.has("ErrorCode")&&!metadata.isNull("ErrorCode"))throw new IOException("服务器无法提供视频源："+metadata.optString("ErrorCode"));
            JSONArray available=metadata.optJSONArray("MediaSources");
            if(available==null||available.length()==0)throw new IOException("没有可用的视频源或直播源");
            selectedSourceId=text(available.getJSONObject(0),"Id");
            if(selectedSourceId.length()==0)throw new IOException("服务器未返回视频源 ID");
        }
        boolean direct = !forceTranscode && subtitleIndex < 0;
        JSONObject body = new JSONObject().put("UserId",userId).put("StartTimeTicks",start)
            .put("IsPlayback",true).put("AutoOpenLiveStream",true).put("MaxStreamingBitrate",direct?Integer.MAX_VALUE:bitrate)
            .put("EnableDirectPlay",direct).put("EnableDirectStream",false)
            .put("EnableTranscoding",true).put("AllowVideoStreamCopy",false).put("AllowAudioStreamCopy",false)
            .put("MediaSourceId",selectedSourceId).put("SubtitleStreamIndex",subtitleIndex).put("AlwaysBurnInSubtitleWhenTranscoding",subtitleIndex>=0)
            .put("DeviceProfile",profile(direct,height,bitrate));
        if (audioIndex >= 0) body.put("AudioStreamIndex",audioIndex);
        if (!direct) body.put("MaxAudioChannels",2);
        JSONObject info = request("POST","/Items/" + id(itemId) + "/PlaybackInfo",body);
        if (info.has("ErrorCode") && !info.isNull("ErrorCode")) {
            if(direct)return playback(itemId,live,start,true,height,bitrate,audioIndex,subtitleIndex,selectedSourceId);
            throw new IOException("服务器无法提供可播放的视频：" + info.optString("ErrorCode"));
        }
        JSONArray sources = info.optJSONArray("MediaSources");
        if (sources == null || sources.length() == 0) throw new IOException("没有可用的视频源或直播源");
        JSONObject source=null;
        for(int i=0;i<sources.length();i++)if(selectedSourceId.equals(text(sources.getJSONObject(i),"Id"))){source=sources.getJSONObject(i);break;}
        // Opening a tuner can replace the provisional channel source with one actual live source.
        if(source==null&&live&&sources.length()==1&&text(sources.getJSONObject(0),"LiveStreamId").length()>0)source=sources.getJSONObject(0);
        if(source==null)throw new IOException("所选视频版本已不可用，请返回后重新选择");
        Playback result = new Playback();
        result.itemId=itemId; result.sourceId=text(source,"Id"); result.sessionId=text(info,"PlaySessionId");
        result.liveStreamId=text(source,"LiveStreamId"); result.startTicks=start; result.live=live;
        if(direct&&!source.optBoolean("SupportsDirectPlay",false)){
            cleanup(result);
            return playback(itemId,live,start,true,height,bitrate,audioIndex,subtitleIndex,selectedSourceId);
        }
        try {
            if (direct && source.optBoolean("SupportsDirectPlay",false)) {
                result.method="DirectPlay";
                result.url=base + "/Videos/" + id(itemId) + "/stream?Static=true&MediaSourceId=" + enc(result.sourceId)
                    + "&api_key=" + enc(token) + "&DeviceId=" + enc(deviceId) + "&PlaySessionId=" + enc(result.sessionId);
                if(result.liveStreamId.length()>0)result.url+="&LiveStreamId="+enc(result.liveStreamId);
            } else {
                result.method="Transcode";
                String path=text(source,"TranscodingUrl");
                if (path.length() == 0) throw new IOException("没有可用的转码地址，请检查账号转码权限和服务端 FFmpeg");
                result.url=resolveStream(path);
                if(subtitleIndex>=0){
                    result.url=withQuery(result.url,"MediaSourceId",result.sourceId);
                    result.url=withQuery(result.url,"SubtitleStreamIndex",String.valueOf(subtitleIndex));
                    result.url=withQuery(result.url,"SubtitleMethod","Encode");
                    result.url=withQuery(result.url,"AlwaysBurnInSubtitleWhenTranscoding","true");
                    result.url=withQuery(result.url,"AllowVideoStreamCopy","false");
                }
            }
            return result;
        } catch (Exception e) { cleanup(result); throw e; }
    }
    private static String withQuery(String url,String key,String value)throws Exception{
        int hash=url.indexOf('#');String fragment=hash<0?"":url.substring(hash);String head=hash<0?url:url.substring(0,hash);
        int q=head.indexOf('?');String base=q<0?head:head.substring(0,q);StringBuilder query=new StringBuilder();
        if(q>=0)for(String part:head.substring(q+1).split("&")){
            String name=part.split("=",2)[0];
            if(part.length()>0&&!URLDecoder.decode(name,"UTF-8").equalsIgnoreCase(key)){if(query.length()>0)query.append('&');query.append(part);}
        }
        if(query.length()>0)query.append('&');query.append(enc(key)).append('=').append(enc(value));return base+"?"+query+fragment;
    }
    public String resolveStream(String path) throws Exception {
        URL server = new URL(base);
        // Jellyfin returns /videos/... relative to its configured BaseUrl, or includes BaseUrl itself.
        String prefix = server.getPath();
        URL stream;
        if (path.startsWith("http://") || path.startsWith("https://")) stream = new URL(path);
        else if (prefix.length() > 0 && path.startsWith(prefix + "/")) stream = new URL(server, path);
        else stream = new URL(base + "/" + (path.startsWith("/") ? path.substring(1) : path));
        if (!stream.getProtocol().equals(server.getProtocol()) || !stream.getHost().equalsIgnoreCase(server.getHost())
                || stream.getPort() != server.getPort()) throw new IOException("服务端返回了不同来源的播放地址");
        String url = stream.toExternalForm();
        if (!url.contains("api_key=")) url += (url.contains("?") ? "&" : "?") + "api_key=" + enc(token);
        return url;
    }
    public void report(String event, Playback p, long position, boolean paused) throws Exception {
        JSONObject body=new JSONObject().put("ItemId",p.itemId).put("MediaSourceId",p.sourceId)
            .put("PlaySessionId",p.sessionId).put("PositionTicks",position).put("IsPaused",paused)
            .put("CanSeek",!p.live).put("PlayMethod",p.method);
        if (p.liveStreamId.length()>0) body.put("LiveStreamId",p.liveStreamId);
        request("POST","/Sessions/Playing" + event,body);
    }
    public void cleanup(Playback p) {
        if (p == null) return;
        if (p.liveStreamId.length()>0) {
            try { request("POST","/LiveStreams/Close?liveStreamId=" + enc(p.liveStreamId),new JSONObject()); }
            catch (Exception ignored) { }
        }
        if ("Transcode".equals(p.method) && p.sessionId.length()>0) {
            try { request("DELETE","/Videos/ActiveEncodings?deviceId=" + enc(deviceId) + "&playSessionId=" + enc(p.sessionId),null); }
            catch (Exception ignored) { }
        }
    }
}
