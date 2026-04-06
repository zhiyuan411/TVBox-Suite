package com.github.tvbox.osc.util.js;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.MonitorUtils;

import com.whl.quickjs.wrapper.Function;
import com.whl.quickjs.wrapper.JSArray;

import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.JSUtils;
import com.whl.quickjs.wrapper.QuickJSContext;
import com.whl.quickjs.wrapper.UriUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;


public class JsSpider extends Spider {

    private final ExecutorService executor;
    private final Class<?> dex;
    private QuickJSContext ctx;
    private JSObject jsObject;
    private JSObject localObject;
    private JSObject jsapiObject;
    private java.util.List<JSObject> subJsObjects;
    private final String key;
    private final String api;
    private final String apiKey;
    private boolean cat;
    private boolean initialized = false;
    private boolean initSuccess = false;
    private volatile boolean isDestroyed = false;
    private final Object initLock = new Object();

    public JsSpider(String key, String api, Class<?> cls) throws Exception {
        this.key = "J" + MD5.encode(key);
        // 关键修复：每个 JsSpider 实例使用独立的单线程线程池
        // 确保 QuickJSContext 的所有操作（创建、使用、销毁）都在同一个线程执行
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("JsSpider-" + key + "-Thread");
            thread.setDaemon(true);
            return thread;
        });
        this.api = api;
        this.apiKey = MD5.encode(api);
        this.dex = cls;
        this.subJsObjects = new java.util.ArrayList<>();
        initializeJS();
    }
    public void cancelByTag() {
        Connect.cancelByTag("js_okhttp_tag");
    }

    private void submit(Runnable runnable) {
        executor.submit(runnable);
    }

    private <T> Future<T> submit(Callable<T> callable) {
        return executor.submit(callable);
    }

    private Object call(String func, Object... args) {
        try {
            // 防御性编程：检查是否已销毁
            if (isDestroyed) {
                LOG.i("[JsSpider-" + key + "] 已销毁，跳过 call: " + func);
                return "";
            }
            
            // 防御性编程：检查必要对象是否有效
            if (jsObject == null || ctx == null) {
                return "";
            }
            
            // 核心执行流程
            return submit(() -> {
                try {
                    Future<Object> future = Async.run(jsObject, func, args);
                    Object result = future.get(30, TimeUnit.SECONDS); // 30秒超时
                    
                    // 定期 GC 调用，释放 JS 引擎中的临时对象
                    try {
                        if (ctx != null) {
                            ctx.runGC();
                        }
                    } catch (Exception e) {
                        // 忽略 GC 异常
                    }
                    
                    return result;
                } catch (TimeoutException e) {
                    try {
                        if (ctx != null) {
                            ctx.runGC();
                        }
                    } catch (Exception gcEx) {
                        // 忽略 GC 异常
                    }
                    return "";
                } catch (Exception e) {
                    try {
                        if (ctx != null) {
                            ctx.runGC();
                        }
                    } catch (Exception gcEx) {
                        // 忽略 GC 异常
                    }
                    return "";
                } catch (Throwable th) {
                    try {
                        if (ctx != null) {
                            ctx.runGC();
                        }
                    } catch (Exception gcEx) {
                        // 忽略 GC 异常
                    }
                    return "";
                }
            }).get(35, TimeUnit.SECONDS);  // 等待 executor 线程完成 JS 调用，额外5秒缓冲
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            return "";
        } catch (Exception e) {
            return "";
        } catch (Throwable th) {
            return "";
        }
    }

    private JSObject cfg(String ext) {
        JSObject cfg = ctx.createJSObject();
        cfg.set("stype", 3);
        cfg.set("skey", key);
        if (Json.invalid(ext)) cfg.set("ext", ext);
        else cfg.set("ext", (JSObject) ctx.parse(ext));
        return cfg;
    }

    @Override
    public void init(Context context, String extend) {
        if (!initialized) {
            synchronized (initLock) {
                if (!initialized) {
                    try {
                        if (cat) {
                            call("init", submit(() -> cfg(extend)).get());
                        } else {
                            if (Json.valid(extend)) {
                                Object parsedExtend = submit(() -> ctx.parse(extend)).get();
                                call("init", parsedExtend);
                            } else {
                                call("init", extend);
                            }
                        }
                        initialized = true;
                    } catch (Exception e) {
                        LOG.i("JS 爬虫初始化失败: " + e.getMessage());
                    }
                }
            }
        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            if (!initSuccess || jsObject == null || ctx == null) {
                LOG.i("JsSpider 未初始化成功，跳过 homeContent: " + key);
                return "{}";
            }
            return (String) call("home", filter);
        }catch (Exception e){
           return "{}";
        }catch (Throwable th){
           return "{}";
        }
    }

    @Override
    public String homeVideoContent() {
        try {
            if (!initSuccess || jsObject == null || ctx == null) {
                LOG.i("JsSpider 未初始化成功，跳过 homeVideoContent: " + key);
                return "{}";
            }
            return (String) call("homeVod");
        }catch (Exception e){
            return "{}";
        }catch (Throwable th){
            return "{}";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)  {
        try {
            if (!initSuccess || jsObject == null || ctx == null) {
                LOG.i("JsSpider 未初始化成功，跳过 categoryContent: " + key);
                return "{}";
            }
            JSObject obj = submit(() -> new JSUtils<String>().toObj(ctx, extend)).get();
            return (String) call("category", tid, pg, filter, obj);
        }catch (Exception e){
            return "{}";
        }catch (Throwable th){
            return "{}";
        }
    }

    @Override
    public String detailContent(List<String> ids)  {
        try {
            if (!initSuccess || jsObject == null || ctx == null) {
                LOG.i("JsSpider 未初始化成功，跳过 detailContent: " + key);
                return "{}";
            }
            return (String) call("detail", ids.get(0));
        }catch (Exception e){
            return "{}";
        }catch (Throwable th){
            return "{}";
        }
    }

    @Override
    public String searchContent(String key, boolean quick)  {
        try {
            if (!initSuccess || jsObject == null || ctx == null) {
                LOG.i("JsSpider 未初始化成功，跳过 searchContent: " + this.key);
                return "{}";
            }
            return (String) call("search", key, quick);
        }catch (Exception e){
            return "{}";
        }catch (Throwable th){
            return "{}";
        }
    }
    @Override
    public String searchContent(String key, boolean quick, String pg)  {
        try {
            if (!initSuccess || jsObject == null || ctx == null) {
                LOG.i("JsSpider 未初始化成功，跳过 searchContent(pg): " + this.key);
                return "{}";
            }
            return (String) call("search", key, quick, pg);
        }catch (Exception e){
            return "{}";
        }catch (Throwable th){
            return "{}";
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            if (!initSuccess || jsObject == null || ctx == null) {
                LOG.i("JsSpider 未初始化成功，跳过 playerContent: " + key);
                return "{}";
            }
            JSArray array = submit(() -> new JSUtils<String>().toArray(ctx, vipFlags)).get();
            return (String) call("play", flag, id, array);
        }catch (Exception e){
            return "{}";
        }catch (Throwable th){
            return "{}";
        }
    }

    @Override
    public boolean manualVideoCheck()  {
        try {
            return (Boolean) call("sniffer");
        }catch (Exception e){
            return false;
        }
    }

    @Override
    public boolean isVideoFormat(String url) {
        try {
            return (Boolean) call("isVideo", url);
        }catch (Exception e){
            return false;
        }
    }

    @Override
    public Object[] proxyLocal(Map<String, String> params)  {
        try {
            if ("catvod".equals(params.get("from"))) return proxy2(params);
            else return submit(() -> proxy1(params)).get();

        }catch (Exception E){
            return new Object[0];
        }
    }

    /**
     * 检查 Spider 是否初始化成功
     * @return true 表示初始化成功，false 表示初始化失败
     */
    public boolean isInitSuccess() {
        return initSuccess;
    }
    
    public String getApiKey() {
        return apiKey;
    }

    @Override
    public void destroy() {
        // 首先设置销毁标志，防止新的操作
        isDestroyed = true;
        
        boolean releaseSuccess = false;
        try {
            Future<Void> future = submit(() -> {
                // 再次检查销毁标志
                if (isDestroyed) {
                    // 释放所有 JSObject 资源
                    releaseAllJsObjects(Thread.currentThread().getName());
                    
                    // 运行 GC 并销毁 ctx（关键：释放 Native 内存）
                    destroyContext(Thread.currentThread().getName());
                }
                
                return null;
            });
            
            // 延长等待时间到 30 秒，确保复杂 Spider 有足够时间释放
            future.get(30, TimeUnit.SECONDS);
            releaseSuccess = true;
        } catch (Exception e) {
            // 异常情况下在线程池中执行强制释放
            try {
                submit(() -> {
                    releaseAllJsObjects(Thread.currentThread().getName());
                    destroyContext(Thread.currentThread().getName());
                    return null;
                }).get(15, TimeUnit.SECONDS);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } finally {
            // 无论如何都要关闭线程池，防止线程泄漏
            shutdownExecutor(Thread.currentThread().getName());
            
            // 如果正常释放失败，再次检查是否有遗漏的资源
            if (!releaseSuccess) {
                performCleanupCheck();
            }
        }
    }
    
    /**
     * 释放所有 JSObject 资源（提取为独立方法）
     */
    private void releaseAllJsObjects(String threadName) {
        // 释放 jsObject
        if (jsObject != null) {
            try {
                jsObject.release();
                jsObject = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        // 释放 localObject
        if (localObject != null) {
            try {
                localObject.release();
                localObject = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        // 释放 jsapiObject
        if (jsapiObject != null) {
            try {
                jsapiObject.release();
                jsapiObject = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        // 释放 subJsObjects
        if (subJsObjects != null && !subJsObjects.isEmpty()) {
            for (int i = 0; i < subJsObjects.size(); i++) {
                JSObject subObj = subJsObjects.get(i);
                if (subObj != null) {
                    try {
                        subObj.release();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            subJsObjects.clear();
        }
    }
    
    /**
     * 销毁 QuickJSContext（关键：释放 Native 内存）
     */
    private void destroyContext(String threadName) {
        if (ctx != null) {
            LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 开始销毁 QuickJS Runtime");
            com.github.tvbox.osc.util.MonitorUtils.monitorMemory(App.getInstance(), "JsSpider.destroyContext.start" + key);
            com.github.tvbox.osc.util.MonitorUtils.startTiming("JsSpider.destroyRuntime" + key);
            
            try {
                // ✅ 先运行 GC，帮助回收 Native 内存
                LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 运行 GC");
                ctx.runGC();
                LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] ctx GC 完成");
                
                // ✅ 再销毁 ctx，释放 Native 资源
                LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 执行 ctx.destroy()");
                ctx.destroy();
                LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] ctx.destroy() 执行完成");
                
                ctx = null;
                LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] ctx 销毁成功，引用已置为 null");
            } catch (Exception e) {
                LOG.e("[线程：" + threadName + "] 销毁 ctx 异常：" + e.getMessage());
            } finally {
                com.github.tvbox.osc.util.MonitorUtils.endTiming("JsSpider.destroyRuntime" + key);
                com.github.tvbox.osc.util.MonitorUtils.monitorMemory(App.getInstance(), "JsSpider.destroyContext.end" + key);
            }
        } else {
            LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] ctx 为 null，跳过销毁");
        }
    }
    
    /**
     * 强制释放资源（异常情况下使用）
     */
    @Deprecated
    private void forceReleaseResources(String threadName) {
        LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 执行强制资源释放");
        try {
            // 即使 Future.get 超时，也尝试直接释放资源
            releaseAllJsObjects(threadName);
            destroyContext(threadName);
        } catch (Exception e) {
            LOG.e("[线程：" + threadName + "] 强制释放资源异常：" + e.getMessage(), e);
        }
    }
    
    /**
     * 关闭 Executor（防止线程泄漏）
     */
    private void shutdownExecutor(String threadName) {
        if (executor != null && !executor.isShutdown()) {
            LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 关闭线程池");
            try {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 线程池未能在 5 秒内终止");
                } else {
                    LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 线程池已关闭");
                }
            } catch (InterruptedException e) {
                LOG.e("[线程：" + threadName + "] 等待线程池关闭被中断", e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.e("[线程：" + threadName + "] 关闭 executor 异常：" + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 执行清理检查（确保没有遗漏的资源）
     */
    private void performCleanupCheck() {
        String threadName = Thread.currentThread().getName();
        boolean hasLeak = false;
        
        if (jsObject != null) {
            LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 检测到 jsObject 未释放");
            hasLeak = true;
        }
        if (localObject != null) {
            LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 检测到 localObject 未释放");
            hasLeak = true;
        }
        if (jsapiObject != null) {
            LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 检测到 jsapiObject 未释放");
            hasLeak = true;
        }
        if (subJsObjects != null && !subJsObjects.isEmpty()) {
            LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 检测到 " + subJsObjects.size() + " 个 subJsObjects 未释放");
            hasLeak = true;
        }
        if (ctx != null) {
            LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] 检测到 ctx 未销毁 - ⚠️ 可能导致 Native 内存泄漏！");
            hasLeak = true;
        }
        
        if (hasLeak) {
            LOG.e("[线程：" + threadName + "] [JsSpider-" + key + "] ⚠️⚠️⚠️ 严重：存在资源泄漏风险，请检查日志定位原因");
        } else {
            LOG.i("[线程：" + threadName + "] [JsSpider-" + key + "] ✓ 所有资源已成功释放");
        }
    }


    private static final String SPIDER_STRING_CODE = "import * as spider from '%s'\n\n" +
            "if (!globalThis.__JS_SPIDER__) {\n" +
            "    if (spider.__jsEvalReturn) {\n" +
            "        globalThis.req = http\n" +
            "        globalThis.__JS_SPIDER__ = spider.__jsEvalReturn()\n" +
            "        globalThis.__JS_SPIDER__.is_cat = true\n" +
            "    } else if (spider.default) {\n" +
            "        globalThis.__JS_SPIDER__ = typeof spider.default === 'function' ? spider.default() : spider.default\n" +
            "    }\n" +
            "}";
    private void initializeJS() throws Exception {
        try {
            String threadName = Thread.currentThread().getName();
            LOG.i("[线程: " + threadName + "] 开始初始化 JS 环境: " + api);
            com.github.tvbox.osc.util.MonitorUtils.monitorMemory(App.getInstance(), "JsSpider.initializeJS.start" + key);
            com.github.tvbox.osc.util.MonitorUtils.monitorThread("JsSpider.initializeJS" + key);
            
            submit(() -> {
                try {
                    String execThreadName = Thread.currentThread().getName();
                    LOG.i("[线程: " + execThreadName + "] 执行 JS 初始化任务: " + api);
                    com.github.tvbox.osc.util.MonitorUtils.startTiming("JsSpider.createCtx" + key);
                    
                    if (ctx == null) {
                        createCtx();
                    }
                    com.github.tvbox.osc.util.MonitorUtils.endTiming("JsSpider.createCtx" + key);
                    
                    if (dex != null) {
                        createDex();
                    }

                    String content = FileUtils.loadModule(api);            
                    if (TextUtils.isEmpty(content)) {
                        LOG.i("[线程: " + execThreadName + "] 模块内容为空: " + api);
                        cleanUpPartialInit();
                        return null;
                    }
                    
                    // 尝试处理压缩格式的Base64编码内容
                    String decodedContent = tryDecodeCompressedContent(content);
                    if (decodedContent != null && !decodedContent.equals(content)) {
                        content = decodedContent;
                    }
                    
                    // 内容和格式校验
                    if (!isValidJSContent(content)) {
                        LOG.i("[线程: " + execThreadName + "] 模块内容无效: " + api);
                        cleanUpPartialInit();
                        return null;
                    }
                    
                    // 尝试加载和执行JS代码
                    boolean loadSuccess = false;
                    com.github.tvbox.osc.util.MonitorUtils.startTiming("JsSpider.loadJS" + key);
                    if(content.startsWith("//bb")){
                        cat = true;
                        try {
                            byte[] b = Base64.decode(content.replace("//bb",""), 0);
                            ctx.execute(byteFF(b), key + ".js");
                            ctx.evaluateModule(String.format(SPIDER_STRING_CODE, key + ".js") + "globalThis." + key + " = __JS_SPIDER__;", "tv_box_root.js");
                            loadSuccess = true;
                        } catch (Exception e) {
                            LOG.i("[线程: " + execThreadName + "] 处理 bb 格式内容异常: " + e.getMessage());
                            cleanUpPartialInit();
                        } catch (Throwable th) {
                            LOG.i("[线程: " + execThreadName + "] 处理 bb 格式内容严重异常: " + th.getMessage());
                            cleanUpPartialInit();
                        }
                    } else {
                        try {
                            // 验证内容是否为有效的JS代码，避免编译无效内容导致JNI错误
                            if (content.startsWith("<")) {
                                LOG.i("[线程: " + execThreadName + "] 无效的JS内容（以'<'开头）: " + api);
                                cleanUpPartialInit();
                                return null;
                            }
                            
                            if (content.contains("__JS_SPIDER__")) {
                                content = content.replaceAll("__JS_SPIDER__\\s*=", "export default ");
                            }
                            String moduleExtName = "default";
                            if (content.contains("__jsEvalReturn") && !content.contains("export default")) {
                                moduleExtName = "__jsEvalReturn";
                                cat = true;
                            }
                            
                            // 捕获evaluateModule过程中的异常，避免JNI错误
                            try {
                                ctx.evaluateModule(content, api);
                                ctx.evaluateModule(String.format(SPIDER_STRING_CODE, api) + "globalThis." + key + " = __JS_SPIDER__;", "tv_box_root.js");
                                loadSuccess = true;
                            } catch (com.whl.quickjs.wrapper.QuickJSException e) {
                                // 特殊处理QuickJSException，避免JNI错误
                                LOG.i("[线程: " + execThreadName + "] QuickJS执行异常: " + e.getMessage());
                                cleanUpPartialInit();
                            } catch (Throwable th) {
                                // 捕获所有其他异常，确保不会导致JNI错误
                                LOG.i("[线程: " + execThreadName + "] JS执行严重异常: " + th.getMessage());
                                cleanUpPartialInit();
                            }
                        } catch (Exception e) {
                            LOG.i("[线程: " + execThreadName + "] 处理模块内容异常: " + e.getMessage());
                            cleanUpPartialInit();
                        } catch (Throwable th) {
                            LOG.i("[线程: " + execThreadName + "] 处理模块内容严重异常: " + th.getMessage());
                            cleanUpPartialInit();
                        }
                    }
                    com.github.tvbox.osc.util.MonitorUtils.endTiming("JsSpider.loadJS" + key);
                    
                    // 只有加载成功后才尝试获取JSObject
                    if (loadSuccess) {
                        try {
                            jsObject = (JSObject) ctx.get(ctx.getGlobalObject(), key);
                            ctx.runGC();
                            if (jsObject != null) {
                                initSuccess = true;
                                LOG.i("[线程: " + execThreadName + "] JS 初始化成功，jsObject 已获取");
                                com.github.tvbox.osc.util.MonitorUtils.monitorMemory(App.getInstance(), "JsSpider.initializeJS.end" + key);
                            } else {
                                LOG.i("[线程: " + execThreadName + "] JSObject 获取结果为 null，清理资源");
                                cleanUpPartialInit();
                            }
                        } catch (Exception e) {
                            LOG.i("[线程: " + execThreadName + "] 获取 JSObject 异常: " + e.getMessage());
                            cleanUpPartialInit();
                        }
                    }
                    return null;
                } catch (OutOfMemoryError e) {
                    String execThreadName = Thread.currentThread().getName();
                    LOG.e("[线程: " + execThreadName + "] 初始化 JS 环境时内存不足", e);
                    e.printStackTrace();
                    cleanUpPartialInit();
                    com.github.tvbox.osc.util.MonitorUtils.monitorMemory(App.getInstance(), "JsSpider.initializeJS.OOM" + key);
                    return null;
                } catch (Exception e) {
                    String execThreadName = Thread.currentThread().getName();
                    LOG.i("[线程: " + execThreadName + "] 初始化 JS 环境异常: " + e.getMessage());
                    cleanUpPartialInit();
                    return null;
                } finally {
                    // 确保即使发生异常，也能保持系统稳定
                    String execThreadName = Thread.currentThread().getName();
                    LOG.i("[线程: " + execThreadName + "] JS 初始化完成: " + api);
                }
            }).get(60, TimeUnit.SECONDS); // 60秒超时
        } catch (TimeoutException e) {
            String threadName = Thread.currentThread().getName();
            LOG.i("[线程: " + threadName + "] JS 初始化超时: " + api);
            // 关键修复：超时异常时，cleanUpPartialInit()必须在executor线程中执行
            try {
                submit(() -> {
                    cleanUpPartialInit();
                    return null;
                }).get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                LOG.e("[线程: " + threadName + "] 超时后清理资源异常", ex);
            }
        } catch (Exception e) {
            String threadName = Thread.currentThread().getName();
            LOG.i("[线程: " + threadName + "] 初始化 JS 时发生异常: " + e.getMessage());
            // 关键修复：其他异常时，cleanUpPartialInit()必须在executor线程中执行
            try {
                submit(() -> {
                    cleanUpPartialInit();
                    return null;
                }).get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                LOG.e("[线程: " + threadName + "] 异常后清理资源异常", ex);
            }
        }
    }
    
    /**
     * 清理部分初始化失败时的资源
     */
    private void cleanUpPartialInit() {
        String threadName = Thread.currentThread().getName();
        LOG.i("[线程: " + threadName + "] [JsSpider-" + key + "] 开始清理部分初始化资源");
        
        try {
            if (jsObject != null) {
                jsObject.release();
                jsObject = null;
            }
        } catch (Exception e) {
            LOG.e("释放部分初始化 jsObject 异常: " + e.getMessage());
        }
        
        try {
            if (localObject != null) {
                localObject.release();
                localObject = null;
            }
        } catch (Exception e) {
            LOG.e("释放部分初始化 localObject 异常: " + e.getMessage());
        }
        
        try {
            if (jsapiObject != null) {
                jsapiObject.release();
                jsapiObject = null;
            }
        } catch (Exception e) {
            LOG.e("释放部分初始化 jsapiObject 异常: " + e.getMessage());
        }
        
        try {
            if (subJsObjects != null && !subJsObjects.isEmpty()) {
                for (int i = 0; i < subJsObjects.size(); i++) {
                    JSObject subObj = subJsObjects.get(i);
                    if (subObj != null) {
                        try {
                            subObj.release();
                        } catch (Exception e) {
                            LOG.e("释放 subJsObjects[" + i + "] 异常: " + e.getMessage());
                        }
                    }
                }
                subJsObjects.clear();
            }
        } catch (Exception e) {
            LOG.e("释放部分初始化 subJsObjects 异常: " + e.getMessage());
        }
        
        try {
            if (ctx != null) {
                ctx.runGC();
                ctx.destroy();
                ctx = null;
            }
        } catch (Exception e) {
            LOG.e("清理部分初始化资源异常: " + e.getMessage());
        }
        LOG.i("[线程: " + threadName + "] [JsSpider-" + key + "] 部分初始化资源清理完成");
    }

    public static byte[] byteFF(byte[] bytes) {
        byte[] newBt = new byte[bytes.length - 4];
        newBt[0] = 1;
        System.arraycopy(bytes, 5, newBt, 1, bytes.length - 5);
        return newBt;
    }

    private void createCtx() {
        String threadName = Thread.currentThread().getName();
        LOG.i("[线程: " + threadName + "] [JsSpider-" + key + "] 开始创建 QuickJS Runtime");
        com.github.tvbox.osc.util.MonitorUtils.monitorMemory(App.getInstance(), "JsSpider.createCtx.start" + key);
        com.github.tvbox.osc.util.MonitorUtils.startTiming("JsSpider.createRuntime" + key);
        
        ctx = QuickJSContext.create();
        LOG.i("[线程: " + threadName + "] [JsSpider-" + key + "] QuickJS Runtime 创建成功，ctx: " + ctx);
        
        com.github.tvbox.osc.util.MonitorUtils.endTiming("JsSpider.createRuntime" + key);
        com.github.tvbox.osc.util.MonitorUtils.monitorMemory(App.getInstance(), "JsSpider.createCtx.end" + key);
        
        ctx.setModuleLoader(new QuickJSContext.BytecodeModuleLoader() {
            @Override
            public byte[] getModuleBytecode(String moduleName) {
                try {
                    String ss = FileUtils.loadModule(moduleName);
                    if (TextUtils.isEmpty(ss)) {
                        LOG.i("echo-getModuleBytecode empty :"+ moduleName);
                        return "export default {};".getBytes();
                    }
                    
                    // 验证内容是否为有效的JS代码，避免编译无效内容导致JNI错误
                    if (ss.startsWith("<")) {
                        LOG.i("echo-getModuleBytecode invalid JS content (starts with '<'):"+ moduleName);
                        return "export default {};".getBytes();
                    }
                    
                    if(ss.startsWith("//DRPY")){
                        try {
                            return Base64.decode(ss.replace("//DRPY",""), Base64.URL_SAFE);
                        } catch (Throwable th) {
                            LOG.i("echo-getModuleBytecode DRPY decode error:"+ moduleName + " - " + th.getMessage());
                            return "export default {};".getBytes();
                        }
                    } else if(ss.startsWith("//bb")){
                        try {
                            byte[] b = Base64.decode(ss.replace("//bb",""), 0);
                            return byteFF(b);
                        } catch (Throwable th) {
                            LOG.i("echo-getModuleBytecode bb decode error:"+ moduleName + " - " + th.getMessage());
                            return "export default {};".getBytes();
                        }
                    } else {
                        // 捕获编译过程中的异常，避免JNI错误
                        try {
                            if (moduleName.contains("cheerio.min.js")) {
                                byte[] compiled = ctx.compileModule(ss, "cheerio.min.js");
                                if (compiled != null) {
                                    FileUtils.setCacheByte("cheerio.min", compiled);
                                }
                            } else if (moduleName.contains("crypto-js.js")) {
                                byte[] compiled = ctx.compileModule(ss, "crypto-js.js");
                                if (compiled != null) {
                                    FileUtils.setCacheByte("crypto-js", compiled);
                                }
                            }
                            byte[] result = ctx.compileModule(ss, moduleName);
                            if (result == null) {
                                LOG.i("echo-getModuleBytecode compiled null, return empty module :"+ moduleName);
                                return "export default {};".getBytes();
                            }
                            return result;
                        } catch (com.whl.quickjs.wrapper.QuickJSException e) {
                            // 特殊处理QuickJSException，避免JNI错误
                            LOG.i("echo-getModuleBytecode QuickJS compile error:"+ moduleName + " - " + e.getMessage());
                            return "export default {};".getBytes();
                        } catch (Throwable th) {
                            // 捕获所有其他异常，确保不会导致JNI错误
                            LOG.i("echo-getModuleBytecode compile error:"+ moduleName + " - " + th.getMessage());
                            return "export default {};".getBytes();
                        }
                    }
                } catch (Throwable th) {
                    // 捕获所有异常，确保即使获取模块内容失败也不会导致JNI错误
                    LOG.i("echo-getModuleBytecode error:"+ moduleName + " - " + th.getMessage());
                    return "export default {};".getBytes();
                }
            }

            @Override
            public String moduleNormalizeName(String moduleBaseName, String moduleName) {
                return UriUtil.resolve(moduleBaseName, moduleName);
            }
        });
        ctx.setConsole(new QuickJSContext.Console() {
            @Override
            public void log(String s) {
                // 日志截断处理，避免过长日志影响可读性
                if (s != null) {
                    if (s.length() > 200) {
                        try {
                            s = s.substring(0, 200) + "... [截断，原始长度: " + s.length() + "]";
                        } catch (IndexOutOfBoundsException e) {
                            LOG.i("QuJs: 日志截断异常: " + e.getMessage());
                            s = s + " [截断异常，原始长度: " + (s != null ? s.length() : 0) + "]";
                        }
                    }
                    // 添加线程标识，与本类中其他位置格式一致
                    String threadName = Thread.currentThread().getName();
                    LOG.i("[线程: " + threadName + "] QuJs：" + s);
                }
            }
        });

        ctx.getGlobalObject().bind(new Global(executor));

        localObject = ctx.createJSObject();
        ctx.getGlobalObject().set("local", localObject);
        localObject.bind(new local());

        ctx.getGlobalObject().getContext().evaluate(FileUtils.loadModule("net.js"));
    }

    private void createDex() {
        try {
            jsapiObject = ctx.createJSObject();
            Class<?> clz = dex;
            Class<?>[] classes = clz.getDeclaredClasses();
            ctx.getGlobalObject().set("jsapi", jsapiObject);
            if (classes.length == 0) invokeSingle(clz, jsapiObject);
            if (classes.length >= 1) invokeMultiple(clz, jsapiObject);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void invokeSingle(Class<?> clz, JSObject jsObj) throws Throwable {
        invoke(clz, jsObj, clz.getDeclaredConstructor(QuickJSContext.class).newInstance(ctx));
    }

    private void invokeMultiple(Class<?> clz, JSObject jsObj) throws Throwable {
        for (Class<?> subClz : clz.getDeclaredClasses()) {
            Object javaObj = subClz.getDeclaredConstructor(clz).newInstance(clz.getDeclaredConstructor(QuickJSContext.class).newInstance(ctx));
            JSObject subObj = ctx.createJSObject();
            subJsObjects.add(subObj);
            invoke(subClz, subObj, javaObj);
            jsObj.set(subClz.getSimpleName(), subObj);
        }
    }

    private void invoke(Class<?> clz, JSObject jsObj, Object javaObj) {
        for (Method method : clz.getMethods()) {
            if (!method.isAnnotationPresent(Function.class)) continue;
            invoke(jsObj, method, javaObj);
        }
    }

    private void invoke(JSObject jsObj, Method method, Object javaObj) {
        jsObj.set(method.getName(), new JSCallFunction() {
            @Override
            public Object call(Object... objects) {
                try {
                    return method.invoke(javaObj, objects);
                } catch (Throwable e) {
                    return null;
                }
            }
        });
    }

    private String getContent() {
        String global = "globalThis." + key;
        String content = FileUtils.loadModule(api);
        if (TextUtils.isEmpty(content)) {return null;}
        if (content.contains("__jsEvalReturn")) {
            ctx.evaluate("req = http");
            return content.concat(global).concat(" = __jsEvalReturn()");
        } else if (content.contains("__JS_SPIDER__")) {
            return content.replace("__JS_SPIDER__", global);
        } else {
            return content.replaceAll("export default.*?[{]", global + " = {");
        }
    }

    private Object[] proxy1(Map<String, String> params) {
        try {
            if (jsObject == null || ctx == null) {
                LOG.i("JSObject 或 ctx 为 null，无法执行 proxy1");
                return new Object[0];
            }
            JSObject object = new JSUtils<String>().toObj(ctx, params);
            Object proxyResult = jsObject.getJSFunction("proxy").call(object);
            if (proxyResult == null) {
                LOG.i("proxy 函数返回 null");
                return new Object[0];
            }
            JSONArray array = ((JSArray) proxyResult).toJsonArray();
            boolean headerAvailable = array.length() > 3 && array.opt(3) != null;
            Object[] result = new Object[4];
            result[0] = array.opt(0);
            result[1] = array.opt(1);
            result[2] = getStream(array.opt(2));
            result[3] = headerAvailable ? getHeader(array.opt(3)) : null;
            if (array.length() > 4) {
                try {
                    if ( array.optInt(4) == 1) {
                        String content = array.optString(2);
                        if (content != null && content.contains("base64,")) {
                            int base64Index = content.indexOf("base64,");
                            if (base64Index >= 0 && base64Index + 7 <= content.length()) {
                                content = content.substring(base64Index + 7);
                            }
                        }
                        result[2] = new ByteArrayInputStream(Base64.decode(content, Base64.DEFAULT));
                    }
                } catch (IndexOutOfBoundsException e) {
                    LOG.i("处理 base64 内容字符串索引异常: " + e.getMessage());
                } catch (Exception e) {
                    LOG.i("处理 base64 内容异常: " + e.getMessage());
                }
            }
            return result;
        } catch (Exception e) {
            LOG.i("执行 proxy1 异常: " + e.getMessage());
            return new Object[0];
        }
    }

    private Map<String, String> getHeader(Object headerRaw) {
        Map<String, String> headers = new HashMap<>();
        if (headerRaw instanceof JSONObject) {
            JSONObject json = (JSONObject) headerRaw;
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                headers.put(key, json.optString(key));
            }
        } else if (headerRaw instanceof String) {
            try {
                JSONObject json = new JSONObject((String) headerRaw);
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    headers.put(key, json.optString(key));
                }
            } catch (JSONException e) {
                LOG.i("getHeader: 无法解析 String 为 JSON"+ e);
            }
        } else if (headerRaw instanceof Map) {
            //noinspection unchecked
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) headerRaw).entrySet()) {
                headers.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return headers;
    }
    
    private Object[] proxy2(Map<String, String> params) throws Exception {
        try {
            if (ctx == null) {
                LOG.i("ctx 为 null，无法执行 proxy2");
                return new Object[0];
            }
            String url = params.get("url");
            String header = params.get("header");
            if (TextUtils.isEmpty(url)) {
                LOG.i("url 为空，无法执行 proxy2");
                return new Object[0];
            }
            JSArray array = submit((Callable<JSArray>) () -> {
                try {
                    return new JSUtils<String>().toArray(ctx, Arrays.asList(url.split("/")));
                } catch (Exception e) {
                    LOG.i("创建 JSArray 异常: " + e.getMessage());
                    return null;
                }
            }).get();
            if (array == null) {
                LOG.i("JSArray 为 null，无法执行 proxy2");
                return new Object[0];
            }
            Object object = submit((Callable<Object>) () -> {
                try {
                    return ctx.parse(header);
                } catch (Exception e) {
                    LOG.i("解析 header 异常: " + e.getMessage());
                    return null;
                }
            }).get();
            String json = (String) call("proxy", array, object);
            if (TextUtils.isEmpty(json)) {
                LOG.i("proxy 函数返回空，无法执行 proxy2");
                return new Object[0];
            }
            Res res = Res.objectFrom(json);
            if (res == null) {
                LOG.i("无法解析 Res 对象，无法执行 proxy2");
                return new Object[0];
            }
            String contentType = res.getContentType();
            if (TextUtils.isEmpty(contentType)) contentType = "application/octet-stream";
            Object[] result = new Object[3];
            result[0] = 200;
            result[1] = contentType;
            try {
                if (res.getBuffer() == 2) {
                    result[2] = new ByteArrayInputStream(Base64.decode(res.getContent(), Base64.DEFAULT));
                } else {
                    result[2] = new ByteArrayInputStream(res.getContent().getBytes());
                }
            } catch (Exception e) {
                LOG.i("处理内容异常: " + e.getMessage());
                result[2] = new ByteArrayInputStream(new byte[0]);
            }
            return result;
        } catch (Exception e) {
            LOG.i("执行 proxy2 异常: " + e.getMessage());
            return new Object[0];
        }
    }
    
    /**
     * 尝试解码压缩格式的Base64编码内容
     * @param content 原始内容
     * @return 解码后的内容，失败则返回null
     */
    private String tryDecodeCompressedContent(String content) {
        if (TextUtils.isEmpty(content)) {
            return null;
        }
        
        // 检查内容长度是否足够
        if (content.length() < 4) {
            LOG.i("tryDecodeCompressedContent: 内容长度不足4: " + content.length());
            return null;
        }
        
        // 获取前4个字符
        String prefix;
        try {
            prefix = content.substring(0, 4);
        } catch (IndexOutOfBoundsException e) {
            LOG.i("tryDecodeCompressedContent: substring(0,4) 异常: " + e.getMessage());
            return null;
        }
        
        // 根据前缀判断是否可能是压缩格式的Base64编码
        boolean isCompressedFormat = prefix.equals("H4sI") || // gzip
                                   prefix.equals("eJx") || // zlib (默认)
                                   prefix.equals("eNr") || // zlib (最佳)
                                   prefix.equals("Qlpo"); // bzip2
        
        if (!isCompressedFormat) {
            return null;
        }
        
        try {
            // 尝试Base64解码
            byte[] decodedBytes = Base64.decode(content, Base64.DEFAULT);
            
            // 根据前缀判断压缩格式并尝试解压
            switch (prefix) {
                case "H4sI": // gzip
                    LOG.i("检测到 gzip 压缩格式");
                    return decompressGzip(decodedBytes);
                case "eJx": // zlib (默认)
                case "eNr": // zlib (最佳)
                    LOG.i("检测到 zlib 压缩格式");
                    return decompressZlib(decodedBytes);
                case "Qlpo": // bzip2
                    LOG.i("检测到 bzip2 压缩格式");
                    return decompressBzip2(decodedBytes);
                default:
                    return null;
            }
        } catch (Exception e) {
            LOG.i("解码压缩内容异常: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 解压 gzip 压缩的字节数组
     * @param bytes 压缩的字节数组
     * @return 解压后的字符串
     */
    private String decompressGzip(byte[] bytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             GZIPInputStream gis = new GZIPInputStream(bis);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                bos.write(buffer, 0, len);
            }
            
            return bos.toString("UTF-8");
        } catch (IOException e) {
            LOG.i("解压 gzip 异常: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 解压 zlib 压缩的字节数组
     * @param bytes 压缩的字节数组
     * @return 解压后的字符串
     */
    private String decompressZlib(byte[] bytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             InflaterInputStream iis = new InflaterInputStream(bis);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = iis.read(buffer)) > 0) {
                bos.write(buffer, 0, len);
            }
            
            return bos.toString("UTF-8");
        } catch (IOException e) {
            LOG.i("解压 zlib 异常: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 解压 bzip2 压缩的字节数组
     * @param bytes 压缩的字节数组
     * @return 解压后的字符串
     */
    private String decompressBzip2(byte[] bytes) {
        // 注意：Android 标准库不包含 Bzip2 解压功能
        // 这里返回null，表示暂不支持bzip2格式
        LOG.i("暂不支持 bzip2 压缩格式");
        return null;
    }

   /* private Object[] proxy2(Map<String, String> params) throws Exception {
        String url = params.get("url");
        String header = params.get("header");
        JSArray array = submit(() -> new JSUtils<String>().toArray(ctx, Arrays.asList(url.split("/")))).get();
        Object object = submit(() -> ctx.parse(header)).get();
        String json = (String) call("proxy", array, object);
        Res res = Res.objectFrom(json);
        Object[] result = new Object[3];
        result[0] = 200;
        result[1] = "application/octet-stream";
        result[2] = new ByteArrayInputStream(Base64.decode(res.getContent(), Base64.DEFAULT));
        return result;
    }*/

    private ByteArrayInputStream getStream(Object o) {
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            byte[] bytes = new byte[a.length()];
            for (int i = 0; i < a.length(); i++) bytes[i] = (byte) a.optInt(i);
            return new ByteArrayInputStream(bytes);
        } else {
            return new ByteArrayInputStream(o.toString().getBytes());
        }
    }
    
    /**
     * 校验JS内容的有效性
     * @param content JS内容
     * @return 是否有效
     */
    private boolean isValidJSContent(String content) {
        try {
            // 检查内容长度
            if (content.length() > 10 * 1024 * 1024) { // 限制10MB
                LOG.i("JS内容过大");
                return false;
            }
            
            // 检查是否为HTML错误页面（以'<'开头）
            if (content.trim().startsWith("<")) {
                LOG.i("JS内容可能是HTML错误页面");
                return false;
            }
            
            // 检查是否包含基本的JS语法结构
            if (content.contains("function") || content.contains("=>") || content.contains("export") || content.contains("var") || content.contains("let") || content.contains("const")) {
                return true;
            }
            
            // 检查是否为base64编码的内容
            if (content.startsWith("//bb") || content.startsWith("//DRPY")) {
                return true;
            }
            
            return false;
        } catch (Exception e) {
            LOG.i("校验JS内容异常: " + e.getMessage());
            return false;
        }
    }
}
