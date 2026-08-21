package com.testpilot.rag.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "embedding_data", columnDefinition = "TEXT", nullable = false)
    private String embeddingData;

    public DocumentChunk() {}

    public DocumentChunk(Long documentId, String content, float[] embedding) {
        this.documentId = documentId;
        this.content = content;
        setEmbeddingVector(embedding);
    }

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getContent() {
        return content;
    }

    public String getEmbeddingData() {
        return embeddingData;
    }

    public float[] getEmbeddingVector() {
        if (embeddingData == null || embeddingData.isEmpty()) {
            return new float[0];
        }
        String[] parts = embeddingData.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }

    public void setEmbeddingVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            this.embeddingData = "";
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        this.embeddingData = sb.toString();
    }
}
