package esum.escm.guidance_server.codeupload;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public record CodeUploadRequest(
        @JsonProperty("class_type") String classType,
        @JsonProperty("package") String packageName,
        @JsonProperty("class_name") String className,
        String imports,
        String code,
        @JsonProperty("language_name") String languageName) {
    private static final Set<String> JAVA_TYPES = Set.of(
            "class", "abstract_class", "interface", "enum", "record", "annotation_interface");
    private static final String JAVA_IDENTIFIER = "\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*";
    private static final Pattern JAVA_NAME = Pattern.compile(JAVA_IDENTIFIER);
    private static final Pattern JAVA_PACKAGE = Pattern.compile(JAVA_IDENTIFIER + "(?:\\." + JAVA_IDENTIFIER + ")*");

    CodeUploadRequest validated() {
        if (languageName == null || languageName.isBlank()) throw invalid("Choose a language.");
        String language = languageName.strip().toLowerCase(Locale.ROOT);
        if (language.equals("none") || language.equals("other")) throw invalid("Choose a language.");
        if (language.codePoints().anyMatch(Character::isISOControl)) throw invalid("Invalid language name.");
        if (className == null || className.isBlank()) throw invalid("Enter a source name.");
        String name = className.strip();
        if (name.codePoints().anyMatch(Character::isISOControl)) throw invalid("Invalid source name.");
        if (packageName == null) throw invalid("The package field is required.");
        if (code == null || code.isBlank()) throw invalid("Enter or choose source code.");
        if (code.length() > 1_000_000 || code.getBytes(StandardCharsets.UTF_8).length > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Source code must be 1 MB or smaller.");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(code)) throw invalid("Source code must be valid UTF-8.");
        checkMetadata(language, "Language name", 2000);
        checkMetadata(name, "Source name", 200);
        checkMetadata(packageName, "Package", 200);
        checkMetadata(classType, "Class type", 2000);
        checkMetadata(imports, "Imports", 2000);

        if (language.equals("java")) {
            if (classType == null || !JAVA_TYPES.contains(classType)) throw invalid("Choose a valid Java class type.");
            if (!JAVA_NAME.matcher(name).matches() || (!packageName.isEmpty() && !JAVA_PACKAGE.matcher(packageName).matches())) {
                throw invalid("Invalid Java package or class name.");
            }
        } else if (classType != null || !packageName.isEmpty() || imports != null) {
            throw invalid("Package, imports and class type apply only to Java source.");
        }
        return new CodeUploadRequest(classType, packageName, name, imports, code, language);
    }

    private static void checkMetadata(String value, String field, int limit) {
        if (value == null) return;
        if (value.codePointCount(0, value.length()) > limit) throw invalid(field + " must be " + limit + " characters or fewer.");
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw invalid(field + " must be valid UTF-8.");
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
