package esum.escm.guidance_server.menu;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
public class MenuOrderService {
    private static final String SELECT = "SELECT MENU_ID, MENU_NM, PARENT_ID, MENU_LVL, SORT_ORDER, USR_TPC FROM PT_MENU_AMN";
    private static final RowMapper<MenuRow> MAPPER = (row, index) -> new MenuRow(
            row.getString("MENU_ID"), row.getString("MENU_NM"), row.getString("PARENT_ID"),
            row.getString("MENU_LVL"), row.getObject("SORT_ORDER", Integer.class), row.getString("USR_TPC"));
    private final JdbcTemplate jdbc;

    public MenuOrderService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public List<MenuResponse> move(String id, String direction) {
        if (!"up".equals(direction) && !"down".equals(direction)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Direction must be up or down");
        }
        var matches = jdbc.query(SELECT + " WHERE MENU_ID = ?", MAPPER, id);
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu not found");
        MenuRow selected = matches.getFirst();
        String userType = selected.userType() == null ? null : selected.userType().trim();
        String parent = selected.parentId() == null ? "" : selected.parentId().trim();
        if (parent.isEmpty() || "0".equals(selected.level())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Top menus cannot be reordered");
        }

        // All moves under this parent acquire the same lock before reading the current order.
        var parents = jdbc.queryForList("SELECT MENU_ID FROM PT_MENU_AMN WHERE MENU_ID = ? FOR UPDATE", String.class, parent);
        if (parents.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Parent menu no longer exists; refresh the menu list");
        var siblings = new ArrayList<>(jdbc.query(SELECT
                + " WHERE TRIM(PARENT_ID) = ? AND TRIM(USR_TPC) <=> ? ORDER BY SORT_ORDER, MENU_ID FOR UPDATE", MAPPER, parent, userType));
        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).id().equals(id)) index = i;
        }
        if (index < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Menu has changed; refresh the menu list");
        if ("0".equals(siblings.get(index).level())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Top menus cannot be reordered");
        }
        int target = index + ("up".equals(direction) ? -1 : 1);
        if (target < 0 || target >= siblings.size()) return siblings.stream().map(MenuRow::response).toList();

        Collections.swap(siblings, index, target);
        var result = new ArrayList<MenuResponse>();
        for (int i = 0; i < siblings.size(); i++) {
            MenuRow sibling = siblings.get(i);
            int order = i + 1;
            if (!Objects.equals(sibling.sortOrder(), order)) {
                jdbc.update("UPDATE PT_MENU_AMN SET SORT_ORDER = ? WHERE MENU_ID = ?", order, sibling.id());
            }
            result.add(new MenuResponse(sibling.id(), parent, sibling.title(), order));
        }
        return result;
    }

    record MenuRow(String id, String title, String parentId, String level, Integer sortOrder, String userType) {
        MenuResponse response() { return new MenuResponse(id, parentId == null ? null : parentId.trim(), title, sortOrder); }
    }

    public record MenuResponse(String menuId, String parentId, String title, Integer sortOrder) {}
}
