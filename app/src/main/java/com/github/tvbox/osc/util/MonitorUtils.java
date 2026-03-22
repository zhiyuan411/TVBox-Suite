package com.github.tvbox.osc.util;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MonitorUtils {

    private static final String TAG = "MonitorUtils";
    private static final Map<String, Long> startTimeMap = new ConcurrentHashMap<>();
    private static final List<Long> nativeMemoryTrend = new ArrayList<>();
    
    // 线程监控
    public static void monitorThread(String tag) {
        int activeThreads = Thread.activeCount();
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup parentGroup;
        while ((parentGroup = rootGroup.getParent()) != null) {
            rootGroup = parentGroup;
        }
        int totalThreads = rootGroup.activeCount();
        
        // 输出线程信息
        Log.d(TAG, "[" + tag + "] Thread count: " + activeThreads + ", Total threads: " + totalThreads);
    }
    
    // 内存监控
    public static void monitorMemory(Context context, String tag) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        
        Debug.MemoryInfo memoryInfo = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memoryInfo);
        
        // 计算内存使用情况
        long nativePss = memoryInfo.nativePss * 1024L; // Native内存
        long totalPss = memoryInfo.getTotalPss() * 1024L; // 总内存
        
        // 计算可用内存
        long availableMemory = memInfo.availMem;
        double memoryUsagePercent = (1.0 - (double) availableMemory / memInfo.totalMem) * 100;
        
        // 输出核心内存信息
        Log.d(TAG, "[" + tag + "] Native: " + (nativePss / 1024 / 1024) + "MB, Total PSS: " + (totalPss / 1024 / 1024) + "MB, Usage: " + String.format("%.1f%%", memoryUsagePercent));
        
        // 记录Native内存增长趋势
        nativeMemoryTrend.add(nativePss);
        if (nativeMemoryTrend.size() > 20) {
            nativeMemoryTrend.remove(0);
        }
        
        // 检查Native内存是否持续增长
        if (nativeMemoryTrend.size() >= 10) {
            boolean isIncreasing = true;
            for (int i = 1; i < nativeMemoryTrend.size(); i++) {
                if (nativeMemoryTrend.get(i) <= nativeMemoryTrend.get(i - 1)) {
                    isIncreasing = false;
                    break;
                }
            }
            if (isIncreasing) {
                Log.w(TAG, "[" + tag + "] Warning: Native memory is continuously increasing!");
            }
        }
        
        // 内存使用阈值告警
        if (memoryUsagePercent > 80) {
            Log.w(TAG, "[" + tag + "] Warning: System memory usage is high: " + String.format("%.1f%%", memoryUsagePercent));
        }
        
        if (nativePss > 100 * 1024 * 1024) { // 超过100MB
            Log.w(TAG, "[" + tag + "] Warning: Native memory usage is high: " + (nativePss / 1024 / 1024) + "MB");
        }
        
        // 内存使用建议
        if (memInfo.lowMemory) {
            Log.w(TAG, "[" + tag + "] Critical: System is in low memory state! Consider releasing resources.");
        }
    }
    
    // 网络连接监控
    public static void monitorNetwork(String tag) {
        // 监控OkHttp连接池
        try {
            // 通过反射获取OkHttp连接池信息
            Class<?> okHttpClass = Class.forName("com.github.catvod.net.OkHttp");
            java.lang.reflect.Method clientMethod = okHttpClass.getMethod("client");
            Object client = clientMethod.invoke(null);
            
            if (client != null) {
                Class<?> clientClass = client.getClass();
                java.lang.reflect.Field connectionPoolField = clientClass.getDeclaredField("connectionPool");
                connectionPoolField.setAccessible(true);
                Object connectionPool = connectionPoolField.get(client);
                
                if (connectionPool != null) {
                    Class<?> poolClass = connectionPool.getClass();
                    java.lang.reflect.Method connectionCountMethod = poolClass.getMethod("connectionCount");
                    int connectionCount = (int) connectionCountMethod.invoke(connectionPool);
                    
                    java.lang.reflect.Method idleConnectionCountMethod = poolClass.getMethod("idleConnectionCount");
                    int idleConnectionCount = (int) idleConnectionCountMethod.invoke(connectionPool);
                    
                    Log.d(TAG, "[" + tag + "] OkHttp connection pool: total=" + connectionCount + ", idle=" + idleConnectionCount);
                }
            }
        } catch (Exception e) {
            // 反射失败时不影响主流程
        }
    }
    
    // Spider监控
    public static void monitorSpider(String tag, int spiderCount) {
        Log.d(TAG, "[" + tag + "] Spider count: " + spiderCount);
        
        // 检查Spider数量是否过多
        if (spiderCount > 50) {
            Log.w(TAG, "[" + tag + "] Warning: Spider count is too high, may cause high memory usage");
        }
    }
    
    // 性能计时开始
    public static void startTiming(String key) {
        startTimeMap.put(key, System.currentTimeMillis());
    }
    
    // 性能计时结束
    public static void endTiming(String key) {
        Long startTime = startTimeMap.remove(key);
        if (startTime != null) {
            long duration = System.currentTimeMillis() - startTime;
            Log.d(TAG, "[" + key + "] Duration: " + duration + "ms");
        }
    }
    
    // OOM预警检查
    public static boolean isMemoryLow(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        return memInfo.lowMemory;
    }
    
    // 内存使用百分比
    public static int getMemoryUsagePercent(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(memInfo);
        long totalMemory = memInfo.totalMem;
        long availableMemory = memInfo.availMem;
        return (int) ((totalMemory - availableMemory) * 100 / totalMemory);
    }
}