package com.orynx.auth.user;

import com.orynx.auth.kafka.KafkaProducer;
import com.orynx.auth.user.event.UserRegisteredEvent;
import org.springframework.stereotype.Service;

import com.orynx.auth.user.dto.RegisterRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final KafkaProducer kafkaProducer;

    public User register(RegisterRequest request) {
        User user = User.builder()
                .email(request.getEmail())
                .password(request.getPassword())
                .build();

        User savedUser= userRepository.save(user);

        UserRegisteredEvent event = UserRegisteredEvent.builder()
                .userId(savedUser.getId())
                .email(savedUser.getEmail())
                .build();

        kafkaProducer.publishUserRegisteredEvent(event);

        return savedUser;
    }

}
