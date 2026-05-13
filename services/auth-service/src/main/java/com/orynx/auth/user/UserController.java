package com.orynx.auth.user;

import com.orynx.auth.common.response.ApiResponse;
import com.orynx.auth.user.dto.RegisterRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
}
