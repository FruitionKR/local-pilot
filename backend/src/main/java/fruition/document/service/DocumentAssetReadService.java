package fruition.document.service;

import fruition.document.exception.DocumentAssetNotFoundException;
import fruition.document.repository.DocumentAssetRepository;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.UUID;

@Service
public class DocumentAssetReadService {

    private final WorkspaceMemberRepository memberRepository;
    private final DocumentAssetRepository assetRepository;
    private final DocumentAssetObjectStorage objectStorage;

    public DocumentAssetReadService(
            WorkspaceMemberRepository memberRepository,
            DocumentAssetRepository assetRepository,
            DocumentAssetObjectStorage objectStorage
    ) {
        this.memberRepository = memberRepository;
        this.assetRepository = assetRepository;
        this.objectStorage = objectStorage;
    }

    @Transactional(readOnly = true)
    public AssetContent read(String workspaceId, String userId, UUID assetId) {
        if (!memberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new DocumentAssetNotFoundException(assetId);
        }
        var asset = assetRepository.findByIdAndWorkspaceId(assetId, workspaceId)
                .orElseThrow(() -> new DocumentAssetNotFoundException(assetId));
        return new AssetContent(
                asset.getContentType(), asset.getByteSize(), quoteEtag(asset.getContentHash()),
                objectStorage.get(asset.getStorageKey()));
    }

    private String quoteEtag(String contentHash) {
        return "\"" + contentHash + "\"";
    }

    public record AssetContent(
            String contentType,
            long contentLength,
            String etag,
            InputStream inputStream
    ) {}
}
