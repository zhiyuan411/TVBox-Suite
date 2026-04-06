
package com.github.catvod.crawler;

import android.content.Context;
import android.util.Log;


import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.MD5;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

public class JarLoader {
    private final ConcurrentHashMap<String, DexClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    private volatile String recentJarKey = "";

    /**
     * 不要在主线程调用我
     *
     * @param cache
     */
    public boolean load(String cache) {
        recentJarKey = "main";
        return loadClassLoader(cache, recentJarKey);
    }

    public void clear() {
        spiders.clear();
        proxyMethods.clear();
        classLoaders.clear();
    }

    private boolean loadClassLoader(String jar, String key) {
        if (classLoaders.containsKey(key)){
            Log.i("JarLoader", "echo-loadClassLoader jar缓存: " + key);
            return true;
        }
        boolean success = false;
        try {
            File cacheDir = new File(App.getInstance().getCacheDir().getAbsolutePath() + "/catvod_csp");
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            final DexClassLoader classLoader = new DexClassLoader(jar, cacheDir.getAbsolutePath(), null, App.getInstance().getClassLoader());
            int count = 0;
            do {
                try {
                    final Class<?> classInit = classLoader.loadClass("com.github.catvod.spider.Init");
                    if (classInit != null) {
                        final Method initMethod = classInit.getMethod("init", Context.class);
                        try {
                            initMethod.invoke(null, App.getInstance());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        Log.i("JarLoader", "echo-自定义爬虫代码加载成功!");
                        success = true;
                        try {
                            Class<?> proxy = classLoader.loadClass("com.github.catvod.spider.Proxy");
                            Method proxyMethod = proxy.getMethod("proxy", Map.class);
                            proxyMethods.put(key, proxyMethod);
                        } catch (Throwable th) {
                            // 可以记录错误日志
                            th.printStackTrace();
                        }
                        break;
                    }
                    Thread.sleep(200);
                } catch (Throwable th) {
                    th.printStackTrace();
                }
                count++;
            } while (count < 2);

            if (success) {
                classLoaders.put(key, classLoader);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return success;
    }

    private DexClassLoader loadJarInternal(String jar, String md5, String key) {
        if (classLoaders.containsKey(key)){
            Log.i("JarLoader", "echo-loadJarInternal jar缓存: " + key);
            return classLoaders.get(key);
        }
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp/" + key + ".jar");
        if (!md5.isEmpty()) {
            if (cache.exists() && MD5.getFileMd5(cache).equalsIgnoreCase(md5)) {
                if(loadClassLoader(cache.getAbsolutePath(), key)){
                    return classLoaders.get(key);
                }else {
                    return null;
                }
            }
        }else {
            if (cache.exists() && !FileUtils.isWeekAgo(cache)) {
                if(loadClassLoader(cache.getAbsolutePath(), key)){
                    return classLoaders.get(key);
                }
            }
        }
        Response response = null;
        InputStream is = null;
        OutputStream os = null;
        try {
            response = OkGo.<File>get(jar).execute();
            assert response.body() != null;
            is = response.body().byteStream();
            os = new FileOutputStream(cache);
            byte[] buffer = new byte[2048];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
            loadClassLoader(cache.getAbsolutePath(), key);
            return classLoaders.get(key);
        } catch (java.net.UnknownHostException e) {
            // 特殊处理DNS解析失败异常，减少日志长度
            Log.i("JarLoader", "echo-loadJarInternal DNS解析失败: " + jar + " - " + e.getMessage());
        } catch (java.io.IOException e) {
            // 特殊处理网络IO异常，减少日志长度
            Log.i("JarLoader", "echo-loadJarInternal 网络IO异常: " + jar + " - " + e.getMessage());
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
                Log.i("JarLoader", "echo-loadJarInternal 资源释放异常: " + e.getMessage());
            }
        }
        return null;
    }

    public Spider getSpider(String key, String cls, String ext, String jar) {
        try {
            if (key == null || key.isEmpty()) {
                Log.i("JarLoader", "echo-getSpider key 为空");
                return new SpiderNull();
            }
            if (cls == null || cls.isEmpty()) {
                Log.i("JarLoader", "echo-getSpider cls 为空");
                return new SpiderNull();
            }
            if (spiders.containsKey(key)) {
                Log.i("JarLoader", "echo-getSpider spider缓存: " + key);
                Spider cachedSpider = spiders.get(key);
                if (cachedSpider != null) {
                    return cachedSpider;
                }
            }
            String clsKey;
            try {
                clsKey = cls.replace("csp_", "");
            } catch (Throwable th) {
                Log.i("JarLoader", "echo-getSpider cls.replace 异常: " + th.getMessage());
                return new SpiderNull();
            }
            String jarUrl = "";
            String jarMd5 = "";
            String jarKey;
            try {
                if (jar == null || jar.isEmpty()) {
                    jarKey = "main";
                } else {
                    String[] urls = jar.split(";md5;");
                    if (urls.length > 0) {
                        jarUrl = urls[0];
                        jarKey = MD5.string2MD5(jarUrl);
                        jarMd5 = urls.length > 1 ? urls[1].trim() : "";
                    } else {
                        jarKey = "main";
                    }
                }
            } catch (Throwable th) {
                Log.i("JarLoader", "echo-getSpider 处理 jar 参数异常: " + th.getMessage());
                return new SpiderNull();
            }
            recentJarKey = jarKey;
            DexClassLoader classLoader;
            try {
                classLoader = jarKey.equals("main") ? classLoaders.get("main") : loadJarInternal(jarUrl, jarMd5, jarKey);
            } catch (Throwable th) {
                Log.i("JarLoader", "echo-getSpider 获取 classLoader 异常: " + th.getMessage());
                return new SpiderNull();
            }
            if (classLoader == null) {
                Log.i("JarLoader", "echo-getSpider classLoader 为 null");
                return new SpiderNull();
            }
            try {
                Log.i("JarLoader", "echo-getSpider 加载spider: " + key);
                Class<?> spiderClazz = classLoader.loadClass("com.github.catvod.spider." + clsKey);
                if (spiderClazz == null) {
                    Log.i("JarLoader", "echo-getSpider loadClass 返回 null");
                    return new SpiderNull();
                }
                Spider sp = (Spider) spiderClazz.newInstance();
                if (sp == null) {
                    Log.i("JarLoader", "echo-getSpider newInstance 返回 null");
                    return new SpiderNull();
                }
                try {
                    sp.init(App.getInstance(), ext);
                } catch (Throwable th) {
                    Log.i("JarLoader", "echo-getSpider sp.init 异常: " + th.getMessage());
                }
                if (jar != null && !jar.isEmpty()) {
                    try {
                        sp.homeContent(false); 
                    } catch (Throwable th) {
                        Log.i("JarLoader", "echo-getSpider sp.homeContent 异常: " + th.getMessage());
                    }
                }
                spiders.put(key, sp);
                return sp;
            } catch (OutOfMemoryError e) {
                Log.i("JarLoader", "echo-getSpider OOM 异常: " + e.getMessage());
            } catch (LinkageError e) {
                Log.i("JarLoader", "echo-getSpider LinkageError 异常: " + e.getMessage());
            } catch (Error e) {
                Log.i("JarLoader", "echo-getSpider Error 异常: " + e.getMessage());
                e.printStackTrace();
            } catch (Throwable th) {
                Log.i("JarLoader", "echo-getSpider 异常: " + th.getMessage());
                th.printStackTrace();
            }
        } catch (OutOfMemoryError e) {
            Log.i("JarLoader", "echo-getSpider 整体 OOM 异常: " + e.getMessage());
        } catch (Error e) {
            Log.i("JarLoader", "echo-getSpider 整体 Error 异常: " + e.getMessage());
            e.printStackTrace();
        } catch (Throwable th) {
            Log.i("JarLoader", "echo-getSpider 整体异常: " + th.getMessage());
            th.printStackTrace();
        }
        return new SpiderNull();
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Json" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            if (classLoader == null) {
                Log.i("JarLoader", "jsonExt: classLoader 为 null");
                return null;
            }
            Class<?> jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            DexClassLoader classLoader = classLoaders.get("main");
            String clsKey = "Mix" + key;
            String hotClass = "com.github.catvod.parser." + clsKey;
            if (classLoader == null) {
                Log.i("JarLoader", "jsonExt: classLoader 为 null");
                return null;
            }
            Class<?> jsonParserCls = classLoader.loadClass(hotClass);
            Method mth = jsonParserCls.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) mth.invoke(null, jxs, name, flag, url);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }

    public Object[] proxyInvoke(Map<String,String> params) {
        try {
            Method proxyFun = proxyMethods.get(recentJarKey);
            if (proxyFun != null) {
                return (Object[]) proxyFun.invoke(null, params);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return null;
    }
}
