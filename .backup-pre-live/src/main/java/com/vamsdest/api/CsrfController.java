package com.vamsdest.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    @GetMapping("/api/csrf")
    public Map<String, String> token(HttpServletRequest request) {
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (attribute instanceof CsrfToken csrfToken) {
            return Map.of("headerName", csrfToken.getHeaderName(), "token", csrfToken.getToken());
        }
        return Map.of("headerName", "X-XSRF-TOKEN", "token", "");
    }
}