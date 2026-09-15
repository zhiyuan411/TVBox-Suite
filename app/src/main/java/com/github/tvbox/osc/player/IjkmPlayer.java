package com.github.tvbox.osc.player;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.text.TextUtils;

import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkUtils;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.PlayerHelper;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;
import xyz.doikki.videoplayer.ijk.IjkPlayer;
import xyz.doikki.videoplayer.ijk.RawDataSourceProvider;

public class IjkmPlayer extends IjkPlayer {

    private IJKCode codec = null;

    public IjkmPlayer(Context context, IJKCode codec) {
        super(context);
        this.codec = codec;
    }

    @Override
    public void setOptions() {
        IJKCode codecTmp = this.codec == null ? ApiConfig.get().getCurrentIJKCode() : this.codec;
        LinkedHashMap<String, String> options = codecTmp.getOption();
        if (options != null) {
            for (String key : options.keySet()) {
                String value = options.get(key);
                String[] opt = key.split("\\|");
                int category = Integer.parseInt(opt[0].trim());
                String name = opt[1].trim();
                try {
                    long valLong = Long.parseLong(value);
                    mMediaPlayer.setOption(category, name, valLong);
                } catch (Exception e) {
                    mMediaPlayer.setOption(category, name, value);
                }
            }
        }
        //开启内置字幕
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", -1);
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,"safe",0);

        // ========== IJK 播放优化注入 ==========
        // 1) 网络读/连接超时：避免 CDN 半开连接导致 read 永久阻塞（配套 stall 看门狗兜底）。
        //    IJK 的 "timeout" 单位是微秒；30s = 30_000_000μs。
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 30_000_000L);
        // 2) max-buffer-size 覆盖：IJK native 的该选项取值范围为 [0, MAX_QUEUE_SIZE]，
        //    本工程内置的 libijkplayer 实测为 [0, 15728640]（默认即 15MB），超过上限会被 AVOption 直接拒绝
        //    （AVERROR(ERANGE)），因此任何 >15MB 的设置都不会生效。
        //    这里按"用户配置 > tv.json > 内部兜底(15MB)"的优先级处理，并对用户值做上限钳制。
        applyIjkMaxBufferOverride(options);

        super.setOptions();
    }

    /**
     * IJK native 中 max-buffer-size 允许的最大值（单位字节）。
     * 通过解析 app/src/main/jniLibs 下的 libijkplayer.so 中 AVOption 表得到：
     * max-buffer-size 的 min=0 / max=15728640 / default=15728640，即上限就是 15MB。
     */
    private static final long IJK_MAX_BUFFER_LIMIT_BYTES = 15L * 1024 * 1024;

    /**
     * 按"用户配置 > tv.json > 内部兜底(15MB)"的优先级为 IJK 设置内存缓冲上限。
     * IJK 的 max-buffer-size 是 read 线程继续读取的"队列总字节"阈值，同时决定
     * 播放中的缓冲水位和暂停时"继续读"能填到的上限（默认已是 native 允许的最大值）。
     */
    private void applyIjkMaxBufferOverride(LinkedHashMap<String, String> codecOptions) {
        final String maxBufferKey = IjkMediaPlayer.OPT_CATEGORY_PLAYER + "|max-buffer-size";
        int userMaxBufferMB = HawkUtils.getIJKMaxBufferSize();
        if (userMaxBufferMB == HawkUtils.IJK_MAX_BUFFER_UNLIMITED) {
            // 无上限：关闭 IJK 的输入缓冲限制，read 线程会一直读到 EOF（内存无上限增长）。
            // infbuf 属于 player 类选项（av_opt_set_dict(ffp, &ffp->player_opts)），必须在 prepare 前下发。
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infbuf", 1);
            return;
        }
        long bytes;
        if (userMaxBufferMB > 0) {
            // 钳制到 native 允许的上限，避免超大值被 AVOption 拒绝而完全失效
            bytes = Math.min((long) userMaxBufferMB * 1024L * 1024L, IJK_MAX_BUFFER_LIMIT_BYTES);
        } else if (codecOptions == null || !codecOptions.containsKey(maxBufferKey)) {
            bytes = IJK_MAX_BUFFER_LIMIT_BYTES;
        } else {
            return; // 用户未配置且 tv.json 已设置 → 保留 tv.json 的值
        }
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", bytes);
    }

    // ==================== 暂停时的缓存行为说明 ====================
    // IJK 的 read 线程由 ff_ffplay.c 中如下条件控制是否继续读取：
    //   if (infinite_buffer < 1 && !is->seek_req &&
    //       (队列总字节 > max-buffer-size || 各流已缓存包数 > min-frames 且队列非空))
    // 本工程内置 libijkplayer 中 min-frames 默认 50000（范围 [2,50000]），因此暂停后主要受
    // max-buffer-size 约束：read 线程会继续把队列填到 ~15MB 才停下（此时速率归零属正常现象），
    // 这就是"暂停后仍继续缓存，但缓存上限不等于用户设置的 256MB"的原因。
    // 注意：max-buffer-size 上限由 native 常量 MAX_QUEUE_SIZE 写死，且 setOption 只在
    // prepare 阶段被消费（av_opt_set_dict(ffp, &ffp->player_opts) / avformat_open_input），
    // 运行时再下发不会生效，因此无法在不重新编译 native 的前提下把该上限调到 15MB 以上。
    // 设置项选择"无上限"时会在 prepare 前下发 infbuf=1（player 类选项），read 线程将不再等待，
    // 一直读到 EOF：暂停后也会持续缓存到整片结束，代价是内存占用无上限（点播=整片大小，直播=持续增长）。
    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        try {
            if (path != null && !TextUtils.isEmpty(path)) {
                if(path.startsWith("rtsp")){
                    mMediaPlayer.setOption(1, "infbuf", 1);
                    mMediaPlayer.setOption(1, "rtsp_transport", "tcp");
                    mMediaPlayer.setOption(1, "rtsp_flags", "prefer_tcp");
                } else if (!path.contains(".m3u8") && (path.contains(".mp4") || path.contains(".mkv") || path.contains(".avi"))) {
                    // 磁盘缓存：>0 启用并作为上限(MB)，<=0 关闭（原布尔开关已由该配置项取代）
                    int diskCacheMaxMB = HawkUtils.getIJKCacheMaxSize();
                    if (diskCacheMaxMB > 0) {
                        String cachePath = FileUtils.getExternalCachePath() + "/ijkcaches/";
                        String cacheMapPath = cachePath;
                        File cacheFile = new File(cachePath);
                        if (!cacheFile.exists()) cacheFile.mkdirs();
                        String tmpMd5 = MD5.string2MD5(path);
                        cachePath += tmpMd5 + ".file";
                        cacheMapPath += tmpMd5 + ".map";
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_file_path", cachePath);
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_map_path", cacheMapPath);
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "parse_cache_map", 1);
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "auto_save_map", 1);
                        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "cache_max_capacity", (long) diskCacheMaxMB * 1024L * 1024L);
                        path = "ijkio:cache:ffio:" + path;
                    }
                }
            }
            setDataSourceHeader(headers);
        } catch (Exception e) {
            mPlayerEventListener.onError(-1, PlayerHelper.getRootCauseMessage(e));
        }
        //mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "ijkio,ffio,async,cache,crypto,file,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data");
        mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "ijkio,ffio,async,cache,crypto,file,dash,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data,concat,subfile,ffconcat");
        //mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L);
//        try {
//            path = encodeSpaceChinese(path);//会导致本地文件无法播放，故注释掉
//        } catch (Exception ignored) {
//
//        }
        super.setDataSource(path, headers);
    }

    private String encodeSpaceChinese(String str) throws UnsupportedEncodingException {
        Pattern p = Pattern.compile("[\u4e00-\u9fa5 ]+");
        Matcher m = p.matcher(str);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, URLEncoder.encode(m.group(0), "UTF-8"));
        m.appendTail(b);
        return b.toString();
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        try {
            mMediaPlayer.setDataSource(new RawDataSourceProvider(fd));
        } catch (Exception e) {
            mPlayerEventListener.onError(-1, PlayerHelper.getRootCauseMessage(e));
        }
    }
    private void setDataSourceHeader(Map<String, String> headers) {
        if (headers != null && !headers.isEmpty()) {
            String userAgent = headers.get("User-Agent");
            if (!TextUtils.isEmpty(userAgent)) {
                mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", userAgent);
                // 移除header中的User-Agent，防止重复
                headers.remove("User-Agent");
            }
            if (headers.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    sb.append(entry.getKey());
                    sb.append(":");
                    String value = entry.getValue();
                    if (!TextUtils.isEmpty(value))
                        sb.append(entry.getValue());
                    sb.append("\r\n");
                    mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", sb.toString());
                }
            }
        }
    }
    public TrackInfo getTrackInfo() {
        IjkTrackInfo[] trackInfo = mMediaPlayer.getTrackInfo();
        if (trackInfo == null) return null;
        TrackInfo data = new TrackInfo();
        int subtitleSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT);
        int audioSelected = mMediaPlayer.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO);
        int index = 0;
        for (IjkTrackInfo info : trackInfo) {
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) {//音轨信息
                String trackName = (data.getAudio().size() + 1) + "：" + info.getInfoInline();
                TrackInfoBean t = new TrackInfoBean();
                t.name = trackName;
                t.language = info.getLanguage();
                t.trackId = index;
                t.selected = index == audioSelected;
                data.addAudio(t);
            }
            if (info.getTrackType() == ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {//内置字幕
                String trackName = (data.getSubtitle().size() + 1) + "：" + info.getInfoInline();
                TrackInfoBean t = new TrackInfoBean();
                t.name = trackName;
                t.language = info.getLanguage();
                t.trackId = index;
                t.selected = index == subtitleSelected;
                data.addSubtitle(t);
            }
            index++;
        }
        return data;
    }

    public void setTrack(int trackIndex) {
        mMediaPlayer.selectTrack(trackIndex);
    }

    public void setOnTimedTextListener(IMediaPlayer.OnTimedTextListener listener) {
        mMediaPlayer.setOnTimedTextListener(listener);
    }

}
