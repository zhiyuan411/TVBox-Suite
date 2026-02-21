package com.github.tvbox.osc.util.js;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;

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
    private final String key;
    private final String api;
    private boolean cat;

    public JsSpider(String key, String api, Class<?> cls) throws Exception {
        this.key = "J" + MD5.encode(key);
        // 使用共享的 JavaScript 执行线程池
        this.executor = com.github.tvbox.osc.viewmodel.SourceViewModel.getJsExecutorService();
        this.api = api;
        this.dex = cls;
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
//        return executor.submit((FunCall.call(jsObject, func, args))).get();
        try {
            String threadName = Thread.currentThread().getName();
            LOG.i("[线程: " + threadName + "] 开始调用 JS 函数: " + func);
            if (jsObject == null) {
                LOG.i("[线程: " + threadName + "] JSObject 为 null，无法调用函数: " + func);
                return "";
            }
            if (ctx == null) {
                LOG.i("[线程: " + threadName + "] JS 上下文为 null，无法调用函数: " + func);
                return "";
            }
            LOG.i("[线程: " + threadName + "] 提交 JS 函数调用任务: " + func);
            return submit(() -> {
                String execThreadName = Thread.currentThread().getName();
                LOG.i("[线程: " + execThreadName + "] 执行 JS 函数调用: " + func);
                try {
                    // 设置JS执行超时时间
                    LOG.i("[线程: " + execThreadName + "] 开始执行 JS 函数: " + func);
                    Future<Object> future = Async.run(jsObject, func, args);
                    LOG.i("[线程: " + execThreadName + "] 等待 JS 函数执行完成: " + func);
                    Object result = future.get(30, TimeUnit.SECONDS); // 30秒超时
                    LOG.i("[线程: " + execThreadName + "] JS 函数执行成功: " + func);
                    return result;
                } catch (TimeoutException e) {
                    LOG.i("[线程: " + execThreadName + "] JS 函数执行超时: " + func);
                    return "";
                } catch (Exception e) {
                    LOG.i("[线程: " + execThreadName + "] JS 函数执行异常: " + func + "，错误: " + e.getMessage());
                    return "";
                } catch (Throwable th) {
                    LOG.i("[线程: " + execThreadName + "] JS 函数执行严重异常: " + func + "，错误: " + th.getMessage());
                    return "";
                }
            }).get(35, TimeUnit.SECONDS);  // 等待 executor 线程完成 JS 调用，额外5秒缓冲
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            String threadName = Thread.currentThread().getName();
            LOG.i("[线程: " + threadName + "] Executor 提交或等待失败"+ e);
            return "";
        } catch (Exception e) {
            String threadName = Thread.currentThread().getName();
            LOG.i("[线程: " + threadName + "] 调用函数时发生异常: " + e.getMessage());
            return "";
        } catch (Throwable th) {
            String threadName = Thread.currentThread().getName();
            LOG.i("[线程: " + threadName + "] 调用函数时发生严重异常: " + th.getMessage());
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
        try {
            if (cat) call("init", submit(() -> cfg(extend)).get());
            else call("init", Json.valid(extend) ? ctx.parse(extend) : extend);
        }catch (Exception e){

        }
    }

    @Override
    public String homeContent(boolean filter) {
        try {
            return (String) call("home", filter);
        }catch (Exception e){
           return null;
        }
    }

    @Override
    public String homeVideoContent() {
        try {
            return (String) call("homeVod");
        }catch (Exception e){
            return null;
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)  {
        try {
            JSObject obj = submit(() -> new JSUtils<String>().toObj(ctx, extend)).get();
            return (String) call("category", tid, pg, filter, obj);
        }catch (Exception e){
            return null;
        }
    }

    @Override
    public String detailContent(List<String> ids)  {
        try {
            return (String) call("detail", ids.get(0));
        }catch (Exception e){
            return null;
        }
    }

    @Override
    public String searchContent(String key, boolean quick)  {
        try {
            return (String) call("search", key, quick);
        }catch (Exception e){
            return null;
        }
    }
    @Override
    public String searchContent(String key, boolean quick, String pg)  {
        try {
            return (String) call("search", key, quick, pg);
        }catch (Exception e){
            return null;
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        try {
            JSArray array = submit(() -> new JSUtils<String>().toArray(ctx, vipFlags)).get();
            return (String) call("play", flag, id, array);
        }catch (Exception e){
            return null;
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

    @Override
    public void destroy() {
        try {
            submit(() -> {
                // 不再关闭线程池，因为它是共享的
                try {
                    if (ctx != null) {
                        ctx.destroy();
                    }
                } catch (Exception e) {
                    LOG.i("销毁 ctx 异常: " + e.getMessage());
                }
                try {
                    if (jsObject != null) {
                        jsObject.release();
                    }
                } catch (Exception e) {
                    LOG.i("释放 jsObject 异常: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            LOG.i("执行 destroy 异常: " + e.getMessage());
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
            submit(() -> {
                try {
                    String execThreadName = Thread.currentThread().getName();
                    LOG.i("[线程: " + execThreadName + "] 执行 JS 初始化任务: " + api);
                    if (ctx == null) {
                        LOG.i("[线程: " + execThreadName + "] 创建 JS 上下文");
                        createCtx();
                    }
                    if (dex != null) {
                        LOG.i("[线程: " + execThreadName + "] 创建 Dex");
                        createDex();
                    }

                    LOG.i("[线程: " + execThreadName + "] 加载模块内容: " + api);
                    String content = FileUtils.loadModule(api);            
                    if (TextUtils.isEmpty(content)) {
                        LOG.i("[线程: " + execThreadName + "] 模块内容为空: " + api);
                        return null;
                    }
                    
                    // 尝试处理压缩格式的Base64编码内容
                    LOG.i("[线程: " + execThreadName + "] 检查压缩格式: " + api);
                    String decodedContent = tryDecodeCompressedContent(content);
                    if (decodedContent != null && !decodedContent.equals(content)) {
                        LOG.i("[线程: " + execThreadName + "] 成功解码压缩格式内容: " + api);
                        content = decodedContent;
                    }
                    
                    // 内容和格式校验
                    LOG.i("[线程: " + execThreadName + "] 校验JS内容有效性: " + api);
                    if (!isValidJSContent(content)) {
                        LOG.i("[线程: " + execThreadName + "] 模块内容无效: " + api);
                        return null;
                    }
                    
                    // 尝试加载和执行JS代码
                    boolean loadSuccess = false;
                    LOG.i("[线程: " + execThreadName + "] 开始执行JS代码: " + api);
                    if(content.startsWith("//bb")){
                        cat = true;
                        try {
                            LOG.i("[线程: " + execThreadName + "] 处理 bb 格式内容: " + api);
                            byte[] b = Base64.decode(content.replace("//bb",""), 0);
                            ctx.execute(byteFF(b), key + ".js");
                            ctx.evaluateModule(String.format(SPIDER_STRING_CODE, key + ".js") + "globalThis." + key + " = __JS_SPIDER__;", "tv_box_root.js");
                            loadSuccess = true;
                            LOG.i("[线程: " + execThreadName + "] 处理 bb 格式成功: " + api);
                        } catch (Exception e) {
                            LOG.i("[线程: " + execThreadName + "] 处理 bb 格式内容异常: " + e.getMessage());
                        } catch (Throwable th) {
                            LOG.i("[线程: " + execThreadName + "] 处理 bb 格式内容严重异常: " + th.getMessage());
                        }
                    } else {
                        try {
                            LOG.i("[线程: " + execThreadName + "] 处理普通格式内容: " + api);
                            if (content.contains("__JS_SPIDER__")) {
                                content = content.replaceAll("__JS_SPIDER__\\s*=", "export default ");
                                LOG.i("[线程: " + execThreadName + "] 处理 __JS_SPIDER__ 标记: " + api);
                            }
                            String moduleExtName = "default";
                            if (content.contains("__jsEvalReturn") && !content.contains("export default")) {
                                moduleExtName = "__jsEvalReturn";
                                cat = true;
                                LOG.i("[线程: " + execThreadName + "] 处理 __jsEvalReturn 标记: " + api);
                            }
                            LOG.i("[线程: " + execThreadName + "] 执行 evaluateModule: " + api);
                            ctx.evaluateModule(content, api);
                            LOG.i("[线程: " + execThreadName + "] 执行 tv_box_root.js: " + api);
                            ctx.evaluateModule(String.format(SPIDER_STRING_CODE, api) + "globalThis." + key + " = __JS_SPIDER__;", "tv_box_root.js");
                            loadSuccess = true;
                            LOG.i("[线程: " + execThreadName + "] 处理普通格式成功: " + api);
                        } catch (Exception e) {
                            LOG.i("[线程: " + execThreadName + "] 处理模块内容异常: " + e.getMessage());
                        } catch (Throwable th) {
                            LOG.i("[线程: " + execThreadName + "] 处理模块内容严重异常: " + th.getMessage());
                        }
                    }
                    
                    // 只有加载成功后才尝试获取JSObject
                    if (loadSuccess) {
                        try {
                            LOG.i("[线程: " + execThreadName + "] 获取 JSObject: " + key);
                            jsObject = (JSObject) ctx.get(ctx.getGlobalObject(), key);
                            if (jsObject == null) {
                                LOG.i("[线程: " + execThreadName + "] 无法获取 JSObject: " + key);
                            } else {
                                LOG.i("[线程: " + execThreadName + "] 成功获取 JSObject: " + key);
                            }
                        } catch (Exception e) {
                            LOG.i("[线程: " + execThreadName + "] 获取 JSObject 异常: " + e.getMessage());
                        }
                    }
                    return null;
                } catch (Exception e) {
                    String execThreadName = Thread.currentThread().getName();
                    LOG.i("[线程: " + execThreadName + "] 初始化 JS 环境异常: " + e.getMessage());
                    return null;
                } finally {
                    // 确保即使发生异常，也能保持系统稳定
                    String execThreadName = Thread.currentThread().getName();
                    LOG.i("[线程: " + execThreadName + "] JS 初始化完成: " + api);
                }
            }).get(60, TimeUnit.SECONDS); // 60秒超时
        } catch (TimeoutException e) {
            LOG.i("[线程: " + Thread.currentThread().getName() + "] JS 初始化超时: " + api);
        } catch (Exception e) {
            LOG.i("[线程: " + Thread.currentThread().getName() + "] 初始化 JS 时发生异常: " + e.getMessage());
        }
    }

    public static byte[] byteFF(byte[] bytes) {
        byte[] newBt = new byte[bytes.length - 4];
        newBt[0] = 1;
        System.arraycopy(bytes, 5, newBt, 1, bytes.length - 5);
        return newBt;
    }

    private void createCtx() {
        ctx = QuickJSContext.create();
        ctx.setModuleLoader(new QuickJSContext.BytecodeModuleLoader() {
            @Override
            public byte[] getModuleBytecode(String moduleName) {
                String ss = FileUtils.loadModule(moduleName);
                if (TextUtils.isEmpty(ss)) {
                    LOG.i("echo-getModuleBytecode empty :"+ moduleName);
                    return null;
                }
                if(ss.startsWith("//DRPY")){
                    return Base64.decode(ss.replace("//DRPY",""), Base64.URL_SAFE);
                } else if(ss.startsWith("//bb")){
                    byte[] b = Base64.decode(ss.replace("//bb",""), 0);
                    return byteFF(b);
                } else {
                    if (moduleName.contains("cheerio.min.js")) {
                        FileUtils.setCacheByte("cheerio.min", ctx.compileModule(ss, "cheerio.min.js"));
                    } else if (moduleName.contains("crypto-js.js")) {
                        FileUtils.setCacheByte("crypto-js", ctx.compileModule(ss, "crypto-js.js"));
                    }
                    return ctx.compileModule(ss, moduleName);
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
                LOG.i("QuJs"+s);
            }
        });

        ctx.getGlobalObject().bind(new Global(executor));

        JSObject local = ctx.createJSObject();
        ctx.getGlobalObject().set("local", local);
        local.bind(new local());

        ctx.getGlobalObject().getContext().evaluate(FileUtils.loadModule("net.js"));
    }

    private void createDex() {
        try {
            JSObject obj = ctx.createJSObject();
            Class<?> clz = dex;
            Class<?>[] classes = clz.getDeclaredClasses();
            ctx.getGlobalObject().set("jsapi", obj);
            if (classes.length == 0) invokeSingle(clz, obj);
            if (classes.length >= 1) invokeMultiple(clz, obj);
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
                        if (content.contains("base64,")) content = content.substring(content.indexOf("base64,") + 7);
                        result[2] = new ByteArrayInputStream(Base64.decode(content, Base64.DEFAULT));
                    }
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
            return null;
        }
        
        // 获取前4个字符
        String prefix = content.substring(0, 4);
        
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
