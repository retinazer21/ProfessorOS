package com.professoros.service;

import com.professoros.model.ThemeMode;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.logging.*;

/** Local appearance preference, independent of academic data and JavaFX. */
public final class ThemePreferences {
    private final Path file;
    public ThemePreferences(Path file) { this.file=file.toAbsolutePath().normalize(); }
    public record Choice(ThemeMode mode, String accent, boolean reduced) {}
    public ThemeMode load() { return read().mode(); }
    public Choice read() {
        if(!Files.exists(file)) { return new Choice(ThemeMode.SYSTEM, "accent", false); }
        try(var input=Files.newInputStream(file)) {
            var properties=new Properties(); properties.load(input);
            return new Choice(ThemeMode.valueOf(properties.getProperty("theme","SYSTEM")), properties.getProperty("accent","accent"), Boolean.parseBoolean(properties.getProperty("reduced","false")));
        } catch(IOException | IllegalArgumentException failure) {
            Logger.getLogger(getClass().getName()).log(Level.WARNING,"Cannot read appearance preference; using System",failure);
            return new Choice(ThemeMode.SYSTEM, "accent", false);
        }
    }
    public void save(ThemeMode mode) throws IOException {
        var old = read(); save(new Choice(mode, old.accent(), old.reduced()));
    }
    public void save(Choice choice) throws IOException {
        java.util.Objects.requireNonNull(choice.mode()); Files.createDirectories(file.getParent());
        Path temporary=Files.createTempFile(file.getParent(),"appearance-",".tmp");
        try {
            var properties=new Properties(); properties.setProperty("theme",choice.mode().name());
            properties.setProperty("accent",choice.accent()); properties.setProperty("reduced",Boolean.toString(choice.reduced()));
            try(var out=Files.newOutputStream(temporary)) { properties.store(out,"Professor OS appearance"); }
            try { Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException unsupported) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
