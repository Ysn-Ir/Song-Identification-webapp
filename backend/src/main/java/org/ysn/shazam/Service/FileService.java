package org.ysn.shazam.Service;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.ysn.shazam.Repository.SongRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
public class FileService {
    private final SongRepository songRepository;

    public FileService(SongRepository songRepository) {
        this.songRepository = songRepository;
    }

    public ResponseEntity<String> saveFile(MultipartFile file , String uploadDir) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("File is empty");
            }

            // 1. Get the path to the upload directory
            Path uploadPath = Paths.get(uploadDir);

            // 2. Create the directory if it doesn't already exist
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // 3. Create the full path to the specific file we are saving
            Path filePath = uploadPath.resolve(file.getOriginalFilename());

            // 4. Save the file! (REPLACE_EXISTING overwrites a file if it has the same name)
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            return ResponseEntity.ok("File saved successfully to: " + filePath.toAbsolutePath());

        } catch (IOException e) {
            // Catching IOException is required when working with the file system
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Could not save the file: " + e.getMessage());
        }
    }

}
