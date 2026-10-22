package dev.combatdata.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class Favorites {
    private final Set<String> names = new TreeSet<>();

    public boolean has(String stem) {
        return names.contains(stem);
    }

    public void set(String stem, boolean favorite) {
        if (favorite) names.add(stem);
        else names.remove(stem);
    }

    public void renamed(String from, String to) {
        if (names.remove(from)) names.add(to);
    }

    public int size() {
        return names.size();
    }

    public static Favorites load(Path file) {
        Favorites f = new Favorites();
        try {
            if (Files.exists(file) && Json.parse(Files.readString(file, StandardCharsets.UTF_8)) instanceof List<?> l)
                for (Object o : l) if (o instanceof String s && !s.isBlank()) f.names.add(s);
        } catch (IOException | RuntimeException ignored) {
        }
        return f;
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(new ArrayList<>(names)), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
