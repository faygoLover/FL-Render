package faygolover.flrender.config;

/**
 * Дальность цветного света (подкраски от источников), в чанках: как дальность прорисовки — свет и его цвет видны
 * одинаково далеко; меньше — для слабого компьютера. Потолок всё равно задаёт качество.
 */
public enum ColorRange {
    RENDER(0), C12(12), C8(8), C6(6), C4(4), C2(2);

    private final int chunks;

    ColorRange(int chunks) {
        this.chunks = chunks;
    }

    /** Предел в чанках от камеры; 0 — по дальности прорисовки. */
    public int chunks() {
        return chunks;
    }
}
