package dev.combatdata.ui;

import java.util.List;
import java.util.function.Consumer;

/** To keep the modal open with an error, the button action writes it to error. */
public final class Modal {
    public record Btn(String label, Ui.Kind kind, Consumer<String> action) {
    }

    final String title;
    final List<String> lines;
    final String input;
    final List<Btn> buttons;
    /** Extra controls under the lines (toggles, choices); null for a plain modal. */
    public interface Body {
        int height(int w);

        void render(Ui ui, int x, int y, int w);
    }

    public String error;
    public int maxLength = 64;
    public Body body;
    public int width = 260;

    public Modal(String title, List<String> lines, String input, List<Btn> buttons) {
        this.title = title;
        this.lines = lines;
        this.input = input;
        this.buttons = buttons;
    }
}
