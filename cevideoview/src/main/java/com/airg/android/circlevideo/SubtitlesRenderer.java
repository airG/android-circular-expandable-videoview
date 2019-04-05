/*
 * ****************************************************************************
 *   Copyright  2019 airG Inc.                                                 *
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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.media.TimedText;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Build;
import android.text.Html;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static android.opengl.GLES20.GL_TEXTURE_2D;

final class SubtitlesRenderer {

    private static final float[] IDENTITY_MATRIX;
    static {
        IDENTITY_MATRIX = new float[16];
        Matrix.setIdentityM(IDENTITY_MATRIX, 0);
    }

    private static final int KERNEL_SIZE = 9;

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = uMVPMatrix * aPosition;\n" +
                    "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
                    "}\n";

    // Simple fragment shader for use with "normal" 2D textures.
    private static final String FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform sampler2D sTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}\n";

    private int mProgramHandle;
    private int mTextureId;
    private int muMVPMatrixLoc;
    private int muTexMatrixLoc;
    private int muKernelLoc;
    private int muTexOffsetLoc;
    private int muColorAdjustLoc;
    private int maPositionLoc;
    private int maTextureCoordLoc;
    private float[] mKernel = new float[KERNEL_SIZE];
    private float[] mTexOffset;
    private float mColorAdjust;

    private float[] modelMatrix;
    private float[] scratchMatrix = new float[32];

    private Context context;
    private Typeface fontFace;
    private int textSize;
    private int maxWidth;

    private Bitmap bitmap;

    private final Object lock = new Object();

    SubtitlesRenderer(Context ctx, Typeface fontFace, int textSize, int maxWidth) {

        context = ctx;
        this.fontFace = fontFace;
        this.textSize = textSize;
        this.maxWidth = maxWidth;

        mProgramHandle = createProgram();

        maPositionLoc = GLES20.glGetAttribLocation(mProgramHandle, "aPosition");
        maTextureCoordLoc = GLES20.glGetAttribLocation(mProgramHandle, "aTextureCoord");
        muMVPMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uMVPMatrix");
        muTexMatrixLoc = GLES20.glGetUniformLocation(mProgramHandle, "uTexMatrix");
        muKernelLoc = GLES20.glGetUniformLocation(mProgramHandle, "uKernel");
        if (muKernelLoc < 0) {
            // no kernel in this one
            muKernelLoc = -1;
            muTexOffsetLoc = -1;
            muColorAdjustLoc = -1;
        } else {
            // has kernel, must also have tex offset and color adj
            muTexOffsetLoc = GLES20.glGetUniformLocation(mProgramHandle, "uTexOffset");
            muColorAdjustLoc = GLES20.glGetUniformLocation(mProgramHandle, "uColorAdjust");

            // initialize default values
            setKernel(new float[]{0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f}, 0f);
            setTexSize(256, 256);
        }
        modelMatrix = new float[16];
        //Matrix.setIdentityM(modelMatrix, 0);
    }

    void setSubtitles(Context context, String vtt, MediaPlayer player) {


        synchronized (lock) {

            bitmap = null;

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {


                    MediaPlayer.TrackInfo[] tracks = player.getTrackInfo();
                    for (int i = 0; i < tracks.length; ++i) {
                        if (tracks[i].getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                            player.deselectTrack(i);
                            player.setOnTimedTextListener(null);
                        }
                    }

                    final String srtText = convertVTTtoSRT(vtt);

                    byte[] srtBytes = srtText.getBytes("ISO-8859-1");


                    File outputDir = context.getCacheDir();
                    File srtFile = File.createTempFile("subtitles", "srt", outputDir);

                    OutputStream os = new FileOutputStream(srtFile);
                    os.write(srtBytes);
                    os.close();
                    player.addTimedTextSource(srtFile.getAbsolutePath(), MediaPlayer.MEDIA_MIMETYPE_TEXT_SUBRIP);

                    tracks = player.getTrackInfo();
                    for (int i = 0; i < tracks.length; ++i) {
                        if (tracks[i].getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                            player.selectTrack(i);
                        }
                    }

                    player.setOnTimedTextListener(new MediaPlayer.OnTimedTextListener() {
                        @Override
                        public void onTimedText(MediaPlayer mediaPlayer, TimedText timedText) {
                            if (null != timedText) {

                                String text = timedText.getText();
                                if (null != text) {
                                    text = text.trim();
                                    if (!text.equals("~")) {
                                        setText(Html.fromHtml(text).toString());

                                    } else {
                                        setText(null);
                                    }
                                } else {
                                    setText(null);
                                }
                            } else {
                                setText(null);
                            }
                        }
                    });
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    void setText(String text) {

        synchronized (lock) {
            Bitmap originalBmp = bitmap;
            if (null != text && text.length() > 0) {
                bitmap = fromText(text, maxWidth);
            } else {
                bitmap = null;
            }
            if (null != originalBmp) {
                originalBmp.recycle();
            }
        }
    }

    void render(float width, float height) {

        synchronized (lock) {
            if (null != bitmap) {
                Matrix.setIdentityM(modelMatrix, 0);

                Transformer transformer = new Transformer();
                float[] transformMatrix = transformer
                        .translate(0, (bitmap.getHeight() - height) / 2 + 30, 0)
                        .scale(1.0f, 1.0f, 1.0f)
                        .rotateAroundX(180)
                        .build();

                Matrix.multiplyMM(modelMatrix, 0, IDENTITY_MATRIX.clone(), 0, transformMatrix, 0);


                float[] projectionMatrix = new float[16];
                float near = -1.0f, far = 1.0f,
                        right = width / 2,
                        top = height /2;

                Matrix.orthoM(projectionMatrix, 0,
                        -right, right,
                        -top, top,
                        near, far);

                Matrix.multiplyMM(scratchMatrix, 0, projectionMatrix, 0, modelMatrix, 0);


                renderBitmap(bitmap);
            }
        }
    }

    private static int spToPx(float sp, Context context) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.getResources().getDisplayMetrics());
    }
    private Bitmap fromText(String text, int maxWidth) {

        int textSizePx = spToPx(textSize, context);

        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(fontFace);
        paint.setTextSize(textSizePx);
        paint.setColor(Color.WHITE);

        StaticLayout textLayout = new StaticLayout(text, paint, maxWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
        int textHeight = textLayout.getHeight();

        Bitmap bitmap = Bitmap.createBitmap(maxWidth, textHeight, Bitmap.Config.ARGB_8888);
        bitmap.setHasAlpha(true);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        canvas.save();

        canvas.translate(0, 0);
        textLayout.draw(canvas);
        canvas.restore();

        return bitmap;
    }

    private void renderBitmap(Bitmap bitmap) {

        float width = bitmap.getWidth();
        float height = bitmap.getHeight();

        float[] vertexArray = new float[]{
                -width / 2f, -height / 2f,   // 0 bottom left
                +width / 2f, -height / 2f,   // 1 bottom right
                -width / 2f, +height / 2f,   // 2 top left
                +width / 2f, +height / 2f,   // 3 top right
        };
        float[] texArray = new float[]{
                0.0f, 0.0f,     // 0 bottom left
                1.0f, 0.0f,     // 1 bottom right
                0.0f, 1.0f,     // 2 top left
                1.0f, 1.0f      // 3 top right
        };

        FloatBuffer vertexBuffer = createFloatBuffer(vertexArray);
        FloatBuffer texBuffer = createFloatBuffer(texArray);


        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_2D, mTextureId);

        GLUtils.texImage2D(GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bitmap, 0);

        checkGlError("draw start");

        // Select the program.
        GLES20.glUseProgram(mProgramHandle);
        checkGlError("glUseProgram");

        // Set the texture.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_2D, mTextureId);

        // Copy the model / view / projection matrix over.
        GLES20.glUniformMatrix4fv(muMVPMatrixLoc, 1, false, scratchMatrix, 0);
        checkGlError("glUniformMatrix4fv");

        // Copy the texture transformation matrix over.
        GLES20.glUniformMatrix4fv(muTexMatrixLoc, 1, false, IDENTITY_MATRIX, 0);
        checkGlError("glUniformMatrix4fv");

        // Enable the "aPosition" vertex attribute.
        GLES20.glEnableVertexAttribArray(maPositionLoc);
        checkGlError("glEnableVertexAttribArray");

        // Connect vertexBuffer to "aPosition".
        GLES20.glVertexAttribPointer(maPositionLoc, 2, GLES20.GL_FLOAT, false, 2 * 4, vertexBuffer);
        checkGlError("glVertexAttribPointer");

        // Enable the "aTextureCoord" vertex attribute.
        GLES20.glEnableVertexAttribArray(maTextureCoordLoc);
        checkGlError("glEnableVertexAttribArray");

        // Connect texBuffer to "aTextureCoord".
        GLES20.glVertexAttribPointer(maTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 2 * 4, texBuffer);
        checkGlError("glVertexAttribPointer");

        // Populate the convolution kernel, if present.
        if (muKernelLoc >= 0) {
            GLES20.glUniform1fv(muKernelLoc, KERNEL_SIZE, mKernel, 0);
            GLES20.glUniform2fv(muTexOffsetLoc, KERNEL_SIZE, mTexOffset, 0);
            GLES20.glUniform1f(muColorAdjustLoc, mColorAdjust);
        }

        // Draw the rect.
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");

        // Done -- disable vertex array, texture, and program.
        GLES20.glDisableVertexAttribArray(maPositionLoc);
        GLES20.glDisableVertexAttribArray(maTextureCoordLoc);
        GLES20.glBindTexture(GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);

    }

    void init() {

        mTextureId = createTextureObject();

    }

    public int createTextureObject() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);

        int texId = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_2D, texId);

        GLES20.glTexParameterf(GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        return texId;
    }

    private void setKernel(float[] values, float colorAdj) {
        if (values.length != KERNEL_SIZE) {
            throw new IllegalArgumentException("Kernel size is " + values.length +
                    " vs. " + KERNEL_SIZE);
        }
        System.arraycopy(values, 0, mKernel, 0, KERNEL_SIZE);
        mColorAdjust = colorAdj;
        //Log.d(TAG, "filt kernel: " + Arrays.toString(mKernel) + ", adj=" + colorAdj);
    }

    /**
     * Sets the size of the texture.  This is used to find adjacent texels when filtering.
     */
    void setTexSize(int width, int height) {
        float rw = 1.0f / width;
        float rh = 1.0f / height;

        // Don't need to create a new array here, but it's syntactically convenient.
        mTexOffset = new float[]{
                -rw, -rh, 0f, -rh, rw, -rh,
                -rw, 0f, 0f, 0f, rw, 0f,
                -rw, rh, 0f, rh, rw, rh
        };
        //Log.d(TAG, "filt size: " + width + "x" + height + ": " + Arrays.toString(mTexOffset));
    }

    private int createProgram() {

        int program = GLES20.glCreateProgram();
        if (program != 0) {

            int vertexShader = loadShader(VERTEX_SHADER, GLES20.GL_VERTEX_SHADER);
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");

            int pixelShader = loadShader(FRAGMENT_SHADER_2D, GLES20.GL_FRAGMENT_SHADER);
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");

            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
        }
        return program;
    }

    private int loadShader(final String source, final int shaderType) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader != 0) {
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);

            if (compiled[0] == 0) {
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
        }
        return shader;
    }

    private void checkGlError(String op) {
        int error;

        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            throw new RuntimeException(op + ": glError " + error);
        }
    }

    public static FloatBuffer createFloatBuffer(float[] coords) {
        // Allocate a direct ByteBuffer, using 4 bytes per float, and copy coords into it.
        ByteBuffer bb = ByteBuffer.allocateDirect(coords.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(coords);
        fb.position(0);
        return fb;
    }

    private static String convertVTTtoSRT(String vtt) {
        vtt += "\n\n"; // Just in case, to comply with regex
        String srt = "";
        Pattern pattern = Pattern.compile("([0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{3})\\s-->\\s([0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{3})[^\\n]*[\\n](((?!\\n\\n).)*)[\\n]{2}", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(vtt);

        SimpleDateFormat fmtVTT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        SimpleDateFormat fmtSRT = new SimpleDateFormat("HH:mm:ss,SSS", Locale.US);

        int index = 1;

        while (matcher.find()) {
            String timeFromStr = matcher.group(1);
            String timeToStr = matcher.group(2);
            String captionText = matcher.group(3);

            try {
                Date timeFrom = fmtVTT.parse(timeFromStr);
                Date timeTo = fmtVTT.parse(timeToStr);

                timeFromStr = fmtSRT.format(timeFrom);
                timeToStr = fmtSRT.format(timeTo);

                srt += String.format(Locale.US, "%d\n%s --> %s\n%s\n\n", index++, timeFromStr, timeToStr, captionText);

                // onTimedText is not called at the end of caption, as it is supposed to be... Insert fake caption with "special" text to signal end of previous caption
                timeFromStr = timeToStr;
                timeTo = new Date(timeTo.getTime() + 10);
                timeToStr = fmtSRT.format(timeTo);
                srt += String.format(Locale.US, "%d\n%s --> %s\n%s\n\n", index++, timeFromStr, timeToStr, "~");

            } catch (ParseException e) {
                e.printStackTrace();
            }

        }

        return srt;
    }

    public static class Transformer {

        private float rotationMatrix[] = new float[16];

        public Transformer() {
            Matrix.setIdentityM(rotationMatrix, 0);
        }

        public Transformer translate(float x, float y, float z) {
            Matrix.translateM(rotationMatrix, 0, x, y, z);
            return this;
        }

        public Transformer scale(float x, float y, float z) {
            Matrix.scaleM(rotationMatrix, 0, x, y, z);
            return this;
        }

        /*in degrees*/
        public Transformer rotateAroundX(float angle) {
            Matrix.rotateM(rotationMatrix, 0, angle, 1, 0, 0);
            return this;
        }

        /*in degrees*/
        public Transformer rotateAroundY(float angle) {
            Matrix.rotateM(rotationMatrix, 0, angle, 0, 1, 0);
            return this;
        }

        /*in degrees*/
        public Transformer rotateAroundZ(float angle) {
            Matrix.rotateM(rotationMatrix, 0, angle, 0, 0, 1);
            return this;
        }

        public float[] build() {
            return rotationMatrix;
        }

        public Transformer reset() {
            Matrix.setIdentityM(rotationMatrix, 0);
            return this;
        }

        public Transformer print() {
            String s = t(rotationMatrix[0]) + ", " + t(rotationMatrix[4]) + ", " + t(rotationMatrix[8]) + ", " + t(rotationMatrix[12])
                    + "\n" + t(rotationMatrix[1]) + ", " + t(rotationMatrix[5]) + ", " + t(rotationMatrix[9]) + ", " + t(rotationMatrix[13])
                    + "\n" + t(rotationMatrix[2]) + ", " + t(rotationMatrix[6]) + ", " + t(rotationMatrix[10]) + ", " + t(rotationMatrix[14])
                    + "\n" + t(rotationMatrix[3]) + ", " + t(rotationMatrix[7]) + ", " + t(rotationMatrix[11]) + ", " + t(rotationMatrix[15]);
            return this;
        }

        private float t(float f) {
            return Math.round(f * 100.0f) / 100.f;
        }

    }
}
