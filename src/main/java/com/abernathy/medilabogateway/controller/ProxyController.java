package com.abernathy.medilabogateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/proxy")
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final RestTemplate restTemplate;
    private final String defaultBackend;

    public ProxyController(RestTemplate restTemplate, @Value("${routing.backendBaseUrl}") String defaultBackend) {
        this.restTemplate = restTemplate;
        this.defaultBackend = defaultBackend;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {

        // Log incoming request
        log.info("Incoming request to gateway: URI={}, SessionId={}, Cookies={}",
                request.getRequestURI(),
                request.getSession(false) != null ? request.getSession(false).getId() : "no‑session",
                request.getCookies() != null ?
                        Arrays.stream(request.getCookies())
                                .map(c -> c.getName() + "=" + c.getValue())
                                .collect(Collectors.joining(","))
                        : "no‑cookies");

        String forwardPath = extractForwardPath(request);
        String query = request.getQueryString();
        String backendUrl = resolveBackendUrl(forwardPath) + (query != null ? "?" + query : "");



        log.info("Computed routing: forwardPath={}, query={}, backendUrl={}",
                forwardPath, query, backendUrl);

        HttpHeaders headers = copyRequestHeaders(request);
        addJwtFromSession(request.getSession(false), headers);
        HttpMethod method = resolveHttpMethod(request.getMethod());
        HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);

        log.info("Headers forwarded to backend: Authorization={}, CookiesHeader={}",
                headers.getFirst(HttpHeaders.AUTHORIZATION),
                headers.get(HttpHeaders.COOKIE));

        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(URI.create(backendUrl), method, entity, byte[].class);
            log.info("Received response from backend. backendUrl={}, status={}",
                    backendUrl, resp.getStatusCode());
            HttpHeaders respHeaders = new HttpHeaders();
            resp.getHeaders().forEach(respHeaders::put);
            return new ResponseEntity<>(resp.getBody(), respHeaders, resp.getStatusCode());
        } catch (RestClientException ex) {
            log.error("Error proxying request to backend. backendUrl={}, error={}", backendUrl, ex.getMessage());
            byte[] msg = ex.getMessage() != null ?
                    ex.getMessage().getBytes(StandardCharsets.UTF_8) :
                    new byte[0];
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(msg);
        }
    }

    private String extractForwardPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String base = request.getContextPath() + "/api/proxy";
        String forwardPath = path.length() <= base.length() ? "/" : path.substring(base.length());
        log.info("Extracted forwardPath = {}", forwardPath);
        return forwardPath;
    }
    private String resolveBackendUrl(String forwardPath) {
        String backendUrl;
        if (forwardPath.startsWith("/patients")) {
            backendUrl = "http://localhost:8081" + forwardPath;
        } else if (forwardPath.startsWith("/api/notes") || forwardPath.startsWith("/notes")) {
            backendUrl = "http://localhost:8083" + forwardPath;
        }  else if (forwardPath.startsWith("/risk")) {
        String seg = forwardPath.substring("/risk".length());
        return "http://localhost:8084/assessment" + seg;
    } else if (forwardPath.startsWith("/assessment")) {
        return "http://localhost:8084" + forwardPath;
    } else {
            backendUrl = "http://localhost:8082" + forwardPath; // default to frontend
        }
        log.info("Resolved backend URL for forwardPath [{}] → {}", forwardPath, backendUrl);
        return backendUrl;
    }

    private HttpHeaders copyRequestHeaders(HttpServletRequest request) {
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
        return headers;
    }

    private void addJwtFromSession(HttpSession session, HttpHeaders headers) {
        if (session != null) {
            Object jwt = session.getAttribute("JWT");
            log.info("Session attribute JWT={}", jwt);
            if (jwt instanceof String) {
                headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
            }else {
                log.info("No session available, cannot add JWT header");
            }
        }
    }

    private HttpMethod resolveHttpMethod(String methodName) {
        try {
            return HttpMethod.valueOf(methodName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return HttpMethod.GET;
        }
    }
}
