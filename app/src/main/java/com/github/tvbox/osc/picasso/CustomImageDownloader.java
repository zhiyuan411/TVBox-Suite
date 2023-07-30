package com.github.tvbox.osc.picasso;

import android.text.TextUtils;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.SSL.SSLSocketFactoryCompat;
import com.github.tvbox.osc.util.UA;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.squareup.picasso.Downloader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class CustomImageDownloader implements Downloader {
    private OkHttpClient client;

    public CustomImageDownloader() {
        String pathName = FileUtils.getCachePath() + "/pic/";
        File file = new File(pathName);
        client = new OkHttpClient.Builder()
                .cache(new okhttp3.Cache(file, 100 * 1024 * 1024)) // 设置缓存大小为 100 MB
                .sslSocketFactory(new SSLSocketFactoryCompat(SSLSocketFactoryCompat.trustAllCert), SSLSocketFactoryCompat.trustAllCert)
                .hostnameVerifier((hostname, session) -> true)
                .readTimeout(10, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public Response load(Request request) throws IOException {
        String url = request.url().toString();
        Request.Builder builder = request.newBuilder();

        String header = null;
        String cookie = null;
        String referer = null;
        String userAgent = null;
        if (url.contains("@Headers=")) header = url.split("@Headers=")[1].split("@")[0];
        if (url.contains("@Cookie=")) cookie = url.split("@Cookie=")[1].split("@")[0];
        if (url.contains("@Referer=")) referer = url.split("@Referer=")[1].split("@")[0];
        if (url.contains("@User-Agent=")) userAgent = url.split("@User-Agent=")[1].split("@")[0];

        // takagen99 : Shift Douban referer to here instead
        if (url.contains("douban")) {
            userAgent = UA.random();
            referer = "https://movie.douban.com/";
            builder.addHeader("User-Agent", userAgent);
            builder.addHeader("Referer", referer);
        }

        url = url.split("@")[0];
        if (!TextUtils.isEmpty(header)) {
            JsonObject jsonInfo = new Gson().fromJson(header, JsonObject.class);
            for (String key : jsonInfo.keySet()) {
                String val = jsonInfo.get(key).getAsString();
                builder.addHeader(key, val);
            }
        } else {
            if (!TextUtils.isEmpty(cookie)) builder.addHeader("Cookie", cookie);
            if (!TextUtils.isEmpty(referer)) builder.addHeader("Referer", referer);
            if (!TextUtils.isEmpty(userAgent)) builder.addHeader("User-Agent", userAgent);
        }
        return client.newCall(builder.url(url).build()).execute();
    }

    @Override
    public void shutdown() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

}