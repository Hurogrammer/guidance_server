package esum.escm.guidance_server.codeupload;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class CodeUploadService {
    private static final int PAGE_SIZE = 20;
    private static final String SUMMARY_COLUMNS = "`SOURCE_KEY`, `CLASS_NAME`, `PACKAGE`, `CLASS_TYPE`, `LANGUAGE_NAME`";
    private static final String INSERT = """
            INSERT INTO `JAVA_CODES` (`CLASS_TYPE`, `PACKAGE`, `CLASS_NAME`, `IMPORTS`, `CODE`, `LANGUAGE_NAME`)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT = "SELECT `CODE_REVISION` FROM `JAVA_CODES` WHERE `SOURCE_KEY` = ?";
    private static final String UPDATE = """
            UPDATE `JAVA_CODES` SET `CLASS_TYPE` = ?, `IMPORTS` = ?, `CODE` = ?, `CODE_REVISION` = `CODE_REVISION` + 1
            WHERE `SOURCE_KEY` = ?
            """;
    private static final Pattern IF_MATCH = Pattern.compile("\"[0-9a-f]{64}\"");
    private static final Pattern SOURCE_ID = Pattern.compile("[0-9a-f]{64}");
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private final JdbcTemplate jdbc;

    public CodeUploadService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SourcePage list(int page) {
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source page.");
        String sql = "SELECT " + SUMMARY_COLUMNS + """
                 FROM `JAVA_CODES` WHERE `SOURCE_KEY` IS NOT NULL
                 ORDER BY `CLASS_NAME`, `PACKAGE`, `LANGUAGE_NAME`, `SOURCE_KEY` LIMIT ? OFFSET ?
                """;
        List<SourceSummary> rows = jdbc.query(sql, (row, index) -> summary(row), PAGE_SIZE + 1, (long) page * PAGE_SIZE);
        if (rows.size() > PAGE_SIZE) return new SourcePage(rows.subList(0, PAGE_SIZE), true);
        return new SourcePage(rows, false);
    }

    public Source get(String id) {
        if (!SOURCE_ID.matcher(id).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source ID.");
        String sql = "SELECT " + SUMMARY_COLUMNS + ", `CODE` FROM `JAVA_CODES` WHERE `SOURCE_KEY` = ?";
        return jdbc.query(sql, (row, index) -> new Source(summary(row), row.getString("CODE")), HexFormat.of().parseHex(id))
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source code not found."));
    }

    private static SourceSummary summary(ResultSet row) throws SQLException {
        return new SourceSummary(HexFormat.of().formatHex(row.getBytes("SOURCE_KEY")), row.getString("CLASS_NAME"),
                row.getString("PACKAGE"), row.getString("CLASS_TYPE"), row.getString("LANGUAGE_NAME"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result create(CodeUploadRequest request) {
        var source = request.validated();
        byte[] key = sourceKey(source);
        try {
            jdbc.update(INSERT, source.classType(), source.packageName(), source.className(),
                    source.imports(), source.code(), source.languageName());
            return new Result(HttpStatus.CREATED, null, HexFormat.of().formatHex(key));
        } catch (DuplicateKeyException error) {
            var revision = currentRevision(key, false);
            if (revision.isEmpty()) throw error;
            return new Result(HttpStatus.CONFLICT, revisionToken(key, revision.get()), null);
        }
    }

    @Transactional
    public Result replace(CodeUploadRequest request, String ifMatch) {
        if (ifMatch == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "Confirm the replacement first.");
        if (!IF_MATCH.matcher(ifMatch).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid code revision.");
        var source = request.validated();
        byte[] key = sourceKey(source);
        var revision = currentRevision(key, true);
        if (revision.isEmpty()) return new Result(HttpStatus.PRECONDITION_FAILED, null, null);
        String current = revisionToken(key, revision.get());
        if (!ifMatch.equals('"' + current + '"')) {
            return new Result(HttpStatus.PRECONDITION_FAILED, current, null);
        }
        jdbc.update(UPDATE, source.classType(), source.imports(), source.code(), key);
        return new Result(HttpStatus.NO_CONTENT, null, HexFormat.of().formatHex(key));
    }

    private Optional<Long> currentRevision(byte[] key, boolean lock) {
        String sql = lock ? SELECT + " FOR UPDATE" : SELECT;
        return jdbc.query(sql, (row, index) -> row.getLong("CODE_REVISION"), key).stream().findFirst();
    }

    private static byte[] sourceKey(CodeUploadRequest source) {
        // Match the generated key in java-codes-upload.sql, regardless of table collation.
        return sha256(hexUtf8(source.languageName()) + ':' + hexUtf8(source.packageName()) + ':' + hexUtf8(source.className()));
    }

    private static String hexUtf8(String value) {
        return HEX.formatHex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String revisionToken(byte[] key, long revision) {
        return HexFormat.of().formatHex(sha256(HEX.formatHex(key) + ':' + revision));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    record Result(HttpStatus status, String revision, String sourceId) {}
    record SourceSummary(String id, String className, String packageName, String classType, String languageName) {}
    record Source(SourceSummary summary, String code) {}
    record SourcePage(List<SourceSummary> items, boolean hasMore) {}
}
