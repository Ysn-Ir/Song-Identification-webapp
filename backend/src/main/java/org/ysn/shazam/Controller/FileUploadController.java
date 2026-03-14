package org.ysn.shazam.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.ysn.shazam.Service.FileService;

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
    // This grabs the folder path we just set in application.properties
    @Value("${file.upload-dir}")
    private String uploadDir;


    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        return fileService.saveFile(file, uploadDir);
    }

}