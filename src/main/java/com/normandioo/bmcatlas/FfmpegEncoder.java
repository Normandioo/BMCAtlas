package com.normandioo.bmcatlas;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The preferred encoder: an .mp4 (H.264) made by the player's own ffmpeg, fed the raw frames on its standard input. The
 * executable is looked for in the path set in the settings, on the PATH, and in the usual places; nothing is downloaded.
 * No Minecraft classes here.
 */
final class FfmpegEncoder implements VideoEncoder {
    private final String exe;
    private Process process;
    private OutputStream in;
    private Path out;
    private final Deque<String> errLines = new ArrayDeque<>();

    FfmpegEncoder(String exe) {
        this.exe = exe;
    }

    /** The command line (a list of arguments, no shell). */
    static List<String> args(String exe, int w, int h, int fps, Path out) {
        return List.of(exe, "-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-r", String.valueOf(fps), "-i", "-",
                "-an", "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                out.toString());
    }

    /** Where ffmpeg may be, in order: the settings' path, each PATH folder, the usual install places, the WinGet packages. */
    static List<String> candidates(String fromSettings, String pathEnv, boolean windows, Path wingetPackages) {
        Set<String> out = new LinkedHashSet<>();
        if (fromSettings != null && !fromSettings.isBlank()) out.add(fromSettings.trim());
        String name = windows ? "ffmpeg.exe" : "ffmpeg";
        if (pathEnv != null) {
            for (String dir : pathEnv.split(windows ? ";" : ":")) {
                if (!dir.isBlank()) out.add(new File(dir.trim().replace("\"", ""), name).getPath());
            }
        }
        if (windows) {
            out.add("C:\\ffmpeg\\bin\\ffmpeg.exe");
            out.add("C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe");
            if (wingetPackages != null) {   // WinGet's install folder is named by version and is not on the game's PATH
                out.add(wingetPackages.resolveSibling("Links").resolve(name).toString());
                out.addAll(winget(wingetPackages, name));
            }
        } else {
            out.add("/usr/bin/ffmpeg");
            out.add("/usr/local/bin/ffmpeg");
            out.add("/opt/homebrew/bin/ffmpeg");
            out.add("/snap/bin/ffmpeg");
        }
        return new ArrayList<>(out);
    }

    /** The ffmpeg.exe files under the WinGet packages folder (the Gyan.FFmpeg package), newest folder name first. */
    static List<String> winget(Path packages, String name) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(packages)) {
            for (Path d : dirs.filter(p -> p.getFileName().toString().startsWith("Gyan.FFmpeg")).toList()) {
                try (Stream<Path> files = Files.walk(d, 4)) {
                    files.filter(f -> f.getFileName().toString().equalsIgnoreCase(name))
                            .sorted(Comparator.reverseOrder()).forEach(f -> out.add(f.toString()));
                }
            }
        } catch (IOException e) {
            // no WinGet packages folder: nothing to add
        }
        return out;
    }

    /** The first candidate that exists and answers "ffmpeg -version", or null. Takes a moment: call it off the game thread. */
    static String find(String fromSettings) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String local = System.getenv("LOCALAPPDATA");
        Path packages = windows && local != null ? Path.of(local, "Microsoft", "WinGet", "Packages") : null;
        for (String c : candidates(fromSettings, System.getenv("PATH"), windows, packages)) {
            if (!new File(c).isFile()) continue;
            try {
                Process p = new ProcessBuilder(c, "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return c;
                p.destroyForcibly();
            } catch (IOException | InterruptedException | RuntimeException e) {
                // not this one
            }
        }
        return null;
    }

    @Override
    public void start(int w, int h, int fps, Path out) throws IOException {
        this.out = out;
        Files.createDirectories(out.getParent());
        process = new ProcessBuilder(args(exe, w, h, fps, out)).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        in = new BufferedOutputStream(process.getOutputStream(), 1 << 20);
        Thread err = new Thread(() -> drain(process.getErrorStream()), "bmcatlas-ffmpeg-log");   // ffmpeg stalls if nobody reads this
        err.setDaemon(true);
        err.start();
    }

    private void drain(InputStream s) {
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(s, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (errLines) {
                    errLines.addLast(line);
                    if (errLines.size() > 20) errLines.removeFirst();
                }
            }
        } catch (IOException ignored) {
            // the process ended
        }
    }

    private String lastErrors() {
        synchronized (errLines) {
            return String.join("\n", errLines);
        }
    }

    @Override
    public void frame(byte[] rgba) throws IOException {
        try {
            in.write(rgba);
        } catch (IOException e) {
            throw new IOException("ffmpeg stopped taking frames:\n" + lastErrors(), e);
        }
    }

    @Override
    public void finish() throws IOException {
        try {
            in.close();
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("ffmpeg did not finish in time");
            }
            if (process.exitValue() != 0) throw new IOException("ffmpeg failed (" + process.exitValue() + "):\n" + lastErrors());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    @Override
    public void abort() {
        if (process != null) {
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (out != null) Files.deleteIfExists(out);
        } catch (IOException ignored) {
            // left behind
        }
    }

    @Override
    public Path output() {
        return out;
    }
}
