package esum.escm.guidance_server.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ContentService {
    private static final String TABLE = "`CONTENT BLOCKS`";
    private static final String COLUMNS = "ID, MENU_ID, SORT_ORDER, TYPE, BODY, FILE_NAME, CONTENT_TYPE, FILE_SIZE, CREATED_BY, ALLOW_AI, IS_OPENED";
    private static final Set<String> TYPES = Set.of("text", "image", "video", "file");
    private static final Pattern FILE_PATH = Pattern.compile("(?:.*/)?api/files/([0-9a-fA-F-]{36})");
    private static final RowMapper<BlockRow> MAPPER = (row, index) -> new BlockRow(
            row.getString("ID"), row.getString("MENU_ID"), row.getInt("SORT_ORDER"), row.getString("TYPE"),
            row.getString("BODY"), row.getString("FILE_NAME"), row.getString("CONTENT_TYPE"),
            row.getLong("FILE_SIZE"), row.getString("CREATED_BY"), row.getInt("ALLOW_AI"), row.getInt("IS_OPENED"));
    private final JdbcTemplate jdbc;
    private final VideoStorage videos;

    public ContentService(JdbcTemplate jdbc, VideoStorage videos) { this.jdbc = jdbc; this.videos = videos; }

    @Transactional(readOnly = true)
    public List<BlockResponse> list(String menuId) {
        return list(menuId, null);
    }

    @Transactional(readOnly = true)
    public List<BlockResponse> list(String menuId, String userType) {
        menuId = ContentMenuKey.of(menuId, userType);
        requireMenu(menuId, false);
        return jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE MENU_ID = ? ORDER BY SORT_ORDER, ID", MAPPER, menuId)
                .stream().map(BlockRow::response).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void attachCode(String menuId, String userType, String reference, String admin) {
        String menu = ContentMenuKey.of(menuId, userType);
        requireMenu(menu, true);
        var existing = jdbc.queryForList("SELECT ID FROM " + TABLE + " WHERE MENU_ID = ? AND TYPE = 'code' AND BINARY BODY = ?", String.class, menu, reference);
        if (!existing.isEmpty()) return;
        jdbc.update("INSERT INTO " + TABLE
                        + " (ID, MENU_ID, SORT_ORDER, TYPE, BODY, CREATED_BY, ALLOW_AI, IS_OPENED) VALUES (?, ?, ?, 'code', ?, ?, 0, 1)",
                UUID.randomUUID().toString(), menu, nextOrder(menu), reference, admin);
    }

    public String codeReference(String id) {
        var rows = jdbc.queryForList("SELECT BODY FROM " + TABLE + " WHERE ID = ? AND TYPE = 'code' AND MENU_ID IS NOT NULL", String.class, id);
        if (rows.isEmpty()) throw notFound("Code block not found");
        return rows.getFirst();
    }

    @Transactional(rollbackFor = IOException.class)
    public UploadResponse upload(MultipartFile file, String expectedType, String admin) throws IOException {
        var info = ContentFilePolicy.inspect(file);
        if (expectedType != null && !expectedType.equals(info.type())) throw badRequest("Choose a file matching the selected content type");
        String id = UUID.randomUUID().toString();
        if (info.type().equals("video")) {
            byte[] reference = videos.store(file);
            jdbc.update("INSERT INTO " + TABLE
                            + " (ID, TYPE, BODY, CONTENT, FILE_NAME, CONTENT_TYPE, FILE_SIZE, CREATED_BY) VALUES (?, ?, '', ?, ?, ?, ?, ?)",
                    id, info.type(), reference, info.name(), info.contentType(), info.size(), admin);
        } else try (var stream = file.getInputStream()) {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO " + TABLE
                        + " (ID, TYPE, BODY, CONTENT, FILE_NAME, CONTENT_TYPE, FILE_SIZE, CREATED_BY) VALUES (?, ?, '', ?, ?, ?, ?, ?)");
                statement.setString(1, id);
                statement.setString(2, info.type());
                statement.setBinaryStream(3, stream, info.size());
                statement.setString(4, info.name());
                statement.setString(5, info.contentType());
                statement.setLong(6, info.size());
                statement.setString(7, admin);
                return statement;
            });
        }
        return new UploadResponse("/api/files/" + id, info.type(), info.name(), info.contentType(), info.size());
    }

    @Transactional
    public BlockResponse create(String menuId, CreateRequest request, String admin) {
        return create(menuId, request, admin, null);
    }

    @Transactional
    public BlockResponse create(String menuId, CreateRequest request, String admin, String userType) {
        menuId = ContentMenuKey.of(menuId, userType);
        if (request.type() == null || !TYPES.contains(request.type())) throw badRequest("Choose text, image, video, or file content");
        String body = validatedBody(request.body() == null ? "" : request.body(), request.type());
        int allowAi = validatedAllowAi(request.allowAi());
        requireMenu(menuId, true);
        int order = nextOrder(menuId);
        String id;
        if (request.type().equals("text")) {
            if (request.fileUrl() != null) throw badRequest("Text blocks cannot contain a file URL");
            id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO " + TABLE + " (ID, MENU_ID, SORT_ORDER, TYPE, BODY, CREATED_BY, ALLOW_AI) VALUES (?, ?, ?, 'text', ?, ?, ?)",
                    id, menuId, order, body, admin, allowAi);
        } else {
            id = fileId(request.fileUrl());
            BlockRow upload = pendingUpload(id, admin);
            if (!upload.type().equals(request.type())) throw badRequest("The uploaded file does not match the block type");
            jdbc.update("UPDATE " + TABLE + " SET MENU_ID = ?, SORT_ORDER = ?, BODY = ?, ALLOW_AI = ?, IS_OPENED = 1 WHERE ID = ?", menuId, order, body, allowAi, id);
        }
        return publishedBlock(id).response();
    }

    @Transactional
    public BlockResponse update(String id, UpdateRequest patch, String admin) {
        if (patch.allowAi() != null) validatedAllowAi(patch.allowAi());
        BlockRow block = publishedBlock(id);
        requireMenu(block.menuId(), true);
        if (block.type().equals("code") && (patch.body() != null || patch.fileUrl() != null)) {
            throw badRequest("Use Add code to replace a saved source.");
        }
        if (patch.body() != null) {
            jdbc.update("UPDATE " + TABLE + " SET BODY = ? WHERE ID = ?", validatedBody(patch.body(), block.type()), id);
        }
        if (patch.allowAi() != null) {
            jdbc.update("UPDATE " + TABLE + " SET ALLOW_AI = ? WHERE ID = ?", patch.allowAi(), id);
        }
        if (patch.sortOrder() != null) {
            if (patch.sortOrder() < 1) throw badRequest("sortOrder must be at least 1");
            jdbc.update("UPDATE " + TABLE + " SET SORT_ORDER = ? WHERE ID = ?", patch.sortOrder(), id);
        }
        if (patch.fileUrl() != null) {
            if (block.type().equals("text")) throw badRequest("Text blocks cannot contain a file URL");
            String sourceId = fileId(patch.fileUrl());
            if (!sourceId.equals(id)) {
                BlockRow upload = pendingUpload(sourceId, admin);
                if (!upload.type().equals(block.type())) throw badRequest("The replacement file does not match the block type");
                retireVideo(id);
                jdbc.update("UPDATE " + TABLE + " AS target JOIN " + TABLE + " AS source ON source.ID = ? "
                        + "SET target.CONTENT = source.CONTENT, target.FILE_NAME = source.FILE_NAME, "
                        + "target.CONTENT_TYPE = source.CONTENT_TYPE, target.FILE_SIZE = source.FILE_SIZE WHERE target.ID = ?", sourceId, id);
                jdbc.update("DELETE FROM " + TABLE + " WHERE ID = ? AND MENU_ID IS NULL", sourceId);
            }
        }
        return publishedBlock(id).response();
    }

    @Transactional
    public OpenStateResponse setOpened(String id, OpenStateRequest request) {
        Integer opened = request.isOpened();
        if (opened == null || (opened != 0 && opened != 1)) throw badRequest("is_opened must be 0 or 1");
        var menus = jdbc.queryForList("SELECT MENU_ID FROM " + TABLE + " WHERE ID = ? AND MENU_ID IS NOT NULL", String.class, id);
        if (menus.isEmpty()) throw notFound("Content block not found");
        String menu = menus.getFirst();
        requireMenu(menu, true);
        // Match content-mutation lock ordering, and never expose a pending upload.
        var states = jdbc.queryForList("SELECT IS_OPENED FROM " + TABLE + " WHERE ID = ? AND MENU_ID = ? FOR UPDATE", Integer.class, id, menu);
        if (states.isEmpty()) throw notFound("Content block not found");
        jdbc.update("UPDATE " + TABLE + " SET IS_OPENED = ? WHERE ID = ? AND MENU_ID = ?", opened, id, menu);
        return new OpenStateResponse(id, opened);
    }

    @Transactional
    public void delete(String id) {
        BlockRow block = publishedBlock(id);
        requireMenu(block.menuId(), true);
        retireVideo(id);
        if (jdbc.update("DELETE FROM " + TABLE + " WHERE ID = ? AND MENU_ID IS NOT NULL", id) == 0) throw notFound("Content block not found");
    }

    @Transactional(readOnly = true)
    public StoredFile file(String id) {
        var files = jdbc.query("SELECT CONTENT, FILE_NAME, CONTENT_TYPE, TYPE, FILE_SIZE FROM " + TABLE
                        + " WHERE ID = ? AND MENU_ID IS NOT NULL AND CONTENT IS NOT NULL",
                (row, index) -> {
                    byte[] data = row.getBytes("CONTENT");
                    Resource resource = row.getString("TYPE").equals("video") && VideoStorage.isReference(data)
                            ? videos.resource(data, row.getLong("FILE_SIZE")) : new ByteArrayResource(data);
                    return new StoredFile(resource, row.getString("FILE_NAME"), row.getString("CONTENT_TYPE"), row.getString("TYPE"));
                }, id);
        if (files.isEmpty()) throw notFound("File not found");
        return files.getFirst();
    }

    private BlockRow publishedBlock(String id) {
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE ID = ? AND MENU_ID IS NOT NULL", MAPPER, id);
        if (rows.isEmpty()) throw notFound("Content block not found");
        return rows.getFirst();
    }

    private void retireVideo(String id) {
        // Fetch only small references, never the existing binary video during replacement/deletion.
        jdbc.query("SELECT CONTENT FROM " + TABLE + " WHERE ID = ? AND TYPE = 'video' AND OCTET_LENGTH(CONTENT) = ? FOR UPDATE",
                (row, index) -> row.getBytes("CONTENT"), id, VideoStorage.REFERENCE_BYTES).forEach(videos::deleteAfterCommit);
    }

    private BlockRow pendingUpload(String id, String admin) {
        var rows = jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE ID = ? AND MENU_ID IS NULL AND CREATED_BY = ? FOR UPDATE", MAPPER, id, admin);
        if (rows.isEmpty()) throw notFound("Upload not found or already attached to a page");
        return rows.getFirst();
    }

    private void requireMenu(String id, boolean lock) {
        String table = ContentMenuKey.isOperator(id) ? "MG_OPER_MENU_AMN" : "PT_MENU_AMN";
        var menus = jdbc.queryForList("SELECT MENU_ID FROM " + table + " WHERE MENU_ID = ?" + (lock ? " FOR UPDATE" : ""),
                String.class, ContentMenuKey.menuId(id));
        if (menus.isEmpty()) throw notFound("Menu not found");
    }

    private int nextOrder(String menuId) {
        Integer maximum = jdbc.queryForObject("SELECT COALESCE(MAX(SORT_ORDER), 0) FROM " + TABLE + " WHERE MENU_ID = ?", Integer.class, menuId);
        return Math.addExact(maximum == null ? 0 : maximum, 1);
    }

    private int validatedAllowAi(Integer value) {
        if (value != null && value != 0 && value != 1) throw badRequest("allow_ai must be 0 or 1");
        return value == null ? 0 : value;
    }

    private String validatedBody(String body, String type) {
        if (body.length() > 1_000_000) throw badRequest("Text must be 1,000,000 characters or fewer");
        if (type.equals("text") && body.isBlank()) throw badRequest("Enter text before saving");
        return body;
    }

    private String fileId(String url) {
        if (url == null || url.isBlank()) throw badRequest("Upload a file before creating this block");
        try {
            String path = URI.create(url).getPath();
            var matcher = FILE_PATH.matcher(path == null ? "" : path);
            if (!matcher.matches()) throw new IllegalArgumentException();
            return UUID.fromString(matcher.group(1)).toString();
        } catch (IllegalArgumentException error) { throw badRequest("Use a file uploaded through this website"); }
    }

    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }

    public record CreateRequest(String type, String body, String fileUrl, String fileName, String contentType, Long fileSize,
                                @JsonProperty("allow_ai") Integer allowAi) {
        public CreateRequest(String type, String body, String fileUrl, String fileName, String contentType, Long fileSize) {
            this(type, body, fileUrl, fileName, contentType, fileSize, null);
        }
    }
    public record UpdateRequest(String body, String fileUrl, Integer sortOrder, @JsonProperty("allow_ai") Integer allowAi) {
        public UpdateRequest(String body, String fileUrl, Integer sortOrder) { this(body, fileUrl, sortOrder, null); }
    }
    public record UploadResponse(String url, String type, String fileName, String contentType, long fileSize) {}
    public record OpenStateRequest(@JsonProperty("is_opened") Integer isOpened) {}
    public record OpenStateResponse(String id, @JsonProperty("is_opened") int isOpened) {}
    public record BlockResponse(String id, String menuId, String type, String body, String fileUrl, int sortOrder,
                                String fileName, String contentType, long fileSize, @JsonProperty("allow_ai") int allowAi,
                                @JsonProperty("is_opened") int isOpened) {
        public BlockResponse(String id, String menuId, String type, String body, String fileUrl, int sortOrder,
                             String fileName, String contentType, long fileSize, int allowAi) {
            this(id, menuId, type, body, fileUrl, sortOrder, fileName, contentType, fileSize, allowAi, 1);
        }
        public BlockResponse(String id, String menuId, String type, String body, String fileUrl, int sortOrder,
                             String fileName, String contentType, long fileSize) {
            this(id, menuId, type, body, fileUrl, sortOrder, fileName, contentType, fileSize, 0);
        }
    }
    public record StoredFile(Resource resource, String name, String contentType, String type) {}
    private record BlockRow(String id, String menuId, int sortOrder, String type, String body,
                            String fileName, String contentType, long fileSize, String createdBy, int allowAi, int isOpened) {
        BlockResponse response() {
            return new BlockResponse(id, ContentMenuKey.menuId(menuId), type, body, fileName == null ? null : "/api/files/" + id,
                    sortOrder, fileName, contentType, fileSize, allowAi, isOpened);
        }
    }
}
