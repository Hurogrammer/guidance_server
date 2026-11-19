package esum.escm.guidance_server.content;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

final class ContentFilePolicy {
    static final long MAX_BYTES = 15L * 1024 * 1024;
    static final long MAX_VIDEO_BYTES = 3L * 1024 * 1024 * 1024;
    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("ogv", "video/ogg"),
            Map.entry("txt", "text/plain"), Map.entry("csv", "text/csv"), Map.entry("pdf", "application/pdf"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

    static FileInfo inspect(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw badRequest("Choose a non-empty file to upload");
        String name = file.getOriginalFilename();
        if (name == null) throw badRequest("A filename is required");
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").trim();
        if (name.isBlank() || name.length() > 255) throw badRequest("The filename must be between 1 and 255 characters");
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = MIME_TYPES.get(extension);
        if (mime == null) throw badRequest("Supported files: PNG, JPG, GIF, WebP, SVG, MP4, WebM, OGV, TXT, CSV, Excel, Word, PDF");
        String type = mime.startsWith("image/") ? "image" : mime.startsWith("video/") ? "video" : "file";
        if (file.getSize() > (type.equals("video") ? MAX_VIDEO_BYTES : MAX_BYTES)) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    type.equals("video") ? "Videos must be 3 GB or smaller" : "Files must be 15 MB or smaller");
        }
        byte[] prefix;
        try (var stream = file.getInputStream()) { prefix = stream.readNBytes(16); }
        String hex = HexFormat.of().formatHex(prefix);
        String ascii = new String(prefix, StandardCharsets.ISO_8859_1);
        boolean valid = switch (extension) {
            case "png" -> hex.startsWith("89504e470d0a1a0a");
            case "jpg", "jpeg" -> hex.startsWith("ffd8ff");
            case "gif" -> ascii.startsWith("GIF87a") || ascii.startsWith("GIF89a");
            case "webp" -> ascii.startsWith("RIFF") && ascii.length() >= 12 && ascii.substring(8, 12).equals("WEBP");
            case "mp4" -> ascii.length() >= 8 && ascii.substring(4, 8).equals("ftyp");
            case "webm" -> hex.startsWith("1a45dfa3");
            case "ogv" -> ascii.startsWith("OggS");
            case "pdf" -> ascii.startsWith("%PDF-");
            case "doc", "xls" -> hex.startsWith("d0cf11e0a1b11ae1");
            case "docx", "xlsx" -> hex.startsWith("504b0304");
            default -> true;
        };
        if (!valid) throw badRequest("The file contents do not match its extension");
        if (extension.equals("svg")) SvgFilePolicy.validate(file);
        return new FileInfo(name, mime, type, file.getSize());
    }

    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    record FileInfo(String name, String contentType, String type, long size) {}
}
