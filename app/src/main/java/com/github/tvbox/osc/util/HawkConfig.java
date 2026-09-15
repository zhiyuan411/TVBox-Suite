package com.github.tvbox.osc.util;

import com.orhanobut.hawk.Hawk;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class HawkConfig {
    public static final String PUSH_TO_ADDR = "push_to_addr"; // 推送到地址的IP
    public static final String PUSH_TO_PORT = "push_to_port"; // 推送到地址的端口
    // URL Configurations
    public static final String API_URL = "api_url";
    public static final String API_HISTORY = "api_history";
    public static final String LIVE_URL = "live_url";
    public static final String LIVE_HISTORY = "live_history";
    public static final String EPG_URL = "epg_url";
    public static final String EPG_HISTORY = "epg_history";
    public static final String PROXY_SERVER = "proxy_server";
    // Settings
    public static final String DEBUG_OPEN = "debug_open";
    public static final String HOME_API = "home_api";
    public static final String HOME_REC = "home_rec";                    // 0 豆瓣 1 推荐 2 历史
    public static final String HOME_REC_STYLE = "home_rec_style";        // true=Grid, false=Line
    public static final String HOME_NUM = "home_num";                    // No. of History
    public static final String HOME_SHOW_SOURCE = "show_source";
    public static final String HOME_LOCALE = "language";                 // 0 中文 1 英文
    public static final String HOME_SEARCH_POSITION = "search_position"; // true=Up, false=Down
    public static final String HOME_MENU_POSITION = "menu_position";     // true=Up, false=Down
	public static final String HOME_DEFAULT_SHOW = "home_default_show";  //启动时直接进直播的开关

    // Player Settings
    public static final String SHOW_PREVIEW = "show_preview";
    public static final String IJK_CODEC = "ijk_codec";
    public static final String PLAY_TYPE = "play_type";     //0 系统 1 ijk 2 exo 10 MXPlayer
    public static final String PLAY_RENDER = "play_render"; //0 texture 2
    public static final String PLAY_SCALE = "play_scale";   //
    public static final String PLAY_TIME_STEP = "play_time_step";
    public static final String PIC_IN_PIC = "pic_in_pic";   // true = on, false = off
    public static final String VIDEO_PURIFY = "video_purify";
    @Deprecated // 已被 IJK_CACHE_MAX_SIZE 取代：<=0 即表示关闭磁盘缓存
    public static final String IJK_CACHE_PLAY = "ijk_cache_play";
    // IJK 磁盘缓存上限(MB)。>0 启用且作为 cache_max_capacity；<=0 关闭磁盘缓存。默认 2048MB(2GB)
    public static final String IJK_CACHE_MAX_SIZE = "ijk_cache_max_size";
    // IJK 内存缓冲上限(MB)。>0 覆盖 tv.json 的 max-buffer-size；<=0 走 tv.json，缺失时使用内部默认值(200MB)
    public static final String IJK_MAX_BUFFER_SIZE = "ijk_max_buffer_size";
    // IJK 卡死看门狗阈值(秒)：STATE_PLAYING 且速度=0 且位置停滞超过该值则自动重连。<5s 视为无效值，取默认 30s
    public static final String IJK_STALL_TIMEOUT = "ijk_stall_timeout";

    public static final String EXO_RENDERER = "exo_renderer";
    public static final String EXO_RENDERER_MODE = "exo_renderer_mode";
    public static final String VOD_PLAYER_PREFERRED = "vod_player_preferred";


    // Other Settings
    public static final String DOH_URL = "doh_url";         // DNS
    public static final String DEFAULT_PARSE = "parse_default";
    public static final String PARSE_WEBVIEW = "parse_webview"; // true 系统 false xwalk
    public static final String SEARCH_VIEW = "search_view";     // 0 列表 1 缩略图
    public static final String SOURCES_FOR_SEARCH = "checked_sources_for_search";
    public static final String STORAGE_DRIVE_SORT = "storage_drive_sort";
    public static final String SUBTITLE_TEXT_SIZE = "subtitle_text_size";
    public static final String SUBTITLE_TEXT_STYLE = "subtitle_text_style";
    public static final String SUBTITLE_TIME_DELAY = "subtitle_time_delay";
    public static final String THEME_SELECT = "theme_select";
    public static final String BACKGROUND_PLAY_TYPE = "background_play_type";
    public static final String FAST_SEARCH_MODE = "fast_search_mode";
    public static final String SCREEN_DISPLAY = "screen_display";
    public static final String SEARCH_FILTER_KEY = "search_filter_key";

    // 搜索并发相关（由用户在设置页自定义，默认值保持与旧版本一致）
    // 搜索调度并发线程数：即“同时处理多少个源”
    public static final String SEARCH_THREAD_COUNT = "search_thread_count";
    // 插件(type==3)子进程执行的并发上限：即“同时有多少个插件在 :spider 子进程中执行”
    public static final String SEARCH_SPIDER_CONCURRENCY = "search_spider_concurrency";
    // OkHttp Dispatcher 总并发上限（默认与 OkHttp 默认 64 一致；进程启动时读取，运行时修改需重启生效）
    public static final String OKHTTP_MAX_REQUESTS = "okhttp_max_requests";
    // OkHttp Dispatcher 单域名并发上限（默认与 OkHttp 默认 5 一致；进程启动时读取，运行时修改需重启生效）
    public static final String OKHTTP_MAX_REQUESTS_PER_HOST = "okhttp_max_requests_per_host";
    // 快速搜索(FastSearchActivity)每批处理的源数量（运行时按搜索任务重新读取，无需重启）
    public static final String SEARCH_BATCH_SIZE = "search_batch_size";

    // Live Settings
    public static final String LIVE_CHANNEL = "last_live_channel_name";
    public static final String LIVE_CHANNEL_GROUP = "last_live_channel_group_name";
    public static final String LIVE_CHANNEL_REVERSE = "live_channel_reverse";
    public static final String LIVE_CROSS_GROUP = "live_cross_group";
    public static final String LIVE_CONNECT_TIMEOUT = "live_connect_timeout";
    public static final String LIVE_SHOW_NET_SPEED = "live_show_net_speed";
    public static final String LIVE_SHOW_TIME = "live_show_time";
    public static final String LIVE_SKIP_PASSWORD = "live_skip_password";
    public static final String LIVE_PLAYER_TYPE = "live_player_type"; // 0 系统 1 ijk 2 exo

    public static boolean isDebug() {
        return Hawk.get(DEBUG_OPEN, false);
    }
    public static boolean hotVodDelete;

    // ==================== 搜索并发配置 ====================
    // 默认值保持与旧版本一致（搜索调度/插件并发=5），OkHttp/Batch 默认与原常量一致
    public static final int DEFAULT_SEARCH_THREAD_COUNT = 5;
    public static final int DEFAULT_SEARCH_SPIDER_CONCURRENCY = 5;
    public static final int DEFAULT_OKHTTP_MAX_REQUESTS = 64;        // 与 OkHttp Dispatcher 默认一致
    public static final int DEFAULT_OKHTTP_MAX_REQUESTS_PER_HOST = 5; // 与 OkHttp Dispatcher 默认一致
    public static final int DEFAULT_SEARCH_BATCH_SIZE = 50;           // 与原 FastSearchActivity.BATCH_SIZE 一致
    // 边界：MIN 已提升到等于 DEFAULT（UI 不再展示比默认更小的值），MAX 大幅放宽以适配高性能手机
    public static final int MAX_SEARCH_THREAD_COUNT = 32;             // 旧 20
    public static final int MAX_SEARCH_SPIDER_CONCURRENCY = 32;       // 旧 20
    public static final int MIN_OKHTTP_MAX_REQUESTS = DEFAULT_OKHTTP_MAX_REQUESTS;   // 64
    public static final int MAX_OKHTTP_MAX_REQUESTS = 512;            // 旧 256
    public static final int MIN_OKHTTP_MAX_REQUESTS_PER_HOST = DEFAULT_OKHTTP_MAX_REQUESTS_PER_HOST; // 5
    public static final int MAX_OKHTTP_MAX_REQUESTS_PER_HOST = 64;    // 旧 32
    public static final int MIN_SEARCH_BATCH_SIZE = DEFAULT_SEARCH_BATCH_SIZE;       // 50
    public static final int MAX_SEARCH_BATCH_SIZE = 1000;             // 旧 200

    /**
     * 搜索调度并发线程数（后台搜索页 / 快速搜索页的调度线程池大小）
     */
    public static int getSearchThreadCount() {
        int value = Hawk.get(SEARCH_THREAD_COUNT, DEFAULT_SEARCH_THREAD_COUNT);
        // 新设计：UI 不展示比默认更小的值；旧版本若存了更小的值，统一 clamp 到默认
        if (value < DEFAULT_SEARCH_THREAD_COUNT) value = DEFAULT_SEARCH_THREAD_COUNT;
        if (value > MAX_SEARCH_THREAD_COUNT) value = MAX_SEARCH_THREAD_COUNT;
        return value;
    }

    /**
     * 插件(type==3)子进程执行的并发上限（共享信号量许可数）
     */
    public static int getSearchSpiderConcurrency() {
        int value = Hawk.get(SEARCH_SPIDER_CONCURRENCY, DEFAULT_SEARCH_SPIDER_CONCURRENCY);
        if (value < DEFAULT_SEARCH_SPIDER_CONCURRENCY) value = DEFAULT_SEARCH_SPIDER_CONCURRENCY;
        if (value > MAX_SEARCH_SPIDER_CONCURRENCY) value = MAX_SEARCH_SPIDER_CONCURRENCY;
        return value;
    }

    /**
     * OkHttp Dispatcher 总并发上限（maxRequests）。进程启动时读取生效，运行时修改需重启。
     */
    public static int getOkhttpMaxRequests() {
        int value = Hawk.get(OKHTTP_MAX_REQUESTS, DEFAULT_OKHTTP_MAX_REQUESTS);
        if (value < MIN_OKHTTP_MAX_REQUESTS) value = MIN_OKHTTP_MAX_REQUESTS;
        if (value > MAX_OKHTTP_MAX_REQUESTS) value = MAX_OKHTTP_MAX_REQUESTS;
        return value;
    }

    /**
     * OkHttp Dispatcher 单域名并发上限（maxRequestsPerHost）。进程启动时读取生效，运行时修改需重启。
     */
    public static int getOkhttpMaxRequestsPerHost() {
        int value = Hawk.get(OKHTTP_MAX_REQUESTS_PER_HOST, DEFAULT_OKHTTP_MAX_REQUESTS_PER_HOST);
        if (value < MIN_OKHTTP_MAX_REQUESTS_PER_HOST) value = MIN_OKHTTP_MAX_REQUESTS_PER_HOST;
        if (value > MAX_OKHTTP_MAX_REQUESTS_PER_HOST) value = MAX_OKHTTP_MAX_REQUESTS_PER_HOST;
        return value;
    }

    /**
     * 快速搜索页每批处理的源数量（BATCH_SIZE）。每次搜索任务前读取，无需重启。
     */
    public static int getSearchBatchSize() {
        int value = Hawk.get(SEARCH_BATCH_SIZE, DEFAULT_SEARCH_BATCH_SIZE);
        if (value < MIN_SEARCH_BATCH_SIZE) value = MIN_SEARCH_BATCH_SIZE;
        if (value > MAX_SEARCH_BATCH_SIZE) value = MAX_SEARCH_BATCH_SIZE;
        return value;
    }

    // ==================== 搜索性能配置 —— 写入侧 ====================
    // 由「搜索性能」弹窗调用，clamp 后再写入 Hawk。getter 自身已 clamp，UI 写值也 clamp 一次更稳妥。
    public static void putSearchThreadCount(int value) {
        if (value < DEFAULT_SEARCH_THREAD_COUNT) value = DEFAULT_SEARCH_THREAD_COUNT;
        if (value > MAX_SEARCH_THREAD_COUNT) value = MAX_SEARCH_THREAD_COUNT;
        Hawk.put(SEARCH_THREAD_COUNT, value);
    }

    public static void putSearchSpiderConcurrency(int value) {
        if (value < DEFAULT_SEARCH_SPIDER_CONCURRENCY) value = DEFAULT_SEARCH_SPIDER_CONCURRENCY;
        if (value > MAX_SEARCH_SPIDER_CONCURRENCY) value = MAX_SEARCH_SPIDER_CONCURRENCY;
        Hawk.put(SEARCH_SPIDER_CONCURRENCY, value);
    }

    public static void putOkhttpMaxRequests(int value) {
        if (value < MIN_OKHTTP_MAX_REQUESTS) value = MIN_OKHTTP_MAX_REQUESTS;
        if (value > MAX_OKHTTP_MAX_REQUESTS) value = MAX_OKHTTP_MAX_REQUESTS;
        Hawk.put(OKHTTP_MAX_REQUESTS, value);
    }

    public static void putOkhttpMaxRequestsPerHost(int value) {
        if (value < MIN_OKHTTP_MAX_REQUESTS_PER_HOST) value = MIN_OKHTTP_MAX_REQUESTS_PER_HOST;
        if (value > MAX_OKHTTP_MAX_REQUESTS_PER_HOST) value = MAX_OKHTTP_MAX_REQUESTS_PER_HOST;
        Hawk.put(OKHTTP_MAX_REQUESTS_PER_HOST, value);
    }

    public static void putSearchBatchSize(int value) {
        if (value < MIN_SEARCH_BATCH_SIZE) value = MIN_SEARCH_BATCH_SIZE;
        if (value > MAX_SEARCH_BATCH_SIZE) value = MAX_SEARCH_BATCH_SIZE;
        Hawk.put(SEARCH_BATCH_SIZE, value);
    }
}
