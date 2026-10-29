package com.investary.research.application;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

public class DownloadNotAllowedException extends ApiException {

    public DownloadNotAllowedException(String reason) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.DOWNLOAD_NOT_ALLOWED, "Download not allowed", reason);
    }
}
