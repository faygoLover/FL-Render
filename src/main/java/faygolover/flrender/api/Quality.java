package faygolover.flrender.api;

/** Сколько графика эффектов может стоить компьютеру. LOW — простые плоские способы (слабый ПК). */
public enum Quality {
    LOW, MEDIUM, HIGH, ULTRA;

    public Quality lower() {
        return this == LOW ? LOW : values()[ordinal() - 1];
    }

    public boolean atLeast(Quality q) {
        return ordinal() >= q.ordinal();
    }

    public static Quality min(Quality a, Quality b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
