package com.github.catvod.crawler;


import android.util.Log;

import com.github.tvbox.osc.base.App;

import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;

import com.github.tvbox.osc.util.js.JsSpider;
import com.lzy.okgo.OkGo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

public class JsLoader {
    private static final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Class<?>> classes = new ConcurrentHashMap<>();
    //当前的Js爬虫key
    private volatile String recentKey = "";

    public static void destroy() {
        for (Spider spider : spiders.values()){
            spider.cancelByTag();
            spider.destroy();
        }
        spiders.clear();
        classes.clear();
    }
    public void clear() {
        spiders.clear();
        classes.clear();
    }

    public static void stopAll() {
        for (Spider spider : spiders.values()){
            spider.cancelByTag();
        }
    }
    
    /**
     * 彻底销毁所有 Spider 并清理缓存，释放 Native 内存
     * 用于批次间清理，防止 Native 内存泄露
     */
    public static void destroyAllAndClear() {
        try {
            int destroyCount = 0;
            for (Map.Entry<String, Spider> entry : spiders.entrySet()){
                String key = entry.getKey();
                Spider spider = entry.getValue();
                try {
                    spider.cancelByTag();
                    spider.destroy();
                    destroyCount++;
                } catch (Exception e) {
                    Log.e("JsLoader", "销毁 Spider 异常: key=" + key, e);
                }
            }
            spiders.clear();
        } catch (Exception e) {
            Log.e("JsLoader", "destroyAllAndClear 异常", e);
        }
    }

    private boolean loadClassLoader(String jar, String key) {
        boolean success = false;
        Class<?> classInit = null;
        try {
            File cacheDir = new File(App.getInstance().getCacheDir().getAbsolutePath() + "/catvod_jsapi");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            DexClassLoader classLoader = new DexClassLoader(jar, cacheDir.getAbsolutePath(), null, App.getInstance().getClassLoader());
            int count = 0;
            do {
                try {
                    classInit = classLoader.loadClass("com.github.catvod.js.Method");
                    if (classInit != null) {
                        Log.i("JSLoader", "echo-自定义jsapi代码加载成功!");
                        success = true;
                        break;
                    }
                    Thread.sleep(200);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                count++;
            } while (count < 5);

            if (success) {
                classes.put(key, classInit);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return success;
    }

    private Class<?> loadJarInternal(String jar, String md5, String key) {
        if (classes.containsKey(key)){
            Log.i("JSLoader", "echo-loadJarInternal cached");
            return classes.get(key);
        }
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp/" + key + ".jar");
        if (!md5.isEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                loadClassLoader(cache.getAbsolutePath(), key);
                return classes.get(key);
            }
        }else {
            if (cache.exists() && !FileUtils.isWeekAgo(cache)) {
                if(loadClassLoader(cache.getAbsolutePath(), key)){
                    return classes.get(key);
                }
            }
        }
        Response response = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            response = OkGo.<File>get(jar).execute();
            is = response.body().byteStream();
            os = new FileOutputStream(cache);
            byte[] buffer = new byte[2048];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
            loadClassLoader(cache.getAbsolutePath(), key);
            return classes.get(key);
        } catch (java.net.UnknownHostException e) {
            // 特殊处理DNS解析失败异常，减少日志长度
            Log.i("JSLoader", "echo-loadJarInternal DNS解析失败: " + jar + " - " + e.getMessage());
        } catch (java.io.IOException e) {
            // 特殊处理网络IO异常，减少日志长度
            Log.i("JSLoader", "echo-loadJarInternal 网络IO异常: " + jar + " - " + e.getMessage());
        } catch (Throwable e) {
            // 其他异常仍打印完整堆栈
            e.printStackTrace();
        } finally {
            try {
                if (is != null) is.close();
                if (os != null) os.close();
                if (response != null) response.close();
            } catch (Exception e) {
                // 资源释放异常只打印简单信息
                Log.i("JSLoader", "echo-loadJarInternal 资源释放异常: " + e.getMessage());
            }
        }
        return null;
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        try {
            if (key == null || key.isEmpty()) {
                Log.e("JsLoader", "getSpider: key 为空");
                return new SpiderNull();
            }
            if (api == null || api.isEmpty()) {
                Log.e("JsLoader", "getSpider: api 为空");
                return new SpiderNull();
            }
            if (spiders.containsKey(key)){
                Spider cached = spiders.get(key);
                if (cached != null) {
                    return cached;
                }
            }
            
            // 检查是否有相同API的Spider已存在，复用Runtime
            String apiKey = com.github.tvbox.osc.util.MD5.string2MD5(api);
            for (Map.Entry<String, Spider> entry : spiders.entrySet()) {
                Spider existingSpider = entry.getValue();
                if (existingSpider instanceof com.github.tvbox.osc.util.js.JsSpider) {
                    com.github.tvbox.osc.util.js.JsSpider jsSpider = (com.github.tvbox.osc.util.js.JsSpider) existingSpider;
                    if (jsSpider.getApiKey() != null && jsSpider.getApiKey().equals(apiKey)) {
                        // 创建新的Spider实例但复用Runtime
                        try {
                            Class<?> classLoader = null;
                            if (jar != null && !jar.isEmpty()) {
                                String[] urls = jar.split(";md5;");
                                if (urls.length > 0) {
                                    String jarUrl = urls[0];
                                    String jarKey = com.github.tvbox.osc.util.MD5.string2MD5(jarUrl);
                                    String jarMd5 = urls.length > 1 ? urls[1].trim() : "";
                                    classLoader = loadJarInternal(jarUrl, jarMd5, jarKey);
                                }
                            }
                            recentKey = key;
                            
                            // 预检查模板语法
                            if (!preCheckTemplate(api)) {
                                return new SpiderNull();
                            }
                            
                            // 创建新的Spider实例
                            Spider sp = new com.github.tvbox.osc.util.js.JsSpider(key, api, classLoader);
                            sp.init(App.getInstance(), ext);
                            
                            if (sp instanceof com.github.tvbox.osc.util.js.JsSpider) {
                                com.github.tvbox.osc.util.js.JsSpider jsSpiderNew = (com.github.tvbox.osc.util.js.JsSpider) sp;
                                if (jsSpiderNew.isInitSuccess()) {
                                    spiders.put(key, sp);
                                    return sp;
                                } else {
                                    // 显式调用 destroy 确保资源释放
                                    try {
                                        jsSpiderNew.destroy();
                                    } catch (Exception e) {
                                        Log.e("JsLoader", "销毁失败的 Spider 异常: " + e.getMessage());
                                    }
                                    return new SpiderNull();
                                }
                            }
                        } catch (Throwable th) {
                            Log.e("JsLoader", "创建 Spider 异常: " + th.getMessage());
                            th.printStackTrace();
                        }
                    }
                }
            }
            
            // 没有可复用的Runtime，创建新的Spider
            Class<?> classLoader = null;
            if (jar != null && !jar.isEmpty()) {
                String[] urls = jar.split(";md5;");
                if (urls.length > 0) {
                    String jarUrl = urls[0];
                    String jarKey = com.github.tvbox.osc.util.MD5.string2MD5(jarUrl);
                    String jarMd5 = urls.length > 1 ? urls[1].trim() : "";
                    classLoader = loadJarInternal(jarUrl, jarMd5, jarKey);
                }
            }
            recentKey = key;
            try {
                // 预检查模板语法
                if (!preCheckTemplate(api)) {
                    return new SpiderNull();
                }
                Spider sp = new com.github.tvbox.osc.util.js.JsSpider(key, api, classLoader);
                sp.init(App.getInstance(), ext);
                
                // 只有初始化成功的 Spider 才添加到缓存中
                if (sp instanceof com.github.tvbox.osc.util.js.JsSpider) {
                    com.github.tvbox.osc.util.js.JsSpider jsSpider = (com.github.tvbox.osc.util.js.JsSpider) sp;
                    if (jsSpider.isInitSuccess()) {
                        spiders.put(key, sp);
                        return sp;
                    } else {
                        // 显式调用 destroy 确保资源释放
                        try {
                            jsSpider.destroy();
                        } catch (Exception e) {
                            Log.e("JsLoader", "销毁失败的 Spider 异常: " + e.getMessage());
                        }
                        return new SpiderNull();
                    }
                }
                
                // 对于非 JsSpider 类型，保持原有逻辑
                spiders.put(key, sp);
                return sp;
            } catch (Throwable th) {
                Log.e("JsLoader", "创建 Spider 异常: " + th.getMessage());
                th.printStackTrace();
            }
        } catch (Throwable th) {
            Log.e("JsLoader", "getSpider 整体异常: " + th.getMessage());
            th.printStackTrace();
        }
        return new SpiderNull();
    }

    /**
     * 预检查模板语法
     * @param api 模板内容
     * @return 是否通过检查
     */
    private boolean preCheckTemplate(String api) {
        try {
            // 简单的语法检查，避免明显的语法错误导致JS引擎崩溃
            if (api == null || api.isEmpty()) {
                return false;
            }
            // 检查括号匹配
            int braces = 0, brackets = 0, parentheses = 0;
            for (char c : api.toCharArray()) {
                switch (c) {
                    case '{': braces++;
                        break;
                    case '}': braces--;
                        break;
                    case '[': brackets++;
                        break;
                    case ']': brackets--;
                        break;
                    case '(': parentheses++;
                        break;
                    case ')': parentheses--;
                        break;
                }
                // 如果出现负数，说明括号不匹配
                if (braces < 0 || brackets < 0 || parentheses < 0) {
                    return false;
                }
            }
            // 检查是否所有括号都匹配
            return braces == 0 && brackets == 0 && parentheses == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public Object[] proxyInvoke(Map<String, String> params) {
        try {
            Spider proxyFun = spiders.get(recentKey);
            if (proxyFun != null) {
                return proxyFun.proxyLocal(params);
            }
        } catch (Throwable th) {
        }
        return null;
    }
}
