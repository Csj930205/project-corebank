package com.corebank.correction;

import com.corebank.audit.AuditContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * 거래 정정 API. /admin/** 라 관리자 토큰이 있어야 한다. 누가 요청·결정했는지는 토큰의 아이디(sub)로 정한다.
 * 본문으로 받으면 다른 사람 이름으로 승인할 수 있기 때문이다.
 */
@RestController
@RequestMapping("/admin/corrections")
public class CorrectionController {

	private final CorrectionService service;

	public CorrectionController(CorrectionService service) {
		this.service = service;
	}

	/** @param reason 왜 되돌리는지. 결정하는 관리자와 나중의 감사가 읽는다 */
	public record CreateRequest(@NotNull @Positive Long transactionId, @NotBlank @Size(max = 500) String reason) {
	}

	/** @param comment 승인 메모(선택) */
	public record ApproveRequest(@Size(max = 500) String comment) {
	}

	/** @param comment 반려 사유(필수). 요청한 관리자가 무엇을 고쳐 다시 요청할지 알아야 한다 */
	public record RejectRequest(@NotBlank @Size(max = 500) String comment) {
	}

	public record CorrectionPage(List<CorrectionView> content, int page, int size, long totalElements, int totalPages) {

		static CorrectionPage from(Page<CorrectionView> page) {
			return new CorrectionPage(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
					page.getTotalPages());
		}
	}

	/** POST /admin/corrections {transactionId, reason}: 정정 요청. 201, 원장은 아직 그대로다. */
	@PostMapping
	public ResponseEntity<CorrectionView> create(@Valid @RequestBody CreateRequest request, @AuthenticationPrincipal Jwt jwt) {
		CorrectionView view = service.request(request.transactionId(), request.reason(), jwt.getSubject());
		audit(view);
		return ResponseEntity.created(URI.create("/admin/corrections/" + view.id())).body(view);
	}

	/** GET /admin/corrections?status=REQUESTED&page=0&size=20: 최근 요청 순 */
	@GetMapping
	public CorrectionPage list(
			@RequestParam(required = false) CorrectionStatus status,
			@RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return CorrectionPage.from(service.search(status, page, size));
	}

	@GetMapping("/{id}")
	public CorrectionView get(@PathVariable long id) {
		return service.get(id);
	}

	/**
	 * POST /admin/corrections/{id}/approval: 승인하고 역분개를 기록한다. 200.
	 * 본문은 없어도 된다(메모를 남길 때만 보낸다).
	 */
	@PostMapping("/{id}/approval")
	public CorrectionView approve(@PathVariable long id, @Valid @RequestBody(required = false) ApproveRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		CorrectionView view = service.approve(id, jwt.getSubject(), request == null ? null : blankToNull(request.comment()));
		audit(view);
		return view;
	}

	/** POST /admin/corrections/{id}/rejection {comment}: 반려. 200 */
	@PostMapping("/{id}/rejection")
	public CorrectionView reject(@PathVariable long id, @Valid @RequestBody RejectRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		CorrectionView view = service.reject(id, jwt.getSubject(), request.comment().strip());
		audit(view);
		return view;
	}

	/** 감사 기록에 대상 계좌를 남긴다. 경로의 {id} 는 정정 요청 번호라 필터가 계좌를 알 수 없다. */
	private static void audit(CorrectionView view) {
		AuditContext.accounts(view.accountId(), null);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
