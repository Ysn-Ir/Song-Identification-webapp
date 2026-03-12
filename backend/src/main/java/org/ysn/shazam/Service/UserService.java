package org.ysn.shazam.Service;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestBody;
import org.ysn.shazam.Repository.UserRepository;
import org.ysn.shazam.model.User;

import java.util.List;

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;
    public User addUser(@RequestBody User user){
        return this.userRepository.save(user);
    }
    public List<User> getAllUser(){
        return this.userRepository.findAll();
    }
}
