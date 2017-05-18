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

#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTextureCoord;
uniform float threshold;
uniform float uRadius;
uniform float uAspectRatio;
uniform samplerExternalOES sTexture;

void main() {
    vec4 gone = vec4 (0.0, 0.0, 0.0, 0.0);
    vec4 actual = texture2D(sTexture, vTextureCoord);

    vec2 ac;
    if (uAspectRatio > 1.0) {
        ac = vec2 ((vTextureCoord.x - 0.5) * uAspectRatio, vTextureCoord.y - 0.5);
    } else if (uAspectRatio < 1.0) {
        ac = vec2 (vTextureCoord.x - 0.5, (vTextureCoord.y - 0.5) / uAspectRatio);
    } else {
        ac = vec2 (vTextureCoord.x - 0.5, vTextureCoord.y - 0.5);
    }

    float dist = uRadius - sqrt (ac.x * ac.x + ac.y * ac.y);

    if (threshold > 0.0 && dist > threshold) {
        gl_FragColor = actual;
    } else {
        gl_FragColor = mix (gone, actual, dist / threshold);
    }
}