// ISpiderService.aidl
package com.github.tvbox.osc;

interface ISpiderService {
    String searchContent(in String key, in String api, in String ext, in String jar, in String wd, boolean quick);
    String homeContent(in String key, in String api, in String ext, in String jar, boolean filter);
    String categoryContent(in String key, in String api, in String ext, in String jar, in String tid, in String pg, boolean filter, in String extendJson);
    String detailContent(in String key, in String api, in String ext, in String jar, in List ids);
    String playerContent(in String key, in String api, in String ext, in String jar, in String flag, in String id, in String vipFlagsJson);
    boolean isAlive();
}
