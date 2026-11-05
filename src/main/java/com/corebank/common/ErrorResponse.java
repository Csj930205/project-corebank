package com.corebank.common;

/**
 * 모든 API 가 공통으로 쓰는 에러 응답.
 *
 * @param code    프로그램이 구분할 수 있는 에러 코드 (예: ACCOUNT_NOT_FOUND)
 * @param message 사람이 읽을 설명
 */
public record ErrorResponse(String code, String message) {
}
