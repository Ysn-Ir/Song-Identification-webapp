package org.ysn.shazam.Service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
@Service
public class ShazamService {
    public String runProgram(String filepath,String command) {
        try {
            String exepath="C:/Users/khali/source/repos/shazam/x64/Debug/shazam.exe";
            ProcessBuilder pb = new ProcessBuilder(exepath,command,filepath);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            StringBuilder result = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line).append("/n");
            }

            process.waitFor();

            return result.toString();

        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
