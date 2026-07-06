package esum.escm.guidance_server.menu;

import esum.escm.guidance_server.entities.PtMenuAmn;
import esum.escm.guidance_server.repositories.PtMenuAmnRepository;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@CrossOrigin
@RestController
public class MenuController {

    private final PtMenuAmnRepository ptMenuAmnRepository;

    public MenuController(PtMenuAmnRepository ptMenuAmnRepository) {
        this.ptMenuAmnRepository = ptMenuAmnRepository;
    }

    @GetMapping("rest/menu")
    public List<PtMenuAmn> getMenu() {
        System.out.println("getMenu called");
        return ptMenuAmnRepository.findAll();
    }
}
