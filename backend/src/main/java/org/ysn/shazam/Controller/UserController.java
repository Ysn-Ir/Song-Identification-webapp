package org.ysn.shazam.Controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.ysn.shazam.Service.UserService;
import org.ysn.shazam.model.User;

import java.util.List;

@RestController
@RequestMapping("api")
public class UserController {
    @Autowired
    private UserService service;

    @GetMapping("/users")
    public List<User> getAllUser(){
        return service.getAllUser();
    }
    @PostMapping("/user")
    public User createUser(@RequestBody User user){
        return service.addUser(user);
    }
}
