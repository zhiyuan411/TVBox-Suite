package com.github.catvod.crawler;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SpiderNull extends Spider {

    private static final String TAG = "SpiderNull";

    @Override
    public void init(Context context) throws Exception {
        Log.i(TAG, "SpiderNull init() - 空实现，不执行任何操作");
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        Log.i(TAG, "SpiderNull init(extend) - 空实现，不执行任何操作");
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        Log.i(TAG, "SpiderNull homeContent() - 返回空 JSON");
        return "{}";
    }

    @Override
    public String homeVideoContent() throws Exception {
        Log.i(TAG, "SpiderNull homeVideoContent() - 返回空 JSON");
        return "{}";
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        Log.i(TAG, "SpiderNull categoryContent() - 返回空 JSON");
        return "{}";
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        Log.i(TAG, "SpiderNull detailContent() - 返回空 JSON");
        return "{}";
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        Log.i(TAG, "SpiderNull searchContent(key,quick) - 返回空 JSON");
        return "{}";
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        Log.i(TAG, "SpiderNull searchContent(key,quick,pg) - 返回空 JSON");
        return "{}";
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Log.i(TAG, "SpiderNull playerContent() - 返回空 JSON");
        return "{}";
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        Log.i(TAG, "SpiderNull manualVideoCheck() - 返回 false");
        return false;
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        Log.i(TAG, "SpiderNull isVideoFormat() - 返回 false");
        return false;
    }

    @Override
    public Object[] proxyLocal(Map<String, String> params) throws Exception {
        Log.i(TAG, "SpiderNull proxyLocal() - 返回 null");
        return null;
    }

    @Override
    public void cancelByTag() {
        Log.i(TAG, "SpiderNull cancelByTag() - 空实现，不执行任何操作");
    }

    @Override
    public void destroy() {
        Log.i(TAG, "SpiderNull destroy() - 空实现，不执行任何操作");
    }

    @Override
    public String liveContent(String url) {
        Log.i(TAG, "SpiderNull liveContent() - 返回空字符串");
        return "";
    }
}
