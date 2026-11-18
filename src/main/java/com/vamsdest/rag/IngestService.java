package com.vamsdest.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Ingests one document from S3: read it, split it into chunks, embed each chunk, store the text in the chunk store, and
 * store the vectors in the vector index. Re-uploading a file replaces its old chunks and vectors.
 */
public final class IngestService {
    static final int MAX_CHUNK_CHARS = 1000;
    static final int CHUNK_OVERLAP = 150;

    private final S3Client s3;
    private final Embedder embedder;
    private final ChunkStore store;
    private final VectorIndex vectors;

    public IngestService(S3Client s3, Embedder embedder, ChunkStore store, VectorIndex vectors) {
        this.s3 = s3;
        this.embedder = embedder;
        this.store = store;
        this.vectors = vectors;
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
        List<VectorIndex.Entry> entries = new ArrayList<>();
        int index = 0;
        for (DocumentReader.Section section : DocumentReader.read(fileName, bytes)) {
            for (String text : Chunker.split(section.text(), MAX_CHUNK_CHARS, CHUNK_OVERLAP)) {
                String chunkKey = ChunkStore.key(docId, index);
                chunks.add(new ChunkStore.StoredChunk(docId, index, fileName, section.location(), text));
                entries.add(new VectorIndex.Entry(chunkKey, embedder.embed(text)));
                index++;
            }
        }
        List<String> previous = store.keysOf(docId);
        if (!previous.isEmpty()) {
            vectors.delete(previous);
        }
        vectors.put(entries);
        store.replaceDocument(docId, chunks);
        return chunks.size();
    }

    /** A stable id from the file name: "Travel Policy 2030.pdf" becomes "travel-policy-2030". */
    static String docId(String fileName) {
        return fileName.replaceAll("\\.[^.]+$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
