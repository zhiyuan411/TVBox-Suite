package com.github.tvbox.osc.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.ISpiderService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SpiderService extends Service {
    private static final String TAG = "SpiderService";
    private static final Gson gson = new Gson();
    
    private final ISpiderService.Stub mBinder = new ISpiderService.Stub() {
        @Override
        public String searchContent(String key, String api, String ext, String jar, String wd, boolean quick) throws RemoteException {
            try {
                Spider sp = getSpider(key, api, ext, jar);
                return sp != null ? sp.searchContent(wd, quick) : "";
            } catch (Throwable th) {
                Log.e(TAG, "searchContent error", th);
                return "";
            }
        }
        
        @Override
        public String homeContent(String key, String api, String ext, String jar, boolean filter) throws RemoteException {
            try {
                Spider sp = getSpider(key, api, ext, jar);
                return sp != null ? sp.homeContent(filter) : "";
            } catch (Throwable th) {
                Log.e(TAG, "homeContent error", th);
                return "";
            }
        }
        
        @Override
        public String categoryContent(String key, String api, String ext, String jar, String tid, String pg, boolean filter, String extendJson) throws RemoteException {
            try {
                Spider sp = getSpider(key, api, ext, jar);
                HashMap<String, String> extend = null;
                if (extendJson != null && !extendJson.isEmpty()) {
                    extend = gson.fromJson(extendJson, new TypeToken<HashMap<String, String>>(){}.getType());
                }
                return sp != null ? sp.categoryContent(tid, pg, filter, extend) : "";
            } catch (Throwable th) {
                Log.e(TAG, "categoryContent error", th);
                return "";
            }
        }
        
        @Override
        public String detailContent(String key, String api, String ext, String jar, List ids) throws RemoteException {
            try {
                Spider sp = getSpider(key, api, ext, jar);
                return sp != null ? sp.detailContent(ids) : "";
            } catch (Throwable th) {
                Log.e(TAG, "detailContent error", th);
                return "";
            }
        }
        
        @Override
        public String playerContent(String key, String api, String ext, String jar, String flag, String id, String vipFlagsJson) throws RemoteException {
            try {
                Spider sp = getSpider(key, api, ext, jar);
                List<String> vipFlags = null;
                if (vipFlagsJson != null && !vipFlagsJson.isEmpty()) {
                    vipFlags = gson.fromJson(vipFlagsJson, new TypeToken<List<String>>(){}.getType());
                }
                return sp != null ? sp.playerContent(flag, id, vipFlags) : "";
            } catch (Throwable th) {
                Log.e(TAG, "playerContent error", th);
                return "";
            }
        }
        
        @Override
        public boolean isAlive() throws RemoteException {
            return true;
        }
    };
    
    private Spider getSpider(String key, String api, String ext, String jar) {
        try {
            return ApiConfig.get().getCSP(key, api, ext, jar);
        } catch (Throwable th) {
            Log.e(TAG, "getSpider error", th);
            return null;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "SpiderService onCreate");
        ApiConfig.get();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "SpiderService onBind");
        return mBinder;
    }
    
    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "SpiderService onUnbind");
        return super.onUnbind(intent);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SpiderService onDestroy");
    }
}
