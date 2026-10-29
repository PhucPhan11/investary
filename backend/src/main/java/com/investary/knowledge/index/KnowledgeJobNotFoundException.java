package com.investary.knowledge.index;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

public class KnowledgeJobNotFoundException extends ApiException {

    public KnowledgeJobNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, ErrorCode.KNOWLEDGE_JOB_NOT_FOUND, "Indexing job not found",
                "No indexing job exists with id " + id);
    }
}
