package com.github.tvbox.osc.util;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.util.Log;

import java.util.List;

/**
 * 内存和进程监控工具类
 */
public class MemoryMonitor {
    private static final String TAG = "MemoryMonitor";
    private static final String SPIDER_PROCESS_SUFFIX = ":spider";
    
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
     * 获取 spider 子进程的详细信息
     * @param context Context
     * @return 子进程信息字符串
     */
    public static String getSpiderProcessInfo(Context context) {
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
            
            if (processes == null) {
                return ":spider 子进程未运行";
            }
            
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.processName != null && process.processName.endsWith(SPIDER_PROCESS_SUFFIX)) {
                    int pid = process.pid;
                    
                    ActivityManager.MemoryInfo outInfo = new ActivityManager.MemoryInfo();
                    activityManager.getMemoryInfo(outInfo);
                    
                    Debug.MemoryInfo[] processMemoryInfo = activityManager.getProcessMemoryInfo(new int[]{pid});
                    if (processMemoryInfo != null && processMemoryInfo.length > 0) {
                        Debug.MemoryInfo memInfo = processMemoryInfo[0];
                        int totalPss = memInfo.getTotalPss();
                        int nativeDedicatedMemory = memInfo.nativePrivateDirty + memInfo.nativeSharedDirty;
                        int dalvikDedicatedMemory = memInfo.dalvikPrivateDirty + memInfo.dalvikSharedDirty;
                        int otherDedicatedMemory = memInfo.otherPrivateDirty + memInfo.otherSharedDirty;
                        
                        return String.format(":spider 子进程内存 [PID: %d, 总 PSS: %d KB, Native: %d KB, Dalvik: %d KB, 其他：%d KB, 系统可用：%d MB]",
                                pid,
                                totalPss,
                                nativeDedicatedMemory,
                                dalvikDedicatedMemory,
                                otherDedicatedMemory,
                                bytesToMB(outInfo.availMem));
                    } else {
                        return String.format(":spider 子进程信息 [PID: %d, 系统可用：%d MB]",
                                pid,
                                bytesToMB(outInfo.availMem));
                    }
                }
            }
            return ":spider 子进程未运行";
        } catch (Exception e) {
            Log.e(TAG, "获取 :spider 子进程内存信息失败", e);
            return "获取 :spider 子进程内存信息失败";
        }
    }
    
    /**
     * 检查 spider 子进程是否满足轮换条件
     * @param context Context
     * @param pssThresholdMB PSS 阈值（MB），例如 400
     * @param pssRatioThreshold PSS 占系统总内存比例阈值（0-100），例如 40
     * @param lowMemoryRatioThreshold 可用内存比例阈值（0-100），例如 15
     * @return 是否满足轮换条件
     */
    public static boolean shouldRotateSpiderProcess(Context context, int pssThresholdMB, int pssRatioThreshold, int lowMemoryRatioThreshold) {
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
            
            if (processes == null) {
                return false;
            }
            
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.processName != null && process.processName.endsWith(SPIDER_PROCESS_SUFFIX)) {
                    int pid = process.pid;
                    
                    ActivityManager.MemoryInfo systemMemInfo = new ActivityManager.MemoryInfo();
                    activityManager.getMemoryInfo(systemMemInfo);
                    
                    double availMemRatio = (systemMemInfo.availMem * 100.0) / systemMemInfo.totalMem;
                    Log.d(TAG, String.format(":spider 子进程内存检查 - 系统可用内存：%.2f%%, 阈值: %d%%",
                            availMemRatio, lowMemoryRatioThreshold));
                    
                    if (availMemRatio < lowMemoryRatioThreshold) {
                        Log.d(TAG, ":spider 子进程满足轮换条件 - 可用内存不足");
                        return true;
                    }
                    
                    Debug.MemoryInfo[] processMemInfos = activityManager.getProcessMemoryInfo(new int[]{pid});
                    if (processMemInfos != null && processMemInfos.length > 0) {
                        Debug.MemoryInfo processMemInfo = processMemInfos[0];
                        int totalPssKB = processMemInfo.getTotalPss();
                        long totalPssMB = totalPssKB / 1024;
                        
                        long totalMemMB = systemMemInfo.totalMem / 1024 / 1024;
                        double pssRatio = (totalPssKB * 100.0) / (systemMemInfo.totalMem / 1024);
                        
                        Log.d(TAG, String.format(":spider 子进程内存检查 - 总 PSS: %d MB (%.2f%%), 阈值: %d MB / %d%%",
                                totalPssMB, pssRatio, pssThresholdMB, pssRatioThreshold));
                        
                        if (totalPssMB > pssThresholdMB && pssRatio > pssRatioThreshold) {
                            Log.d(TAG, ":spider 子进程满足轮换条件 - PSS过高");
                            return true;
                        }
                    }
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "检查 :spider 子进程轮换条件失败", e);
            return false;
        }
    }
    
    /**
     * 打印内存和进程信息到日志（包括子进程）
     * @param context Context
     * @param prefix 日志前缀标识
     */
    public static void printMemoryLog(Context context, String prefix) {
        try {
            String memoryInfo = getMemoryInfo();
            String processInfo = getProcessInfo(context);
            String spiderProcessInfo = getSpiderProcessInfo(context);
            
            Log.i(TAG, "===== " + prefix + " =====");
            Log.i(TAG, memoryInfo);
            Log.i(TAG, processInfo);
            Log.i(TAG, spiderProcessInfo);
            Log.i(TAG, "====================");
        } catch (Exception e) {
            Log.e(TAG, "打印内存日志失败：" + prefix, e);
        }
    }
    
    /**
     * 获取 spider 子进程的 PID
     * @param context Context
     * @return spider 子进程的 PID，如果未运行返回 -1
     */
    public static int getSpiderProcessPid(Context context) {
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
            
            if (processes == null) {
                return -1;
            }
            
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.processName != null && process.processName.endsWith(SPIDER_PROCESS_SUFFIX)) {
                    return process.pid;
                }
            }
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "获取 :spider 子进程 PID 失败", e);
            return -1;
        }
    }
    
    /**
     * 字节转 MB
     */
    private static long bytesToMB(long bytes) {
        return bytes / 1024 / 1024;
    }
}
