package esum.escm.guidance_server.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Date;

@Entity
@Table(name = "PT_MENU_AMN")
public class PtMenuAmn implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 메뉴ID
     */
    @Id
    @Column(name = "MENU_ID", length = 20)
    private String menuId;

    /**
     * 메뉴레벨
     */
    @Column(name = "MENU_LVL", length = 1)
    private String menuLevel;

    /**
     * 메뉴명
     */
    @Column(name = "MENU_NM", length = 100)
    private String menuName;

    /**
     * 메뉴경로
     */
    @Column(name = "MENU_PATH", length = 200)
    private String menuPath;

    /**
     * 상위메뉴
     */
    @Column(name = "UP_MENU_ID", length = 20)
    private String upMenuId;

    /**
     * 서비스그룹명
     */
    @Column(name = "SVC_GRPNM", length = 100)
    private String serviceGroupName;

    /**
     * 파일명
     */
    @Column(name = "FLNM", length = 100)
    private String fileName;

    /**
     * 파일종류
     */
    @Column(name = "FL_KD", length = 10)
    private String fileKind;

    /**
     * 화면표시여부
     */
    @Column(name = "SCRN_MRK_YN", length = 1)
    private String screenMarkYN;

    /**
     * 사용여부
     */
    @Column(name = "UYN", length = 1)
    private String useYN;

    /**
     * 지사사용여부
     */
    @Column(name = "BRO_UYN", length = 1)
    private String branchOfficeUseYN;

    /**
     * 사용자권한구분코드
     */
    @Column(name = "USR_AUTH_DSC", length = 10)
    private String userAuthorityDividingCode;

    /**
     * 사용자유형코드
     */
    @Column(name = "USR_TPC", length = 10)
    private String userTypeCode;

    /**
     * 최초등록일시
     */
    @Column(name = "FSRG_DTM")
    private Date firstRegisterDateTime;

    /**
     * 최초등록자
     */
    @Column(name = "FS_RGM", length = 20)
    private String firstRegisterMan;

    /**
     * 최종변경일시
     */
    @Column(name = "LSCHG_DTM")
    private Date lastChangeDateTime;

    /**
     * 최종변경자
     */
    @Column(name = "LS_CHGM", length = 20)
    private String lastChangeMan;

    /**
     * 화면버전
     */
    @Column(name = "VERSION", length = 10)
    private String version;

    /**
     * 빌링사용여부
     */
    @Column(name = "BL_UYN", length = 1)
    private String billingUseYN;

    /**
     * 메뉴그룹
     */
    @Column(name = "MENU_GROUP", length = 20)
    private String menuGroup;


    public String getMenuId() {
        return menuId;
    }

    public void setMenuId(String menuId) {
        this.menuId = menuId;
    }

    public String getMenuLevel() {
        return menuLevel;
    }

    public void setMenuLevel(String menuLevel) {
        this.menuLevel = menuLevel;
    }

    public String getMenuName() {
        return menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName;
    }

    public String getMenuPath() {
        return menuPath;
    }

    public void setMenuPath(String menuPath) {
        this.menuPath = menuPath;
    }

    public String getUpMenuId() {
        return upMenuId;
    }

    public void setUpMenuId(String upMenuId) {
        this.upMenuId = upMenuId;
    }

    public String getServiceGroupName() {
        return serviceGroupName;
    }

    public void setServiceGroupName(String serviceGroupName) {
        this.serviceGroupName = serviceGroupName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileKind() {
        return fileKind;
    }

    public void setFileKind(String fileKind) {
        this.fileKind = fileKind;
    }

    public String getScreenMarkYN() {
        return screenMarkYN;
    }

    public void setScreenMarkYN(String screenMarkYN) {
        this.screenMarkYN = screenMarkYN;
    }

    public String getUseYN() {
        return useYN;
    }

    public void setUseYN(String useYN) {
        this.useYN = useYN;
    }

    public String getBranchOfficeUseYN() {
        return branchOfficeUseYN;
    }

    public void setBranchOfficeUseYN(String branchOfficeUseYN) {
        this.branchOfficeUseYN = branchOfficeUseYN;
    }

    public String getUserAuthorityDividingCode() {
        return userAuthorityDividingCode;
    }

    public void setUserAuthorityDividingCode(String userAuthorityDividingCode) {
        this.userAuthorityDividingCode = userAuthorityDividingCode;
    }

    public String getUserTypeCode() {
        return userTypeCode;
    }

    public void setUserTypeCode(String userTypeCode) {
        this.userTypeCode = userTypeCode;
    }

    public Date getFirstRegisterDateTime() {
        return firstRegisterDateTime;
    }

    public void setFirstRegisterDateTime(Date firstRegisterDateTime) {
        this.firstRegisterDateTime = firstRegisterDateTime;
    }

    public String getFirstRegisterMan() {
        return firstRegisterMan;
    }

    public void setFirstRegisterMan(String firstRegisterMan) {
        this.firstRegisterMan = firstRegisterMan;
    }

    public Date getLastChangeDateTime() {
        return lastChangeDateTime;
    }

    public void setLastChangeDateTime(Date lastChangeDateTime) {
        this.lastChangeDateTime = lastChangeDateTime;
    }

    public String getLastChangeMan() {
        return lastChangeMan;
    }

    public void setLastChangeMan(String lastChangeMan) {
        this.lastChangeMan = lastChangeMan;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getBillingUseYN() {
        return billingUseYN;
    }

    public void setBillingUseYN(String billingUseYN) {
        this.billingUseYN = billingUseYN;
    }

    public String getMenuGroup() {
        return menuGroup;
    }

    public void setMenuGroup(String menuGroup) {
        this.menuGroup = menuGroup;
    }
}
