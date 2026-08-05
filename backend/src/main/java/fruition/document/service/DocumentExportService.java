package fruition.document.service;

import fruition.document.domain.Document;
import fruition.document.domain.DocumentEditState;
import fruition.document.domain.DocumentRole;
import fruition.document.dto.DocumentExportResult;
import fruition.document.exception.DocumentNotFoundException;
import fruition.document.exception.DocumentAssetExportException;
import fruition.document.repository.DocumentAssetReferenceRepository;
import fruition.document.repository.DocumentAssetRepository;
import fruition.document.repository.DocumentEditStateRepository;
import fruition.document.repository.DocumentRepository;
import fruition.workspace.exception.WorkspaceNotFoundException;
import fruition.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final DocumentAssetReferenceRepository referenceRepository;
    private final DocumentAssetRepository assetRepository;
    private final DocumentAssetObjectStorage objectStorage;

    public DocumentExportService(
            DocumentRepository documentRepository,
            DocumentEditStateRepository editStateRepository,
            WorkspaceMemberRepository workspaceMemberRepository,
            DocumentAssetReferenceRepository referenceRepository,
            DocumentAssetRepository assetRepository,
            DocumentAssetObjectStorage objectStorage
    ) {
        this.documentRepository = documentRepository;
        this.editStateRepository = editStateRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.referenceRepository = referenceRepository;
        this.assetRepository = assetRepository;
        this.objectStorage = objectStorage;
    }

    @Transactional(readOnly = true)
    public DocumentExportResult exportMarkdown(
            String workspaceId,
            String userId,
            String documentId
    ) {
        if (!workspaceMemberRepository.existsByWorkspace_IdAndUser_Id(workspaceId, userId)) {
            throw new WorkspaceNotFoundException(workspaceId);
        }
        Document document = documentRepository
                .findByIdAndWorkspaceIdAndDeletedAtIsNull(documentId, workspaceId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getDocumentRole() != DocumentRole.EDITABLE) {
            throw new DocumentNotFoundException(documentId);
        }
        DocumentEditState editState = editStateRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        String exportBaseName = safeDocumentName(document.getDisplayName());
        String markdownFilename = exportBaseName + ".md";
        var references = referenceRepository.findAllByIdDocumentId(documentId);
        if (references.isEmpty()) {
            return new DocumentExportResult(
                    markdownFilename, editState.getMarkdown().getBytes(StandardCharsets.UTF_8));
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

        Map<UUID, fruition.document.domain.DocumentAsset> assetsById = new LinkedHashMap<>();
        assets.forEach(asset -> assetsById.put(asset.getId(), asset));
        Map<UUID, String> entryNames = createEntryNames(assetsById);
        String rewrittenMarkdown = editState.getMarkdown();
        for (Map.Entry<UUID, String> entry : entryNames.entrySet()) {
            rewrittenMarkdown = rewrittenMarkdown.replace(
                    "/api/workspaces/" + workspaceId + "/assets/" + entry.getKey() + "/content",
                    "./" + entry.getValue());
        }
        return createZip(exportBaseName + ".zip", markdownFilename,
                rewrittenMarkdown, assetsById, entryNames);
    }

    private Map<UUID, String> createEntryNames(
            Map<UUID, fruition.document.domain.DocumentAsset> assetsById) {
        Map<String, Integer> counts = new HashMap<>();
        Set<String> used = new HashSet<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        assetsById.forEach((assetId, asset) -> {
            String base = safeFilename(asset.getOriginalFilename(), assetId);
            int count = counts.merge(base, 1, Integer::sum);
            String filename = count == 1 ? base : suffix(base, count);
            while (!used.add(filename)) {
                filename = suffix(base, ++count);
                counts.put(base, count);
            }
            names.put(assetId, "assets/" + filename);
        });
        return names;
    }

    private DocumentExportResult createZip(
            String zipFilename,
            String markdownFilename,
            String markdown,
            Map<UUID, fruition.document.domain.DocumentAsset> assetsById,
            Map<UUID, String> entryNames
    ) {
        Path temporaryZip = null;
        try {
            temporaryZip = Files.createTempFile("document-export-", ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporaryZip))) {
                zip.putNextEntry(new ZipEntry(markdownFilename));
                zip.write(markdown.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                for (Map.Entry<UUID, String> entry : entryNames.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getValue()));
                    try (var input = objectStorage.get(assetsById.get(entry.getKey()).getStorageKey())) {
                        input.transferTo(zip);
                    }
                    zip.closeEntry();
                }
            }
            return new DocumentExportResult(
                    zipFilename, "application/zip", Files.readAllBytes(temporaryZip));
        } catch (Exception exception) {
            throw new DocumentAssetExportException("이미지 ZIP을 완성하지 못했습니다.", exception);
        } finally {
            if (temporaryZip != null) {
                try {
                    Files.deleteIfExists(temporaryZip);
                } catch (java.io.IOException ignored) {
                    // 응답용 bytes 완성 여부를 바꾸지 않는다.
                }
            }
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
}
