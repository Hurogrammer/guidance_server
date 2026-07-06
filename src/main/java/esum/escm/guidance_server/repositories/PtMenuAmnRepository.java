package esum.escm.guidance_server.repositories;

import esum.escm.guidance_server.entities.PtMenuAmn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PtMenuAmnRepository extends JpaRepository<PtMenuAmn, String> {
}
