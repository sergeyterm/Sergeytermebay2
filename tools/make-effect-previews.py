#!/usr/bin/env python3
"""Regenerate the in-app sample atlas with Pillow and a headless Mesa GLES2 context."""
import ctypes as C
import ctypes.util
import json
from pathlib import Path
import re
import textwrap

from PIL import Image, ImageDraw, ImageEnhance, ImageOps

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/personal/sonyvegasvideo"
WIDTH, HEIGHT, COLUMNS = 144, 90, 6


def landscape(alternate=False):
    size = 3
    image = Image.new("RGB", (WIDTH * size, HEIGHT * size))
    draw = ImageDraw.Draw(image)
    upper, lower = ((21, 29, 66), (133, 74, 102)) if not alternate else ((31, 21, 63), (153, 75, 71))
    for y in range(HEIGHT * size):
        ratio = min(1, y / (57 * size))
        draw.line((0, y, WIDTH * size, y),
                  fill=tuple(round(a + (b - a) * ratio) for a, b in zip(upper, lower)))

    def polygon(points, color):
        draw.polygon([(x * size, y * size) for x, y in points], fill=color)

    def line(points, color, width=1):
        draw.line([(x * size, y * size) for x, y in points], fill=color, width=width * size)

    sun_x = 43 if not alternate else 105
    draw.ellipse(((sun_x - 17) * size, 10 * size, (sun_x + 17) * size, 44 * size),
                 fill="#ffd16b" if not alternate else "#f59875")
    for y in range(32, 46, 4):
        line([(sun_x - 16, y), (sun_x + 16, y)], "#cb727d")
    if not alternate:
        polygon([(0, 54), (28, 32), (43, 42), (76, 25), (106, 49), (124, 35), (144, 49), (144, 70), (0, 70)], "#3a386b")
        polygon([(64, 31), (76, 25), (91, 37), (79, 34), (75, 38), (71, 32)], "#a9c5e4")
        polygon([(0, 61), (22, 47), (38, 56), (60, 43), (91, 61), (115, 49), (144, 56), (144, 74), (0, 74)], "#254669")
    else:
        for x, w, top in [(1, 15, 39), (17, 8, 30), (27, 19, 45), (48, 13, 25),
                           (63, 20, 38), (85, 14, 51), (101, 9, 43), (112, 15, 30), (129, 15, 47)]:
            polygon([(x, top), (x+w, top), (x+w, 63), (x, 63)], "#383361")
            for u in range(x + 2, x + w - 1, 4):
                for v in range(top + 4, 62, 6):
                    line([(u, v), (u + 1, v)], "#ffc08a")

    polygon([(0, 62), (144, 62), (144, 90), (0, 90)], "#183850" if not alternate else "#392e55")
    for i, y in enumerate([65, 69, 75, 83, 89]):
        line([(0, y), (144, y)], ("#33758f", "#58aab5", "#3d869a")[i % 3])
    for i, x in enumerate([4, 26, 49, 72, 95, 119, 141]):
        line([(64 + i * 3, 63), (x, 89)], "#396378" if not alternate else "#9b5577")

    polygon([(94, 84), (108, 63), (130, 63), (140, 84)], "#53c8c0")
    polygon([(108, 63), (112, 57), (125, 57), (130, 63)], "#e5fbdf")
    polygon([(120, 63), (130, 63), (140, 84), (129, 84)], "#19839d")
    polygon([(7, 84), (27, 72), (36, 72), (55, 84)], "#ee786d")
    polygon([(28, 74), (33, 74), (45, 84), (31, 84)], "#ffda96")
    return image.resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS).convert("RGBA")


class Renderer:
    def __init__(self):
        self.egl = C.CDLL(ctypes.util.find_library("EGL") or "libEGL.so.1")
        ptr, integer, uint = C.c_void_p, C.c_int, C.c_uint
        display = self.egl_fn("eglGetPlatformDisplay", ptr, uint, ptr, ptr)(0x31DD, None, None)
        assert self.egl_fn("eglInitialize", uint, ptr, ptr, ptr)(display, None, None), "EGL initialization failed"
        assert self.egl_fn("eglBindAPI", uint, uint)(0x30A0)
        config = ptr()
        count = integer()
        attrs = (integer * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
        assert self.egl_fn("eglChooseConfig", uint, ptr, ptr, ptr, integer, ptr)(
            display, attrs, C.byref(config), 1, C.byref(count)) and count.value
        surface = self.egl_fn("eglCreatePbufferSurface", ptr, ptr, ptr, ptr)(
            display, config, (integer * 5)(0x3057, WIDTH, 0x3056, HEIGHT, 0x3038))
        context = self.egl_fn("eglCreateContext", ptr, ptr, ptr, ptr, ptr)(
            display, config, None, (integer * 3)(0x3098, 2, 0x3038))
        assert self.egl_fn("eglMakeCurrent", uint, ptr, ptr, ptr, ptr)(display, surface, surface, context)
        self.address = self.egl_fn("eglGetProcAddress", ptr, C.c_char_p)
        self.vertices = (C.c_float * 16)(-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1)
        texture = uint()
        self.gl("glGenTextures", None, integer, ptr)(1, C.byref(texture))
        self.gl("glBindTexture", None, uint, uint)(0x0DE1, texture)
        parameter = self.gl("glTexParameteri", None, uint, uint, integer)
        for name, value in ((0x2800, 0x2601), (0x2801, 0x2601), (0x2802, 0x812F), (0x2803, 0x812F)):
            parameter(0x0DE1, name, value)
        self.gl("glViewport", None, integer, integer, integer, integer)(0, 0, WIDTH, HEIGHT)

    def egl_fn(self, name, result, *args):
        fn = getattr(self.egl, name)
        fn.restype, fn.argtypes = result, args
        return fn

    def gl(self, name, result, *args):
        address = self.address(name.encode())
        assert address, name
        return C.CFUNCTYPE(result, *args)(address)

    def program(self, vertex, fragment):
        u, i, p = C.c_uint, C.c_int, C.c_void_p
        program = self.gl("glCreateProgram", u)()
        for kind, source in ((0x8B31, vertex), (0x8B30, fragment)):
            shader = self.gl("glCreateShader", u, u)(kind)
            string = C.c_char_p(source.encode())
            self.gl("glShaderSource", None, u, i, p, p)(shader, 1, C.byref(string), None)
            self.gl("glCompileShader", None, u)(shader)
            ok = i()
            self.gl("glGetShaderiv", None, u, u, p)(shader, 0x8B81, C.byref(ok))
            if not ok.value:
                log = C.create_string_buffer(8192)
                self.gl("glGetShaderInfoLog", None, u, i, p, p)(shader, 8192, None, log)
                raise RuntimeError(log.value.decode())
            self.gl("glAttachShader", None, u, u)(program, shader)
        self.gl("glLinkProgram", None, u)(program)
        ok = i()
        self.gl("glGetProgramiv", None, u, u, p)(program, 0x8B82, C.byref(ok))
        assert ok.value, "Shader linking failed"
        self.gl("glUseProgram", None, u)(program)
        index = self.gl("glGetAttribLocation", i, u, C.c_char_p)(program, b"aFramePosition")
        self.gl("glEnableVertexAttribArray", None, u)(index)
        self.gl("glVertexAttribPointer", None, u, i, u, u, i, p)(index, 4, 0x1406, 0, 0, self.vertices)
        return program

    def render(self, program, image, uniforms):
        u, i, p = C.c_uint, C.c_int, C.c_void_p
        pixels = image.transpose(Image.Transpose.FLIP_TOP_BOTTOM).tobytes()
        self.gl("glTexImage2D", None, u, i, i, i, i, i, u, u, C.c_char_p)(
            0x0DE1, 0, 0x1908, WIDTH, HEIGHT, 0, 0x1908, 0x1401, pixels)
        for name, value in uniforms.items():
            index = self.gl("glGetUniformLocation", i, u, C.c_char_p)(program, name.encode())
            if isinstance(value, int):
                self.gl("glUniform1i", None, i, i)(index, value)
            else:
                values = value if isinstance(value, tuple) else (value,)
                self.gl(f"glUniform{len(values)}f", None, i, *([C.c_float] * len(values)))(index, *values)
        self.gl("glDrawArrays", None, u, i, i)(5, 0, 4)
        out = (C.c_ubyte * (WIDTH * HEIGHT * 4))()
        self.gl("glReadPixels", None, i, i, i, i, u, u, p)(0, 0, WIDTH, HEIGHT, 0x1908, 0x1401, out)
        assert self.gl("glGetError", u)() == 0
        return Image.frombytes("RGBA", (WIDTH, HEIGHT), bytes(out)).transpose(Image.Transpose.FLIP_TOP_BOTTOM)


def preview_tiles():
    before, alternate = landscape(), landscape(alternate=True)
    renderer = Renderer()
    code = (JAVA / "CreativeVideoEffect.java").read_text()
    sources = [textwrap.dedent(re.search(rf'String {name} = """\n(.*?)"""', code, re.S)[1])
               for name in ("VERTEX", "FRAGMENT")]
    program = renderer.program(*sources)
    modes = [int(value) for value in re.findall(r', (\d+)\)[,;]', (JAVA / "VisualEffect.java").read_text())]
    assert modes == list(range(21)), "Update atlas positions when effect modes change"
    uniforms = dict(uTexture=0, uTexel=(1/WIDTH, 1/HEIGHT), uAspect=WIDTH/HEIGHT, uStrength=1.0, uPhase=0.37)
    yield before
    for mode in modes[1:]:
        yield renderer.render(program, before, dict(uniforms, uMode=mode))

    old = (JAVA / "VideoEffects.java").read_text()
    sources = []
    for name, stop in (("VERTEX", "private static final String FRAGMENT"), ("FRAGMENT", "\n}")):
        literal = old.split(f"private static final String {name}=", 1)[1].split(stop, 1)[0]
        sources.append("".join(json.loads(value) for value in re.findall(r'"(?:\\.|[^"\\])*"', literal)))
    program = renderer.program(*sources)
    uniforms = dict(uTexSampler=0, uPixelStep=(1/WIDTH, 1/HEIGHT), uMosaic=0.0,
                    uChroma=0, uKey=(0.0, 1.0, 0.0), uThreshold=0.25, uOpacity=1.0,
                    uAlphaOnly=1, uProgress=0.56)
    for mode in range(1, 5):
        front = renderer.render(program, before, dict(uniforms, uTransition=mode))
        yield Image.alpha_composite(alternate, front)

    rgb = before.convert("RGB")
    mono = ImageOps.grayscale(rgb)
    yield mono.convert("RGB")
    yield ImageOps.colorize(mono, "#251909", "#ffdda4")
    yield ImageEnhance.Contrast(ImageEnhance.Color(rgb).enhance(1.55)).enhance(1.12)
    r, g, b = rgb.split()
    yield Image.merge("RGB", (r.point(lambda c: int(c * .85)), g, b.point(lambda c: min(255, int(c * 1.14)))))
    yield Image.merge("RGB", (r.point(lambda c: min(255, int(c * 1.15))), g, b.point(lambda c: int(c * .81))))
    yield ImageOps.invert(rgb)
    yield Image.blend(ImageEnhance.Color(rgb).enhance(.55), Image.new("RGB", rgb.size, "#c18e77"), .22)


def main():
    tiles = list(preview_tiles())
    assert len(tiles) == 32
    atlas = Image.new("RGB", (WIDTH * COLUMNS, HEIGHT * ((len(tiles) + COLUMNS - 1) // COLUMNS)))
    for i, tile in enumerate(tiles):
        atlas.paste(tile, (i % COLUMNS * WIDTH, i // COLUMNS * HEIGHT))
    output = ROOT / "app/src/main/res/drawable-nodpi/effect_previews.webp"
    output.parent.mkdir(exist_ok=True)
    atlas.save(output, quality=92, method=6)
    print(f"{len(tiles)} sample frames, {output.stat().st_size} bytes")


if __name__ == "__main__":
    main()
