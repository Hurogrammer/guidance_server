package esum.escm.guidance_server.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Video bytes live on persistent disk. Only this small, versioned reference is stored in CONTENT. */
@Component
public class VideoStorage {
    private static final Logger log = LoggerFactory.getLogger(VideoStorage.class);
    private static final String PREFIX = "escm-video:v1:";
    public static final int REFERENCE_BYTES = PREFIX.length() + 36;
    private final Path directory;

    public VideoStorage(@Value("${content.video-storage-path:${user.home}/.escm/guidance/videos}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    byte[] store(MultipartFile file) throws IOException {
        requireTransaction();
        Files.createDirectories(directory);
        String key = UUID.randomUUID().toString();
        Path target = directory.resolve(key);
        try (var input = file.getInputStream();
             var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[128 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > ContentFilePolicy.MAX_VIDEO_BYTES) {
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Videos must be 3 GB or smaller");
                }
                output.write(buffer, 0, count);
            }
            if (total != file.getSize()) throw new IOException("The video upload was incomplete");
        } catch (IOException | RuntimeException error) {
            remove(key);
            throw error;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) remove(key);
            }
        });
        return (PREFIX + key).getBytes(StandardCharsets.US_ASCII);
    }

    Resource resource(byte[] reference, long size) {
        String key = key(reference);
        if (key == null) throw new IllegalArgumentException("Invalid video storage reference");
        Path target = directory.resolve(key);
        try {
            if (!Files.isRegularFile(target) || Files.size(target) != size) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video file not found");
            }
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Video storage is unavailable");
        }
        return new FileSystemResource(target);
    }

    public void deleteAfterCommit(byte[] reference) {
        String key = key(reference);
        if (key == null) return;
        requireTransaction();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { remove(key); }
        });
    }

    static boolean isReference(byte[] reference) { return key(reference) != null; }

    private static String key(byte[] reference) {
        if (reference == null || reference.length != REFERENCE_BYTES) return null;
        String value = new String(reference, StandardCharsets.US_ASCII);
        if (!value.startsWith(PREFIX)) return null;
        String key = value.substring(PREFIX.length());
        // Never resolve a client-supplied filename or a path read verbatim from the database.
        return key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ? key : null;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Video storage requires a database transaction");
        }
    }

    private void remove(String key) {
        try { Files.deleteIfExists(directory.resolve(key)); }
        catch (IOException error) { log.warn("Could not clean up stored video {}", key, error); }
    }
}
