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
        this.executor = Executors.newSingleThreadExecutor();
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
            if (jsObject == null) {
                LOG.i("JSObject 为 null，无法调用函数: " + func);
                return "";
            }
            return submit(() -> {
                try {
                    // 设置JS执行超时时间
                    Future<Object> future = Async.run(jsObject, func, args);
                    return future.get(30, TimeUnit.SECONDS); // 30秒超时
                } catch (TimeoutException e) {
                    LOG.i("JS 函数执行超时: " + func);
                    return "";
                } catch (Exception e) {
                    LOG.i("JS 函数执行异常: " + func + "，错误: " + e.getMessage());
                    return "";
                } catch (Throwable th) {
                    LOG.i("JS 函数执行严重异常: " + func + "，错误: " + th.getMessage());
                    return "";
                }
            }).get(35, TimeUnit.SECONDS);  // 等待 executor 线程完成 JS 调用，额外5秒缓冲
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOG.i("Executor 提交或等待失败"+ e);
            return "";
        } catch (Exception e) {
            LOG.i("调用函数时发生异常: " + e.getMessage());
            return "";
        } catch (Throwable th) {
            LOG.i("调用函数时发生严重异常: " + th.getMessage());
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
                try {
                    executor.shutdownNow();
                } catch (Exception e) {
                    LOG.i("关闭 executor 异常: " + e.getMessage());
                }
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
            submit(() -> {
                try {
                    if (ctx == null) createCtx();
                    if (dex != null) createDex();

                    String content = FileUtils.loadModule(api);            
                    if (TextUtils.isEmpty(content)) {
                        LOG.i("模块内容为空: " + api);
                        return null;
                    }
                    
                    // 内容和格式校验
                    if (!isValidJSContent(content)) {
                        LOG.i("模块内容无效: " + api);
                        return null;
                    }
                    
                    // 尝试加载和执行JS代码
                    boolean loadSuccess = false;
                    if(content.startsWith("//bb")){
                        cat = true;
                        try {
                            byte[] b = Base64.decode(content.replace("//bb",""), 0);
                            ctx.execute(byteFF(b), key + ".js");
                            ctx.evaluateModule(String.format(SPIDER_STRING_CODE, key + ".js") + "globalThis." + key + " = __JS_SPIDER__;", "tv_box_root.js");
                            loadSuccess = true;
                        } catch (Exception e) {
                            LOG.i("处理 bb 格式内容异常: " + e.getMessage());
                        } catch (Throwable th) {
                            LOG.i("处理 bb 格式内容严重异常: " + th.getMessage());
                        }
                    } else {
                        try {
                            if (content.contains("__JS_SPIDER__")) {
                                content = content.replaceAll("__JS_SPIDER__\\s*=", "export default ");
                            }
                            String moduleExtName = "default";
                            if (content.contains("__jsEvalReturn") && !content.contains("export default")) {
                                moduleExtName = "__jsEvalReturn";
                                cat = true;
                            }
                            ctx.evaluateModule(content, api);
                            ctx.evaluateModule(String.format(SPIDER_STRING_CODE, api) + "globalThis." + key + " = __JS_SPIDER__;", "tv_box_root.js");
                            loadSuccess = true;
                        } catch (Exception e) {
                            LOG.i("处理模块内容异常: " + e.getMessage());
                        } catch (Throwable th) {
                            LOG.i("处理模块内容严重异常: " + th.getMessage());
                        }
                    }
                    
                    // 只有加载成功后才尝试获取JSObject
                    if (loadSuccess) {
                        try {
                            jsObject = (JSObject) ctx.get(ctx.getGlobalObject(), key);
                            if (jsObject == null) {
                                LOG.i("无法获取 JSObject: " + key);
                            }
                        } catch (Exception e) {
                            LOG.i("获取 JSObject 异常: " + e.getMessage());
                        }
                    }
                    return null;
                } catch (Exception e) {
                    LOG.i("初始化 JS 环境异常: " + e.getMessage());
                    return null;
                } finally {
                    // 确保即使发生异常，也能保持系统稳定
                    LOG.i("JS 初始化完成: " + api);
                }
            }).get(60, TimeUnit.SECONDS); // 60秒超时
        } catch (TimeoutException e) {
            LOG.i("JS 初始化超时: " + api);
        } catch (Exception e) {
            LOG.i("初始化 JS 时发生异常: " + e.getMessage());
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
