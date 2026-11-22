package esum.escm.guidance_server.codeupload;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "JAVA_CODES_DB_TEST", matches = "true")
class CodeUploadDatabaseTest {
    private static JdbcTemplate jdbc;
    private static CodeUploadService service;
    private static TransactionTemplate transaction;

    @BeforeAll static void prepareDatabase() throws IOException {
        String url = System.getenv("JAVA_CODES_TEST_JDBC_URL");
        assertNotNull(url, "Provide the isolated MySQL JDBC URL");
        assertTrue(Pattern.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/java_codes_test", url),
                "Use a loopback-only, empty java_codes_test database");
        var database = new DriverManagerDataSource(url, System.getenv("JAVA_CODES_TEST_USER"),
                System.getenv("JAVA_CODES_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(database);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()", Integer.class));
        jdbc.execute("""
                CREATE TABLE `JAVA_CODES` (
                  `CLASS_TYPE` VARCHAR(2000), `PACKAGE` VARCHAR(2000) NOT NULL,
                  `CLASS_NAME` VARCHAR(2000) NOT NULL, `CODE` LONGTEXT NOT NULL,
                  `IMPORTS` VARCHAR(2000), `LANGUAGE_NAME` VARCHAR(2000) NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """);
        jdbc.execute(Files.readString(Path.of("sql/java-codes-upload.sql")));
        service = new CodeUploadService(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        transaction.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    }

    @Test void mapsAllSixFieldsAndSeparatesCaseSensitiveNames() {
        var source = new CodeUploadRequest("record", "org.예제", "RecordName", "import java.util.List;",
                "\ufeffpackage org.예제;\r\n\t// A 🚀\r\nrecord RecordName() {}\r\n", " Java ");
        var saved = create(source);
        assertEquals(HttpStatus.CREATED, saved.status());
        assertEquals(HttpStatus.CREATED, create(new CodeUploadRequest("class", "org.예제", "recordName", null,
                "class recordName {}", "java")).status());
        assertEquals(HttpStatus.CREATED, create(new CodeUploadRequest("class", "Org.예제", "RecordName", null,
                "class RecordName {}", "java")).status());
        var row = jdbc.queryForMap("SELECT * FROM `JAVA_CODES` WHERE BINARY `PACKAGE`=? AND BINARY `CLASS_NAME`=?",
                source.packageName(), source.className());
        assertEquals("record", row.get("CLASS_TYPE"));
        assertEquals(source.packageName(), row.get("PACKAGE"));
        assertEquals(source.className(), row.get("CLASS_NAME"));
        assertEquals(source.imports(), row.get("IMPORTS"));
        assertEquals(source.code(), row.get("CODE"));
        assertEquals("java", row.get("LANGUAGE_NAME"));
        assertEquals(source.code(), service.get(saved.sourceId()).code());
        assertTrue(service.list(0).items().stream().anyMatch(item -> item.id().equals(saved.sourceId())));
    }

    @Test void concurrentInsertCreatesOneRowAndStaleConfirmDoesNotReplaceIt() throws Exception {
        var initial = python("one");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return create(initial); });
            var b = pool.submit(() -> { start.await(); return create(initial); });
            start.countDown();
            var results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(row -> row.status() == HttpStatus.CREATED).count());
            var conflict = results.stream().filter(row -> row.status() == HttpStatus.CONFLICT).findFirst().orElseThrow();
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM `JAVA_CODES` WHERE `LANGUAGE_NAME`='python'", Integer.class));
            String confirmed = quote(conflict.revision());
            assertEquals(HttpStatus.NO_CONTENT, replace(python("two"), confirmed).status());
            var stale = replace(python("three"), confirmed);
            assertEquals(HttpStatus.PRECONDITION_FAILED, stale.status());
            assertNotEquals(conflict.revision(), stale.revision());
            assertEquals("two", jdbc.queryForObject("SELECT `CODE` FROM `JAVA_CODES` WHERE `LANGUAGE_NAME`='python'", String.class));
            var replaced = replace(python("three"), quote(stale.revision()));
            assertEquals(HttpStatus.NO_CONTENT, replaced.status());
            assertEquals("three", service.get(replaced.sourceId()).code());
            assertEquals("three", jdbc.queryForObject("SELECT `CODE` FROM `JAVA_CODES` WHERE `LANGUAGE_NAME`='python'", String.class));
        }
    }

    private CodeUploadService.Result create(CodeUploadRequest source) {
        return transaction.execute(status -> service.create(source));
    }

    private CodeUploadService.Result replace(CodeUploadRequest source, String token) {
        return transaction.execute(status -> service.replace(source, token));
    }

    private static CodeUploadRequest python(String code) {
        return new CodeUploadRequest(null, "", "script.py", null, code, "Python");
    }

    private static String quote(String revision) {
        return '"' + revision + '"';
    }
}
