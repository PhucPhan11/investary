package com.investary.research.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

public class LinkNotFoundException extends ApiException {

    public LinkNotFoundException(UUID linkId) {
        super(HttpStatus.NOT_FOUND, ErrorCode.LINK_NOT_FOUND, "Link not found", "Link " + linkId + " was not found");
    }
}
