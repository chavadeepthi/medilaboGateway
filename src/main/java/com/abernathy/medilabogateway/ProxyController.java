package com.abernathy.medilabogateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

@RestController
@RequestMapping("/api/proxy")
public class ProxyController {

    private final RestTemplate restTemplate;
    private final String backendBase;

    public ProxyController(RestTemplate restTemplate, @Value("${routing.backendBaseUrl}") String backendBase) {
        this.restTemplate = restTemplate;
        this.backendBase = backendBase;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String forwardPath = extractForwardPath(request);
        String query = request.getQueryString();
        String url = backendBase + forwardPath + (query != null ? "?" + query : "");

        // Copy headers
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if ("host".equalsIgnoreCase(name)) continue;
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                headers.add(name, values.nextElement());
            }
        }

        // Add JWT from session
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object jwt = session.getAttribute("JWT");
            if (jwt instanceof String) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
            }
        }

        // Determine HTTP method safely
        HttpMethod method;
        try {
            method = HttpMethod.valueOf(request.getMethod().toUpperCase());
        } catch (IllegalArgumentException ex) {
            method = HttpMethod.GET;
        }

        HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);
        ResponseEntity<byte[]> resp;
        try {
            resp = restTemplate.exchange(URI.create(url), method, entity, byte[].class);
        } catch (RestClientException ex) {
            byte[] msg = ex.getMessage() != null ? ex.getMessage().getBytes(StandardCharsets.UTF_8) : new byte[0];
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(msg);
        }

        // Copy response headers
        HttpHeaders respHeaders = new HttpHeaders();
        resp.getHeaders().forEach((k, v) -> respHeaders.put(k, v));

        return new ResponseEntity<>(resp.getBody(), respHeaders, resp.getStatusCode());
    }

    private String extractForwardPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String base = request.getContextPath() + "/api/proxy";
        if (path.length() <= base.length()) {
            return "/";
        }
        return path.substring(base.length());
    }
}
