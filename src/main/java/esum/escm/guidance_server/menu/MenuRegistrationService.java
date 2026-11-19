package esum.escm.guidance_server.menu;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

@Service
public class MenuRegistrationService {
    private final JdbcTemplate jdbc;
    public MenuRegistrationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public Recommendation recommend(String userType, String parentId, String title, String prefix) {
        requireUserType(userType, false);
        Parent parent = parent(parentId, userType, false);
        int depth = parent == null ? 0 : parent.depth() + 1;
        if (depth == 0) {
            var matches = title == null || title.isBlank() ? List.<Match>of() : jdbc.query(
                    "SELECT MENU_ID, MENU_NM, USR_TPC FROM PT_MENU_AMN WHERE MENU_LVL = '0'"
                            + " AND TRIM(MENU_NM) = ? AND MENU_ID REGEXP '^[A-Za-z0-9]{2}000000$'"
                            + " ORDER BY CASE WHEN TRIM(USR_TPC) = ? THEN 0 ELSE 1 END, MENU_ID",
                    (row, index) -> new Match(row.getString("MENU_ID"), row.getString("MENU_NM"), row.getString("USR_TPC")), title.trim(), userType);
            return new Recommendation(0, null, null, userType,
                    matches.isEmpty() ? "" : matches.getFirst().menuId().substring(0, 2), "000", "000", matches);
        }
        var existing = depth != 2 || (prefix != null && !prefix.isBlank()) ? List.<String>of()
                : jdbc.queryForList("SELECT MENU_ID FROM PT_MENU_AMN WHERE PARENT_ID = ? AND TRIM(USR_TPC) = ?"
                        + " AND MENU_LVL = '2' AND MENU_ID REGEXP '^[A-Za-z0-9]{2}[0-9]{6}$' ORDER BY MENU_ID LIMIT 1", String.class, parent.id(), userType);
        String base = depth == 1 ? parent.id().substring(0, 2)
                : prefix == null || prefix.isBlank() ? (existing.isEmpty() ? parent.id() : existing.getFirst()).substring(0, 5) : prefix;
        if (depth == 2 && !base.matches("[A-Za-z0-9]{2}[0-9]{3}")) throw bad("Enter two letters or digits followed by three digits for the depth-2 prefix");
        List<String> ids = jdbc.queryForList("SELECT MENU_ID FROM PT_MENU_AMN WHERE MENU_ID LIKE ?", String.class, base + "%");
        String free = freeSegment(ids, base, depth);
        return new Recommendation(depth, parent.id(), parent.title(), userType, base.substring(0, 2),
                depth == 1 ? free : base.substring(2), depth == 1 ? "000" : free, List.of());
    }

    static String freeSegment(List<String> ids, String prefix, int depth) {
        Set<String> used = new HashSet<>();
        for (String id : ids) {
            // MENU_ID uses a case-insensitive collation; case variants occupy the same prefix.
            if (id.matches("[A-Za-z0-9]{2}[0-9]{6}") && id.regionMatches(true, 0, prefix, 0, prefix.length())) {
                used.add(depth == 1 ? id.substring(2, 5) : id.substring(5));
            }
        }
        for (int i = 1; i <= 999; i++) {
            String segment = String.format(java.util.Locale.ROOT, "%03d", i);
            if (!used.contains(segment)) return segment;
        }
        return "";
    }

    @Transactional(readOnly = true)
    public Availability availability(String menuId) {
        requireId(menuId);
        return new Availability(!exists(menuId));
    }

    @Transactional
    public MenuResponse create(CreateRequest request, String admin) {
        requireId(request.menuId());
        if (request.title() == null || request.title().isBlank() || request.title().trim().length() > 100) {
            throw bad("Enter a menu name of 1 to 100 characters");
        }
        // A catalog-row lock serializes registration within a user type, including top menus.
        requireUserType(request.userType(), true);
        Parent parent = parent(request.parentId(), request.userType(), true);
        int depth = parent == null ? 0 : parent.depth() + 1;
        String id = request.menuId();
        if (depth == 0 && !id.endsWith("000000")) throw bad("Depth-0 IDs must end in six zeroes");
        if (depth == 1 && (!id.startsWith(parent.id().substring(0, 2)) || !id.endsWith("000") || id.substring(2, 5).equals("000"))) {
            throw bad("Depth-1 IDs must use the parent's first two characters, a number from 001 to 999, and end in 000");
        }
        if (depth == 2 && id.endsWith("000")) throw bad("The last three digits must be between 001 and 999");
        if (exists(id)) throw duplicate();
        Integer maximum = jdbc.queryForObject("SELECT COALESCE(MAX(SORT_ORDER), 0) FROM PT_MENU_AMN"
                + " WHERE NULLIF(TRIM(PARENT_ID), '') <=> ? AND TRIM(USR_TPC) = ?", Integer.class,
                parent == null ? null : parent.id(), request.userType());
        int order = Math.addExact(maximum == null ? 0 : maximum, 1);
        String title = request.title().trim();
        try {
            jdbc.update("INSERT INTO PT_MENU_AMN (MENU_ID, MENU_LVL, MENU_NM, PARENT_ID, USR_TPC, SORT_ORDER,"
                            + " UYN, SCRN_MRK_YN, FS_RGM, LS_CHGM, FSRG_DTM, LSCHG_DTM) VALUES (?, ?, ?, ?, ?, ?, 'Y', 'Y', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    id, String.valueOf(depth), title, parent == null ? null : parent.id(), request.userType(), order, admin, admin);
        } catch (DuplicateKeyException error) { throw duplicate(); }
        return new MenuResponse(id, parent == null ? null : parent.id(), title, order, request.userType(), depth);
    }

    private boolean exists(String id) {
        return !jdbc.queryForList("SELECT MENU_ID FROM PT_MENU_AMN WHERE MENU_ID = ?", String.class, id).isEmpty();
    }

    private void requireUserType(String userType, boolean lock) {
        if (userType != null && "99".equals(userType.trim())) throw bad("Operator menus are read-only");
        if (userType == null || userType.isBlank() || userType.length() > 10) throw bad("Choose a user type");
        var matches = jdbc.queryForList("SELECT SIMP_C FROM MG_COMN_C_AMN WHERE SIMP_TPC = 'USR_TPC' AND SIMP_C = ?"
                + (lock ? " FOR UPDATE" : ""), String.class, userType);
        if (matches.isEmpty()) throw bad("User type not found");
    }

    private Parent parent(String id, String userType, boolean lock) {
        if (id == null || id.isBlank()) return null;
        var parents = jdbc.query("SELECT MENU_ID, MENU_NM, MENU_LVL, USR_TPC FROM PT_MENU_AMN WHERE MENU_ID = ?"
                        + (lock ? " FOR UPDATE" : ""),
                (row, index) -> new Parent(row.getString("MENU_ID"), row.getString("MENU_NM"),
                        switch (String.valueOf(row.getString("MENU_LVL"))) { case "0" -> 0; case "1" -> 1; case "2" -> 2; default -> -1; }, row.getString("USR_TPC")), id);
        if (parents.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent menu not found");
        Parent parent = parents.getFirst();
        if (!userType.equals(parent.userType() == null ? null : parent.userType().trim())) throw bad("The parent must belong to the selected user type");
        if (parent.depth() < 0 || parent.depth() >= 2) throw bad("New menus can only be registered at depths 0, 1, and 2");
        if (!parent.id().matches("[A-Za-z0-9]{2}[0-9]{6}")) throw bad("The parent ID must contain two letters or digits followed by six digits");
        return parent;
    }

    private static void requireId(String id) { if (id == null || !id.matches("[A-Za-z0-9]{2}[0-9]{6}")) throw bad("Menu ID must contain two letters or digits followed by six digits"); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException duplicate() { return new ResponseStatusException(HttpStatus.CONFLICT, "This menu ID already exists. Choose a different ID."); }
    record Parent(String id, String title, int depth, String userType) {}
    public record Match(String menuId, String title, String userType) {}
    public record Recommendation(int depth, String parentId, String parentTitle, String userType, String top, String middle, String leaf, List<Match> matches) {}
    public record Availability(boolean available) {}
    public record CreateRequest(String menuId, String parentId, String title, String userType) {}
    public record MenuResponse(String menuId, String parentId, String title, int sortOrder, String userType, int depth) {}
}
