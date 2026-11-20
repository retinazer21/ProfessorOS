package com.professoros.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Windows current-user DPAPI storage. The file contains only authenticated ciphertext. */
public final class DesktopSessionStore implements PortalPublishingClient.RefreshStore, AutoCloseable {
    private final Path file;
    private final FileChannel lock;

    public static DesktopSessionStore open(String origin) throws Exception {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) return null;
        if (origin == null || origin.isBlank()) return null;
        String profile = System.getenv("LOCALAPPDATA");
        if (profile == null || profile.isBlank()) throw new IOException("Windows session storage is unavailable.");
        var digest = MessageDigest.getInstance("SHA-256").digest(origin.getBytes(StandardCharsets.UTF_8));
        return new DesktopSessionStore(Path.of(profile,"ProfessorOS","sessions"), HexFormat.of().formatHex(digest));
    }

    DesktopSessionStore(Path directory, String project) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve(project + ".dpapi");
        lock = FileChannel.open(directory.resolve(project + ".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try {
            if (lock.tryLock() == null) throw new IOException("A Professor OS session is already open on this device.");
        } catch (IOException | OverlappingFileLockException failure) {
            lock.close();
            throw new IOException("Close the other Professor OS window before opening this session.",failure);
        }
    }

    @Override public String load() throws IOException {
        if (Files.notExists(file)) return null;
        if (Files.size(file)>8192) throw new IOException("Saved session data is invalid.");
        return crypt(Files.readString(file,StandardCharsets.US_ASCII),false);
    }

    @Override public void save(String token) throws IOException {
        String protectedValue = crypt(token,true);
        Path temporary = Files.createTempFile(file.getParent(),"session-",".tmp");
        try {
            Files.writeString(temporary,protectedValue,StandardCharsets.US_ASCII);
            try (var channel = FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    @Override public void clear() throws IOException { Files.deleteIfExists(file); }
    @Override public void close() throws IOException { lock.close(); }

    private static String crypt(String input, boolean protect) throws IOException {
        String script = "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Security; $s=[Console]::In.ReadToEnd();"
                + (protect
                ? "$b=[Text.Encoding]::UTF8.GetBytes($s); [Console]::Write([Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect($b,$null,'CurrentUser')))"
                : "$b=[Convert]::FromBase64String($s); [Console]::Write([Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect($b,$null,'CurrentUser')))");
        var executable = Path.of(System.getenv("SystemRoot"),"System32","WindowsPowerShell","v1.0","powershell.exe");
        String command = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        var process = new ProcessBuilder(executable.toString(),"-NoLogo","-NoProfile","-NonInteractive","-WindowStyle","Hidden",
                "-EncodedCommand",command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.US_ASCII)); }
            if (!process.waitFor(10,TimeUnit.SECONDS) || process.exitValue()!=0)
                throw new IOException("Windows could not access the protected session.");
            var result = new String(process.getInputStream().readNBytes(8193),StandardCharsets.US_ASCII);
            if (result.isBlank() || result.length()>8192) throw new IOException("Windows returned invalid session data.");
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Protected session storage was interrupted.",interrupted);
        } finally { process.destroyForcibly(); }
    }
}
