package org.ysn.shazam.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

@Service
public class ShazamService {

    private static final Logger log = LoggerFactory.getLogger(ShazamService.class);

    @Value("${shazam.executable.path:C:/Users/khali/source/repos/shazam/x64/Debug/shazam.exe}")
    private String exePath;

    @Value("${ffmpeg.path:ffmpeg}")
    private String configuredFfmpegPath;

    public String findFfmpegExecutable() {
        String[] candidates = {
                configuredFfmpegPath,
                "ffmpeg",
                "C:\\Users\\khali\\AppData\\Local\\Microsoft\\WinGet\\Packages\\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\\ffmpeg-8.0.1-full_build\\bin\\ffmpeg.exe",
                "/usr/bin/ffmpeg"
        };
        for (String c : candidates) {
            try {
                File f = new File(c);
                if (f.exists() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            } catch (Exception ignored) {}
        }
        return "ffmpeg";
    }

    /**
     * Normalizes any input audio file (MP3, WAV, M4A, WEBM, FLAC, OGG) to 16kHz mono 16-bit PCM WAV
     * with EBU R128 loudness normalization (-14 LUFS).
     * This ensures low-volume microphone recordings and quiet ambient music exceed the C++ binary's
     * peak detection threshold (mag > 2.0).
     */
    public String normalizeAudio(String inputPath) {
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            return inputPath;
        }

        String ffmpeg = findFfmpegExecutable();
        File parentDir = inputFile.getParentFile();
        if (parentDir == null) {
            parentDir = new File(".");
        }
        File normalizedFile = new File(parentDir, "norm_" + System.currentTimeMillis() + "_" + inputFile.getName() + ".wav");

        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(ffmpeg);
            cmd.add("-y");
            cmd.add("-i");
            cmd.add(inputFile.getAbsolutePath());
            cmd.add("-ar");
            cmd.add("16000");
            cmd.add("-ac");
            cmd.add("1");
            cmd.add("-af");
            cmd.add("loudnorm=I=-14:TP=-1:LRA=11");
            cmd.add("-c:a");
            cmd.add("pcm_s16le");
            cmd.add(normalizedFile.getAbsolutePath());

            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                while (r.readLine() != null) {} // Drain output
            }
            int code = proc.waitFor();
            if (code == 0 && normalizedFile.exists() && normalizedFile.length() > 100) {
                log.info("Audio normalized successfully: {} -> {}", inputPath, normalizedFile.getAbsolutePath());
                return normalizedFile.getAbsolutePath();
            } else {
                log.warn("FFmpeg normalization returned exit code {}, falling back to original: {}", code, inputPath);
                return inputPath;
            }
        } catch (Exception e) {
            log.warn("FFmpeg normalization error, falling back to original: {}", e.getMessage());
            return inputPath;
        }
    }

    /**
     * Normalizes the audio to reference broadcast loudness & 16kHz mono WAV, runs the C++ engine,
     * and automatically cleans up the normalized intermediate file.
     */
    public String runProgramWithNormalization(String filepath, String command) {
        String normalizedPath = normalizeAudio(filepath);
        try {
            return runProgram(normalizedPath, command);
        } finally {
            if (normalizedPath != null && !normalizedPath.equals(filepath)) {
                try {
                    new File(normalizedPath).delete();
                } catch (Exception ignored) {}
            }
        }
    }

    public String runProgram(String filepath, String command) {
        if (command == null || command.trim().isEmpty()) {
            command = "getFingerprint";
        }

        File exeFile = new File(exePath);
        if (!exeFile.exists()) {
            throw new IllegalStateException("Shazam C++ executable not found at configured path: " + exePath);
        }

        File audioFile = new File(filepath);
        if (!audioFile.exists()) {
            throw new IllegalArgumentException("Target audio file does not exist: " + filepath);
        }

        try {
            log.info("Executing Shazam binary [{}] with command [{}] on [{}]", exePath, command, filepath);
            ProcessBuilder pb = new ProcessBuilder(exePath, command, filepath);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            // Read standard output
            StringBuilder stdout = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (stdout.length() > 0) {
                        stdout.append("\n");
                    }
                    stdout.append(line);
                }
            }

            // Read standard error
            StringBuilder stderr = new StringBuilder();
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    if (stderr.length() > 0) {
                        stderr.append("\n");
                    }
                    stderr.append(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String errorMsg = stderr.length() > 0 ? stderr.toString() : stdout.toString();
                log.error("Shazam C++ binary exited with code {}: {}", exitCode, errorMsg);
                throw new RuntimeException("C++ process exited with error code " + exitCode + ": " + errorMsg);
            }

            return stdout.toString();

        } catch (Exception e) {
            log.error("Error running Shazam C++ process", e);
            throw new RuntimeException("Failed to run Shazam audio analysis: " + e.getMessage(), e);
        }
    }
}
