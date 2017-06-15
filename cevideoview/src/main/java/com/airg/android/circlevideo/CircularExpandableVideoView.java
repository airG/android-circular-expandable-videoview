/*
 * ****************************************************************************
 *   Copyright  2017 airG Inc.                                                 *
 *                                                                             *
 *   Licensed under the Apache License, Version 2.0 (the "License");           *
 *   you may not use this file except in compliance with the License.          *
 *   You may obtain a copy of the License at                                   *
 *                                                                             *
 *       http://www.apache.org/licenses/LICENSE-2.0                            *
 *                                                                             *
 *   Unless required by applicable law or agreed to in writing, software       *
 *   distributed under the License is distributed on an "AS IS" BASIS,         *
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 *   See the License for the specific language governing permissions and       *
 *   limitations under the License.                                            *
 * ***************************************************************************
 */

package com.airg.android.circlevideo;

import android.animation.Animator;
import android.animation.FloatEvaluator;
import android.animation.IntEvaluator;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.support.v4.view.GestureDetectorCompat;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;

import com.airg.android.logging.Logger;
import com.airg.android.logging.TaggedLogger;

import java.io.IOException;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import static com.airg.android.circlevideo.Helper.calculateNormalizedRadius;

/**
 * Created by MahramF.
 */

public class CircularExpandableVideoView extends GLSurfaceView
        implements
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnPreparedListener, MediaPlayer.OnVideoSizeChangedListener, ValueAnimator.AnimatorUpdateListener {

    // Log tag
    private static final TaggedLogger LOG = Logger.tag("CEVideoView");

    static final float COLLAPSED_RADIUS = 0.5f;

    private final MediaPlayer player = new MediaPlayer();
    private final VideoRenderer mRenderer;

    private State state;
    private boolean playWhenReady;
    private boolean paused;

    private VideoSurfaceViewListener actionsListener;

    boolean collapsed = false;
    @Setter
    boolean restartOnExpand = false;
    private boolean loopVideo = false;

    int collapsedWidth = 0;
    int collapsedHeight = 0;

    int currentWidth;
    int currentHeight;

    int currentLeftPadding = 0;
    int currentRightPadding = 0;
    int currentBottomPadding = 0;
    int currentTopPadding = 0;

    int collapsedLeftPadding = 0;
    int collapsedRightPadding = 0;
    int collapsedBottomPadding = 0;
    int collapsedTopPadding = 0;

    int expandedLeftPadding = 0;
    int expandedRightPadding = 0;
    int expandedBottomPadding = 0;
    int expandedTopPadding = 0;

    float collapsedVolume = 0f;
    float expandedVolume = 1f;

    float currentVolume = 0f;

    int animationDuration = 500;

    volatile boolean animating = false;

    private GestureDetectorCompat gestureDetector;
    private final GestureDetector.OnGestureListener gestureListener = new GestureListener();

    private Paint clickPaint;

    public CircularExpandableVideoView(Context context) {
        this(context, null);
    }

    public CircularExpandableVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);

        final TypedArray ta = context.getTheme().obtainStyledAttributes(attrs, R.styleable.CircularExpandableVideoView, 0, 0);

        try {
            animationDuration = ta.getInteger(R.styleable.CircularExpandableVideoView_cevAnimationDuration, animationDuration);

            collapsed = ta.getBoolean(R.styleable.CircularExpandableVideoView_cevCollapsed, collapsed);
            restartOnExpand = ta.getBoolean(R.styleable.CircularExpandableVideoView_cevRestartOnExpand, restartOnExpand);
            loopVideo = ta.getBoolean(R.styleable.CircularExpandableVideoView_cevLoopVideo, loopVideo);

            collapsedVolume = ta.getFloat(R.styleable.CircularExpandableVideoView_cevCollapsedVolume, collapsedVolume);

            if (!Helper.checkRage(collapsedVolume, 0f, 1f))
                throw new IllegalArgumentException("Invalid collapsed volume (valid: 0-1): " + collapsedVolume);

            expandedVolume = ta.getFloat(R.styleable.CircularExpandableVideoView_cevExpandedVolume, expandedVolume);

            if (!Helper.checkRage(expandedVolume, 0f, 1f))
                throw new IllegalArgumentException("Invalid expanded volume (valid: 0-1): " + expandedVolume);

            collapsedHeight = ta.getDimensionPixelSize(R.styleable.CircularExpandableVideoView_cevCollapsedHeight, collapsedHeight);
            collapsedWidth = ta.getDimensionPixelSize(R.styleable.CircularExpandableVideoView_cevCollapsedWidth, collapsedWidth);

            collapsedLeftPadding = ta.getDimensionPixelSize(R.styleable.CircularExpandableVideoView_cevCollapsedLeftPadding, collapsedLeftPadding);
            collapsedRightPadding = ta.getDimensionPixelSize(R.styleable.CircularExpandableVideoView_cevCollapsedRightPadding, collapsedRightPadding);
            collapsedBottomPadding = ta.getDimensionPixelSize(R.styleable.CircularExpandableVideoView_cevCollapsedBottomPadding, collapsedBottomPadding);
            collapsedTopPadding = ta.getDimensionPixelSize(R.styleable.CircularExpandableVideoView_cevCollapsedTopPadding, collapsedTopPadding);

            expandedLeftPadding = ta.getDimensionPixelSize(R.styleable.CircularExpandableVideoView_cevExpandedLeftPadding, expandedLeftPadding);
            expandedRightPadding = ta.getDimensionPixelSize(R.styleable.CircularExpandableVideoView_cevExpandedRightPadding, expandedRightPadding);
            expandedBottomPadding = ta.getDimensionPixelSize(R.styleable.CircularExpandableVideoView_cevExpandedBottomPadding, expandedBottomPadding);
            expandedTopPadding = ta.getDimensionPixelSize(R.styleable.CircularExpandableVideoView_cevExpandedTopPadding, expandedTopPadding);
        } finally {
            ta.recycle();
        }

        mRenderer = new VideoRenderer(this);

        initView();
    }

    private synchronized void initView() {
        setZOrderOnTop(true);

        if (isInEditMode()) {
            clickPaint = new Paint();
            clickPaint.setColor(Color.RED);
            clickPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        }

        gestureDetector = new GestureDetectorCompat(getContext(), gestureListener);
        currentWidth = collapsedWidth;
        currentHeight = collapsedHeight;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2)
            setLayerType(LAYER_TYPE_SOFTWARE, null);

        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(PixelFormat.RGBA_8888);

        state = State.UNINITIALIZED;

        initMediaPlayer();
        setRenderer(mRenderer);
    }

    private synchronized void initMediaPlayer() {
        state = State.UNINITIALIZED;
        player.setOnVideoSizeChangedListener(this);
        player.reset();

        currentVolume = collapsed ? collapsedVolume : expandedVolume;
        player.setVolume(currentVolume, currentVolume);

        playWhenReady = false;
        state = State.INITIALIZED;
    }

    public synchronized void setListener(final VideoSurfaceViewListener listener) {
        actionsListener = listener;
    }

    /**
     * @see android.media.MediaPlayer#setDataSource(String)
     */
    public synchronized void setVideoPath(final String path) {
        initMediaPlayer();

        try {
            player.setDataSource(path);
            prepare();
        } catch (IOException e) {
            LOG.d(e);
        }
    }

    /**
     * @see android.media.MediaPlayer#setDataSource(android.content.Context, android.net.Uri)
     */
    public synchronized void setVideoUri(final Uri uri) {
        initMediaPlayer();

        try {
            player.setDataSource(getContext(), uri);
            prepare();
        } catch (IOException e) {
            LOG.e(e);
        }
    }

    /**
     * @see android.media.MediaPlayer#setDataSource(java.io.FileDescriptor)
     */
    public synchronized void setVideoFileDescriptor(final AssetFileDescriptor afd) {
        initMediaPlayer();

        try {
            long startOffset = afd.getStartOffset();
            long length = afd.getLength();
            player.setDataSource(afd.getFileDescriptor(), startOffset, length);
            prepare();
        } catch (IOException e) {
            LOG.e(e);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        LOG.d("Releasing players");
        player.release();
        super.onDetachedFromWindow();
    }

    private synchronized void prepare() {
        try {
            player.setOnCompletionListener(this);
            player.setOnErrorListener(this);
            player.setOnPreparedListener(this);
            player.prepareAsync();
        } catch (Exception e) {
            e.printStackTrace();
            LOG.e(e);
        }
    }

    @Override
    public synchronized void onPrepared(MediaPlayer mediaPlayer) {
        state = State.PREPARED;

        if (playWhenReady) {
            LOG.d("Player is prepared and play() was called.");
            play();
        }

        if (actionsListener != null) {
            actionsListener.onPrepared(mediaPlayer);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        synchronized (mRenderer) {
            if (collapsed) {
                currentWidth = collapsedWidth;
                currentHeight = collapsedHeight;

                currentBottomPadding = collapsedBottomPadding;
                currentTopPadding = collapsedTopPadding;
                currentLeftPadding = collapsedLeftPadding;
                currentRightPadding = collapsedRightPadding;
            } else {
                currentWidth = getMeasuredHeight();
                currentHeight = getMeasuredWidth();

                currentLeftPadding = expandedLeftPadding;
                currentRightPadding = expandedRightPadding;
                currentBottomPadding = expandedBottomPadding;
                currentTopPadding = expandedTopPadding;
            }
        }

        LOG.d("View size changed: %dx%d => %dx%d", oldw, oldh, w, h);
        mRenderer.updateScale();
    }

    public void seekTo(final int msec) {
        player.seekTo(msec);
    }

    public MediaPlayer getMediaPlayer () {
        return player;
    }

    public int getDuration () {
        return player.getDuration();
    }

    public int getVideoHeight () {
        return player.getVideoHeight();
    }

    public int getVideoWidth () {
        return player.getVideoWidth();
    }

    public void setLooping (final boolean loop) {
        loopVideo = loop;
        player.setLooping(loop);
    }

    public synchronized void play() {
        switch (state) {
            case PLAY:
                if (paused) {
                    if (BuildConfig.DEBUG) LOG.d("Resuming paused video");
                    player.start();
                    paused = false;
                } else if (BuildConfig.DEBUG) LOG.d("Already playing");
                break;
            case END:
                // restart playback
            case PREPARED:
                if (BuildConfig.DEBUG) LOG.d("Starting playback");
                player.start();
                state = State.PLAY;
                break;
            default:
                playWhenReady = true;
                if (BuildConfig.DEBUG) LOG.d("Not yet prepared. WIll play when ready");
        }
    }

    public synchronized void pause() {
        if (paused) {
            LOG.d("Already paused");
            return;
        }

        player.pause();
        paused = true;
    }

    public synchronized void stop() {
        if (state != State.PLAY) {
            LOG.d("Not playing. Won't stop.");
            return;
        }

        player.stop();
        state = State.PREPARED;
    }

    public void setAnimationDuration(final int duration) {
        animationDuration = duration;
    }

    @Override
    public synchronized void onCompletion(MediaPlayer mp) {
        state = State.END;
        if (BuildConfig.DEBUG) LOG.d("Video has ended.");

        if (actionsListener != null) {
            actionsListener.onVideoEnd(mp);
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        LOG.e("Mediaplayer error: 0x%x (eaxtra: 0x%x)", what, extra);
        return false;
    }

    @Override
    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        if (BuildConfig.DEBUG) LOG.d("Video size changed (%dx%d)", width, height);
        mRenderer.setVideoSize(width, height);
    }

    public boolean isCollapsed () {
        synchronized (mRenderer) {
            return collapsed;
        }
    }

    public void toggle() {
        synchronized (mRenderer) {
            if (animating) return;

            if (collapsed)
                expand();
            else collapse();
        }
    }

    private AnimationState currentState() {
        return AnimationState.builder()
                .width(currentWidth)
                .height(currentHeight)
                .volume(currentVolume)
                .cropRadius(mRenderer.cropRadius)
                .paddingLeft(currentLeftPadding)
                .paddingRight(currentRightPadding)
                .paddingBottom(currentBottomPadding)
                .paddingTop(currentTopPadding)
                .build();
    }

    public void collapse() {
        synchronized (mRenderer) {
            if (collapsed) {
                if (BuildConfig.DEBUG) LOG.d("Already collapsed");
                return;
            }

            final AnimationState from = currentState();

            final AnimationState to =
                    AnimationState.builder()
                            .width(collapsedWidth)
                            .height(collapsedHeight)
                            .cropRadius(COLLAPSED_RADIUS)
                            .volume(collapsedVolume)
                            .paddingLeft(collapsedLeftPadding)
                            .paddingRight(collapsedRightPadding)
                            .paddingBottom(collapsedBottomPadding)
                            .paddingTop(collapsedTopPadding)
                            .build();

            if (BuildConfig.DEBUG) LOG.d("Collapsing sizeFrom %s sizeTo %s", from, to);

            final ValueAnimator animator = ValueAnimator.ofObject(new VideoCollapseEvaluator(), from, to);

            animator.setDuration(animationDuration)
                    .addListener(new ExpandCollapseListener(true));
            animator.addUpdateListener(this);
            animator.start();
        }
    }

    public void expand() {
        synchronized (mRenderer) {
            if (!collapsed) {
                if (BuildConfig.DEBUG) LOG.d("Already expanded");
                return;
            }

            final AnimationState from = currentState();

            final int targetWidth = getMeasuredWidth();
            final int targetHeight = getMeasuredHeight();

            final AnimationState to =
                    AnimationState.builder()
                            .width(targetWidth)
                            .height(targetHeight)
                            .cropRadius(calculateNormalizedRadius(targetWidth, targetHeight, mRenderer.surfaceWidth(), mRenderer.surfaceHeight()))
                            .volume(expandedVolume)
                            .paddingLeft(expandedLeftPadding)
                            .paddingRight(expandedRightPadding)
                            .paddingBottom(expandedBottomPadding)
                            .paddingTop(expandedTopPadding)
                            .build();

            if (BuildConfig.DEBUG) LOG.d("Expanding from %s to %s", from, to);

            final ValueAnimator animator = ValueAnimator.ofObject(new VideoExpandEvaluator(), from, to);
            animator.setDuration(animationDuration)
                    .addListener(new ExpandCollapseListener(false));
            animator.addUpdateListener(this);
            animator.start();

            if (!restartOnExpand) return;

            player.seekTo(0);
        }
    }

    @Override
    public void onAnimationUpdate(ValueAnimator animation) {
        final AnimationState current = (AnimationState) animation.getAnimatedValue();
        synchronized (mRenderer) {
            currentWidth = current.width;
            currentHeight = current.height;
            mRenderer.cropRadius = current.cropRadius;

            currentLeftPadding = current.paddingLeft;
            currentRightPadding = current.paddingRight;
            currentTopPadding = current.paddingTop;
            currentBottomPadding = current.paddingBottom;

            currentVolume = current.volume;
            player.setVolume(currentVolume, currentVolume);

            if (BuildConfig.DEBUG) LOG.d("New size: %dx%d, R: %s", currentWidth, currentHeight, current.cropRadius);
            mRenderer.updateScale();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!isInEditMode()) return;

        drawEditModeBounds(canvas);
        canvas.drawRect(mRenderer.clickBounds(), clickPaint);
    }

    private void drawEditModeBounds(Canvas canvas) {
        // TODO: later
    }

    public void setSurface(final SurfaceTexture surface) {
        Surface s = new Surface(surface);
        player.setSurface(s);
        s.release();
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onDown(MotionEvent e) {
            synchronized (mRenderer) {
                // if animating, ignore touch
                if (animating) return false;

                // if expanded, accept touch anywhere
                if (!collapsed) return true;

                // if collapsed, only accept touch inside video
                final float x = e.getX();
                final float y = e.getY();

                return mRenderer.insideClickBounds(x, y);
            }
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            synchronized (mRenderer) {
                if (animating) return false;

                if (!collapsed && velocityY > 0) {
                    collapse();
                    return true;
                }

                if (collapsed && velocityY < 0) {
                    expand();
                    return true;
                }
            }

            return false;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            synchronized (mRenderer) {
                if (animating) return false;

                if (!collapsed && null != actionsListener) {
                    actionsListener.onClick();
                    return true;
                }
            }

            expand();
            return true;
        }
    }

    private enum State {
        UNINITIALIZED, INITIALIZED, PREPARED, PLAY, END
    }

    private static final float SPLIT = 0.65f;

    private static abstract class BaseVideoAnimateEvaluator implements TypeEvaluator<AnimationState> {
        final IntEvaluator intEvaluator = new IntEvaluator();
        final FloatEvaluator floatEvaluator = new FloatEvaluator();
    }

    private static class VideoExpandEvaluator extends BaseVideoAnimateEvaluator {

        private final float radiusChangeThreshold = SPLIT;

        @Override
        public AnimationState evaluate(float fraction, AnimationState startValue, AnimationState endValue) {
            final AnimationState values = new AnimationState();

            final float sizeFrac = fraction >= radiusChangeThreshold
                    ? 1f
                    : (radiusChangeThreshold * fraction) / radiusChangeThreshold;

            final float radiusFrac = fraction <= radiusChangeThreshold
                    ? 0f
                    : (fraction - radiusChangeThreshold) / (1f - radiusChangeThreshold);

            values.width = intEvaluator.evaluate(sizeFrac, startValue.width, endValue.width);
            values.height = intEvaluator.evaluate(sizeFrac, startValue.height, endValue.height);

            values.cropRadius = floatEvaluator.evaluate(radiusFrac, startValue.cropRadius, endValue.cropRadius);

            values.paddingLeft = intEvaluator.evaluate(fraction, startValue.paddingLeft, endValue.paddingLeft);
            values.paddingRight = intEvaluator.evaluate(fraction, startValue.paddingRight, endValue.paddingRight);
            values.paddingTop = intEvaluator.evaluate(fraction, startValue.paddingTop, endValue.paddingTop);
            values.paddingBottom = intEvaluator.evaluate(fraction, startValue.paddingBottom, endValue.paddingBottom);

            values.volume = floatEvaluator.evaluate(fraction, startValue.volume, endValue.volume);

            return values;
        }
    }

    private static class VideoCollapseEvaluator extends BaseVideoAnimateEvaluator {

        private final float sizeChangeThreshold = 1f - SPLIT;

        @Override
        public AnimationState evaluate(float fraction, AnimationState startValue, AnimationState endValue) {
            final AnimationState values = new AnimationState();

            final float sizeFrac = fraction <= sizeChangeThreshold
                    ? 0f
                    : (fraction - sizeChangeThreshold) / (1f - sizeChangeThreshold);

            final float radiusFrac = fraction >= sizeChangeThreshold
                    ? 1f
                    : (sizeChangeThreshold * fraction) / sizeChangeThreshold;

            values.width = intEvaluator.evaluate(sizeFrac, startValue.width, endValue.width);
            values.height = intEvaluator.evaluate(sizeFrac, startValue.height, endValue.height);

            values.cropRadius = floatEvaluator.evaluate(radiusFrac, startValue.cropRadius, endValue.cropRadius);

            values.paddingLeft = intEvaluator.evaluate(fraction, startValue.paddingLeft, endValue.paddingLeft);
            values.paddingRight = intEvaluator.evaluate(fraction, startValue.paddingRight, endValue.paddingRight);
            values.paddingTop = intEvaluator.evaluate(fraction, startValue.paddingTop, endValue.paddingTop);
            values.paddingBottom = intEvaluator.evaluate(fraction, startValue.paddingBottom, endValue.paddingBottom);

            values.volume = floatEvaluator.evaluate(fraction, startValue.volume, endValue.volume);

            return values;
        }
    }

    private class ExpandCollapseListener implements Animator.AnimatorListener {
        private final boolean endCollapseValue;

        private ExpandCollapseListener(final boolean willCollapse) {
            endCollapseValue = willCollapse;
        }

        @Override
        public void onAnimationStart(Animator animation) {
            synchronized (mRenderer) {
                animating = true;
            }
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            synchronized (mRenderer) {
                collapsed = endCollapseValue;
                animating = false;

                if (null == actionsListener) return;

                if (collapsed) {
                    actionsListener.onMinimized();
                } else {
                    actionsListener.onMaximized();
                }
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            synchronized (mRenderer) {
                animating = false;
            }
        }

        @Override
        public void onAnimationRepeat(Animator animation) {

        }
    }

    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    @EqualsAndHashCode
    private static class AnimationState {
        int width = 0;
        int height = 0;

        int paddingLeft = 0;
        int paddingRight = 0;
        int paddingBottom = 0;
        int paddingTop = 0;

        float cropRadius = 0f;

        float volume = 0f;
    }

    public interface VideoSurfaceViewListener extends MediaPlayer.OnPreparedListener {
        void onMaximized();

        void onMinimized();

        void onClick();

        void onVideoEnd(MediaPlayer mp);
    }
}  // End of class VideoSurfaceView.