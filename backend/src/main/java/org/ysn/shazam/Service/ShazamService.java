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
