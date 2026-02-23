package com.undcover.freedom.pyramid;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PythonSpider extends Spider {
    PyObject app;
    PyObject pySpider;
    boolean loadSuccess = false;
    private String cachePath;
    private String name;

    public PythonSpider() {
        this("/storage/emulated/0/plugin/");
    }

    public PythonSpider(String cache) {
        this("", cache);
    }

    public PythonSpider(String name, String cache) {
        this.cachePath = cache;
        this.name = name;
    }

    @Override
    public void init(Context context) {
        app.callAttr("init", pySpider);
    }

    public void init(Context context, String url) {
        app = PythonLoader.getInstance().pyApp;
        PyObject retValue = app.callAttr("downloadPlugin", cachePath, url);
        Uri uri = Uri.parse(url);
        String extInfo = uri.getQueryParameter("extend");
        if (null == extInfo) extInfo = "";
        String path = retValue.toString();
        File file = new File(path);
        if (file.exists()) {
            pySpider = app.callAttr("loadFromDisk", path);

            List<PyObject> poList = app.callAttr("getDependence", pySpider).asList();
            for (PyObject po : poList) {
                String api = po.toString();
                String depUrl = PythonLoader.getInstance().getUrlByApi(api);
                if (!depUrl.isEmpty()) {
                    String tmpPath = app.callAttr("downloadPlugin", cachePath, depUrl).toString();
                    if (!new File(tmpPath).exists()) {
                        PyToast.showCancelableToast(api + "加载失败!");
                        return;
                    }
                }
            }
            app.callAttr("init", pySpider, extInfo);
            loadSuccess = true;
            Log.i("PyLoader",name + ": 下載插件成功！" + "echo-init extInfo: " +url+ extInfo);
        } else {
            PyToast.showCancelableToast(name + "下载插件失败");
        }
    }

    public String getName() {
        if (name.isEmpty()) {
            PyObject po = app.callAttr("getName", pySpider);
            return po.toString();
        } else {
            return name;
        }
    }

    public JSONObject map2json(HashMap<String, String> extend) {
        JSONObject jo = new JSONObject();
        try {
            if (extend != null) {
                for (String key : extend.keySet()) {
                    jo.put(key, extend.get(key));
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jo;
    }

    public JSONObject map2json(Map extend) {
        JSONObject jo = new JSONObject();
        try {
            if (extend != null) {
                for (Object key : extend.keySet()) {
                    jo.put(key.toString(), extend.get(key));
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jo;
    }

    public JSONArray list2json(List<String> array) {
        JSONArray ja = new JSONArray();
        if (array != null) {
            for (String str : array) {
                ja.put(str);
            }
        }
        return ja;
    }

    public String paramLog(Object... obj) {
        StringBuilder sb = new StringBuilder();
        sb.append("request params:[");
        for (Object o : obj) {
            sb.append(o).append("-");
        }
        sb.append("]");
        return sb.toString();
    }

    public Object[] proxyLocal(Map<String,String> params) {
        List<PyObject> list = app.callAttr("localProxy", pySpider, map2json(params).toString()).asList();
        boolean base64 = list.size() > 4 && list.get(4).toInt() == 1;
        boolean headerAvailable = list.size() > 3 && list.get(3) != null;
        Object[] result = new Object[4];
        result[0] = list.get(0).toInt();
        result[1] = list.get(1).toString();
        result[2] = getStream(list.get(2), base64);
        result[3] = headerAvailable ? getHeader(list.get(3)) : null;
        return result;
    }


    private Map<String, String> getHeader(PyObject headerObj) {
        if (headerObj == null) {
            return null;
        }
        // 处理 headerObj
        Map<String, String> headerMap = new HashMap<>();
        for (PyObject key : headerObj.asMap().keySet()) {
            headerMap.put(key.toString(), Objects.requireNonNull(headerObj.asMap().get(key)).toString());
        }
        return headerMap;
    }

    private ByteArrayInputStream getStream(PyObject o, boolean base64) {
        if (o == null) return new ByteArrayInputStream(new byte[0]);
        String typeStr = o.type().toString();
        if (typeStr.contains("bytes")) return new ByteArrayInputStream(o.toJava(byte[].class));
        String content = o.toString();
        if (base64 && content.contains("base64,")) {
            content = content.split("base64,")[1];
        }
        return new ByteArrayInputStream(base64 ? decode(content) : content.getBytes());
    }

    public String replaceLocalUrl(String content) {
        return content.replace("http://127.0.0.1:UndCover/proxy", PythonLoader.getInstance().localProxyUrl());
    }

    /**
     * 首页数据内容
     *
     * @param filter 是否开启筛选
     * @return
     */
    public String homeContent(boolean filter) {
        PyObject po = null;
        try {
            po = app.callAttr("homeContent", pySpider, filter);
            if (po == null) {
                return "";
            }
            String rsp = po.toString();
            return rsp;
        } catch (Throwable th) {
            PyLog.nw("homeContent" + "-" + name, "异常: " + th.getMessage());
            return "";
        } finally {
            // 释放PyObject资源
            if (po != null) {
                try {
                    po.close();
                } catch (Exception e) {
                    // 忽略释放异常
                }
            }
        }
    }

    /**
     * 首页最近更新数据 如果上面的homeContent中不包含首页最近更新视频的数据 可以使用这个接口返回
     *
     * @return
     */
    public String homeVideoContent() {
        PyObject po = null;
        try {
            po = app.callAttr("homeVideoContent", pySpider);
            if (po == null) {
                return "";
            }
            String rsp = po.toString();
            return rsp;
        } catch (Throwable th) {
            PyLog.nw("homeVideoContent" + "-" + name, "异常: " + th.getMessage());
            return "";
        } finally {
            // 释放PyObject资源
            if (po != null) {
                try {
                    po.close();
                } catch (Exception e) {
                    // 忽略释放异常
                }
            }
        }
    }

    /**
     * 分类数据
     *
     * @param tid
     * @param pg
     * @param filter
     * @param extend
     * @return
     */
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        PyObject po = null;
        try {
            po = app.callAttr("categoryContent", pySpider, tid, pg, filter, map2json(extend).toString());
            if (po == null) {
                return "";
            }
            String rsp = po.toString();
            return rsp;
        } catch (Throwable th) {
            PyLog.nw("categoryContent" + "-" + name, "异常: " + th.getMessage());
            return "";
        } finally {
            // 释放PyObject资源
            if (po != null) {
                try {
                    po.close();
                } catch (Exception e) {
                    // 忽略释放异常
                }
            }
        }
    }

    /**
     * 详情数据
     *
     * @param ids
     * @return
     */
    public String detailContent(List<String> ids) {
        PyObject po = null;
        try {
            po = app.callAttr("detailContent", pySpider, list2json(ids).toString());
            if (po == null) {
                return "";
            }
            String rsp = po.toString();
            return rsp;
        } catch (Throwable th) {
            PyLog.nw("detailContent" + "-" + name, "异常: " + th.getMessage());
            return "";
        } finally {
            // 释放PyObject资源
            if (po != null) {
                try {
                    po.close();
                } catch (Exception e) {
                    // 忽略释放异常
                }
            }
        }
    }

    /**
     * 搜索数据内容
     *
     * @param key
     * @param quick
     * @return
     */
    public String searchContent(String key, boolean quick) {
        String threadName = Thread.currentThread().getName();
        PyLog.nw("[线程: " + threadName + "] searchContent" + "-" + name, "入口");
        PyObject po = null;
        try {
            // 输入参数预校验
            if (key == null || key.trim().isEmpty()) {
                return "";
            }
            po = app.callAttr("searchContent", pySpider, key, quick);
            if (po == null) {
                return "";
            }
            String rsp = po.toString();
            PyLog.nw("[线程: " + threadName + "] searchContent" + "-" + name, rsp);
            return rsp;
        } catch (Exception e) {
            e.printStackTrace();
            PyLog.nw("[线程: " + threadName + "] searchContent" + "-" + name, "异常: " + e.getMessage());
            return "";
        } catch (Throwable th) {
            th.printStackTrace();
            PyLog.nw("[线程: " + threadName + "] searchContent" + "-" + name, "异常: " + th.getMessage());
            return "";
        } finally {
            // 释放PyObject资源
            if (po != null) {
                try {
                    // 尝试释放资源
                    po.close();
                } catch (Exception e) {
                    // 忽略释放异常
                }
            }
        }
    }

    /**
     * 带超时控制的搜索方法
     * @param key 搜索关键词
     * @param quick 是否快速搜索
     * @param timeout 超时时间（秒）
     * @return 搜索结果
     */
    public String searchContentWithTimeout(String key, boolean quick, long timeout) {
        String threadName = Thread.currentThread().getName();
        PyLog.nw("[线程: " + threadName + "] searchContentWithTimeout" + "-" + name, paramLog(key, quick, timeout));
        try {
            // 使用共享线程池执行搜索任务
            java.util.concurrent.ExecutorService executorService = com.github.tvbox.osc.viewmodel.SourceViewModel.getPythonExecutorService();
            java.util.concurrent.Future<String> future = executorService.submit(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() throws Exception {
                    return searchContent(key, quick);
                }
            });
            
            // 设置超时
            return future.get(timeout, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            e.printStackTrace();
            PyLog.nw("[线程: " + threadName + "] searchContentWithTimeout" + "-" + name, "超时: " + e.getMessage());
            return "";
        } catch (Exception e) {
            e.printStackTrace();
            PyLog.nw("[线程: " + threadName + "] searchContentWithTimeout" + "-" + name, "异常: " + e.getMessage());
            return "";
        } catch (Throwable th) {
            th.printStackTrace();
            PyLog.nw("[线程: " + threadName + "] searchContentWithTimeout" + "-" + name, "异常: " + th.getMessage());
            return "";
        }
    }

    /**
     * 播放信息
     *
     * @param flag
     * @param id
     * @return
     */
    public String playerContent(String flag, String id, List<String> vipFlags) {
        PyObject po = null;
        try {
            // 输入参数预校验
            if (flag == null || flag.trim().isEmpty() || id == null || id.trim().isEmpty()) {
                PyLog.nw("playerContent" + "-" + name, "Empty flag or id");
                return "";
            }
            if (vipFlags == null) {
                vipFlags = new ArrayList<>();
            }
            po = app.callAttr("playerContent", pySpider, flag, id, list2json(vipFlags).toString());
            if (po == null) {
                return "";
            }
            String rsp = replaceLocalUrl(po.toString());
            return rsp;
        } catch (Exception e) {
            e.printStackTrace();
            PyLog.nw("playerContent" + "-" + name, "异常: " + e.getMessage());
            return "";
        } catch (Throwable th) {
            th.printStackTrace();
            PyLog.nw("playerContent" + "-" + name, "异常: " + th.getMessage());
            return "";
        } finally {
            // 释放PyObject资源
            if (po != null) {
                try {
                    po.close();
                } catch (Exception e) {
                    // 忽略释放异常
                }
            }
        }
    }

    /**
     * 直播列表数据
     * @return
     */
    public String liveContent(String url) {
        PyObject po = null;
        try {
            po = app.callAttr("liveContent", pySpider, url);
            if (po == null) {
                return "";
            }
            String rsp = po.toString();
            PyLog.nw("liveContent" + "-" + name, rsp);
            return rsp;
        } catch (Exception e) {
            e.printStackTrace();
            PyLog.nw("liveContent" + "-" + name, "异常: " + e.getMessage());
            return "";
        } catch (Throwable th) {
            th.printStackTrace();
            PyLog.nw("liveContent" + "-" + name, "异常: " + th.getMessage());
            return "";
        } finally {
            // 释放PyObject资源
            if (po != null) {
                try {
                    po.close();
                } catch (Exception e) {
                    // 忽略释放异常
                }
            }
        }
    }

    /**
     * webview解析时使用 可自定义判断当前加载的 url 是否是视频
     *
     * @param url
     * @return
     */
    public boolean isVideoFormat(String url) {
        return false;
    }

    /**
     * 是否手动检测webview中加载的url
     *
     * @return
     */
    public boolean manualVideoCheck() {
        return false;
    }

    public static byte[] decode(String s) {
        return decode(s, Base64.DEFAULT | Base64.NO_WRAP);
    }

    public static byte[] decode(String s, int flags) {
        return Base64.decode(s, flags);
    }
}
