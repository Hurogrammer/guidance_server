package esum.escm.guidance_server.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Read-only navigation for user type 99. This table has no USR_TPC or SORT_ORDER. */
@Entity
@Immutable
@Table(name = "MG_OPER_MENU_AMN")
public class MgOperMenuAmn {
    @Id
    @Column(name = "MENU_ID")
    private String menuId;

    @Column(name = "MENU_LVL")
    private String menuLevel;

    @Column(name = "MENU_NM")
    private String menuName;

    @Column(name = "UP_MENU_ID")
    private String upMenuId;

    protected MgOperMenuAmn() {}

    public MgOperMenuAmn(String menuId, String menuLevel, String menuName, String upMenuId) {
        this.menuId = menuId;
        this.menuLevel = menuLevel;
        this.menuName = menuName;
        this.upMenuId = upMenuId;
    }

    public String getMenuId() { return menuId; }
    public String getMenuLevel() { return menuLevel; }
    public String getMenuName() { return menuName; }
    public String getUpMenuId() { return upMenuId; }
}
