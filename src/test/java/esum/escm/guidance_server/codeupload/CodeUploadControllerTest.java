package esum.escm.guidance_server.codeupload;

import esum.escm.guidance_server.content.ContentAdminClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CodeUploadControllerTest {
    private static final String PATH = "/api/java-codes";
    private static final String ADMIN = "Bearer admin-fixture";
    private static final String JSON = """
            {"class_type":"abstract_class","package":"org.example","class_name":"Upload",
             "imports":"import java.util.List;","code":"  // fixture\\n","language_name":" Java "}
            """;
    private final ContentAdminClient auth = mock(ContentAdminClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        when(auth.requireAdmin(null)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(auth.requireAdmin("Bearer expired")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        when(auth.requireAdmin("Bearer reader")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        mvc = MockMvcBuilders.standaloneSetup(new CodeUploadController(new CodeUploadService(jdbc), auth)).build();
    }

    @Test void bindsSnakeCaseFieldsAndKeepsSourceWhitespace() throws Exception {
        mvc.perform(post(PATH).header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isCreated());
        verify(jdbc).update(contains("`CLASS_TYPE`, `PACKAGE`, `CLASS_NAME`, `IMPORTS`, `CODE`, `LANGUAGE_NAME`"),
                eq("abstract_class"), eq("org.example"), eq("Upload"), eq("import java.util.List;"), eq("  // fixture\n"), eq("java"));
    }

    @Test void bothWritesRequireAValidAdminSession() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(JSON)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH).header("Authorization", "Bearer expired").contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization", "Bearer reader").contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isForbidden());
        mvc.perform(put(PATH).header("Authorization", "Bearer reader").contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isForbidden());
        verifyNoInteractions(jdbc);
    }

    @Test void rejectsMissingLanguageAndUnconfirmedReplacement() throws Exception {
        mvc.perform(post(PATH).header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.replace(" Java ", "")))
                .andExpect(status().isBadRequest()).andExpect(content().string("Choose a language."));
        mvc.perform(put(PATH).header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put(PATH).header("Authorization", ADMIN).header("If-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON).content(JSON)).andExpect(status().isBadRequest());
        verifyNoInteractions(jdbc);
    }

    @Test void includesTheCurrentRevisionInConflictResponses() throws Exception {
        var service = mock(CodeUploadService.class);
        String previous = "a".repeat(64);
        String current = "b".repeat(64);
        String token = '"' + previous + '"';
        when(service.create(any())).thenReturn(new CodeUploadService.Result(HttpStatus.CONFLICT, previous));
        when(service.replace(any(), eq(token))).thenReturn(new CodeUploadService.Result(HttpStatus.PRECONDITION_FAILED, current));
        var endpoint = MockMvcBuilders.standaloneSetup(new CodeUploadController(service, auth)).build();
        endpoint.perform(post(PATH).header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.revision").value(previous));
        endpoint.perform(put(PATH).header("Authorization", ADMIN).header("If-Match", token)
                        .contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.revision").value(current));
    }

    @Test void limitsUtf8SourceBytesAndUnicodeMetadata() {
        assertEquals(HttpStatus.CONTENT_TOO_LARGE, assertThrows(ResponseStatusException.class,
                () -> new CodeUploadRequest(null, "", "example", null, "한".repeat(333_334), "Python").validated()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> new CodeUploadRequest(null, "", "🚀".repeat(2001), null, "source", "Python").validated()).getStatusCode());
        assertEquals("🚀".repeat(2000),
                new CodeUploadRequest(null, "", "🚀".repeat(2000), null, "source", "Python").validated().className());
    }
}
