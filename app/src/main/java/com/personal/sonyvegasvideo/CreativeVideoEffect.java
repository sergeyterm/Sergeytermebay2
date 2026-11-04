package com.personal.sonyvegasvideo;

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

/** Video looks rendered by the same GPU program for preview and export. */
final class CreativeVideoEffect implements GlEffect {
    private static final long ANIMATION_PERIOD_US = 4_000_000L;

    private final VisualEffect effect;
    private final float strength;
    private final long originUs;

    CreativeVideoEffect(VisualEffect effect, float strength, long originUs) {
        this.effect = effect;
        this.strength = strength;
        this.originUs = originUs;
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
            throws VideoFrameProcessingException {
        return new Program(useHdr);
    }

    private final class Program extends BaseGlShaderProgram {
        private final GlProgram shader;
        private int width;
        private int height;

        Program(boolean useHdr) throws VideoFrameProcessingException {
            super(useHdr, 1);
            try {
                shader = new GlProgram(VERTEX, FRAGMENT);
                shader.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), 4);
            } catch (GlUtil.GlException error) {
                throw new VideoFrameProcessingException(error);
            }
        }

        @Override
        public Size configure(int inputWidth, int inputHeight) {
            width = inputWidth;
            height = inputHeight;
            return new Size(width, height);
        }

        @Override
        public void drawFrame(int textureId, long presentationTimeUs)
                throws VideoFrameProcessingException {
            long phaseUs = Math.floorMod(presentationTimeUs - originUs, ANIMATION_PERIOD_US);
            try {
                shader.use();
                shader.setSamplerTexIdUniform("uTexture", textureId, 0);
                shader.setFloatsUniform("uTexel", new float[] {1f / width, 1f / height});
                shader.setFloatUniform("uAspect", (float) width / height);
                shader.setIntUniform("uMode", effect.shaderMode);
                shader.setFloatUniform("uStrength", strength);
                shader.setFloatUniform("uPhase", phaseUs / (float) ANIMATION_PERIOD_US);
                shader.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (GlUtil.GlException error) {
                throw new VideoFrameProcessingException(error, presentationTimeUs);
            }
        }

        @Override
        public void release() throws VideoFrameProcessingException {
            try {
                shader.delete();
            } catch (GlUtil.GlException error) {
                throw new VideoFrameProcessingException(error);
            } finally {
                super.release();
            }
        }
    }

    private static final String VERTEX = """
            attribute vec4 aFramePosition;
            varying mediump vec2 vUv;

            void main() {
                gl_Position = aFramePosition;
                vUv = aFramePosition.xy * 0.5 + 0.5;
            }
            """;

    private static final String FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexture;
            uniform vec2 uTexel;
            uniform float uAspect;
            uniform float uStrength;
            uniform float uPhase;
            uniform int uMode;
            varying mediump vec2 vUv;

            const float TAU = 6.2831853;

            vec4 sampleFrame(vec2 uv) {
                return texture2D(uTexture, clamp(uv, vec2(0.0), vec2(1.0)));
            }

            float luminance(vec3 rgb) {
                return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            }

            vec3 straightColor(vec4 pixel) {
                return pixel.rgb / max(pixel.a, 0.0001);
            }

            vec4 tint(vec3 rgb, float alpha) {
                return vec4(rgb * alpha, alpha);
            }

            float noise(vec2 cell) {
                vec3 p = fract(vec3(cell.xyx) * 0.1031);
                p += dot(p, p.yzx + 33.33);
                return fract((p.x + p.y) * p.z);
            }

            vec4 lineBlur(vec2 uv, vec2 offset) {
                vec4 total = vec4(0.0);
                for (int i = -4; i <= 4; i++) {
                    total += sampleFrame(uv + offset * float(i));
                }
                return total / 9.0;
            }

            vec4 softBlur(vec2 uv, vec2 radius) {
                vec4 total = sampleFrame(uv) * 4.0;
                total += 2.0 * (sampleFrame(uv + vec2(radius.x, 0.0))
                    + sampleFrame(uv - vec2(radius.x, 0.0))
                    + sampleFrame(uv + vec2(0.0, radius.y))
                    + sampleFrame(uv - vec2(0.0, radius.y)));
                total += sampleFrame(uv + radius) + sampleFrame(uv - radius)
                    + sampleFrame(uv + vec2(radius.x, -radius.y))
                    + sampleFrame(uv + vec2(-radius.x, radius.y));
                return total / 16.0;
            }

            float outline(vec2 uv) {
                vec2 x = vec2(uTexel.x * 2.0, 0.0);
                vec2 y = vec2(0.0, uTexel.y * 2.0);
                vec3 dx = abs(sampleFrame(uv + x).rgb - sampleFrame(uv - x).rgb);
                vec3 dy = abs(sampleFrame(uv + y).rgb - sampleFrame(uv - y).rgb);
                vec3 edge = dx + dy;
                return smoothstep(0.025, 0.45, max(edge.r, max(edge.g, edge.b)));
            }

            vec4 applyEffect(vec2 uv, vec4 original) {
                vec2 scale = vec2(uAspect, 1.0);
                vec2 centered = (uv - 0.5) * scale;
                float radius = length(centered);
                float t = TAU * uPhase;

                if (uMode == 1) {
                    return sampleFrame(uv + vec2(0.045 * sin(uv.y * 22.0 + t * 3.0),
                        0.018 * sin(uv.x * 19.0 - t * 2.0)));
                }
                if (uMode == 2) {
                    vec2 ripple = centered / max(radius, 0.001)
                        * (0.038 * sin(34.0 * radius - t * 3.0));
                    return sampleFrame(uv + ripple / scale);
                }
                if (uMode == 3) return sampleFrame(vec2(min(uv.x, 1.0 - uv.x), uv.y));
                if (uMode == 4) return sampleFrame(vec2(uv.x, min(uv.y, 1.0 - uv.y)));
                if (uMode == 5) {
                    float angle = radius > 0.0001 ? atan(centered.y, centered.x) : 0.0;
                    float sector = TAU / 6.0;
                    angle = abs(mod(angle + sector * 0.5, sector) - sector * 0.5);
                    return sampleFrame(0.5 + radius * vec2(cos(angle), sin(angle)) / scale);
                }
                if (uMode == 6) {
                    return sampleFrame(0.5 + (uv - 0.5) * (0.64 + 0.5 * dot(uv - 0.5, uv - 0.5)));
                }
                if (uMode == 7) {
                    float angle = 2.4 * (1.0 - smoothstep(0.0, 0.65, radius));
                    float c = cos(angle);
                    float s = sin(angle);
                    return sampleFrame(0.5 + mat2(c, s, -s, c) * centered / scale);
                }
                if (uMode == 8) {
                    float outer = smoothstep(0.14, 0.52, radius);
                    return mix(original, softBlur(uv, vec2(0.018) / scale), outer);
                }
                if (uMode == 9) return lineBlur(uv, (uv - 0.5) * 0.045);
                if (uMode == 10) return lineBlur(uv, vec2(0.009, 0.004));
                if (uMode == 11) {
                    vec2 offset = vec2(0.019, 0.006);
                    return tint(vec3(straightColor(sampleFrame(uv + offset)).r,
                        straightColor(original).g, straightColor(sampleFrame(uv - offset)).b), original.a);
                }
                if (uMode == 12) {
                    float band = floor(uv.y * 23.0);
                    float tick = floor(uPhase * 48.0);
                    float shift = (noise(vec2(band, tick)) - 0.5) * 0.19;
                    shift *= step(0.60, noise(vec2(band + 29.0, tick)));
                    vec2 moved = uv + vec2(shift, 0.0);
                    vec4 pixel = sampleFrame(moved);
                    return tint(vec3(straightColor(sampleFrame(moved + vec2(0.018, 0.0))).r,
                        straightColor(pixel).g, straightColor(sampleFrame(moved - vec2(0.018, 0.0))).b), pixel.a);
                }
                if (uMode == 13) {
                    float row = floor(uv.y * min(240.0, 1.0 / uTexel.y));
                    vec2 shifted = uv + vec2(0.009 * sin(row * 0.8 + t * 3.0), 0.0);
                    vec4 pixel = sampleFrame(shifted);
                    vec3 rgb = vec3(straightColor(sampleFrame(shifted + vec2(0.012, 0.0))).r,
                        straightColor(pixel).g, straightColor(sampleFrame(shifted - vec2(0.012, 0.0))).b);
                    rgb = mix(vec3(luminance(rgb)), rgb, 0.75);
                    float scan = 1.0 - 0.18 * mod(row, 2.0);
                    float grain = (noise(floor(uv / uTexel) + floor(uPhase * 72.0)) - 0.5) * 0.10;
                    return tint(clamp(rgb * scan * 0.87 + 0.055 + grain, 0.0, 1.0), pixel.a);
                }
                vec3 base = straightColor(original);
                if (uMode == 14) {
                    vec3 neon = mix(vec3(0.08, 0.9, 1.0), vec3(1.0, 0.15, 0.75), luminance(base));
                    return tint(base * 0.12 + neon * outline(uv) * 0.85, original.a);
                }
                if (uMode == 15) {
                    float paper = 0.78 + 0.22 * luminance(base);
                    return tint(vec3(paper * (1.0 - 0.86 * outline(uv))), original.a);
                }
                if (uMode == 16) {
                    vec3 ink = floor(clamp(base, 0.0, 1.0) * 5.0 + 0.5) / 5.0;
                    return tint(ink * (1.0 - outline(uv) * 0.88), original.a);
                }
                if (uMode == 17) {
                    float falloff = smoothstep(0.15, 0.62, length((uv - 0.5) * vec2(1.1, 1.0)));
                    return vec4(original.rgb * (1.0 - 0.82 * falloff), original.a);
                }
                if (uMode == 18) {
                    vec3 blurred = straightColor(softBlur(uv, vec2(0.019) / scale));
                    vec3 glow = blurred * smoothstep(0.18, 0.78, luminance(blurred));
                    return tint(clamp(base + glow * 0.7, 0.0, 1.0), original.a);
                }
                if (uMode == 19) {
                    vec2 cell = fract(centered * 42.0) - 0.5;
                    float dotSize = 0.08 + 0.48 * (1.0 - luminance(base));
                    float ink = 1.0 - smoothstep(dotSize - 0.035, dotSize + 0.035, length(cell));
                    return tint(mix(vec3(0.95, 0.93, 0.86), base * 0.48, ink), original.a);
                }
                if (uMode == 20) {
                    float grain = noise(floor(uv / uTexel) + floor(uPhase * 96.0)) - 0.5;
                    return tint(clamp(base + grain * 0.28, 0.0, 1.0), original.a);
                }
                return original;
            }

            void main() {
                vec4 original = sampleFrame(vUv);
                gl_FragColor = mix(original, applyEffect(vUv, original), uStrength);
            }
            """;
}
