package com.corebank.audit;

import java.time.LocalDateTime;

/**
 * 감사 기록 한 줄. 컬럼 설명은 V11__create_audit_log.sql 참고.
 *
 * @param id 저장 전에는 null
 */
public record AuditLogEntry(
		Long id,
		LocalDateTime occurredAt,
		String requestId,
		ActorType actorType,
		String actorUsername,
		Long customerId,
		AuditAction action,
		String httpMethod,
		String path,
		int statusCode,
		Outcome outcome,
		String errorCode,
		Long targetAccountId,
		Long counterpartAccountId,
		String clientIp,
		String userAgent,
		long durationMs) {

	public enum ActorType {
		ADMIN, CUSTOMER, ANONYMOUS
	}

	public enum Outcome {
		SUCCESS, DENIED, FAILURE;

		static Outcome of(int status) {
			if (status >= 200 && status < 300) {
				return SUCCESS;
			}
			if (status == 401 || status == 403) {
				return DENIED;
			}
			return FAILURE;
		}
	}

}
