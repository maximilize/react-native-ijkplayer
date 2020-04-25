package com.github.chadsmith.RCTIJKPlayer;

import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.BassBoost;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.PresetReverb;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import bolts.Task;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaMeta;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.MediaInfo;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

public class RCTIJKPlayer extends FrameLayout implements LifecycleEventListener, onTimedTextAvailable, AudioEffect.OnEnableStatusChangeListener, IMediaPlayer.OnPreparedListener, IMediaPlayer.OnErrorListener, IMediaPlayer.OnCompletionListener, IMediaPlayer.OnInfoListener, IMediaPlayer.OnBufferingUpdateListener {
    public enum Events {
        EVENT_LOAD_START("onVideoLoadStart"),
        EVENT_LOAD("onVideoLoad"),
        EVENT_STALLED("onVideoBuffer"),
        EVENT_ERROR("onVideoError"),
        EVENT_PROGRESS("onVideoProgress"),
        EVENT_PAUSE("onVideoPause"),
        EVENT_STOP("onVideoStop"),
        EVENT_END("onVideoEnd"),
        EVENT_TIMED_TEXT("onTimedText"),
        EVENT_VIDEO_PLAYING("onPlay");
        private final String mName;

        Events(final String name) {
            mName = name;
        }

        @Override
        public String toString() {
            return mName;
        }
    }

    public static final String EVENT_PROP_DURATION = "duration";
    public static final String EVENT_PROP_CURRENT_TIME = "currentTime";

    public static final String EVENT_PROP_ERROR = "error";
    public static final String EVENT_PROP_WHAT = "what";
    public static final String EVENT_PROP_EXTRA = "extra";
    public static final String EVENT_PROP_DATASOURCE = "dataSource";

    public static final String EVENT_PROP_BUFFERING_PROG = "progress";

    public RCTEventEmitter mEventEmitter;

    private boolean mPaused = false;
    private boolean mMuted = false;
    private float mVolume = 1.0f;
    private float mPlaybackSpeed = 1.0f;
    private boolean mRepeat = false;
    private boolean mLoaded = false;
    private boolean mStalled = false;
    private int mCurrentAudioSessionId = 0;

    private Equalizer mEqualizer;

    private WritableArray mVideoTracks = null;
    private WritableArray mAudioTracks = null;
    private WritableArray mTextTracks = null;

    private ThemedReactContext themedContext;

    private float mProgressUpdateInterval = 250;

    private Handler mProgressUpdateHandler = new Handler();
    private Runnable mProgressUpdateRunnable = null;

    private IjkVideoView ijkVideoView;

    public RCTIJKPlayer(ThemedReactContext themedReactContext) {
        super(themedReactContext);

        themedContext = themedReactContext;

        mEventEmitter = themedReactContext.getJSModule(RCTEventEmitter.class);
        themedReactContext.addLifecycleEventListener(this);

        IjkMediaPlayer.loadLibrariesOnce(null);
        IjkMediaPlayer.native_profileBegin("libijkplayer.so");

        // ensureIjkVideoView();
    }

    private void ensureIjkVideoView() {
        if (ijkVideoView == null) {
            ijkVideoView = new IjkVideoView(themedContext);
            setProgressUpdateRunnable();
            ijkVideoView.setOnTimedTextAvailableListener(this);
            ijkVideoView.setOnPreparedListener(this);
            ijkVideoView.setOnErrorListener(this);
            ijkVideoView.setOnCompletionListener(this);
            ijkVideoView.setOnInfoListener(this);
            ijkVideoView.setOnBufferingUpdateListener(this);
            addView(ijkVideoView);
            ijkVideoView.setContext(themedContext, getId());
        }
    }

    private void setProgressUpdateRunnable() {
        if (ijkVideoView != null)
            mProgressUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    if (ijkVideoView != null && ijkVideoView.isPlaying() && !mPaused) {
                        WritableMap event = Arguments.createMap();
                        event.putDouble(EVENT_PROP_CURRENT_TIME, ijkVideoView.getCurrentPosition() / 1000.0);
                        event.putDouble(EVENT_PROP_DURATION, ijkVideoView.getDuration() / 1000.0);
                        event.putInt("tcpSpeed", (int) ijkVideoView.getTcpSpeed());
                        event.putInt("fileSize", (int) ijkVideoView.getFileSize());
                        mEventEmitter.receiveEvent(getId(), Events.EVENT_PROGRESS.toString(), event);
                        mProgressUpdateHandler.postDelayed(mProgressUpdateRunnable, Math.round(mProgressUpdateInterval));
                    }
                }
            };

    }


    private void releasePlayer() {
        if (ijkVideoView != null)
            Task.callInBackground(new Callable<Void>() {

                @Override
                public Void call() throws Exception {
                    ijkVideoView.setOnPreparedListener(null);
                    ijkVideoView.setOnErrorListener(null);
                    ijkVideoView.setOnCompletionListener(null);
                    ijkVideoView.setOnInfoListener(null);
                    ijkVideoView.setOnBufferingUpdateListener(null);
                    ijkVideoView.stopPlayback();
                    mAudioTracks = null;
                    mVideoTracks = null;
                    mTextTracks = null;
                    mProgressUpdateRunnable = null;
                    ijkVideoView = null;
                    return null;
                }
            });
    }


    public void setSrc(final String uriString, final ReadableMap headers, final ReadableMap playerOptions, final String userAgent) {
        if (uriString == null)
            return;

        mLoaded = false;
        mStalled = false;

        WritableMap src = Arguments.createMap();
        src.putString(RCTIJKPlayerManager.PROP_SRC_URI, uriString);
        WritableMap event = Arguments.createMap();
        event.putMap(RCTIJKPlayerManager.PROP_SRC, src);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_LOAD_START.toString(), event);

        Log.d("IJKMEDIA", "HERE BABY");

        ensureIjkVideoView();

        if (userAgent != null && ijkVideoView != null)
            ijkVideoView.setUserAgent(userAgent);

        Map<String, String> headerMap = null;
        if (headers != null) {
            ReadableMapKeySetIterator iterator = headers.keySetIterator();
            StringBuilder str = new StringBuilder();
            while (iterator.hasNextKey()) {
                String key = iterator.nextKey();
                ReadableType type = headers.getType(key);
                switch (type) {
                    case String:
                        str
                            .append(key)
                            .append(": ")
                            .append(headers.getString(key))
                            .append("\r\n");
                        break;
                }
            }

            headerMap = new HashMap<>();
            headerMap.put("Headers", str.toString());
        }

        Map<String, Integer> playerOptionsMap = null;
        if (playerOptions != null) {
            playerOptionsMap = new HashMap<>();
            ReadableMapKeySetIterator iterator = playerOptions.keySetIterator();
            while (iterator.hasNextKey()) {
                String key = iterator.nextKey();
                playerOptionsMap.put(key, playerOptions.getInt(key));
            }
        }

        if (headers != null || playerOptions != null) {
            mAudioTracks = null;
            mVideoTracks = null;
            mTextTracks = null;
        }

        ijkVideoView.setVideoPath(uriString, headerMap, playerOptionsMap);
    }


    public void setPausedModifier(final boolean paused) {
        Log.i("STATUS_CHANGE", "PAUSING   :" + paused);
        //if (mPaused == paused) return;

        mPaused = paused;

        if (ijkVideoView == null) return;
        if (mPaused) {
            if (ijkVideoView.isPlaying()) {
                ijkVideoView.pause();
            }
        } else {
            if (!ijkVideoView.isPlaying()) {
                ijkVideoView.start();
                mProgressUpdateHandler.post(mProgressUpdateRunnable);
            }
        }
    }

    public void setSeekModifier(final double seekTime, final boolean pauseAfterSeek) {
        if (ijkVideoView == null) return;
        ijkVideoView.seekTo((int) (seekTime * 1000));
    }

    public void setResizeModifier(final String resizeMode) {
        if (ijkVideoView == null) return;
        ijkVideoView.setVideoAspect(resizeMode);
    }

    public void setMutedModifier(final boolean muted) {
        mMuted = muted;
        if (ijkVideoView == null) return;
        if (mMuted) {
            ijkVideoView.setVolume(0.0f, 0.0f);
        } else {
            ijkVideoView.setVolume(mVolume, mVolume);
        }
    }

    public void setVolumeModifier(final float volume) {
        mVolume = volume;
        if (ijkVideoView == null) return;
        if (volume > 1.0f || volume < 0.0f) return;
        ijkVideoView.setVolume(mVolume, mVolume);
    }

    public void setStereoPanModifier(final float left, final float right) {
        if (ijkVideoView == null) return;
        if (left > 1.0f || left < 0.0f || right > 1.0f || right < 0.0f) return;
        ijkVideoView.setVolume(left, right);
    }

    public void setRepeatModifer(final boolean repeat) {
        mRepeat = repeat;
        if (ijkVideoView == null) return;
        ijkVideoView.repeat(mRepeat);
    }

    public void setPlaybackRateModifer(final float rate) {
        mPlaybackSpeed = rate;
        if (ijkVideoView == null) return;
        ijkVideoView.setPlaybackRate(mPlaybackSpeed);
    }

    public void setProgressUpdateInterval(final int progressUpdateInterval) {
        mProgressUpdateInterval = progressUpdateInterval;
        mProgressUpdateRunnable = null;
        setProgressUpdateRunnable();
    }

    public void getCurrentSelectedTracks(Promise promise) {
        if (ijkVideoView == null) {
            promise.reject("Player not initialized");
            return;
        }
        WritableMap args = new Arguments().createMap();
        args.putInt("selectedAudioTrack", ijkVideoView.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO));
        args.putInt("selectedVideoTrack", ijkVideoView.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO));
        args.putInt("selectedTextTrack", ijkVideoView.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT));
        promise.resolve(args);
    }

    public void selectAudioTrack(int trackID) {
        if (ijkVideoView == null) return;
        Log.d("SETTING_TRACK", String.valueOf(trackID));
        ijkVideoView.selectTrack(trackID);

    }


    public void selectVideoTrack(int trackID) {
        if (ijkVideoView == null) return;
        ijkVideoView.selectTrack(trackID);
    }

    public void deselectTrack(int trackID) {
        if (ijkVideoView == null) return;
        ijkVideoView.deSelectTrack(trackID);
    }

    public void selectTextTrack(int trackID) {
        if (ijkVideoView == null) return;
        ijkVideoView.selectTrack(trackID);
    }

    public void setSubtitleDisplay(int textSize, String color, String position, String backgroundColor) {
        if (ijkVideoView == null) return;
        ijkVideoView.setSubtitleDisplay(getContext(), textSize, position, color, backgroundColor);
    }

    public void setSubtitles(final boolean subtitlesEnabled) {
        if (ijkVideoView == null) return;
        ijkVideoView.setSubtitles(subtitlesEnabled);
    }

    public void setAudio(final boolean audioEnabled) {
        if (ijkVideoView == null) return;
        ijkVideoView.setAudio(audioEnabled);
    }

    public void setVideo(final boolean videoEnabled) {
        if (ijkVideoView == null) return;
        ijkVideoView.setVideo(videoEnabled);
    }

    public void setAudioFocus(final boolean audioFocus) {
        if (ijkVideoView == null) return;
        if (audioFocus) {
            ijkVideoView.getAudioFocus();
        } else {
            ijkVideoView.abandonAudioFocus();
        }
    }

    public boolean mPlayInBackground = true;

    public void setBackgroundPlay(final boolean playInBackground) {
        mPlayInBackground = playInBackground;
    }

    public void takeSnapshot(final String path, Promise promise) throws IOException {
        if (ijkVideoView == null) {
            promise.reject("Error", "video not loaded yet");
            return;
        }

        Bitmap bitmap = ijkVideoView.getBitmap();

        if (bitmap == null) {
            promise.reject("Error", "bitmap is null");
            return;
        }

        File dir = new File(path.replace("file://", ""));

        if (!dir.exists()) {
            dir.mkdirs();
        }

        String fileName = "snapshots-" + UUID.randomUUID().toString() + "." + "jpeg";

        String filePath = path + fileName;
        File file = new File(filePath.replace("file://", ""));

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            promise.resolve(filePath);
        } catch (Exception e) {
            Log.d("ERROR", e.getMessage());
        } finally {

            bitmap.recycle();
            if (fos != null)
                fos.close();
        }
    }


    public void setSnapshotPath(final String snapshotPath) throws IOException {
        if (ijkVideoView == null) return;
        Bitmap bitmap = ijkVideoView.getBitmap();
        if (bitmap == null)
            return;
        File file = new File(snapshotPath);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } finally {
            bitmap.recycle();
            if (fos != null)
                fos.close();
        }
    }


    public void applyModifiers() {
        setPausedModifier(mPaused);
        setMutedModifier(mMuted);
    }


    @Override
    public void onEnableStatusChange(AudioEffect effect, boolean enabled) {
        Log.i("STATUS_CHANGE", String.valueOf(effect));
    }

    @Override
    protected void onDetachedFromWindow() {
        releasePlayer();
        super.onDetachedFromWindow();
    }

    @Override
    public void onCompletion(IMediaPlayer iMediaPlayer) {
        WritableMap event = Arguments.createMap();
        mEventEmitter.receiveEvent(getId(), Events.EVENT_END.toString(), event);
    }

    @Override
    public boolean onError(IMediaPlayer iMediaPlayer, int frameworkErr, int implErr) {
        WritableMap event = Arguments.createMap();
        WritableMap error = Arguments.createMap();
        error.putInt(EVENT_PROP_WHAT, frameworkErr);
        event.putMap(EVENT_PROP_ERROR, error);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_ERROR.toString(), event);
        releasePlayer();
        return true;
    }

    @Override
    public boolean onInfo(IMediaPlayer iMediaPlayer, int message, int val) {
        switch (message) {
            case IMediaPlayer.MEDIA_INFO_BUFFERING_START:
                setMutedModifier(true);
                postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        setMutedModifier(false);
                        setVolumeModifier(1);
                    }
                }, 2000);
                mStalled = true;
                break;

            case IMediaPlayer.MEDIA_INFO_BUFFERING_END:
                setMutedModifier(false);
                mStalled = false;
                break;

            case IjkMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START:
                if (mPaused) {
                    setMutedModifier(false);
                    ijkVideoView.pause();
                } else {
                    setMutedModifier(false);
                    WritableMap event = Arguments.createMap();
                    event.putString("paused", "true");
                    mEventEmitter.receiveEvent(getId(), Events.EVENT_VIDEO_PLAYING.toString(), event);
                }
                break;

            case IjkMediaPlayer.MEDIA_INFO_VIDEO_SEEK_RENDERING_START:
                Log.d("PAUSED_VIDEO", String.valueOf(mPaused));
                if (mPaused) {
                    ijkVideoView.pause();
                    setMutedModifier(false);
                    WritableMap event = Arguments.createMap();
                    event.putString("paused", "true");
                    mEventEmitter.receiveEvent(getId(), Events.EVENT_PAUSE.toString(), event);
                } else {
                    setMutedModifier(false);
                    WritableMap event = Arguments.createMap();
                    event.putString("paused", "true");
                    mEventEmitter.receiveEvent(getId(), Events.EVENT_VIDEO_PLAYING.toString(), event);
                }
                break;

            case IjkMediaPlayer.MEDIA_INFO_AUDIO_SEEK_RENDERING_START:
                if (mPaused) {
                    setMutedModifier(false);
                    ijkVideoView.pause();
                    WritableMap event = Arguments.createMap();
                    event.putString("paused", "true");
                    mEventEmitter.receiveEvent(getId(), Events.EVENT_PAUSE.toString(), event);
                } else {
                    setMutedModifier(false);
                    WritableMap event = Arguments.createMap();
                    event.putString("paused", "true");
                    mEventEmitter.receiveEvent(getId(), Events.EVENT_VIDEO_PLAYING.toString(), event);
                }
                break;
        }

        return true;
    }


    private OnAudioSessionIdRecieved mAudioSessionIdListener;

    public void setOnAudioSessionIdListener(OnAudioSessionIdRecieved audioSessionIdListener) {
        mAudioSessionIdListener = audioSessionIdListener;
    }


    @Override
    public void onTimedText(String subtitle) {
        WritableMap event = Arguments.createMap();
        event.putString("text", subtitle);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_TIMED_TEXT.toString(), event);
    }

    @Override
    public void onPrepared(IMediaPlayer iMediaPlayer) {
        if (mLoaded)
            return;
        if (mAudioSessionIdListener != null)
            mAudioSessionIdListener.onAudioSessionId(iMediaPlayer);

        WritableMap event = Arguments.createMap();
        WritableArray videoTracks = new Arguments().createArray();
        WritableArray audioTracks = new Arguments().createArray();
        WritableArray textTracks = new Arguments().createArray();

        MediaInfo mediainfo = iMediaPlayer.getMediaInfo();
        ArrayList<IjkMediaMeta.IjkStreamMeta> streams = mediainfo.mMeta.mStreams;

        event.putString("format", mediainfo.mMeta.mFormat);
        event.putString("bitrate", String.valueOf(mediainfo.mMeta.mBitrate));
        event.putString("startTime", String.valueOf(mediainfo.mMeta.mStartUS));
        event.putString("audioDecoder", String.valueOf(mediainfo.mAudioDecoder));
        event.putString("audioDecoderImpl", String.valueOf(mediainfo.mAudioDecoderImpl));
        event.putString("videoDecoder", String.valueOf(mediainfo.mVideoDecoder));
        event.putString("videoDecoderImpl", String.valueOf(mediainfo.mVideoDecoderImpl));
        event.putInt("audioSessionID", iMediaPlayer.getAudioSessionId());
        event.putInt("selectedAudioTrack", ijkVideoView.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO));
        event.putInt("selectedVideoTrack", ijkVideoView.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_VIDEO));
        event.putInt("selectedTextTrack", ijkVideoView.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT));
        event.putString("dataSource", iMediaPlayer.getDataSource());
        event.putBoolean("canGoForward", ijkVideoView.canSeekForward());
        event.putBoolean("canGoBackward", ijkVideoView.canSeekBackward());
        event.putBoolean("canPause", ijkVideoView.canPause());

        for (int i = 0; i < streams.size(); i++) {
            WritableMap args = new Arguments().createMap();
            IjkMediaMeta.IjkStreamMeta streamMeta = streams.get(i);

            args.putInt("track_id", streamMeta.mIndex);
            args.putString("type", streamMeta.mType);
            args.putString("codecShortName", streamMeta.mCodecName);
            args.putString("codecLongName", streamMeta.mCodecLongName);
            args.putString("codecProfile", streamMeta.mCodecProfile);
            args.putString("language", streamMeta.mLanguage);
            args.putInt("width", streamMeta.mWidth);

            args.putInt("height", streamMeta.mHeight);
            args.putString("bitrate", String.valueOf(streamMeta.mBitrate));
            args.putInt("sampleRate", streamMeta.mSampleRate);
            args.putInt("channelLayout", (int) streamMeta.mChannelLayout);
            args.putInt("fps_den", streamMeta.mFpsDen);
            args.putInt("fps_num", streamMeta.mFpsNum);
            args.putInt("sar_den", streamMeta.mSarDen);
            args.putInt("sar_num", streamMeta.mSarNum);
            args.putInt("tbr_den", streamMeta.mTbrDen);
            args.putInt("tbr_num", streamMeta.mTbrNum);

            if (streamMeta.mType.equals("video")) {
                videoTracks.pushMap(args);
            } else if (streamMeta.mType.equals("audio")) {
                audioTracks.pushMap(args);
            } else if (streamMeta.mType.equals("timedtext")) {
                textTracks.pushMap(args);
            } else {
                event.putMap("extra", args);
            }
        }

        mVideoTracks = videoTracks;
        mAudioTracks = audioTracks;
        mTextTracks = textTracks;

        event.putArray("videoTracks", videoTracks);
        event.putArray("audioTracks", audioTracks);
        event.putArray("textTracks", textTracks);

        event.putDouble(EVENT_PROP_DURATION, ijkVideoView.getDuration() / 1000.0);
        event.putDouble(EVENT_PROP_CURRENT_TIME, ijkVideoView.getCurrentPosition() / 1000.0);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_LOAD.toString(), event);
        mLoaded = true;

        applyModifiers();
    }

    @Override
    public void onBufferingUpdate(IMediaPlayer iMediaPlayer, int percent) {
        if (!mStalled)
            return;
        WritableMap event = Arguments.createMap();
        event.putDouble(EVENT_PROP_BUFFERING_PROG, percent / 100);
        event.putInt("tcpSpeed", (int) ijkVideoView.getTcpSpeed());
        event.putInt("fileSize", (int) ijkVideoView.getFileSize());
        event.putString(EVENT_PROP_DATASOURCE, iMediaPlayer.getDataSource());
        mEventEmitter.receiveEvent(getId(), Events.EVENT_STALLED.toString(), event);
    }

    @Override
    public void onHostPause() {
        if (!mPlayInBackground) {
            ijkVideoView.pause();
        }
    }

    @Override
    public void onHostResume() {
        if (!mPlayInBackground && !mPaused) {
            Log.d("PAUSING", String.valueOf(mPaused));
            setPausedModifier(false);
        }
    }

    @Override
    public void onHostDestroy() {
        releasePlayer();
    }
}
