package com.abernathy.medilabogateway;


import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PostLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final com.abernathy.medilabogateway.JwtUtil jwtUtil;

    public PostLoginSuccessHandler(com.abernathy.medilabogateway.JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String username = authentication.getName();
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        Map<String, Object> extra = new HashMap<>();
        extra.put("displayName", username); // example

        String jwt = jwtUtil.generateToken(username, roles, extra);

        // store JWT in HttpSession. Browser receives normal JSESSIONID cookie.
        request.getSession(true).setAttribute("JWT", jwt);

        // redirect to root (or frontend)
        response.sendRedirect("/");
        log.info("Generated JWT={} for user={}", jwt, username);
    }
}