package esum.escm.guidance_server.menu;

import esum.escm.guidance_server.content.ContentAdminClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@CrossOrigin
@RestController
public class MenuOrderController {
    private final MenuOrderService service;
    private final ContentAdminClient auth;

    public MenuOrderController(MenuOrderService service, ContentAdminClient auth) {
        this.service = service;
        this.auth = auth;
    }

    @PostMapping("/api/menus/{id}/move")
    public List<MenuOrderService.MenuResponse> move(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody MoveRequest request) {
        auth.requireAdmin(authorization);
        return service.move(id, request.direction());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> apiError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).contentType(MediaType.TEXT_PLAIN).body(error.getReason());
    }

    public record MoveRequest(String direction) {}
}
