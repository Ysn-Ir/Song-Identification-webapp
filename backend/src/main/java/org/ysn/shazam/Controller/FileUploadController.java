package org.ysn.shazam.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.ysn.shazam.Repository.SongRepository;
import org.ysn.shazam.Service.FileService;
import org.ysn.shazam.model.Song;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@RestController
@RequestMapping("/api")
// Remember to match this to your React port (e.g., 3000, 5173, etc.)
@CrossOrigin(origins = "http://localhost:5173")
public class FileUploadController {


    @Autowired
    FileService fileService;
    @Autowired
    SongRepository songRepository;
    @Value("${file.upload-dir}")
    private String uploadDir;


    @PostMapping("/upload")
    public ResponseEntity<String> uploadFiles(@RequestParam("file") MultipartFile[] files) {
        try {
            for (MultipartFile file : files) {
                // Assuming this saves the file and we ignore its individual return for now
                fileService.saveFile(file, uploadDir);

            }
            return ResponseEntity.ok("All files uploaded successfully!");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error uploading files");
        }
    }

}