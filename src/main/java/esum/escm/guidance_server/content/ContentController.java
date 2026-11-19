package esum.escm.guidance_server.content;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@CrossOrigin(exposedHeaders = {"Content-Disposition", "Content-Length", "Content-Range", "Accept-Ranges"})
@RestController
public class ContentController {
    private final ContentService content;
    public ContentController(ContentService content) { this.content = content; }

    @GetMapping("/api/menus/{menuId}/blocks")
    public List<ContentService.BlockResponse> list(@PathVariable String menuId, @RequestParam(required = false) String usrTpc) {
        return usrTpc == null ? content.list(menuId) : content.list(menuId, usrTpc);
    }

    @PostMapping("/api/menus/{menuId}/blocks")
    public ContentService.BlockResponse create(@PathVariable String menuId, @RequestBody ContentService.CreateRequest request,
                                               @RequestAttribute(ContentWebConfiguration.ADMIN_ATTRIBUTE) String admin,
                                               @RequestParam(required = false) String usrTpc) {
        return usrTpc == null ? content.create(menuId, request, admin) : content.create(menuId, request, admin, usrTpc);
    }

    @PutMapping("/api/blocks/{id}")
    public ContentService.BlockResponse update(@PathVariable String id, @RequestBody ContentService.UpdateRequest request,
                                               @RequestAttribute(ContentWebConfiguration.ADMIN_ATTRIBUTE) String admin) {
        return content.update(id, request, admin);
    }

    @PutMapping("/api/blocks/{id}/opened")
    public ContentService.OpenStateResponse setOpened(@PathVariable String id, @RequestBody ContentService.OpenStateRequest request) {
        return content.setOpened(id, request);
    }

    @DeleteMapping("/api/blocks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { content.delete(id); }

    @PostMapping(value = "/api/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ContentService.UploadResponse upload(@RequestParam("file") MultipartFile file,
                                                @RequestParam(required = false) String type,
                                                @RequestAttribute(ContentWebConfiguration.ADMIN_ATTRIBUTE) String admin) throws IOException {
        return content.upload(file, type, admin);
    }

    @GetMapping("/api/files/{id}")
    public ResponseEntity<Resource> file(@PathVariable String id, HttpServletRequest request) throws IOException {
        return fileResponse(id, false, request.getMethod().equals("HEAD"));
    }

    @GetMapping("/api/files/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id, HttpServletRequest request) throws IOException {
        return fileResponse(id, true, request.getMethod().equals("HEAD"));
    }

    private ResponseEntity<Resource> fileResponse(String id, boolean download, boolean head) throws IOException {
        var file = content.file(id);
        var disposition = (download || file.type().equals("file") ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.name(), StandardCharsets.UTF_8).build();
        var response = ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header("X-Content-Type-Options", "nosniff");
        if ("image/svg+xml".equals(file.contentType())) {
            // Also protect direct navigation to an uploaded SVG, outside the page's <img>.
            response.header("Content-Security-Policy", "sandbox; default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
                    .header("Referrer-Policy", "no-referrer");
        }
        return head ? response.contentLength(file.resource().contentLength()).build() : response.body(file.resource());
    }
}
