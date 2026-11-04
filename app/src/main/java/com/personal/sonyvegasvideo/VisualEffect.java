package com.personal.sonyvegasvideo;

/** Stable project IDs and labels shared by the effect picker and renderer. */
enum VisualEffect {
    NONE("none", "Без эффекта", 0),
    WAVE("wave", "Волна", 1),
    RIPPLE("ripple", "Рябь", 2),
    MIRROR("mirror", "Зеркало по горизонтали", 3),
    MIRROR_VERTICAL("mirror_vertical", "Зеркало по вертикали", 4),
    KALEIDOSCOPE("kaleidoscope", "Калейдоскоп", 5),
    FISHEYE("fisheye", "Рыбий глаз", 6),
    SWIRL("swirl", "Вихрь", 7),
    FOCUS_BLUR("focus_blur", "Фокус в центре", 8),
    ZOOM_BLUR("zoom_blur", "Размытие при приближении", 9),
    MOTION_BLUR("motion_blur", "Направленное размытие", 10),
    RGB_SPLIT("rgb_split", "Разделение RGB", 11),
    GLITCH("glitch", "Глитч", 12),
    VHS("vhs", "Видеокассета VHS", 13),
    NEON("neon", "Неоновые контуры", 14),
    SKETCH("sketch", "Карандашный рисунок", 15),
    COMIC("comic", "Комикс", 16),
    VIGNETTE("vignette", "Виньетка", 17),
    BLOOM("bloom", "Свечение", 18),
    HALFTONE("halftone", "Печатные точки", 19),
    GRAIN("grain", "Кинозерно", 20);

    private static final VisualEffect[] OPTIONS = values();

    final String id;
    final String label;
    final int shaderMode;

    VisualEffect(String id, String label, int shaderMode) {
        this.id = id;
        this.label = label;
        this.shaderMode = shaderMode;
    }

    static VisualEffect fromId(String id) {
        for (VisualEffect effect : OPTIONS) {
            if (effect.id.equals(id)) return effect;
        }
        return NONE;
    }

    boolean usesTime() {
        return this == WAVE || this == RIPPLE || this == GLITCH || this == VHS || this == GRAIN;
    }
}
