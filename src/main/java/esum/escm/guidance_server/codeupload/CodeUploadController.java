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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@CrossOrigin
@RestController
public class CodeUploadController {
    private final CodeUploadService service;
    private final ContentAdminClient auth;

    public CodeUploadController(CodeUploadService service, ContentAdminClient auth) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping("/api/blocks/{blockId}/code")
    public ResponseEntity<CodeUploadService.Source> get(@PathVariable("blockId") String blockId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(blockId));
    }

    @PostMapping("/api/menus/{menuId}/java-codes")
    public ResponseEntity<?> create(
            @PathVariable("menuId") String menuId, @RequestParam(value = "usrTpc", required = false) String userType,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody CodeUploadRequest request) {
        String admin = auth.requireAdmin(authorization);
        return response(service.create(menuId, userType, admin, request));
    }

    @PutMapping("/api/menus/{menuId}/java-codes")
    public ResponseEntity<?> replace(
            @PathVariable("menuId") String menuId, @RequestParam(value = "usrTpc", required = false) String userType,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody CodeUploadRequest request) {
        String admin = auth.requireAdmin(authorization);
        return response(service.replace(menuId, userType, admin, request, ifMatch));
    }

    private ResponseEntity<?> response(CodeUploadService.Result result) {
        var response = ResponseEntity.status(result.status());
        if (result.status() == HttpStatus.CONFLICT) {
            return response.body(new Conflict("Code already exists for this package and source name.", result.revision()));
        }
        if (result.status() == HttpStatus.PRECONDITION_FAILED) {
            String message = result.revision() == null ? "The saved source no longer exists. Use Save to add it."
                    : "The saved source changed. Confirm the replacement again.";
            return response.body(new Conflict(message, result.revision()));
        }
        return response.build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> apiError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).contentType(MediaType.TEXT_PLAIN).body(error.getReason());
    }

    record Conflict(String message, String revision) {}
}
