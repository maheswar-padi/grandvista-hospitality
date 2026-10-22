package com.grandvista.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grandvista.platform.error.ProblemDetails;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Writes 401 / 403 responses in the same problem-document format as every other error. */
public class ProblemDetailSecurityHandlers {

    private final ObjectMapper objectMapper;

    public ProblemDetailSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(response, ProblemDetails.of(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED",
                    "A valid access token is required."));
        };
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> write(response, ProblemDetails.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to perform this action."));
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
