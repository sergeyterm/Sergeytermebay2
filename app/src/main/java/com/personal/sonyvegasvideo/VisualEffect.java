package com.personal.sonyvegasvideo;

/** Stable project IDs and labels shared by the effect picker and renderer. */
enum VisualEffect {
    NONE("none", "Без эффекта", "Исходный кадр", 0),
    WAVE("wave", "Волна", "Изгибает кадр бегущими волнами", 1),
    RIPPLE("ripple", "Рябь", "Волны расходятся от центра", 2),
    MIRROR("mirror", "Зеркало по горизонтали", "Отражает левую половину вправо", 3),
    MIRROR_VERTICAL("mirror_vertical", "Зеркало по вертикали", "Отражает нижнюю половину вверх", 4),
    KALEIDOSCOPE("kaleidoscope", "Калейдоскоп", "Собирает кадр в симметричный узор", 5),
    FISHEYE("fisheye", "Рыбий глаз", "Увеличивает центр и изгибает края", 6),
    SWIRL("swirl", "Вихрь", "Закручивает изображение вокруг центра", 7),
    FOCUS_BLUR("focus_blur", "Фокус в центре", "Размывает края и оставляет центр резким", 8),
    ZOOM_BLUR("zoom_blur", "Размытие при приближении", "Растягивает детали лучами от центра", 9),
    MOTION_BLUR("motion_blur", "Направленное размытие", "Размазывает детали по диагонали", 10),
    RGB_SPLIT("rgb_split", "Разделение RGB", "Раздвигает цветовые каналы по краям деталей", 11),
    GLITCH("glitch", "Глитч", "Сдвигает полосы, как при цифровом сбое", 12),
    VHS("vhs", "Видеокассета VHS", "Добавляет строки, шум и цветовые помехи", 13),
    NEON("neon", "Неоновые контуры", "Выделяет яркие контуры на тёмном фоне", 14),
    SKETCH("sketch", "Карандашный рисунок", "Делает тёмные контуры на светлом фоне", 15),
    COMIC("comic", "Комикс", "Упрощает цвета и подчёркивает линии", 16),
    VIGNETTE("vignette", "Виньетка", "Затемняет углы кадра", 17),
    BLOOM("bloom", "Свечение", "Добавляет ореол вокруг ярких участков", 18),
    HALFTONE("halftone", "Печатные точки", "Стилизует изображение под точечную печать", 19),
    GRAIN("grain", "Кинозерно", "Добавляет подвижное плёночное зерно", 20);

    private static final VisualEffect[] OPTIONS = values();

    final String id;
    final String label;
    final String description;
    final int shaderMode;

    VisualEffect(String id, String label, String description, int shaderMode) {
        this.id = id;
        this.label = label;
        this.description = description;
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
