package com.github.tvbox.osc.util;

import android.content.Context;

import androidx.media3.exoplayer.DefaultRenderersFactory;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.IJKCode;
import com.orhanobut.hawk.Hawk;

import java.util.List;

import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;

public class HawkUtils {

    private static final String DANMU_OPEN = "danmu_open";
    private static final String DANMU_MAXLINE = "danmu_maxline";
    private static final String DANMU_SPEED = "danmu_speed";
    private static final String DANMU_ALPHA = "danmu_alpha";
    private static final String DANMU_SIZESCALE = "danmu_sizescale";
    private static final String DANMU_COLOR = "danmu_color";

    public static boolean getDanmuOpen() {
        return Hawk.get(DANMU_OPEN, true);
    }

    public static void setDanmuOpen(boolean danmuOpen) {
        Hawk.put(DANMU_OPEN, danmuOpen);
    }

    public static int getDanmuMaxLine() {
        return Hawk.get(DANMU_MAXLINE, 3);
    }

    public static void setDanmuMaxLine(int danmuMaxLine) {
        Hawk.put(DANMU_MAXLINE,danmuMaxLine);
    }

    public static float getDanmuSpeed() {
        return Hawk.get(DANMU_SPEED, 1.5f);
    }

    public static void setDanmuSpeed(float danmuSpeed) {
        Hawk.put(DANMU_SPEED,danmuSpeed);
    }

    public static float getDanmuAlpha() {
        return Hawk.get(DANMU_ALPHA, 90 / 100.0f);
    }

    public static void setDanmuAlpha(float danmuAlpha) {
        Hawk.put(DANMU_ALPHA,danmuAlpha);
    }

    public static float getDanmuSizeScale() {
        return Hawk.get(DANMU_SIZESCALE, 0.8f);
    }

    public static void setDanmuSizeScale(float danmuSizeScale) {
        Hawk.put(DANMU_SIZESCALE,danmuSizeScale);
    }
    public static boolean getDanmuColor() {
        return Hawk.get(DANMU_COLOR, false);
    }
    public static void setDanmuColor(boolean color) {
        Hawk.put(DANMU_COLOR,color);
    }
    public static String getIJKCodec() {
        return Hawk.get(HawkConfig.IJK_CODEC, "");
    }

    public static void nextIJKCodec() {
        List<IJKCode> ijkCodes = ApiConfig.get().getIjkCodes();
        String ijkCodec = getIJKCodec();
        int index = 0;
        for (int i = 0; i < ijkCodes.size(); i++) {
            IJKCode ijkCode = ijkCodes.get(i);
            if (ijkCode.getName().equals(ijkCodec)) {
                index = i;
                break;
            }
        }
        ijkCodes.get(index).selected(false);
        index++;
        index %= ijkCodes.size();
        ijkCodes.get(index).selected(true);
    }

    /**
     * IJK 磁盘缓存上限(MB)
     * >0  启用磁盘缓存，并作为 cache_max_capacity
     * <=0 关闭磁盘缓存
     */
    public static int getIJKCacheMaxSize() {
        return Hawk.get(HawkConfig.IJK_CACHE_MAX_SIZE, 2048);
    }

    public static void setIJKCacheMaxSize(int mb) {
        Hawk.put(HawkConfig.IJK_CACHE_MAX_SIZE, mb);
    }

    public static void nextIJKCacheMaxSize() {
        int[] opts = {0, 256, 512, 1024, 2048, 4096};
        int current = getIJKCacheMaxSize();
        int idx = 0;
        for (int i = 0; i < opts.length; i++) {
            if (opts[i] == current) {
                idx = i;
                break;
            }
        }
        idx = (idx + 1) % opts.length;
        setIJKCacheMaxSize(opts[idx]);
    }

    public static String getIJKCacheMaxSizeDesc() {
        int v = getIJKCacheMaxSize();
        if (v <= 0) return "关闭";
        return v % 1024 == 0 ? (v / 1024 + "GB") : (v + "MB");
    }

    /**
     * IJK 卡死看门狗阈值(秒)：播放中速度为 0 且位置停滞超过该值则自动重连。
     * <5s 视为无效值，取默认 30s。
     */
    public static int getIJKStallTimeout() {
        int v = Hawk.get(HawkConfig.IJK_STALL_TIMEOUT, 30);
        return v < 5 ? 30 : v;
    }

    public static void setIJKStallTimeout(int seconds) {
        Hawk.put(HawkConfig.IJK_STALL_TIMEOUT, seconds);
    }

    public static void nextIJKStallTimeout() {
        int[] opts = {5, 10, 20, 30, 45, 60};
        int current = getIJKStallTimeout();
        int idx = 0;
        for (int i = 0; i < opts.length; i++) {
            if (opts[i] == current) {
                idx = i;
                break;
            }
        }
        idx = (idx + 1) % opts.length;
        setIJKStallTimeout(opts[idx]);
    }

    public static String getIJKStallTimeoutDesc() {
        return getIJKStallTimeout() + "秒";
    }

    /**
     * IJK native 中 max-buffer-size 允许的最大值(MB)。
     * 实测本工程内置 libijkplayer.so 的 AVOption：min=0 / max=15728640 / default=15728640，
     * 即默认已是最大值 15MB，>15MB 的设置会被 AVOption 直接拒绝（不会生效）。
     */
    public static final int IJK_MAX_BUFFER_LIMIT_MB = 15;

    /**
     * 无上限缓冲（infbuf=1）：关闭 IJK read 线程的"队列已满就停"限制，
     * 会一直往内存里读到整片结束（或直播流被暂停观看前的全部数据），内存占用无上限。
     */
    public static final int IJK_MAX_BUFFER_UNLIMITED = -1;

    /**
     * IJK 内存缓冲上限(MB)
     * >0  覆盖 tv.json 的 max-buffer-size（并钳制到 native 允许的 15MB）
     * <=0 走 tv.json 的配置，缺失时使用 native 默认水位（15MB）
     * -1  无上限（infbuf=1）
     */
    public static int getIJKMaxBufferSize() {
        int v = Hawk.get(HawkConfig.IJK_MAX_BUFFER_SIZE, 0);
        if (v == IJK_MAX_BUFFER_UNLIMITED) return IJK_MAX_BUFFER_UNLIMITED;
        return v <= 0 ? 0 : Math.min(v, IJK_MAX_BUFFER_LIMIT_MB);
    }

    public static void setIJKMaxBufferSize(int mb) {
        Hawk.put(HawkConfig.IJK_MAX_BUFFER_SIZE, mb);
    }

    public static void nextIJKMaxBufferSize() {
        int[] opts = {0, 2, 4, 8, IJK_MAX_BUFFER_LIMIT_MB, IJK_MAX_BUFFER_UNLIMITED};
        int current = getIJKMaxBufferSize();
        int idx = 0;
        for (int i = 0; i < opts.length; i++) {
            if (opts[i] == current) {
                idx = i;
                break;
            }
        }
        idx = (idx + 1) % opts.length;
        setIJKMaxBufferSize(opts[idx]);
    }

    public static String getIJKMaxBufferSizeDesc() {
        int v = getIJKMaxBufferSize();
        if (v == IJK_MAX_BUFFER_UNLIMITED) return "无上限(慎用)";
        return v <= 0 ? "默认(" + IJK_MAX_BUFFER_LIMIT_MB + "MB)" : (v + "MB");
    }

    /**
     * 获取exo渲染器 自己存储的数据
     *
     * @return int
     */
    public static int getExoRenderer() {
        return Hawk.get(HawkConfig.EXO_RENDERER, 0);
    }

    public static void nextExoRenderer() {
        App app = App.getInstance();
        String[] array = app.getResources().getStringArray(R.array.media_content_ExoPlayer_renderer);
        int renderer = getExoRenderer();
        renderer++;
        renderer %= array.length;
        Hawk.put(HawkConfig.EXO_RENDERER, renderer);
    }

    /**
     * 创建exo渲染器
     *
     * @param context 上下文
     * @return {@link DefaultRenderersFactory }
     */
    public static DefaultRenderersFactory createExoRendererActualValue(Context context) {
        int renderer = getExoRenderer();
        switch (renderer) {
            case 1:
                return new NextRenderersFactory(context);
            case 0:
            default:
                return new DefaultRenderersFactory(context);
        }
    }

    /**
     * 获取exo渲染器描述
     *
     * @return {@link String }
     */
    public static String getExoRendererDesc() {
        App app = App.getInstance();
        String[] array = app.getResources().getStringArray(R.array.media_content_ExoPlayer_renderer);
        return array[getExoRenderer()];
    }

    /**
     * 获取exo渲染器模式 自己存储的 值
     *
     * @return int
     */
    public static int getExoRendererMode() {
        return Hawk.get(HawkConfig.EXO_RENDERER_MODE, 1);
    }

    public static void nextExoRendererMode() {
        int rendererMode = getExoRendererMode();
        App app = App.getInstance();
        String[] array = app.getResources().getStringArray(R.array.media_content_ExoPlayer_renderer_mode);
        rendererMode++;
        rendererMode %= array.length;
        Hawk.put(HawkConfig.EXO_RENDERER_MODE, rendererMode);
    }


    /**
     * 返回程序 需要的值 exo渲染器模式
     */
    public static int getExoRendererModeActualValue() {
        int i = getExoRendererMode();
        switch (i) {
            case 0:
                return DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
            case 2:
                return DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF;
            case 1:
            default:
                return DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
        }
    }

    /**
     * 获取exo渲染器模式描述
     *
     * @return {@link String }
     */
    public static String getExoRendererModeDesc() {
        App app = App.getInstance();
        String[] array = app.getResources().getStringArray(R.array.media_content_ExoPlayer_renderer_mode);
        return array[getExoRendererMode()];
    }

    // Vod 播放器首选
    public static int getVodPlayerPreferred() {
        return Hawk.get(HawkConfig.VOD_PLAYER_PREFERRED, 0);
    }

    public static void nextVodPlayerPreferred() {
        int index = getVodPlayerPreferred();
        App app = App.getInstance();
        String[] array = app.getResources().getStringArray(R.array.media_content_General_VodPlayerPreferred);
        index++;
        index %= array.length;
        Hawk.put(HawkConfig.VOD_PLAYER_PREFERRED, index);
    }

    public static boolean getVodPlayerPreferredConfigurationFile() {
        int i = getVodPlayerPreferred();
        return i == 0;
    }

    public static String getVodPlayerPreferredDesc() {
        App app = App.getInstance();
        String[] array = app.getResources().getStringArray(R.array.media_content_General_VodPlayerPreferred);
        return array[getVodPlayerPreferred()];
    }

    public static String getLastLiveChannelGroup() {
        return Hawk.get(HawkConfig.LIVE_CHANNEL_GROUP, "");
    }

    public static void setLastLiveChannelGroup(String group) {
        Hawk.put(HawkConfig.LIVE_CHANNEL_GROUP, group);
    }

    public static String getLastLiveChannel() {
        return Hawk.get(HawkConfig.LIVE_CHANNEL, "");
    }

    public static void setLastLiveChannel(String channel) {
        Hawk.put(HawkConfig.LIVE_CHANNEL, channel);
    }
}
