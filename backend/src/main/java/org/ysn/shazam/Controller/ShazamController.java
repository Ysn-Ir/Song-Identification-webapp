package org.ysn.shazam.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.ysn.shazam.Service.ShazamService;
import org.ysn.shazam.model.file;

@RestController
@RequestMapping("api")
public class ShazamController {
    @Autowired
    private ShazamService shazamService;
    @PostMapping("/file")
    public String testexec(@RequestBody file f){
        String filepath="C:/Users/khali/OneDrive/Bureau/learning/datascience/test5.ogg";
        return shazamService.runProgram(f.getFilepath().toString(),f.getCommand().toString());
    }
}
