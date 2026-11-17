package com.vamsdest.rag;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Open-source embeddings: all-MiniLM-L6-v2 (384 dimensions) running in-process through ONNX Runtime. There is no
 * external API call, no per-request cost, and no account quota. The model is loaded lazily, on first use.
 */
public final class LocalEmbedder implements Embedder {
    private final Supplier<EmbeddingModel> loader;
    private volatile EmbeddingModel model;

    public LocalEmbedder(Supplier<EmbeddingModel> loader) {
        this.loader = loader;
    }

    /** Loads the model and tokenizer from files (the Lambda path: copied from S3 to /tmp). */
    public static LocalEmbedder fromFiles(Path model, Path tokenizer) {
        return new LocalEmbedder(() -> new OnnxEmbeddingModel(model, tokenizer, PoolingMode.MEAN));
    }

    @Override
    public float[] embed(String text) {
        EmbeddingModel current = model;
        if (current == null) {
            synchronized (this) {
                if (model == null) {
                    model = loader.get();
                }
                current = model;
            }
        }
        return current.embed(text).content().vector();
    }
}
