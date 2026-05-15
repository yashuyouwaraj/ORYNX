package com.orynx.auth.user;

import com.orynx.auth.common.response.ApiResponse;
import com.orynx.auth.user.dto.RegisterRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.orynx.auth.user.dto.LoginRequest;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public ApiResponse<User> register(
            @Valid @RequestBody RegisterRequest request
    ) {

        User user = userService.register(request);

        return ApiResponse.<User>builder()
                .success(true)
                .message("User registered successfully")
                .data(user)
                .build();
    }

    @PostMapping("/login")
    public ApiResponse<String> login(@Valid @RequestBody LoginRequest request){
        String token = userService.login(request);

        return ApiResponse.<String>builder()
                .success(true)
                .message("Login successful")
                .data(token)
                .build();
    }
}
