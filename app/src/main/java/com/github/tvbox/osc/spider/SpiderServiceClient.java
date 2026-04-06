package com.github.tvbox.osc.spider;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.github.tvbox.osc.ISpiderService;
import com.github.tvbox.osc.util.MemoryMonitor;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class SpiderServiceClient {
    private static final String TAG = "SpiderServiceClient";
    private static final long BIND_TIMEOUT = 5000;
    private static final Gson gson = new Gson();
    
    private static SpiderServiceClient sInstance;
    
    private final Context mContext;
    private ISpiderService mService;
    private boolean mIsBound;
    private ServiceConnection mServiceConnection;
    
    private SpiderServiceClient(Context context) {
        mContext = context.getApplicationContext();
    }
    
    public static synchronized SpiderServiceClient getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SpiderServiceClient(context);
        }
        return sInstance;
    }
    
    public boolean bindService() {
        if (mIsBound) {
            return true;
        }
        
        CountDownLatch latch = new CountDownLatch(1);
        
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.d(TAG, "onServiceConnected");
                mService = ISpiderService.Stub.asInterface(service);
                mIsBound = true;
                latch.countDown();
            }
            
            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.d(TAG, "onServiceDisconnected");
                mService = null;
                mIsBound = false;
            }
        };
        
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(mContext, "com.github.tvbox.osc.service.SpiderService"));
        
        boolean bindResult = mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        
        if (bindResult) {
            try {
                boolean awaitResult = latch.await(BIND_TIMEOUT, TimeUnit.MILLISECONDS);
                if (!awaitResult) {
                    Log.e(TAG, "Bind service timeout");
                    unbindService();
                    return false;
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Bind service interrupted", e);
                Thread.currentThread().interrupt();
                unbindService();
                return false;
            }
        }
        
        return mIsBound;
    }
    
    public void unbindService() {
        if (mIsBound && mServiceConnection != null) {
            mContext.unbindService(mServiceConnection);
            mIsBound = false;
            mService = null;
            mServiceConnection = null;
        }
    }
    
    /**
     * 主动重启子进程
     * 1. 获取旧进程 PID
     * 2. 解绑服务
     * 3. 强制杀死旧进程
     * 4. 等待清理
     * 5. 重新绑定新进程
     * @return 是否重启成功
     */
    public boolean restartService() {
        Log.d(TAG, "主动重启 SpiderService");
        try {
            int oldPid = MemoryMonitor.getSpiderProcessPid(mContext);
            Log.d(TAG, "旧 spider 进程 PID: " + oldPid);
            
            unbindService();
            Log.d(TAG, "已解绑服务");
            
            if (oldPid != -1) {
                try {
                    Log.d(TAG, "强制杀死旧进程 PID: " + oldPid);
                    android.os.Process.killProcess(oldPid);
                } catch (Exception e) {
                    Log.e(TAG, "杀死旧进程失败", e);
                }
            }
            
            Log.d(TAG, "等待进程清理...");
            Thread.sleep(1000);
            
            Log.d(TAG, "重新绑定服务...");
            return bindService();
        } catch (Exception e) {
            Log.e(TAG, "重启 SpiderService 失败", e);
            return false;
        }
    }
    
    private void ensureServiceConnected() throws RemoteException {
        if (!mIsBound || mService == null) {
            if (!bindService()) {
                throw new RemoteException("Failed to bind SpiderService");
            }
        }
        if (!mService.isAlive()) {
            throw new RemoteException("SpiderService is not alive");
        }
    }
    
    public String searchContent(String key, String api, String ext, String jar, String wd, boolean quick) {
        try {
            ensureServiceConnected();
            return mService.searchContent(key, api, ext, jar, wd, quick);
        } catch (Throwable th) {
            Log.e(TAG, "searchContent error", th);
            mIsBound = false;
            mService = null;
            return "";
        }
    }
    
    public String homeContent(String key, String api, String ext, String jar, boolean filter) {
        try {
            ensureServiceConnected();
            return mService.homeContent(key, api, ext, jar, filter);
        } catch (Throwable th) {
            Log.e(TAG, "homeContent error", th);
            mIsBound = false;
            mService = null;
            return "";
        }
    }
    
    public String categoryContent(String key, String api, String ext, String jar, String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            ensureServiceConnected();
            String extendJson = extend != null ? gson.toJson(extend) : "";
            return mService.categoryContent(key, api, ext, jar, tid, pg, filter, extendJson);
        } catch (Throwable th) {
            Log.e(TAG, "categoryContent error", th);
            mIsBound = false;
            mService = null;
            return "";
        }
    }
    
    public String detailContent(String key, String api, String ext, String jar, List ids) {
        try {
            ensureServiceConnected();
            return mService.detailContent(key, api, ext, jar, ids);
        } catch (Throwable th) {
            Log.e(TAG, "detailContent error", th);
            mIsBound = false;
            mService = null;
            return "";
        }
    }
    
    public String playerContent(String key, String api, String ext, String jar, String flag, String id, List<String> vipFlags) {
        try {
            ensureServiceConnected();
            String vipFlagsJson = vipFlags != null ? gson.toJson(vipFlags) : "";
            return mService.playerContent(key, api, ext, jar, flag, id, vipFlagsJson);
        } catch (Throwable th) {
            Log.e(TAG, "playerContent error", th);
            mIsBound = false;
            mService = null;
            return "";
        }
    }
}
