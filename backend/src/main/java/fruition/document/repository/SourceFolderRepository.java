package fruition.document.repository;

import fruition.document.domain.SourceFolder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SourceFolderRepository extends JpaRepository<SourceFolder, UUID> {
}
