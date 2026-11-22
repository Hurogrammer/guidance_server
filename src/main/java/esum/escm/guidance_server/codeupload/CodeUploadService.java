package esum.escm.guidance_server.codeupload;

import esum.escm.guidance_server.content.ContentService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class CodeUploadService {
    private static final String KEY_CONDITION = " WHERE `PACKAGE` = ? AND `CLASS_NAME` = ?";
    private static final String INSERT = """
            INSERT INTO `JAVA_CODES` (`CLASS_TYPE`, `PACKAGE`, `CLASS_NAME`, `IMPORTS`, `CODE`, `LANGUAGE_NAME`)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_REVISION =
            "SELECT `PACKAGE`, `CLASS_NAME`, `CODE_REVISION` FROM `JAVA_CODES`" + KEY_CONDITION;
    private static final String SELECT_SOURCE =
            "SELECT `PACKAGE`, `CLASS_NAME`, `CLASS_TYPE`, `LANGUAGE_NAME`, `CODE` FROM `JAVA_CODES`" + KEY_CONDITION;
    private static final String UPDATE = """
            UPDATE `JAVA_CODES` SET `CLASS_TYPE` = ?, `IMPORTS` = ?, `CODE` = ?, `LANGUAGE_NAME` = ?,
                                    `CODE_REVISION` = `CODE_REVISION` + 1
            """ + KEY_CONDITION;
    private static final Pattern IF_MATCH = Pattern.compile('"' + "[0-9a-f]{64}" + '"');
    private static final HexFormat HEX = HexFormat.of();
    private final JdbcTemplate jdbc;
    private final ContentService content;

    public CodeUploadService(JdbcTemplate jdbc, ContentService content) {
        this.jdbc = jdbc;
        this.content = content;
    }

    @Transactional(readOnly = true)
    public Source get(String blockId) {
        String reference = content.codeReference(blockId);
        // An empty package is encoded with a leading dot, including for filenames with extensions.
        int separator = reference.startsWith(".") ? 0 : reference.lastIndexOf('.');
        if (separator < 0 || separator == reference.length() - 1) throw sourceNotFound();
        return jdbc.query(SELECT_SOURCE, (row, index) -> new Source(row.getString("PACKAGE"),
                        row.getString("CLASS_NAME"), row.getString("CLASS_TYPE"), row.getString("LANGUAGE_NAME"), row.getString("CODE")),
                reference.substring(0, separator), reference.substring(separator + 1))
                .stream().findFirst().orElseThrow(CodeUploadService::sourceNotFound);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result create(String menuId, String userType, String admin, CodeUploadRequest request) {
        var source = request.validated();
        var key = new SourceKey(source.packageName(), source.className());
        try {
            jdbc.update(INSERT, source.classType(), key.packageName(), key.className(),
                    source.imports(), source.code(), source.languageName());
        } catch (DuplicateKeyException error) {
            var existing = findRevision(key, false).orElseThrow(() -> error);
            return new Result(HttpStatus.CONFLICT, existing.token());
        }
        content.attachCode(menuId, userType, key.reference(), source.description(), admin);
        return new Result(HttpStatus.CREATED, null);
    }

    @Transactional
    public Result replace(String menuId, String userType, String admin, CodeUploadRequest request, String ifMatch) {
        if (ifMatch == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "Confirm the replacement first.");
        if (!IF_MATCH.matcher(ifMatch).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid code revision.");
        var source = request.validated();
        var revision = findRevision(new SourceKey(source.packageName(), source.className()), true);
        if (revision.isEmpty()) return new Result(HttpStatus.PRECONDITION_FAILED, null);
        var existing = revision.get();
        String current = existing.token();
        if (!ifMatch.equals('"' + current + '"')) return new Result(HttpStatus.PRECONDITION_FAILED, current);

        // Use the stored key so case-insensitive primary-key matches have one canonical reference.
        var key = existing.key();
        jdbc.update(UPDATE, source.classType(), source.imports(), source.code(), source.languageName(),
                key.packageName(), key.className());
        content.attachCode(menuId, userType, key.reference(), source.description(), admin);
        return new Result(HttpStatus.NO_CONTENT, null);
    }

    private Optional<SourceRevision> findRevision(SourceKey key, boolean lock) {
        String sql = lock ? SELECT_REVISION + " FOR UPDATE" : SELECT_REVISION;
        return jdbc.query(sql, (row, index) -> new SourceRevision(
                        new SourceKey(row.getString("PACKAGE"), row.getString("CLASS_NAME")), row.getLong("CODE_REVISION")),
                key.packageName(), key.className()).stream().findFirst();
    }

    private static ResponseStatusException sourceNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Source code not found.");
    }

    private record SourceKey(String packageName, String className) {
        String reference() { return packageName + "." + className; }
    }

    private record SourceRevision(SourceKey key, long revision) {
        String token() {
            String value = HEX.formatHex(key.packageName().getBytes(StandardCharsets.UTF_8)) + ':'
                    + HEX.formatHex(key.className().getBytes(StandardCharsets.UTF_8)) + ':' + revision;
            try {
                return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
        }
    }

    record Result(HttpStatus status, String revision) {}
    record Source(String packageName, String className, String classType, String languageName, String code) {}
}
