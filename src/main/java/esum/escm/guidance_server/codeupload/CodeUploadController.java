package esum.escm.guidance_server.codeupload;

import esum.escm.guidance_server.content.ContentAdminClient;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@CrossOrigin(exposedHeaders = HttpHeaders.LOCATION)
@RestController
@RequestMapping("/api/java-codes")
public class CodeUploadController {
    private final CodeUploadService service;
    private final ContentAdminClient auth;

    public CodeUploadController(CodeUploadService service, ContentAdminClient auth) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping
    public ResponseEntity<CodeUploadService.SourcePage> list(
            @RequestParam(value = "page", defaultValue = "0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(page));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CodeUploadService.Source> get(@PathVariable("id") String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                    @RequestBody CodeUploadRequest request) {
        auth.requireAdmin(authorization);
        return response(service.create(request));
    }

    @PutMapping
    public ResponseEntity<?> replace(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                     @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                     @RequestBody CodeUploadRequest request) {
        auth.requireAdmin(authorization);
        return response(service.replace(request, ifMatch));
    }

    private ResponseEntity<?> response(CodeUploadService.Result result) {
        var response = ResponseEntity.status(result.status());
        if (result.status() == HttpStatus.CONFLICT) {
            return response.body(new Conflict("Code already exists for this source.", result.revision()));
        }
        if (result.status() == HttpStatus.PRECONDITION_FAILED) {
            String message = result.revision() == null ? "The saved source no longer exists. Use Save to add it."
                    : "The saved source changed. Confirm the replacement again.";
            return response.body(new Conflict(message, result.revision()));
        }
        return response.location(URI.create("/api/java-codes/" + result.sourceId())).build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> apiError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).contentType(MediaType.TEXT_PLAIN).body(error.getReason());
    }

    record Conflict(String message, String revision) {}
}
