package com.github.tvbox.osc.util;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.util.Log;

/**
 * 内存和进程监控工具类
 */
public class MemoryMonitor {
    private static final String TAG = "MemoryMonitor";
    
    /**
     * 获取当前进程的内存信息
     * @return 内存信息字符串
     */
    public static String getMemoryInfo() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();
            
            return String.format("Java 堆内存 [总：%d MB, 已用：%d MB, 空闲：%d MB, 最大：%d MB, 使用率：%.2f%%]",
                    bytesToMB(totalMemory),
                    bytesToMB(usedMemory),
                    bytesToMB(freeMemory),
                    bytesToMB(maxMemory),
                    (usedMemory * 100.0 / totalMemory));
        } catch (Exception e) {
            Log.e(TAG, "获取 Java 堆内存信息失败", e);
            return "获取 Java 堆内存信息失败";
        }
    }
    
    /**
     * 获取当前进程的详细信息
     * @param context Context
     * @return 进程信息字符串
     */
    public static String getProcessInfo(Context context) {
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            int pid = android.os.Process.myPid();
            
            ActivityManager.MemoryInfo outInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(outInfo);
            
            Debug.MemoryInfo[] processMemoryInfo = activityManager.getProcessMemoryInfo(new int[]{pid});
            if (processMemoryInfo != null && processMemoryInfo.length > 0) {
                Debug.MemoryInfo memInfo = processMemoryInfo[0];
                int totalPss = memInfo.getTotalPss();
                int nativeDedicatedMemory = memInfo.nativePrivateDirty + memInfo.nativeSharedDirty;
                int dalvikDedicatedMemory = memInfo.dalvikPrivateDirty + memInfo.dalvikSharedDirty;
                int otherDedicatedMemory = memInfo.otherPrivateDirty + memInfo.otherSharedDirty;
                
                return String.format("进程内存 [PID: %d, 总 PSS: %d KB, Native: %d KB, Dalvik: %d KB, 其他：%d KB, 系统可用：%d MB]",
                        pid,
                        totalPss,
                        nativeDedicatedMemory,
                        dalvikDedicatedMemory,
                        otherDedicatedMemory,
                        bytesToMB(outInfo.availMem));
            } else {
                return String.format("进程信息 [PID: %d, 系统可用：%d MB]",
                        pid,
                        bytesToMB(outInfo.availMem));
            }
        } catch (Exception e) {
            Log.e(TAG, "获取进程内存信息失败", e);
            return "获取进程内存信息失败";
        }
    }
    
    /**
     * 打印内存和进程信息到日志
     * @param context Context
     * @param prefix 日志前缀标识
     */
    public static void printMemoryLog(Context context, String prefix) {
        try {
            String memoryInfo = getMemoryInfo();
            String processInfo = getProcessInfo(context);
            
            Log.i(TAG, "===== " + prefix + " =====");
            Log.i(TAG, memoryInfo);
            Log.i(TAG, processInfo);
            Log.i(TAG, "====================");
        } catch (Exception e) {
            Log.e(TAG, "打印内存日志失败：" + prefix, e);
        }
    }
    
    /**
     * 字节转 MB
     */
    private static long bytesToMB(long bytes) {
        return bytes / 1024 / 1024;
    }
}
