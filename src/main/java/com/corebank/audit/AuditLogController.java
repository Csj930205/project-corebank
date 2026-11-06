package com.corebank.audit;

import com.corebank.common.DateRange;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 감사 로그 조회. /admin/** 이므로 관리자만 쓸 수 있다(SecurityConfig). 이 조회 자체도 감사 기록에 남는다(AUDIT_LOG_SEARCH).
 */
@RestController
@RequestMapping("/admin/audit-logs")
public class AuditLogController {

	private final AuditLogRepository repository;

	public AuditLogController(AuditLogRepository repository) {
		this.repository = repository;
	}

	/** @param page 0부터 시작하는 쪽 번호 */
	public record AuditLogPage(List<AuditLogEntry> content, int page, int size, long totalElements, int totalPages) {
	}

	/**
	 * GET /admin/audit-logs?actor=&action=&outcome=&accountId=&from=&to=&page=0&size=50 : 최신순 목록.
	 * from, to 는 날짜(YYYY-MM-DD)이고 양 끝을 포함한다. 잘못된 값(없는 action 등)은 400 INVALID_REQUEST.
	 */
	@GetMapping
	public AuditLogPage search(
			@RequestParam(required = false) @Size(max = 50) String actor,
			@RequestParam(required = false) AuditAction action,
			@RequestParam(required = false) AuditLogEntry.Outcome outcome,
			@RequestParam(required = false) Long accountId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
			@RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
		String actorOrNull = actor == null || actor.isBlank() ? null : actor.trim();
		AuditLogRepository.Page result = repository.search(
				new AuditLogRepository.Criteria(actorOrNull, action, outcome, accountId, new DateRange(from, to)), page, size);
		int totalPages = (int) ((result.totalElements() + size - 1) / size);
		return new AuditLogPage(result.content(), page, size, result.totalElements(), totalPages);
	}

}
