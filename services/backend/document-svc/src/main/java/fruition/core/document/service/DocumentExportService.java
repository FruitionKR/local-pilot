package fruition.core.document.service;

import fruition.core.document.domain.Document;
import fruition.core.document.domain.DocumentEditState;
import fruition.core.document.domain.DocumentRole;
import fruition.core.document.dto.DocumentExportResult;
import fruition.core.document.exception.DocumentNotFoundException;
import fruition.core.document.exception.DocumentAssetExportException;
import fruition.core.document.repository.DocumentAssetReferenceRepository;
import fruition.core.document.repository.DocumentAssetRepository;
import fruition.core.document.repository.DocumentEditStateRepository;
import fruition.core.document.repository.DocumentRepository;
import fruition.core.authz.WorkspaceAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class DocumentExportService {

    private static final int MAX_ASSETS = 100;
    private static final long MAX_ASSET_BYTES = 100L * 1024 * 1024;

    private final DocumentRepository documentRepository;
    private final DocumentEditStateRepository editStateRepository;
    private final WorkspaceAccessGuard workspaceAccessGuard;
    private final DocumentAssetReferenceRepository referenceRepository;
    private final DocumentAssetRepository assetRepository;
    private final DocumentAssetObjectStorage objectStorage;
    private final TransactionTemplate transactionTemplate;

    public DocumentExportService(
            DocumentRepository documentRepository,
            DocumentEditStateRepository editStateRepository,
            WorkspaceAccessGuard workspaceAccessGuard,
            DocumentAssetReferenceRepository referenceRepository,
            DocumentAssetRepository assetRepository,
            DocumentAssetObjectStorage objectStorage,
            TransactionTemplate transactionTemplate
    ) {
        this.documentRepository = documentRepository;
        this.editStateRepository = editStateRepository;
        this.workspaceAccessGuard = workspaceAccessGuard;
        this.referenceRepository = referenceRepository;
        this.assetRepository = assetRepository;
        this.objectStorage = objectStorage;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * DB 조회는 짧은 트랜잭션에서 끝내고 object storage 다운로드와 ZIP 생성은 트랜잭션 밖에서 한다.
     * 최대 100MB를 내려받는 동안 DB 커넥션을 붙잡지 않기 위해서다.
     */
    public DocumentExportResult exportMarkdown(
            String workspaceId,
            String userId,
            String documentId
    ) {
        ExportSource source = transactionTemplate.execute(
                status -> loadSource(workspaceId, userId, documentId));

        if (source.assets().isEmpty()) {
            return DocumentExportResult.markdown(
                    source.baseName() + ".md", source.markdown().getBytes(StandardCharsets.UTF_8));
        }

        Map<UUID, String> entryNames = createEntryNames(source.assets());
        String rewrittenMarkdown = source.markdown();
        for (Map.Entry<UUID, String> entry : entryNames.entrySet()) {
            rewrittenMarkdown = rewrittenMarkdown.replace(
                    "/api/workspaces/" + workspaceId + "/assets/" + entry.getKey() + "/content",
                    "./" + entry.getValue());
        }
        return createZip(source.baseName() + ".zip", source.baseName() + ".md",
                rewrittenMarkdown, source.assets(), entryNames);
    }

    private ExportSource loadSource(String workspaceId, String userId, String documentId) {
        workspaceAccessGuard.requireMember(workspaceId, userId);
        Document document = documentRepository
                .findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new DocumentNotFoundException(documentId);
        }
        String markdown = editStateRepository.findById(documentId)
                .map(DocumentEditState::getMarkdown)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        String baseName = safeDocumentName(document.getDisplayName());
        var references = referenceRepository.findAllByIdDocumentId(documentId);
        if (references.isEmpty()) {
            return new ExportSource(baseName, markdown, List.of());
        }
        if (references.size() > MAX_ASSETS) {
            throw new DocumentAssetExportException("내보낼 이미지는 최대 100개입니다.");
        }

        var assetIds = references.stream().map(reference -> reference.getAssetId()).toList();
        var assets = assetRepository.findAllByIdInAndWorkspaceId(assetIds, workspaceId);
        if (assets.size() != assetIds.size()) {
            throw new DocumentAssetExportException("내보낼 이미지 asset을 찾을 수 없습니다.");
        }
        long totalBytes = assets.stream().mapToLong(asset -> asset.getByteSize()).sum();
        if (totalBytes > MAX_ASSET_BYTES) {
            throw new DocumentAssetExportException("내보낼 이미지 합계는 최대 100MB입니다.");
        }

        return new ExportSource(baseName, markdown, assets.stream()
                .map(asset -> new ExportAsset(
                        asset.getId(), asset.getStorageKey(), asset.getOriginalFilename()))
                .toList());
    }

    private Map<UUID, String> createEntryNames(List<ExportAsset> assets) {
        Map<String, Integer> counts = new HashMap<>();
        Set<String> used = new HashSet<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        assets.forEach(asset -> {
            String base = safeFilename(asset.originalFilename(), asset.id());
            int count = counts.merge(base, 1, Integer::sum);
            String filename = count == 1 ? base : suffix(base, count);
            while (!used.add(filename)) {
                filename = suffix(base, ++count);
                counts.put(base, count);
            }
            names.put(asset.id(), "assets/" + filename);
        });
        return names;
    }

    private DocumentExportResult createZip(
            String zipFilename,
            String markdownFilename,
            String markdown,
            List<ExportAsset> assets,
            Map<UUID, String> entryNames
    ) {
        Map<UUID, String> storageKeys = new LinkedHashMap<>();
        assets.forEach(asset -> storageKeys.put(asset.id(), asset.storageKey()));

        Path temporaryZip = null;
        try {
            temporaryZip = Files.createTempFile("document-export-", ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporaryZip))) {
                zip.putNextEntry(new ZipEntry(markdownFilename));
                zip.write(markdown.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                for (Map.Entry<UUID, String> entry : entryNames.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getValue()));
                    try (var input = objectStorage.get(storageKeys.get(entry.getKey()))) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
            // stream이 닫힐 때 임시 파일도 함께 지워진다. 응답을 다 쓴 뒤 컨테이너가 닫아 준다.
            long contentLength = Files.size(temporaryZip);
            InputStream content = Files.newInputStream(temporaryZip, StandardOpenOption.DELETE_ON_CLOSE);
            return DocumentExportResult.zip(zipFilename, contentLength, content);
        } catch (Exception exception) {
            deleteQuietly(temporaryZip);
            throw new DocumentAssetExportException("이미지 ZIP을 완성하지 못했습니다.", exception);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 이미 실패 응답으로 가는 경로라 삭제 실패가 결과를 바꾸지 않는다.
        }
    }

    private String safeFilename(String originalFilename, UUID assetId) {
        String normalized = originalFilename.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return basename.isBlank() || basename.equals(".") || basename.equals("..")
                ? assetId.toString() : basename;
    }

    private String safeDocumentName(String displayName) {
        String normalized = displayName.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        return basename.isBlank() || basename.equals(".") || basename.equals("..")
                ? "document" : basename;
    }

    private String suffix(String filename, int count) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) return filename + "-" + count;
        return filename.substring(0, dot) + "-" + count + filename.substring(dot);
    }

    /** 트랜잭션 밖에서 ZIP을 만들 수 있도록 entity 대신 필요한 값만 들고 나온다. */
    private record ExportAsset(UUID id, String storageKey, String originalFilename) {}

    private record ExportSource(String baseName, String markdown, List<ExportAsset> assets) {}
}
