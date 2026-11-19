package esum.escm.guidance_server.menu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class UserTypeService {
    private final JdbcTemplate jdbc;

    public UserTypeService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<UserType> list() {
        return jdbc.query("SELECT SIMP_C, SIMP_CNM FROM MG_COMN_C_AMN WHERE SIMP_TPC = ? ORDER BY SIMP_C_SORT_SQ, SIMP_C",
                (row, index) -> new UserType(row.getString("SIMP_C").trim(), row.getString("SIMP_CNM")), "USR_TPC");
    }

    public record UserType(String code, String name) {}
}
