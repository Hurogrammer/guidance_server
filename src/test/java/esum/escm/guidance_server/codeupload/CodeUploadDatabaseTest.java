package esum.escm.guidance_server.codeupload;

import esum.escm.guidance_server.content.ContentService;
import esum.escm.guidance_server.content.VideoStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "JAVA_CODES_DB_TEST", matches = "true")
class CodeUploadDatabaseTest {
    private static JdbcTemplate jdbc;
    private static CodeUploadService service;
    private static ContentService content;
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
                  `CLASS_TYPE` VARCHAR(2000), `PACKAGE` VARCHAR(200) NOT NULL,
                  `CLASS_NAME` VARCHAR(200) NOT NULL, `CODE` LONGTEXT NOT NULL,
                  `IMPORTS` VARCHAR(2000), `LANGUAGE_NAME` VARCHAR(2000) NOT NULL,
                  PRIMARY KEY (`PACKAGE`, `CLASS_NAME`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """);
        jdbc.execute("CREATE TABLE PT_MENU_AMN (MENU_ID VARCHAR(20) PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE MG_OPER_MENU_AMN (MENU_ID VARCHAR(20) PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO PT_MENU_AMN VALUES ('menu-a'), ('menu-b')");
        jdbc.execute("INSERT INTO MG_OPER_MENU_AMN VALUES ('menu-a')");
        jdbc.execute("""
                CREATE TABLE `CONTENT BLOCKS` (
                    ID VARCHAR(36) PRIMARY KEY, MENU_ID VARCHAR(20), SORT_ORDER INT NOT NULL, TYPE VARCHAR(10) NOT NULL,
                    BODY LONGTEXT NOT NULL, CONTENT LONGBLOB, FILE_NAME VARCHAR(255), CONTENT_TYPE VARCHAR(127),
                    FILE_SIZE BIGINT NOT NULL DEFAULT 0, CREATED_BY VARCHAR(15) NOT NULL, ALLOW_AI INT NOT NULL DEFAULT 0,
                    IS_OPENED INT NOT NULL DEFAULT 1, INDEX (MENU_ID, SORT_ORDER, ID),
                    CONSTRAINT CK_CONTENT_BLOCK_TYPE CHECK (TYPE IN ('text', 'image', 'video', 'file'))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.execute(Files.readString(Path.of("sql/java-codes-upload.sql")));
        jdbc.execute(Files.readString(Path.of("sql/code-content-block-type.sql")));
        content = new ContentService(jdbc, mock(VideoStorage.class));
        service = new CodeUploadService(jdbc, content);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(database));
        transaction.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    }

    @Test void savesExactSourceAndPublishesItsReferenceOnlyOnTheUploadingPage() {
        var source = new CodeUploadRequest("record", "org.예제", "RecordName", "import java.util.List;",
                "\ufeffpackage org.예제;\r\n\t// A 🚀\r\nrecord RecordName() {}\r\n", " Java ");
        var saved = create("menu-a", null, source);
        assertEquals(HttpStatus.CREATED, saved.status());
        var row = jdbc.queryForMap("SELECT * FROM `JAVA_CODES` WHERE BINARY `PACKAGE`=? AND BINARY `CLASS_NAME`=?",
                source.packageName(), source.className());
        assertEquals("record", row.get("CLASS_TYPE"));
        assertEquals(source.packageName(), row.get("PACKAGE"));
        assertEquals(source.className(), row.get("CLASS_NAME"));
        assertEquals(source.imports(), row.get("IMPORTS"));
        assertEquals(source.code(), row.get("CODE"));
        assertEquals("java", row.get("LANGUAGE_NAME"));
        var block = content.list("menu-a").getFirst();
        assertEquals("code", block.type());
        assertEquals("org.예제.RecordName", block.body());
        assertEquals(source.code(), service.get(block.id()).code());
        assertTrue(content.list("menu-b").stream().noneMatch(item -> item.body().equals(block.body())));
        assertTrue(content.list("menu-a", "99").stream().noneMatch(item -> item.body().equals(block.body())));
    }

    @Test void rollsBackSourceWhenAttachingToAnUnknownMenu() {
        var source = new CodeUploadRequest(null, "", "rollback.py", null, "print(1)", "Python");
        var error = assertThrows(ResponseStatusException.class, () -> create("missing", null, source));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `JAVA_CODES` WHERE `CLASS_NAME`='rollback.py'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM `CONTENT BLOCKS` WHERE MENU_ID='missing'", Integer.class));
    }

    @Test void concurrentInsertCreatesOneRowAndStaleConfirmDoesNotReplaceIt() throws Exception {
        var initial = python("one");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return create("menu-a", "99", initial); });
            var b = pool.submit(() -> { start.await(); return create("menu-a", "99", initial); });
            start.countDown();
            var results = List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(row -> row.status() == HttpStatus.CREATED).count());
            var conflict = results.stream().filter(row -> row.status() == HttpStatus.CONFLICT).findFirst().orElseThrow();
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM `JAVA_CODES` WHERE `LANGUAGE_NAME`='python'", Integer.class));
            String confirmed = quote(conflict.revision());
            assertEquals(HttpStatus.NO_CONTENT, replace("menu-a", "99", python("two"), confirmed).status());
            var stale = replace("menu-b", null, python("three"), confirmed);
            assertEquals(HttpStatus.PRECONDITION_FAILED, stale.status());
            assertNotEquals(conflict.revision(), stale.revision());
            assertEquals("two", jdbc.queryForObject("SELECT `CODE` FROM `JAVA_CODES` WHERE `LANGUAGE_NAME`='python'", String.class));
            var replaced = replace("menu-b", null, python("three"), quote(stale.revision()));
            assertEquals(HttpStatus.NO_CONTENT, replaced.status());
            var operator = content.list("menu-a", "99");
            assertEquals(1, operator.size());
            assertEquals(".script.py", operator.getFirst().body());
            assertEquals("three", service.get(operator.getFirst().id()).code());
            assertEquals(".script.py", content.list("menu-b").getFirst().body());
            assertEquals("three", jdbc.queryForObject("SELECT `CODE` FROM `JAVA_CODES` WHERE `LANGUAGE_NAME`='python'", String.class));
        }
    }

    private CodeUploadService.Result create(String menu, String userType, CodeUploadRequest source) {
        return transaction.execute(status -> service.create(menu, userType, "admin", source));
    }

    private CodeUploadService.Result replace(String menu, String userType, CodeUploadRequest source, String token) {
        return transaction.execute(status -> service.replace(menu, userType, "admin", source, token));
    }

    private static CodeUploadRequest python(String code) {
        return new CodeUploadRequest(null, "", "script.py", null, code, "Python");
    }

    private static String quote(String revision) {
        return '"' + revision + '"';
    }
}
