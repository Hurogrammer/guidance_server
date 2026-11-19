package esum.escm.guidance_server.menu;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@CrossOrigin
@RestController
public class UserTypeController {
    private final UserTypeService service;

    public UserTypeController(UserTypeService service) { this.service = service; }

    @GetMapping("/api/user-types")
    public List<UserTypeService.UserType> list() { return service.list(); }
}
