package com.orynx.auth.user;

import com.orynx.auth.auth.JwtService;
import com.orynx.auth.kafka.KafkaProducer;
import com.orynx.auth.user.dto.LoginRequest;
import com.orynx.auth.user.event.UserRegisteredEvent;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.orynx.auth.user.dto.RegisterRequest;
import lombok.extern.slf4j.Slf4j;

import lombok.RequiredArgsConstructor;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final KafkaProducer kafkaProducer;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public User register(RegisterRequest request) {
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .role("USER")
                .build();

        User savedUser= userRepository.save(user);

        UserRegisteredEvent event = UserRegisteredEvent.builder()
                .userId(savedUser.getId())
                .email(savedUser.getEmail())
                .build();

        kafkaProducer.publishUserRegisteredEvent(event);

        log.info(
                "Registering user with email: {}",
                request.getEmail()
        );
        return savedUser;
    }

    public String login(LoginRequest request){
        User user = userRepository.findByEmail(
                request.getEmail()
        ).orElseThrow(()-> new RuntimeException("User not found"));

        boolean matches = passwordEncoder.matches(
                request.getPassword(),
                user.getPassword()
        );

        if(!matches){
            throw new RuntimeException("Invalid credentials");
        }

        log.info(
                "Login attempt for email: {}",
                request.getEmail()
        );

        return jwtService.generateToken(user.getEmail());
    }

}
