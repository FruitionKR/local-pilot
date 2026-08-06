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

    /**
     * 권한을 확인하고 응답 헤더에 필요한 metadata만 읽는다. ETag는 DB의 content hash에서 나오므로
     * 조건부 요청 판정에 object storage를 호출할 필요가 없다.
     */
    @Transactional(readOnly = true)
    public AssetMetadata readMetadata(String workspaceId, String userId, UUID assetId) {
        if (!memberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new DocumentAssetNotFoundException(assetId);
        }
        var asset = assetRepository.findByIdAndWorkspaceId(assetId, workspaceId)
                .orElseThrow(() -> new DocumentAssetNotFoundException(assetId));
        return new AssetMetadata(
                asset.getContentType(), asset.getByteSize(), quoteEtag(asset.getContentHash()),
                asset.getStorageKey());
    }

    /** 실제 bytes를 보낼 때만 호출한다. {@code 304} 응답 경로에서는 열지 않는다. */
    public InputStream openStream(AssetMetadata metadata) {
        return objectStorage.get(metadata.storageKey());
    }

    private String quoteEtag(String contentHash) {
        return "\"" + contentHash + "\"";
    }

    public record AssetMetadata(
            String contentType,
            long contentLength,
            String etag,
            String storageKey
    ) {}
}
