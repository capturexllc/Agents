package com.vamsdest.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Ingests one document from S3: read it, split it into chunks, embed each chunk, and replace the document's chunks in
 * the store. Re-uploading a file replaces its old chunks.
 */
public final class IngestService {
    static final int MAX_CHUNK_CHARS = 1000;
    static final int CHUNK_OVERLAP = 150;

    private final S3Client s3;
    private final Embedder embedder;
    private final ChunkStore store;

    public IngestService(S3Client s3, Embedder embedder, ChunkStore store) {
        this.s3 = s3;
        this.embedder = embedder;
        this.store = store;
    }

    /** Returns the number of chunks stored, or 0 when the file type is not a supported document. */
    public int ingest(String bucket, String key) {
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        if (!DocumentReader.supports(fileName)) {
            return 0;
        }
        byte[] bytes = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        String docId = docId(fileName);

        List<ChunkStore.StoredChunk> chunks = new ArrayList<>();
        int index = 0;
        for (DocumentReader.Section section : DocumentReader.read(fileName, bytes)) {
            for (String text : Chunker.split(section.text(), MAX_CHUNK_CHARS, CHUNK_OVERLAP)) {
                chunks.add(new ChunkStore.StoredChunk(docId, index++, fileName, section.location(), text,
                        embedder.embed(text)));
            }
        }
        store.replaceDocument(docId, chunks);
        return chunks.size();
    }

    /** A stable id from the file name: "Travel Policy 2030.pdf" becomes "travel-policy-2030". */
    static String docId(String fileName) {
        return fileName.replaceAll("\\.[^.]+$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
