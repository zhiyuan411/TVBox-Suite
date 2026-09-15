package xyz.doikki.videoplayer.controller;

import android.graphics.Bitmap;

public interface MediaPlayerControl {

    void start();

    void pause();

    long getDuration();

    long getCurrentPosition();

    void seekTo(long pos);

    boolean isPlaying();

    int getBufferedPercentage();

    void startFullScreen();

    void stopFullScreen();

    boolean isFullScreen();

    void setMute(boolean isMute);

    boolean isMute();

    void setScreenScaleType(int screenScaleType);

    void setSpeed(float speed);

    float getSpeed();

    long getTcpSpeed();

    /**
     * 获取当前已缓存到内存中的数据大小(字节)，-1 表示不支持
     */
    long getBufferedBytes();

    /**
     * 获取当前视频流的平均码率(bit/s)，-1 表示不支持
     */
    long getBitRate();

    void replay(boolean resetPosition);

    void setMirrorRotation(boolean enable);

    Bitmap doScreenShot();

    int[] getVideoSize();

    void setRotation(float rotation);

    void startTinyScreen();

    void stopTinyScreen();

    boolean isTinyScreen();
}