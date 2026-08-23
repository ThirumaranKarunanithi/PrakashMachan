package com.ledgerintegrity.platform.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CDC-002 / AC-12 over real HTTP: a client user reaches ONLY their engagement's
 * evidence portal — sanitized data, no staff APIs, no other clients — and staff
 * cannot use the client surface either.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:clientportaldb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ClientPortalSecurityTest {

    @Autowired TestRestTemplate rest;

    @Test
    void clientSeesOnlyTheirSanitizedPortalAndNothingElse() {
        // staff: firm + two engagements, one evidence request each
        String staff = register("Portal & Co", "CA Staff", "staff@portal.firm", "staff-pass-1");
        String eng1 = createEngagement(staff, "CLIENT-ONE");
        String eng2 = createEngagement(staff, "CLIENT-TWO");
        String ex1 = seedException(staff, eng1);
        String ex2 = seedException(staff, eng2);
        String req1 = createEvidenceRequest(staff, ex1, "Board minutes for the provision");
        createEvidenceRequest(staff, ex2, "Fixed asset register");

        // staff creates a client user for engagement 1 only
        ResponseEntity<Map> created = exchange(staff, HttpMethod.POST,
                "/api/engagements/" + eng1 + "/client-users",
                Map.of("email", "cfo@clientone.example", "displayName", "Client One CFO", "password", "client-pass-1"),
                Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());

        // client logs in
        ResponseEntity<Map> login = rest.postForEntity("/api/auth/login",
                json(Map.of("email", "cfo@clientone.example", "password", "client-pass-1")), Map.class);
        assertEquals(HttpStatus.OK, login.getStatusCode());
        assertEquals("CLIENT", login.getBody().get("role"));
        String client = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        // client sees exactly their one request, sanitized (no rule ids / exposure / exception ids)
        ResponseEntity<String> reqs = exchange(client, HttpMethod.GET, "/api/client/requests", null, String.class);
        assertEquals(HttpStatus.OK, reqs.getStatusCode());
        assertTrue(reqs.getBody().contains("Board minutes for the provision"));
        assertFalse(reqs.getBody().contains("Fixed asset register")); // other engagement invisible
        assertFalse(reqs.getBody().contains("ruleId"));
        assertFalse(reqs.getBody().contains("exposure"));
        assertFalse(reqs.getBody().contains("exceptionId"));

        // client is blocked from every staff API
        for (String path : List.of("/api/engagements", "/api/dashboard",
                "/api/engagements/" + eng1, "/api/engagements/" + eng1 + "/exceptions")) {
            assertEquals(HttpStatus.FORBIDDEN,
                    exchange(client, HttpMethod.GET, path, null, String.class).getStatusCode(), path);
        }

        // client uploads a response; staff sees the version with the client's identity
        ResponseEntity<Map> upload = uploadAsClient(client, req1, "minutes.pdf", "board minutes content");
        assertEquals(HttpStatus.OK, upload.getStatusCode());
        assertEquals("cfo@clientone.example", upload.getBody().get("uploadedBy"));
        ResponseEntity<String> staffView = exchange(staff, HttpMethod.GET,
                "/api/engagements/" + eng1 + "/evidence-requests", null, String.class);
        assertTrue(staffView.getBody().contains("minutes.pdf"));
        assertTrue(staffView.getBody().contains("RESPONDED"));

        // staff cannot use the client surface
        assertEquals(HttpStatus.FORBIDDEN,
                exchange(staff, HttpMethod.GET, "/api/client/requests", null, String.class).getStatusCode());
    }

    // ---------- helpers ----------

    private String register(String firm, String name, String email, String password) {
        ResponseEntity<Map> res = rest.postForEntity("/api/auth/register-firm",
                json(Map.of("firmName", firm, "displayName", name, "email", email, "password", password)), Map.class);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        return res.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
    }

    private String createEngagement(String cookie, String client) {
        ResponseEntity<Map> res = exchange(cookie, HttpMethod.POST, "/api/engagements",
                Map.of("clientName", client, "fyStart", "2024-04-01", "fyEnd", "2025-03-31",
                        "closeDate", "2025-03-31"), Map.class);
        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        return (String) res.getBody().get("id");
    }

    /** Import a 2-line GL and run rules so an exception exists to hang a request on. */
    private String seedException(String cookie, String engagementId) {
        String gl = "voucher_id,voucher_type,txn_date,created_at,account_code,account_name,debit,credit,narration,source,user_id,reversal_of\n"
                + "V1,Journal,2025-03-30,2025-04-05 20:00,5901,Misc,900000.00,,provision,Manual,BOSS,\n"
                + "V1,Journal,2025-03-30,2025-04-05 20:00,2501,Provisions,,900000.00,provision,Manual,BOSS,\n";
        String tb = "account_code,account_name,opening,debit,credit,closing\n"
                + "5901,Misc,0,900000.00,0,900000.00\n2501,Provisions,0,0,900000.00,-900000.00\n";
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("gl", namedFile("gl.csv", gl));
        form.add("tb", namedFile("tb.csv", tb));
        form.add("mapping", "client-a-gl");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.add("X-XSRF-TOKEN", csrfFor(cookie));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        assertEquals(HttpStatus.OK, rest.exchange("/api/engagements/" + engagementId + "/imports",
                HttpMethod.POST, new HttpEntity<>(form, headers), String.class).getStatusCode());
        exchange(cookie, HttpMethod.POST, "/api/engagements/" + engagementId + "/rule-runs", Map.of(), String.class);
        ResponseEntity<List> exceptions = exchange(cookie, HttpMethod.GET,
                "/api/engagements/" + engagementId + "/exceptions", null, List.class);
        return (String) ((Map<?, ?>) exceptions.getBody().get(0)).get("id");
    }

    private String createEvidenceRequest(String cookie, String exceptionId, String title) {
        ResponseEntity<Map> res = exchange(cookie, HttpMethod.POST,
                "/api/exceptions/" + exceptionId + "/evidence-requests",
                Map.of("title", title, "requestedBy", "CA Staff"), Map.class);
        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        return (String) res.getBody().get("id");
    }

    private ResponseEntity<Map> uploadAsClient(String cookie, String requestId, String fileName, String content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", namedFile(fileName, content));
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        headers.add("X-XSRF-TOKEN", csrfFor(cookie));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange("/api/client/requests/" + requestId + "/documents",
                HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
    }

    private static ByteArrayResource namedFile(String name, String content) {
        return new ByteArrayResource(content.getBytes()) {
            @Override public String getFilename() { return name; }
        };
    }

    private static HttpEntity<Map<String, String>> json(Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private <T> ResponseEntity<T> exchange(String cookie, HttpMethod method, String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookie);
        if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
        if (method != HttpMethod.GET) headers.add("X-XSRF-TOKEN", csrfFor(cookie));
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    // Phase A: complete the CSRF handshake like a real client
    private final java.util.Map<String, String> csrfBySession = new java.util.HashMap<>();

    private String csrfFor(String cookie) {
        return csrfBySession.computeIfAbsent(cookie, c -> {
            HttpHeaders h = new HttpHeaders();
            h.add(HttpHeaders.COOKIE, c);
            Map<?, ?> body = rest.exchange("/api/auth/csrf", HttpMethod.GET,
                    new HttpEntity<>(h), Map.class).getBody();
            return (String) body.get("token");
        });
    }

}
