package com.c4i.tracking.common.exception;

public class TargetNotFoundException extends RuntimeException {

    public TargetNotFoundException(String targetId) {
        super("표적을 찾을 수 없습니다: " + targetId);
    }
}
