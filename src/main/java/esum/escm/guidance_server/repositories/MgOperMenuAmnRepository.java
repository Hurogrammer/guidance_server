package esum.escm.guidance_server.repositories;

import esum.escm.guidance_server.entities.MgOperMenuAmn;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface MgOperMenuAmnRepository extends Repository<MgOperMenuAmn, String> {
    List<MgOperMenuAmn> findAll(Sort sort);
    Optional<MgOperMenuAmn> findById(String id);
}
