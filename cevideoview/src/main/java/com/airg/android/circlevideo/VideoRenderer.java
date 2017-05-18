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

import android.content.res.Resources;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import com.airg.android.logging.Logger;
import com.airg.android.logging.TaggedLogger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import lombok.Getter;

import static com.airg.android.circlevideo.Helper.aspectRatio;
import static com.airg.android.circlevideo.Helper.loadShaderCode;
import static com.airg.android.circlevideo.Helper.normalize;
import static com.airg.android.circlevideo.Helper.setCenter;

/**
 * The majority of this code comes from http://stackoverflow.com/a/14999912/675750, which in turn comes from the Android source code itself
 */

final class VideoRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private static final TaggedLogger LOG = Logger.tag("CEVideoRenderer");

    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;

    private static final int FLOAT_SIZE_BYTES = 4;
    private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
    private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
    private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;

    private final float[] mTriangleVerticesData = {
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0, 0.f, 0.f,
            1.0f, -1.0f, 0, 1.f, 0.f,
            -1.0f, 1.0f, 0, 0.f, 1.f,
            1.0f, 1.0f, 0, 1.f, 1.f,
    };

    private final FloatBuffer mTriangleVertices;

    private float[] mMVPMatrix = new float[16];
    private float[] mSTMatrix = new float[16];

    private int mProgram;
    private int mTextureID;
    private int muMVPMatrixHandle;
    private int muSTMatrixHandle;
    private int maPositionHandle;
    private int maTextureHandle;
    private int muAAThresholdHandle;
    private int muRadiusHandle;
    private int muAspectRatioHandle;

    private SurfaceTexture mSurface;
    private boolean updateSurface = false;

    @Getter
    private int surfaceWidth = 0;
    @Getter
    private int surfaceHeight = 0;

    private int videoW = 0;
    private int videoH = 0;

    private float antiAliasThreshold;
    private PointF cropCenter = new PointF();
    float cropRadius = 0f;
    private float aspectRatio = 1f;

    private final CircularExpandableVideoView view;

    @Getter
    private final RectF clickBounds = new RectF();
    private final RectF videoBounds = new RectF();

    VideoRenderer(final CircularExpandableVideoView videoSurfaceView) {
        view = videoSurfaceView;
        mTriangleVertices = ByteBuffer.allocateDirect(
                mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTriangleVertices.put(mTriangleVerticesData).position(0);

        Matrix.setIdentityM(mSTMatrix, 0);
    }

    synchronized void updateScale() {
        final float viewWidth;
        final float viewHeight;

        if (view.animating) {
            viewWidth = view.currentWidth;
            viewHeight = view.currentHeight;
        } else if (view.collapsed) {
            viewWidth = view.collapsedWidth;
            viewHeight = view.collapsedHeight;
        } else {
            viewWidth = view.getMeasuredWidth();
            viewHeight = view.getMeasuredHeight();
        }

        if (videoW == 0 || videoH == 0 || viewWidth == 0 || viewHeight == 0)
            return;

        final float videoWidth = (float) videoW;
        final float videoHeight = (float) videoH;

        final float scale = Math.max(viewHeight / videoHeight, viewWidth / videoWidth);

        final float scaledVideoWidth = scale * videoWidth;
        final float scaledVideoHeight = scale * videoHeight;

        if (view.animating) {
            updateCurrentTextureCoords(scaledVideoWidth, scaledVideoHeight);
        } else if (view.collapsed) {
            updateCurrentTextureCoords(scaledVideoWidth, scaledVideoHeight);
            cropRadius = CircularExpandableVideoView.COLLAPSED_RADIUS;
        } else {
            updateExpandedTextureCoords(scaledVideoWidth, scaledVideoHeight);
        }

        aspectRatio = scaledVideoWidth / scaledVideoHeight;

        setCenter(cropCenter, mTriangleVerticesData[5], mTriangleVerticesData[0], mTriangleVerticesData[11], mTriangleVerticesData[1]);

        if (BuildConfig.DEBUG) LOG.d("%dx%d - Center: %s, Radius: %s", view.currentWidth, view.currentHeight, cropCenter, cropRadius);

        final float minVideoDim = Math.min(scaledVideoHeight, scaledVideoWidth);
        final float onePercent = Math.min(5f, minVideoDim / 100f);
        antiAliasThreshold = onePercent / minVideoDim;

        synchronized (mTriangleVertices) {
            mTriangleVertices.clear();
            mTriangleVertices.put(mTriangleVerticesData).position(0);
        }
    }

    private void updateCurrentTextureCoords(final float scaledVideoWidth, final float scaledVideoHeight) {
        final float var = aspectRatio(scaledVideoWidth, scaledVideoHeight);
        final float sar = aspectRatio(view.currentWidth, view.currentHeight);
        final float centerX = surfaceWidth / 2f;
        final float centerY = surfaceHeight / 2f;
        final float halfViewWidth = (float) view.currentWidth / 2f;

        if (BuildConfig.DEBUG) {
            LOG.d("Video: %.2fx%.2f, Current: %dx%d, Surface: %dx%d", scaledVideoWidth, scaledVideoHeight, view.currentWidth, view.currentHeight, surfaceWidth, surfaceHeight);
            LOG.d("Center: %.3f,%.3f", centerX, centerY);
            LOG.d("Padding L:%d, B: %d, R: %d, T:%d", view.currentLeftPadding, view.currentBottomPadding, view.currentRightPadding, view.currentTopPadding);
        }

        clickBounds.bottom = surfaceHeight - view.currentBottomPadding;

        if (var >= sar) {   // fill width, leak top & bottom
            videoBounds.bottom = clickBounds.bottom;

            if (BuildConfig.DEBUG) LOG.d("Current: wider video (screen: %.3f, video: %.3f)", sar, var);

            clickBounds.top = Math.max(view.currentTopPadding, clickBounds.bottom - scaledVideoHeight);
            videoBounds.top = clickBounds.top;

            clickBounds.left = Math.max(centerX - halfViewWidth, view.currentLeftPadding);
            clickBounds.right = Math.min(clickBounds.left + view.currentWidth, surfaceWidth - view.currentRightPadding);

            final float halfVideoWidthDiff = (scaledVideoWidth - (float) view.currentWidth) / 2f;

            videoBounds.left = clickBounds.left - halfVideoWidthDiff;
            videoBounds.right = clickBounds.right + halfVideoWidthDiff;
        } else {                // Video is wider. Fill height, leak sides
            if (BuildConfig.DEBUG) LOG.d("Current: narrow video (screen: %.3f, video: %.3f)", sar, var);

            clickBounds.top = Math.max(clickBounds.bottom - view.currentHeight, view.currentTopPadding);

            final float halfVideoHeightDiff = (scaledVideoHeight - (float) view.currentHeight) / 2f;

            videoBounds.bottom = clickBounds.bottom + halfVideoHeightDiff;
            videoBounds.top = clickBounds.top - halfVideoHeightDiff;

            clickBounds.left = Math.max(centerX - halfViewWidth, view.currentLeftPadding);
            videoBounds.left = clickBounds.left;

            clickBounds.right = Math.min(clickBounds.left + view.currentWidth, surfaceWidth - view.currentRightPadding);
            videoBounds.right = clickBounds.right;
        }

        final float centeredBottom = centerY - videoBounds.bottom;
        final float centeredTop = centerY - videoBounds.top;
        final float centeredLeft = videoBounds.left - centerX;
        final float centeredRight = videoBounds.right - centerX;

        final float normalBottom = normalize(2f * centeredBottom, surfaceHeight);
        final float normalTop = normalize(2f * centeredTop, surfaceHeight);
        final float normalLeft = normalize(2f * centeredLeft, surfaceWidth);
        final float normalRight = normalize(2f * centeredRight, surfaceWidth);

        if (BuildConfig.DEBUG) {
            LOG.d("normalized %.3f, %.3f", normalize(view.currentWidth, surfaceWidth), normalize(view.currentHeight, surfaceHeight));
            LOG.d("Click Left: %.3f, Bottom: %.3f, Right: %.3f, Top: %.3f", clickBounds.left, clickBounds.bottom, clickBounds.right, clickBounds.top);
            LOG.d("Video Left: %.3f, Bottom: %.3f, Right: %.3f, Top: %.3f", videoBounds.left, videoBounds.bottom, videoBounds.right, videoBounds.top);
            LOG.d("nLeft: %.3f, nBottom: %.3f, nRight: %.3f, nTop: %.3f", normalLeft, normalBottom, normalRight, normalTop);
        }

        mTriangleVerticesData[0] = normalLeft;
        mTriangleVerticesData[1] = normalBottom;
        mTriangleVerticesData[5] = normalRight;
        mTriangleVerticesData[6] = normalBottom;
        mTriangleVerticesData[10] = normalLeft;
        mTriangleVerticesData[11] = normalTop;
        mTriangleVerticesData[15] = normalRight;
        mTriangleVerticesData[16] = normalTop;
    }

    private void updateExpandedTextureCoords(final float width, final float height) {
        final float left;
        final float bottom;
        final float right;
        final float top;

        final float var = aspectRatio(width, height);
        final float sar = aspectRatio(surfaceWidth, surfaceHeight);

        bottom = 1.0f - normalize(view.currentBottomPadding, surfaceHeight);

        if (var == sar) {
            right = 1.0f - normalize(view.currentLeftPadding, surfaceWidth);
            left = -1.0f - normalize(view.currentLeftPadding, surfaceWidth);
            top = 1.0f - normalize(view.currentBottomPadding, surfaceHeight);
        } else if (sar > var) {    // Video is taller. Fill width, leak height.
            right = normalize(width - view.currentLeftPadding, surfaceWidth) - 0.5f;
            left = 0.5f - normalize(width - view.currentLeftPadding, surfaceWidth);
            top = bottom + (2f * normalize(height, surfaceHeight));
        } else {                    // Video is wider. Fill height, leak width
            top = 1.0f - normalize(view.currentBottomPadding, surfaceHeight);
            right = normalize(width, surfaceWidth);
            left = -right;
        }

        mTriangleVerticesData[0] = left;
        mTriangleVerticesData[1] = bottom;
        mTriangleVerticesData[5] = right;
        mTriangleVerticesData[6] = bottom;
        mTriangleVerticesData[10] = left;
        mTriangleVerticesData[11] = top;
        mTriangleVerticesData[15] = right;
        mTriangleVerticesData[16] = top;
    }

    public void onDrawFrame(GL10 glUnused) {
        synchronized (this) {
            if (updateSurface) {
                mSurface.updateTexImage();
                mSurface.getTransformMatrix(mSTMatrix);
                updateSurface = false;
            }
        }

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_DST_COLOR);

        GLES20.glUseProgram(mProgram);
        checkGlError("glUseProgram");

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);

        mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);

        checkGlError("glVertexAttribPointer maPosition");
        GLES20.glEnableVertexAttribArray(maPositionHandle);
        checkGlError("glEnableVertexAttribArray maPositionHandle");

        GLES20.glUniform1f(muRadiusHandle, cropRadius);
        checkGlError("glUniform1f radius");

        GLES20.glUniform1f(muAspectRatioHandle, aspectRatio);
        checkGlError("glUniform1f aspectRatio");

        GLES20.glUniform1f(muAAThresholdHandle, antiAliasThreshold);
        checkGlError("glUniform1f antiAliasThreshold");

        synchronized (mTriangleVertices) {
            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            GLES20.glVertexAttribPointer(maTextureHandle, 3, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer maTextureHandle");
        }

        GLES20.glEnableVertexAttribArray(maTextureHandle);
        checkGlError("glEnableVertexAttribArray maTextureHandle");

        Matrix.setIdentityM(mMVPMatrix, 0);
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");
        GLES20.glFinish();
    }

    public synchronized void onSurfaceChanged(GL10 glUnused, int width, int height) {
        if (BuildConfig.DEBUG) LOG.d("Surface changed (%dx%d)", width, height);
        surfaceWidth = width;
        surfaceHeight = height;

        updateScale();
    }

    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        mProgram = createProgram();
        if (mProgram == 0) {
            LOG.e("Unable to setup shaders");
            return;
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        checkGlError("glGetAttribLocation aPosition");
        if (maPositionHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition");
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        checkGlError("glGetAttribLocation aTextureCoord");
        if (maTextureHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord");
        }

        muRadiusHandle = GLES20.glGetUniformLocation(mProgram, "uRadius");
        checkGlError("glGetUniformLocation radius");
        if (muRadiusHandle == -1)
            throw new RuntimeException("Could not get attrib location for uRadius");

        muAspectRatioHandle = GLES20.glGetUniformLocation(mProgram, "uAspectRatio");
        checkGlError("glGetUniformLocation uAspectRatio");
        if (muAspectRatioHandle == -1)
            throw new RuntimeException("Could not get attrib location for uAspectRatio");

        muAAThresholdHandle = GLES20.glGetUniformLocation(mProgram, "threshold");
        checkGlError("glGetUniformLocation threshold");
        if (muAAThresholdHandle == -1)
            throw new RuntimeException("Could not get attrib location for threshold");

        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        checkGlError("glGetUniformLocation uMVPMatrix");
        if (muMVPMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uMVPMatrix");
        }

        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
        checkGlError("glGetUniformLocation uSTMatrix");
        if (muSTMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uSTMatrix");
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);

        mTextureID = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTextureID);
        checkGlError("glBindTexture mTextureID");

            /*
             * Create the SurfaceTexture that will feed this textureID,
             * and pass it to the MediaPlayer
             */
        mSurface = new SurfaceTexture(mTextureID);
        mSurface.setOnFrameAvailableListener(this);

        view.setSurface(mSurface);

        synchronized (this) {
            updateSurface = false;
        }
    }

    synchronized public void onFrameAvailable(SurfaceTexture surface) {
        updateSurface = true;
    }

    private int getShaderResourceId(final int type) {
        switch (type) {
            case GLES20.GL_VERTEX_SHADER:
                return R.raw.masked_vertex_shader;
            case GLES20.GL_FRAGMENT_SHADER:
                return R.raw.masked_fragment_shader;
            default:
                throw new IllegalArgumentException("Unknown shader type: " + type);
        }
    }

    private int loadShader(final Resources res, final int shaderType) {
        final String source = loadShaderCode(res, getShaderResourceId(shaderType));

        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);

            if (compiled[0] == 0) {
                LOG.e("Could not compile shader %d: %s", shaderType, GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
        }
        return shader;
    }

    private int createProgram() {
        final Resources resources = view.getResources();

        int vertexShader = loadShader(resources, GLES20.GL_VERTEX_SHADER);
        if (vertexShader == 0) {
            return 0;
        }
        int pixelShader = loadShader(resources, GLES20.GL_FRAGMENT_SHADER);
        if (pixelShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        if (program != 0) {
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                LOG.e("Could not link program:\n%s", GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        return program;
    }

    private void checkGlError(String op) {
        int error;

        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            LOG.e("%s: glError %s", op, error);
            throw new RuntimeException(op + ": glError " + error);
        }
    }

    synchronized boolean insideClickBounds(final float x, final float y) {
        return clickBounds.contains(x, y);
    }

    synchronized void setVideoSize(int width, int height) {
        videoW = width;
        videoH = height;

        updateScale();
    }
}
