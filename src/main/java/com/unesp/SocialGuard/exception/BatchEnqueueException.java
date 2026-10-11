package com.unesp.SocialGuard.exception;

public class BatchEnqueueException extends RuntimeException {

    private final String batchId;

    public BatchEnqueueException(String batchId, Throwable cause) {
        super("Não foi possível publicar o lote " + batchId + " na fila.", cause);
        this.batchId = batchId;
    }

    public String getBatchId() {
        return batchId;
    }
}
