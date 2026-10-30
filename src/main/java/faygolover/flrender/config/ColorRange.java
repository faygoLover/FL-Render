package faygolover.flrender.config;

/**
 * Дальность цветного света (подкраски от источников): как дальность прорисовки — свет и его цвет видны одинаково
 * далеко; меньше — для слабого компьютера (меньше памяти и расчёта). Потолок всё равно задаёт качество.
 */
public enum ColorRange {
    RENDER(0), FAR(128), MEDIUM(64), NEAR(32);

    private final int blocks;

    ColorRange(int blocks) {
        this.blocks = blocks;
    }

    /** Предел в блоках от камеры; 0 — по дальности прорисовки. */
    public int blocks() {
        return blocks;
    }
}
