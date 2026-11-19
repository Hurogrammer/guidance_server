package esum.escm.guidance_server.content;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Isolate operator documents without changing existing content rows or their menu IDs. */
final class ContentMenuKey {
    private static final String OPERATOR_PREFIX = "99:";

    private ContentMenuKey() {}

    static String of(String menuId, String userType) {
        if (menuId == null || menuId.isBlank() || isOperator(menuId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use a menu ID without a content prefix");
        if (!"99".equals(userType == null ? null : userType.trim())) return menuId;
        // CONTENT BLOCKS.MENU_ID is VARCHAR(20); existing operator IDs are eight characters.
        if (menuId.length() > 17)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Operator menu ID is too long");
        return OPERATOR_PREFIX + menuId;
    }

    static boolean isOperator(String key) { return key != null && key.startsWith(OPERATOR_PREFIX); }
    static String menuId(String key) { return isOperator(key) ? key.substring(OPERATOR_PREFIX.length()) : key; }
}
