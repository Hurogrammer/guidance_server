package esum.escm.guidance_server.menu;

import esum.escm.guidance_server.content.ContentAdminClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@CrossOrigin
@RestController
public class MenuDeletionController {
    private final MenuDeletionService service;
    private final ContentAdminClient auth;

    public MenuDeletionController(MenuDeletionService service, ContentAdminClient auth) {
        this.service = service;
        this.auth = auth;
    }

    @DeleteMapping("/api/menus/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireAdmin(authorization);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> apiError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).contentType(MediaType.TEXT_PLAIN).body(error.getReason());
    }

    @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<String> conflict(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.TEXT_PLAIN)
                .body("The menu could not be deleted because its data is in use. Refresh and try again.");
    }
}
