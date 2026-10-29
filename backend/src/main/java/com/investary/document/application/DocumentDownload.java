package com.investary.document.application;

import org.springframework.core.io.Resource;

import com.investary.document.domain.Document;

public record DocumentDownload(Document document, Resource content) {
}
