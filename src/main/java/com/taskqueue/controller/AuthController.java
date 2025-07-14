package com.taskqueue.controller;

import com.taskqueue.model.User;
import com.taskqueue.model.Role;
import com.taskqueue.model.LoginResponse;
import com.taskqueue.service.UserService;
import com.taskqueue.service.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {
    @Autowired
    private UserService userService;

    @Autowired
    private JwtService jwtService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String email = body.get("email");
        String password = body.get("password");
        if (userService.findByUsername(username).isPresent() || userService.findByEmail(email).isPresent()) {
            return ResponseEntity.badRequest().body("Username or email already exists");
        }
        User user = userService.registerUser(username, email, password);
        return ResponseEntity.ok(user);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        return userService.findByUsername(username)
                .filter(user -> userService.checkPassword(password, user.getPassword()))
                .map(user -> ResponseEntity.ok((Object) new LoginResponse(
                        jwtService.generateToken(user.getUsername(), user.getRole().name()),
                        user.getUsername(),
                        user.getRole().name()
                )))
                .orElse(ResponseEntity.status(401).body("Invalid credentials"));
    }
} 