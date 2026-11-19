package esum.escm.guidance_server.menu;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class RelativeTablesService {
    private final JdbcTemplate jdbc;

    public RelativeTablesService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public RelativeTables get(String menuId, String userType) {
        var rows = jdbc.query("SELECT RELAVANT_TABLES FROM " + menuTable(userType) + " WHERE MENU_ID = ?",
                (row, index) -> new RelativeTables(menuId, row.getString("RELAVANT_TABLES")), menuId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu not found");
        return rows.getFirst();
    }

    @Transactional
    public RelativeTables save(String menuId, List<String> tableNames, String admin, String userType) {
        String table = menuTable(userType);
        if (tableNames == null || tableNames.size() > 30)
            throw badRequest("Provide an array of at most 30 table names");
        var names = new LinkedHashSet<String>();
        for (String value : tableNames) {
            if (value == null) throw badRequest("Table names must be strings");
            String name = value.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (!name.matches("[A-Z_][A-Z0-9_$]{0,63}"))
                throw badRequest("Use table names of up to 64 letters, numbers, underscores, or $, starting with a letter or underscore.");
            names.add(name);
        }
        String formatted = names.isEmpty() ? null : "PVS.(" + String.join("|", names) + ")";
        // A locking existence check also makes identical saves succeed on drivers reporting changed rows only.
        if (jdbc.queryForList("SELECT MENU_ID FROM " + table + " WHERE MENU_ID = ? FOR UPDATE",
                String.class, menuId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Menu not found");
        if (table.equals("MG_OPER_MENU_AMN")) {
            jdbc.update("UPDATE MG_OPER_MENU_AMN SET RELAVANT_TABLES = ? WHERE MENU_ID = ?", formatted, menuId);
        } else {
            jdbc.update("UPDATE PT_MENU_AMN SET RELAVANT_TABLES = ?, LSCHG_DTM = CURRENT_TIMESTAMP, LS_CHGM = ? WHERE MENU_ID = ?",
                    formatted, admin, menuId);
        }
        return new RelativeTables(menuId, formatted);
    }

    private static String menuTable(String userType) {
        if (userType != null && userType.isBlank()) throw badRequest("Choose a user type");
        // Only these fixed table names enter SQL; a menu scope never grants administrator access.
        return "99".equals(userType == null ? null : userType.trim()) ? "MG_OPER_MENU_AMN" : "PT_MENU_AMN";
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    public record RelativeTables(String menuId, String relativeTables) {}
}
