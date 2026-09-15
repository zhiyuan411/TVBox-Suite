package com.github.tvbox.osc.player.controller;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HawkUtils;
import com.github.tvbox.osc.util.LOG;
import com.orhanobut.hawk.Hawk;

import java.util.Map;

import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.controller.IGestureComponent;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

public abstract class BaseController extends BaseVideoController implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, View.OnTouchListener {
    private GestureDetector mGestureDetector;
    private AudioManager mAudioManager;
    private boolean mIsGestureEnabled = true;
    private int mStreamVolume;
    private float mBrightness;
    private int mSeekPosition;
    private boolean mFirstTouch;
    private boolean mChangePosition;
    private boolean mChangeBrightness;
    private boolean mChangeVolume;
    private boolean mCanChangePosition = true;
    private boolean mEnableInNormal;
    private boolean mCanSlide;
    private int mCurPlayState;

    protected Handler mHandler;

    protected HandlerCallback mHandlerCallback;

    protected interface HandlerCallback {
        void callback(Message msg);
    }

    private boolean mIsDoubleTapTogglePlayEnabled = true;


    public BaseController(@NonNull Context context) {
        super(context);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull Message msg) {
                int what = msg.what;
                switch (what) {
                    case 100: { // 亮度+音量调整
                        mSlideInfo.setVisibility(VISIBLE);
                        mSlideInfo.setText(msg.obj.toString());
                        break;
                    }
                    case 101: { // 亮度+音量调整 关闭
                        mSlideInfo.setVisibility(GONE);
                        break;
                    }
                    case 201: { // Show Volume Dialog
                        mDialogVolume.setVisibility(VISIBLE);
                        break;
                    }
                    case 202: { // Hide Volume Dialog
                        mDialogVolume.setVisibility(GONE);
                        break;
                    }
                    case 203: { // Show Volume Dialog
                        mDialogBrightness.setVisibility(VISIBLE);
                        break;
                    }
                    case 204: { // Hide Volume Dialog
                        mDialogBrightness.setVisibility(GONE);
                        break;
                    }
                    default: {
                        if (mHandlerCallback != null)
                            mHandlerCallback.callback(msg);
                        break;
                    }
                }
                return false;
            }
        });
        mHandler.post(mRunnable);
    }

    public BaseController(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BaseController(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    private TextView mSlideInfo;
    private ProgressBar mLoading;
    private ProgressBar mLoadingHide;
    private ViewGroup mPauseRoot;
    private TextView mPauseTime;
    private TextView mSpeedTextTop;
    private TextView mSpeedTextTopr;
    private TextView mSpeedTextHide;
    private TextView mCacheTextTop;
    private TextView mCacheTextTopr;
    private TextView mCacheTextHide;
    // b处缓存数字旁的"MB"单位（小字），随缓存数字有无一起显隐
    private TextView mCacheUnitTop;
    private TextView mCacheUnitHide;
    private LinearLayout mSpeedTop;
    // 缓存大小文本缓存，用于跳过无变化的 setText
    private String mLastCacheText;

    private LinearLayout mDialogVolume;
    private LinearLayout mDialogBrightness;
    private ProgressBar mDialogVolumeProgressBar;
    private ProgressBar mDialogBrightnessProgressBar;
    private ProgressBar mDialogVideoProgressBar;
    private ProgressBar mDialogVideoPauseBar;

    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            long tcpSpeed = mControlWrapper.getTcpSpeed();
            String format = String.format("%.2f", (float) tcpSpeed / 1024.0 / 1024.0);
            mSpeedTextTop.setText(format);
            mSpeedTextTopr.setText(format);
            mSpeedTextHide.setText(format);
            updateCacheText();
            updatePlayInfo();
            // IJK 卡死看门狗：仅 IJK 播放器、播放中（非暂停/非缓冲）、速度为 0 且位置停滞超阈值时自动重连
            checkIjkStallAndRecover(tcpSpeed);
            mHandler.postDelayed(this, 1000);
        }
    };

    /**
     * 刷新"当前内存缓存大小"展示。
     * 与网速共用 1Hz 的刷新节奏（不再新增定时器），每秒仅多两次 IJK property 读取，开销可忽略；
     * 文本无变化时跳过 setText，避免无意义的重绘。
     * 不支持读取缓存字节的播放器（如 Exo/系统播放器）返回 -1，此时清空文本。
     */
    private void updateCacheText() {
        long bufferedBytes = mControlWrapper.getBufferedBytes();
        // b处（点击/缓冲时顶栏）数字与单位分开展示：数字用大号字，单位"MB"由旁边的静态小字 TextView 承担；
        // a处（屏显）数字与单位同号字，整体 "5.23MB" 一起展示。
        String cacheValue = bufferedBytes < 0 ? "" : String.format("%.2f", bufferedBytes / 1024.0 / 1024.0);
        String cacheText = bufferedBytes < 0 ? "" : String.format("%.2fMB", bufferedBytes / 1024.0 / 1024.0);
        if (cacheText.equals(mLastCacheText)) return;
        mLastCacheText = cacheText;
        // b处数字与单位分离，单位仅在数字非空时显示（缓存不可用时数字与单位一起隐藏，避免只剩"MB"）
        int unitVisibility = cacheValue.isEmpty() ? View.GONE : View.VISIBLE;
        if (mCacheTextTop != null) mCacheTextTop.setText(cacheValue);
        if (mCacheTextTopr != null) mCacheTextTopr.setText(cacheText);
        if (mCacheTextHide != null) mCacheTextHide.setText(cacheValue);
        if (mCacheUnitTop != null) mCacheUnitTop.setVisibility(unitVisibility);
        if (mCacheUnitHide != null) mCacheUnitHide.setVisibility(unitVisibility);
    }

    /**
     * 刷新顶部播放信息（分辨率 + 码率等）的钩子，默认空实现，由具体控制器按需覆写。
     * 与网速/缓存共用 1Hz 刷新节奏，不新增定时器；Live 等未覆写者不受影响。
     */
    protected void updatePlayInfo() {
    }

    // ===== IJK 卡死看门狗 =====
    private long mStallLastPosition = -1L;
    private long mStallStartTimeMs = -1L;
    private int mStallRecoveries = 0;
    private boolean mAutoReconnecting = false;
    private static final int MAX_STALL_RECOVERIES = 3;

    /**
     * IJK 卡死检测：当速度为 0 且播放位置在阈值时间内完全不变化时，认为播放器卡死（连接层 hang 死），
     * 通过 replay(false) 保留位置重建播放器实例，恢复连接。
     *
     * 防误伤：
     * - 仅作用于 IJK 播放器（PLAY_TYPE == 1）
     * - 仅在 STATE_PLAYING 且 mControlWrapper.isPlaying() = true 时计时
     *   （暂停/缓冲/错误/准备等状态下不计时）
     * - 速度 > 0 或位置发生变化时立即重置计时
     * - 单次播放会话最多自动重连 3 次，超过后停止自动恢复，等待用户手动干预
     */
    private void checkIjkStallAndRecover(long tcpSpeed) {
        if (Hawk.get(HawkConfig.PLAY_TYPE, 0) != 1) {
            // 非 IJK 播放器不启用看门狗
            mStallStartTimeMs = -1L;
            mStallLastPosition = -1L;
            return;
        }
        if (mCurPlayState != VideoView.STATE_PLAYING || !mControlWrapper.isPlaying()) {
            mStallStartTimeMs = -1L;
            mStallLastPosition = -1L;
            return;
        }
        long pos = mControlWrapper.getCurrentPosition();
        // 卡死阈值(秒 → 毫秒)，getIJKStallTimeout() 内部已对 <5s 的无效值兜底为 30s
        long stallTimeoutMs = HawkUtils.getIJKStallTimeout() * 1000L;
        boolean isStuck = (tcpSpeed == 0 && pos == mStallLastPosition && mStallLastPosition >= 0);
        if (isStuck) {
            if (mStallStartTimeMs == -1L) {
                mStallStartTimeMs = SystemClock.uptimeMillis();
            } else if (SystemClock.uptimeMillis() - mStallStartTimeMs >= stallTimeoutMs) {
                if (mStallRecoveries < MAX_STALL_RECOVERIES) {
                    mStallRecoveries++;
                    mStallStartTimeMs = -1L;
                    mAutoReconnecting = true;
                    LOG.i("IJK stall detected for " + stallTimeoutMs
                            + "ms, auto reconnect (" + mStallRecoveries + "/" + MAX_STALL_RECOVERIES + ")");
                    mControlWrapper.replay(false);
                } else {
                    // 已达上限，停止自动恢复，等待用户手动干预
                    LOG.i("IJK stall watchdog reached max auto-reconnect attempts, stop recovering");
                    mStallStartTimeMs = -1L;
                }
            }
        } else {
            mStallStartTimeMs = -1L;
        }
        mStallLastPosition = pos;
    }

    @Override
    protected void initView() {
        super.initView();
        mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        mGestureDetector = new GestureDetector(getContext(), this);
        setOnTouchListener(this);
        mSlideInfo = findViewWithTag("vod_control_slide_info");
        mLoading = findViewWithTag("vod_control_loading");
        mLoadingHide = findViewWithTag("vod_control_loading_hide");
        mPauseRoot = findViewWithTag("vod_control_pause");
        mPauseTime = findViewWithTag("vod_control_pause_t");
        mSpeedTextTop = findViewWithTag("play_speed_top");
        mSpeedTextTopr = findViewWithTag("play_speed_topr");
        mSpeedTextHide = findViewWithTag("play_speed_top_hide");
        mCacheTextTop = findViewWithTag("play_cache_top");
        mCacheTextTopr = findViewWithTag("play_cache_topr");
        mCacheTextHide = findViewWithTag("play_cache_top_hide");
        mCacheUnitTop = findViewWithTag("play_cache_top_unit");
        mCacheUnitHide = findViewWithTag("play_cache_top_hide_unit");
        mSpeedTop = findViewWithTag("top_container_hide");

        mDialogVolume = findViewWithTag("dialog_volume");
        mDialogBrightness = findViewWithTag("dialog_brightness");
        mDialogVolumeProgressBar = findViewWithTag("progressbar_volume");
        mDialogBrightnessProgressBar = findViewWithTag("progressbar_brightness");
        mDialogVideoProgressBar = findViewWithTag("progressbar_video");
        mDialogVideoPauseBar = findViewWithTag("pausebar_video");
    }

    @Override
    protected void setProgress(int duration, int position) {
        super.setProgress(duration, position);
        mPauseTime.setText(PlayerUtils.stringForTime(position) + " / " + PlayerUtils.stringForTime(duration));
        // takagen99 : Update mini bar (via touch)
        int percent = (int) (((double) position / (double) duration) * 100);
        mDialogVideoProgressBar.setProgress(percent);
        mDialogVideoPauseBar.setProgress(percent);
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        switch (playState) {
            case VideoView.STATE_IDLE:
                mLoading.setVisibility(GONE);
                mLoadingHide.setVisibility(GONE);
                mSpeedTop.setVisibility(GONE);
                break;
            case VideoView.STATE_PLAYING:
                mPauseRoot.setVisibility(GONE);
                mLoading.setVisibility(GONE);
                mLoadingHide.setVisibility(GONE);
                mSpeedTop.setVisibility(GONE);
                break;
            case VideoView.STATE_PAUSED:
                mPauseRoot.setVisibility(VISIBLE);
                mLoading.setVisibility(GONE);
                mLoadingHide.setVisibility(GONE);
                mSpeedTop.setVisibility(GONE);
                break;
            case VideoView.STATE_PREPARED:
            case VideoView.STATE_ERROR:
            case VideoView.STATE_BUFFERED:
                mLoading.setVisibility(GONE);
                mLoadingHide.setVisibility(GONE);
                mSpeedTop.setVisibility(GONE);
                break;
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_BUFFERING:
                mLoading.setVisibility(VISIBLE);
                mLoadingHide.setVisibility(VISIBLE);
                mSpeedTop.setVisibility(VISIBLE);
                break;
            case VideoView.STATE_PLAYBACK_COMPLETED:
                mLoading.setVisibility(GONE);
                mLoadingHide.setVisibility(GONE);
                mPauseRoot.setVisibility(GONE);
                mSpeedTop.setVisibility(GONE);
                break;
        }
    }

    /**
     * 设置是否可以滑动调节进度，默认可以
     */
    public void setCanChangePosition(boolean canChangePosition) {
        mCanChangePosition = canChangePosition;
    }

    /**
     * 是否在竖屏模式下开始手势控制，默认关闭
     */
    public void setEnableInNormal(boolean enableInNormal) {
        mEnableInNormal = enableInNormal;
    }

    /**
     * 是否开启手势控制，默认开启，关闭之后，手势调节进度，音量，亮度功能将关闭
     */
    public void setGestureEnabled(boolean gestureEnabled) {
        mIsGestureEnabled = gestureEnabled;
    }

    /**
     * 是否开启双击播放/暂停，默认开启
     */
    public void setDoubleTapTogglePlayEnabled(boolean enabled) {
        mIsDoubleTapTogglePlayEnabled = enabled;
    }

    @Override
    public void setPlayerState(int playerState) {
        super.setPlayerState(playerState);
        if (playerState == VideoView.PLAYER_NORMAL) {
            mCanSlide = mEnableInNormal;
        } else if (playerState == VideoView.PLAYER_FULL_SCREEN) {
            mCanSlide = true;
        }
    }

    @Override
    public void setPlayState(int playState) {
        super.setPlayState(playState);
        mCurPlayState = playState;
        // 新一次播放会话开始：区分"用户主动重开"与"看门狗自动重连"
        // 自动重连触发的 PREPARING 不重置 mStallRecoveries，避免对坏源无限循环自动恢复
        if (playState == VideoView.STATE_PREPARING) {
            if (mAutoReconnecting) {
                mAutoReconnecting = false;
            } else {
                mStallRecoveries = 0;
            }
            mStallStartTimeMs = -1L;
            mStallLastPosition = -1L;
        }
    }

    protected boolean isInPlaybackState() {
        return mControlWrapper != null
                && mCurPlayState != VideoView.STATE_ERROR
                && mCurPlayState != VideoView.STATE_IDLE
                && mCurPlayState != VideoView.STATE_PREPARING
                && mCurPlayState != VideoView.STATE_PREPARED
                && mCurPlayState != VideoView.STATE_START_ABORT
                && mCurPlayState != VideoView.STATE_PLAYBACK_COMPLETED;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return mGestureDetector.onTouchEvent(event);
    }

    /**
     * 手指按下的瞬间
     */
    @Override
    public boolean onDown(MotionEvent e) {
        if (!isInPlaybackState() //不处于播放状态
                || !mIsGestureEnabled //关闭了手势
                || PlayerUtils.isEdge(getContext(), e)) //处于屏幕边沿
            return true;
        mStreamVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity == null) {
            mBrightness = 0;
        } else {
            mBrightness = activity.getWindow().getAttributes().screenBrightness;
        }
        mFirstTouch = true;
        mChangePosition = false;
        mChangeBrightness = false;
        mChangeVolume = false;
        return true;
    }

    /**
     * 单击
     */
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        if (isInPlaybackState()) {
            mControlWrapper.toggleShowState();
        }
        return true;
    }

    /**
     * 双击
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (mIsDoubleTapTogglePlayEnabled && !isLocked() && isInPlaybackState()) togglePlay();
        return true;
    }

    /**
     * 在屏幕上滑动
     */
    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (!isInPlaybackState() //不处于播放状态
                || !mIsGestureEnabled //关闭了手势
                || !mCanSlide //关闭了滑动手势
                || isLocked() //锁住了屏幕
                || PlayerUtils.isEdge(getContext(), e1)) //处于屏幕边沿
            return true;
        float deltaX = e1.getX() - e2.getX();
        float deltaY = e1.getY() - e2.getY();
        if (mFirstTouch) {
            mChangePosition = Math.abs(distanceX) >= Math.abs(distanceY);
            if (!mChangePosition) {
                //半屏宽度
                int halfScreen = PlayerUtils.getScreenWidth(getContext(), true) / 2;
                if (e2.getX() > halfScreen) {
                    mChangeVolume = true;
                } else {
                    mChangeBrightness = true;
                }
            }

            if (mChangePosition) {
                //根据用户设置是否可以滑动调节进度来决定最终是否可以滑动调节进度
                mChangePosition = mCanChangePosition;
            }

            if (mChangePosition || mChangeBrightness || mChangeVolume) {
                for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
                    IControlComponent component = next.getKey();
                    if (component instanceof IGestureComponent) {
                        ((IGestureComponent) component).onStartSlide();
                    }
                }
            }
            mFirstTouch = false;
        }
        if (mChangePosition) {
            slideToChangePosition(deltaX);
        } else if (mChangeBrightness) {
            slideToChangeBrightness(deltaY);
        } else if (mChangeVolume) {
            slideToChangeVolume(deltaY);
        }
        return true;
    }

    protected void slideToChangePosition(float deltaX) {
        deltaX = -deltaX;
        int width = getMeasuredWidth();
        int duration = (int) mControlWrapper.getDuration();
        int currentPosition = (int) mControlWrapper.getCurrentPosition();
        int position = (int) (deltaX / width * 120000 + currentPosition);
        if (position > duration) position = duration;
        if (position < 0) position = 0;
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onPositionChange(position, currentPosition, duration);
            }
        }
        updateSeekUI(currentPosition, position, duration);
        mSeekPosition = position;
    }

    protected void updateSeekUI(int curr, int seekTo, int duration) {

    }

    protected void slideToChangeBrightness(float deltaY) {
        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity == null) return;
        Window window = activity.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        int height = getMeasuredHeight();
//        if (mBrightness == -1.0f) mBrightness = 0.5f;
        if (mBrightness <= 0.00f) {
            mBrightness = 0.50f;
        } else if (mBrightness < 0.01f) {
            mBrightness = 0.01f;
        }
        float brightness = deltaY * 2 / height * 1.0f + mBrightness;
        if (brightness > 1.0f) {
            brightness = 1.0f;
        } else if (brightness < 0.01f) {
            brightness = 0.01f;
        }
        int percent = (int) (brightness * 100);
        attributes.screenBrightness = brightness;
        window.setAttributes(attributes);
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onBrightnessChange(percent);
            }
        }
        mDialogBrightnessProgressBar.setProgress(percent);
        Message msg = Message.obtain();
        msg.what = 203;
        msg.obj = "亮度 " + percent + "%";
        mHandler.sendMessage(msg);
        mHandler.removeMessages(204);
        mHandler.sendEmptyMessageDelayed(204, 600);
    }

    protected void slideToChangeVolume(float deltaY) {
        int streamMaxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int height = getMeasuredHeight();
        float deltaV = deltaY * 2 / height * streamMaxVolume;
        float index = mStreamVolume + deltaV;
        if (index > streamMaxVolume) index = streamMaxVolume;
        if (index < 0) index = 0;
        int percent = (int) (index / streamMaxVolume * 100);
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (int) index, 0);
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onVolumeChange(percent);
            }
        }
        mDialogVolumeProgressBar.setProgress(percent);
        Message msg = Message.obtain();
        msg.what = 201;
        msg.obj = "音量 " + percent + "%";
        mHandler.sendMessage(msg);
        mHandler.removeMessages(202);
        mHandler.sendEmptyMessageDelayed(202, 600);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        //滑动结束时事件处理
        if (!mGestureDetector.onTouchEvent(event)) {
            int action = event.getAction();
            switch (action) {
                case MotionEvent.ACTION_UP:
                    stopSlide();
                    if (mSeekPosition > 0) {
                        mControlWrapper.seekTo(mSeekPosition);
                        mSeekPosition = 0;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    stopSlide();
                    mSeekPosition = 0;
                    break;
            }
        }
        return super.onTouchEvent(event);
    }

    private void stopSlide() {
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onStopSlide();
            }
        }
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {

    }

    @Override
    public void onShowPress(MotionEvent e) {

    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }


    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    public boolean onKeyEvent(KeyEvent event) {
        return false;
    }
}