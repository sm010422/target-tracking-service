package com.c4i.tracking.common.exception;

public class ApprovalNotFoundException extends RuntimeException {

    public ApprovalNotFoundException(Long id) {
        super("승인 요청을 찾을 수 없습니다: id=" + id);
    }
}
