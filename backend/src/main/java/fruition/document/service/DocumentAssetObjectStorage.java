package fruition.document.service;

public interface DocumentAssetObjectStorage {
    void put(String objectKey, String contentType, byte[] bytes);
    void delete(String objectKey);
}
