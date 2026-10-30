package com.investary.investor.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.investary.common.error.ApiException;
import com.investary.common.error.ErrorCode;

public class GoalNotFoundException extends ApiException {

    public GoalNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, ErrorCode.GOAL_NOT_FOUND, "Goal not found", "Goal " + id + " was not found");
    }
}
