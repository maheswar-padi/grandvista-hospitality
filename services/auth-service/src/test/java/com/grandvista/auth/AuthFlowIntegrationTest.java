package com.grandvista.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "gv.auth.bootstrap-admin.email=Admin@Example.test",
        "gv.auth.bootstrap-admin.password=bootstrap-admin-pass-123",
        "gv.auth.service-clients[0].client-id=booking-service",
        "gv.auth.service-clients[0].client-secret=0123456789abcdef0123456789abcdef-test",
        "gv.auth.service-clients[1].client-id=payment-service",
        "gv.auth.service-clients[1].client-secret="
})
@AutoConfigureMockMvc
@Import(PostgresTestContainer.class)
@Testcontainers(disabledWithoutDocker = true)
class AuthFlowIntegrationTest {

    private static final String CSRF = "X-Requested-With";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JwtDecoder decoder;

    private String uniqueEmail() {
        return "guest-" + UUID.randomUUID() + "@example.test";
    }

    private MvcResult register(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","fullName":"Test Guest"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
    }

    @Test
    void registerIssuesCustomerTokenAndHardenedRefreshCookie() throws Exception {
        String email = uniqueEmail();
        MvcResult result = register(email, "correct-horse-42");

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        Jwt jwt = decoder.decode(body.get("accessToken").asText());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("CUSTOMER");
        assertThat(jwt.getAudience()).containsExactly("grandvista-api");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("grandvista-auth");
        assertThat(jwt.getClaimAsString("email")).isEqualTo(email);

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("gv_refresh=", "HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth");
        assertThat(body.get("user").get("roles")).hasSize(1);
    }

    @Test
    void registrationCannotEscalateRoleAndRejectsWeakPasswords() throws Exception {
        String email = uniqueEmail();
        MvcResult result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"correct-horse-42\",\"fullName\":\"Mallory\",\"roles\":[\"ADMIN\"]}"
                                .formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("ADMIN");

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"fullName\":\"X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")));
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse-42");
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"correct-horse-42\",\"fullName\":\"Again\"}"
                                .formatted(email.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void loginFailureIsGenericAndAccountLocksAfterRepeatedFailures() throws Exception {
        String email = uniqueEmail();
        register(email, "correct-horse-42");

        MvcResult unknown = login(uniqueEmail(), "whatever-123");
        MvcResult wrong = login(email, "wrong-password-1");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(unknown.getResponse().getContentAsString()).get("detail"))
                .isEqualTo(json.readTree(wrong.getResponse().getContentAsString()).get("detail"));

        for (int i = 0; i < 4; i++) {
            login(email, "wrong-password-1");
        }
        // even the correct password is refused while locked
        MvcResult locked = login(email, "correct-horse-42");
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getContentAsString()).contains("ACCOUNT_TEMPORARILY_LOCKED");
    }

    @Test
    void refreshRotatesTokenAndReuseRevokesTheWholeFamily() throws Exception {
        String email = uniqueEmail();
        MvcResult registered = register(email, "correct-horse-42");
        Cookie first = registered.getResponse().getCookie("gv_refresh");

        // CSRF header is mandatory for cookie-authenticated endpoints
        mvc.perform(post("/api/v1/auth/refresh").cookie(first))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_HEADER_MISSING"));

        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(first).header(CSRF, "XMLHttpRequest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        Cookie second = refreshed.getResponse().getCookie("gv_refresh");
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        // replaying the rotated token is detected and kills the newer token too
        mvc.perform(post("/api/v1/auth/refresh").cookie(first).header(CSRF, "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(second).header(CSRF, "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
        MvcResult registered = register(uniqueEmail(), "correct-horse-42");
        Cookie cookie = registered.getResponse().getCookie("gv_refresh");

        mvc.perform(post("/api/v1/auth/logout").cookie(cookie).header(CSRF, "XMLHttpRequest"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
        mvc.perform(post("/api/v1/auth/refresh").cookie(cookie).header(CSRF, "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meRequiresValidTokenAndAdminApiRequiresAdminRole() throws Exception {
        MvcResult registered = register(uniqueEmail(), "correct-horse-42");
        String token = json.readTree(registered.getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token + "tampered"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void bootstrapAdminCanManageRolesButNotDemoteSelf() throws Exception {
        MvcResult adminLogin = login("admin@example.test", "bootstrap-admin-pass-123");
        assertThat(adminLogin.getResponse().getStatus()).isEqualTo(200);
        JsonNode admin = json.readTree(adminLogin.getResponse().getContentAsString());
        String adminToken = admin.get("accessToken").asText();
        String adminId = admin.get("user").get("id").asText();

        MvcResult customer = register(uniqueEmail(), "correct-horse-42");
        String customerId = json.readTree(customer.getResponse().getContentAsString()).get("user").get("id").asText();

        mvc.perform(put("/api/v1/admin/users/{id}/roles", customerId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"STAFF\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("STAFF"));

        mvc.perform(put("/api/v1/admin/users/{id}/roles", adminId).header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"CUSTOMER\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CANNOT_REMOVE_OWN_ADMIN"));
    }

    @Test
    void adminUserSearchWorksWithAndWithoutText() throws Exception {
        String adminToken = json.readTree(login("admin@example.test", "bootstrap-admin-pass-123").getResponse()
                .getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/users").param("q", "ADMIN@example").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].email").value("admin@example.test"));
    }

    @Test
    void serviceTokensRequireValidClientCredentials() throws Exception {
        String good = "Basic " + Base64.getEncoder().encodeToString(
                "booking-service:0123456789abcdef0123456789abcdef-test".getBytes(StandardCharsets.UTF_8));
        String bad = "Basic " + Base64.getEncoder().encodeToString("booking-service:nope".getBytes(StandardCharsets.UTF_8));
        String unconfigured = "Basic " + Base64.getEncoder().encodeToString("payment-service:".getBytes(StandardCharsets.UTF_8));

        MvcResult ok = mvc.perform(post("/internal/v1/service-tokens").header("Authorization", good)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials"))
                .andExpect(status().isOk()).andReturn();
        Jwt jwt = decoder.decode(json.readTree(ok.getResponse().getContentAsString()).get("access_token").asText());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("SERVICE");
        assertThat(jwt.getAudience()).containsExactly("grandvista-internal");
        assertThat(jwt.getSubject()).isEqualTo("service:booking-service");

        for (String auth : new String[] {bad, unconfigured}) {
            mvc.perform(post("/internal/v1/service-tokens").header("Authorization", auth)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void jwksPublishesOnlyThePublicKey() throws Exception {
        String body = mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        JsonNode key = json.readTree(body).get("keys").get(0);
        assertThat(key.get("kty").asText()).isEqualTo("RSA");
        assertThat(key.has("d")).as("private exponent must never be published").isFalse();
    }
}
