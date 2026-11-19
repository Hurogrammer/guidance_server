package esum.escm.guidance_server.menu;

import esum.escm.guidance_server.content.VideoStorage;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;

@Service
public class MenuDeletionService {
    private static final String SELECT = "SELECT MENU_ID, PARENT_ID, MENU_LVL, USR_TPC FROM PT_MENU_AMN";
    private static final RowMapper<MenuRow> MAPPER = (row, index) -> new MenuRow(
            row.getString("MENU_ID"), normalized(row.getString("PARENT_ID")),
            normalized(row.getString("MENU_LVL")), normalized(row.getString("USR_TPC")));
    private final JdbcTemplate jdbc;
    private final VideoStorage videos;

    public MenuDeletionService(JdbcTemplate jdbc, VideoStorage videos) { this.jdbc = jdbc; this.videos = videos; }

    @Transactional
    public void delete(String id) {
        MenuRow selected = find(id, false);
        requireChild(selected);

        // Match registration's lock order; then lock the parent as menu ordering does.
        jdbc.queryForList("SELECT SIMP_C FROM MG_COMN_C_AMN WHERE SIMP_TPC = 'USR_TPC' AND SIMP_C = ? FOR UPDATE",
                String.class, selected.userType());
        if (jdbc.queryForList("SELECT MENU_ID FROM PT_MENU_AMN WHERE MENU_ID = ? FOR UPDATE",
                String.class, selected.parentId()).isEmpty()) {
            throw conflict("Parent menu no longer exists; refresh the menu list");
        }
        MenuRow locked = find(id, true);
        requireChild(locked);
        if (!selected.equals(locked)) throw conflict("Menu has changed; refresh the menu list");

        // Retain every ID before any delete. PARENT_ID is authoritative, even for edited ID prefixes.
        var branch = new ArrayList<MenuRow>();
        var visited = new HashSet<String>();
        branch.add(locked);
        visited.add(locked.id());
        for (int i = 0; i < branch.size(); i++) {
            var children = jdbc.query(SELECT + " WHERE TRIM(PARENT_ID) = ? ORDER BY MENU_ID FOR UPDATE",
                    MAPPER, branch.get(i).id());
            for (MenuRow child : children) {
                if (!visited.add(child.id())) throw conflict("The menu hierarchy contains a cycle");
                if (!Objects.equals(locked.userType(), child.userType())) {
                    throw conflict("This branch contains menus from another user type; correct its parent links before deleting");
                }
                if ("0".equals(child.level())) throw conflict("This branch contains a top menu; correct its parent links before deleting");
                branch.add(child);
            }
        }

        // Content writes also lock their menu, so no new attached blocks can race this cleanup.
        // Pending uploads have no MENU_ID and are left alone. All writes roll back on failure.
        for (MenuRow menu : branch) {
            jdbc.query("SELECT CONTENT FROM `CONTENT BLOCKS` WHERE MENU_ID = ? AND TYPE = 'video' AND OCTET_LENGTH(CONTENT) = ? FOR UPDATE",
                    (row, index) -> row.getBytes("CONTENT"), menu.id(), VideoStorage.REFERENCE_BYTES).forEach(videos::deleteAfterCommit);
            jdbc.update("DELETE FROM `CONTENT BLOCKS` WHERE MENU_ID = ?", menu.id());
        }
        for (int i = branch.size() - 1; i >= 0; i--) {
            if (jdbc.update("DELETE FROM PT_MENU_AMN WHERE MENU_ID = ?", branch.get(i).id()) != 1) {
                throw conflict("Menu has changed; refresh the menu list");
            }
        }
    }

    private MenuRow find(String id, boolean lock) {
        var rows = jdbc.query(SELECT + " WHERE MENU_ID = ?" + (lock ? " FOR UPDATE" : ""), MAPPER, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu not found");
        return rows.getFirst();
    }

    private static void requireChild(MenuRow menu) {
        if (menu.parentId() == null || "0".equals(menu.level())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Top menus cannot be deleted");
        }
    }

    private static String normalized(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private record MenuRow(String id, String parentId, String level, String userType) {}
}
