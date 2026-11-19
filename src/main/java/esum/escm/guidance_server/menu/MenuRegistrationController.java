package esum.escm.guidance_server.menu;

import esum.escm.guidance_server.content.ContentAdminClient;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@CrossOrigin
@RestController
public class MenuRegistrationController {
    private final MenuRegistrationService service;
    private final ContentAdminClient auth;
    public MenuRegistrationController(MenuRegistrationService service, ContentAdminClient auth) { this.service = service; this.auth = auth; }

    @GetMapping("/api/menu-registration")
    public MenuRegistrationService.Recommendation recommend(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String userType, @RequestParam(required = false) String parentId,
            @RequestParam(required = false) String title, @RequestParam(required = false) String prefix) {
        auth.requireAdmin(authorization);
        return service.recommend(userType, parentId, title, prefix);
    }

    @GetMapping("/api/menu-registration/availability")
    public MenuRegistrationService.Availability availability(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestParam String menuId) {
        auth.requireAdmin(authorization);
        return service.availability(menuId);
    }

    @PostMapping("/api/menus")
    @ResponseStatus(HttpStatus.CREATED)
    public MenuRegistrationService.MenuResponse create(@RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody MenuRegistrationService.CreateRequest request) {
        return service.create(request, auth.requireAdmin(authorization));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> apiError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).contentType(MediaType.TEXT_PLAIN).body(error.getReason());
    }
}
