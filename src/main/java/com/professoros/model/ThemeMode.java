package com.professoros.model;

public enum ThemeMode {
    LIGHT, DARK, SYSTEM, SAKURA, AURORA, OCEANIC, EMBER, FOREST, NEON_NOIR, LAVENDER_HAZE, SOLARIS;
    public boolean dark(boolean systemDark) {
        return switch (this) {
            case SYSTEM -> systemDark;
            case LIGHT, SAKURA, LAVENDER_HAZE, SOLARIS -> false;
            default -> true;
        };
    }
    public ThemeMode next() { return switch(this) { case LIGHT -> DARK; case DARK -> SYSTEM; default -> LIGHT; }; }
    @Override public String toString() {
        return switch(this) { case LIGHT -> "Light"; case DARK -> "Dark"; case SYSTEM -> "System"; default -> name(); };
    }
}
