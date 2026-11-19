package esum.escm.guidance_server.content;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

@Component
public class ContentAdminClient {
    private final RestClient client;

    @Autowired
    public ContentAdminClient(@Value("${content.auth-base-url:http://localhost:8081}") String baseUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    ContentAdminClient(RestClient client) { this.client = client; }

    public String requireAdmin(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin sign-in is required");
        }
        try {
            Session session = client.get().uri("/api/auth/me").header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve().body(Session.class);
            if (session == null || session.username() == null || session.username().isBlank()
                    || session.expiresAt() == null || !session.expiresAt().isAfter(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in again");
            }
            if (!session.admin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access is required");
            return session.username();
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401 || error.getStatusCode().value() == 403) {
                throw new ResponseStatusException(error.getStatusCode(), "Admin sign-in is required");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The authentication service is unavailable");
        } catch (RestClientException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The authentication service is unavailable");
        }
    }

    public record Session(String username, boolean admin, Instant expiresAt) {}
}
