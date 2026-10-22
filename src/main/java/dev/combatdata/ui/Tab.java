package dev.combatdata.ui;

interface Tab {
    void render(Ui ui, int x, int y, int w, int h);

    default void onShow() {
    }

    default boolean scrolled(double mx, double my, double dy) {
        return false;
    }

    default boolean pressed(double mx, double my) {
        return false;
    }

    default void dragged(double mx, double my, double dx) {
    }

    default void released(double mx, double my) {
    }
}
