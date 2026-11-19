package esum.escm.guidance_server.menu;

import esum.escm.guidance_server.content.ContentAdminClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@CrossOrigin
@RestController
@RequestMapping("/api/menus/{id}/relative-tables")
public class RelativeTablesController {
    private final RelativeTablesService service;
    private final ContentAdminClient auth;

    public RelativeTablesController(RelativeTablesService service, ContentAdminClient auth) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping
    public RelativeTablesService.RelativeTables get(@PathVariable String id,
            @RequestParam(required = false) String usrTpc,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.requireAdmin(authorization);
        return service.get(id, usrTpc);
    }

    @PutMapping
    public RelativeTablesService.RelativeTables save(@PathVariable String id,
            @RequestParam(required = false) String usrTpc,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody SaveRequest request) {
        String admin = auth.requireAdmin(authorization);
        return service.save(id, request.tableNames(), admin, usrTpc);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> apiError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).contentType(MediaType.TEXT_PLAIN).body(error.getReason());
    }

    public record SaveRequest(List<String> tableNames) {}
}
