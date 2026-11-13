package com.corebank.product;

import com.corebank.auth.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 예금 상품 API. GET /products 는 로그인한 누구나(고객이 계좌를 열 때 고른다), /admin/products 는 관리자만.
 */
@RestController
public class ProductController {

	private final ProductService service;

	public ProductController(ProductService service) {
		this.service = service;
	}

	/**
	 * @param type       상품 종류. 없으면 입출금(DEMAND)
	 * @param annualRate 연이율(%). 0 이상 20 이하, 소수 둘째 자리까지(2.35). 셋째 자리가 있으면 400 이다.
	 *                   잘라서 받으면 관리자가 넣은 금리와 다른 금리로 이자가 나간다.
	 * @param termMonths 정기예금 가입 기간(개월, 1~60). 정기예금이면 필수, 입출금이면 보내면 안 된다
	 */
	public record CreateRequest(
			@NotBlank @Size(max = 50) String name,
			ProductType type,
			@NotNull @DecimalMin("0") @DecimalMax("20") @Digits(integer = 2, fraction = 2) BigDecimal annualRate,
			Integer termMonths) {

		ProductType typeOrDefault() {
			return type == null ? ProductType.DEMAND : type;
		}

		/** 종류와 기간이 짝이 맞는지. @AssertTrue 메서드도 @Valid 가 검사해 어기면 400 INVALID_REQUEST 다 */
		@AssertTrue
		boolean isTermValid() {
			return switch (typeOrDefault()) {
				case DEMAND -> termMonths == null;
				case TIME -> termMonths != null && termMonths >= 1 && termMonths <= TimeDepositProduct.MAX_TERM_MONTHS;
			};
		}
	}

	/** @param annualRate 새 연이율(%). 등록과 같은 범위 */
	public record RateChangeRequest(
			@NotNull @DecimalMin("0") @DecimalMax("20") @Digits(integer = 2, fraction = 2) BigDecimal annualRate) {
	}

	public record Products(List<ProductResponse> content) {
	}

	public record Rate(Long id, BigDecimal annualRate, LocalDate effectiveFrom, String changedBy,
			LocalDateTime createdAt) {

		static Rate from(DepositProductRate rate) {
			return new Rate(rate.getId(), rate.getAnnualRate(), rate.getEffectiveFrom(), rate.getChangedBy(),
					rate.getCreatedAt());
		}
	}

	public record Rates(Long productId, List<Rate> content) {
	}

	/** GET /products: 판매 중인 상품(입출금·정기예금) */
	@GetMapping("/products")
	public Products active() {
		return new Products(service.listActive().stream().map(ProductResponse::from).toList());
	}

	/** GET /admin/products: 판매 중지 포함 전체 */
	@GetMapping("/admin/products")
	public Products all() {
		return new Products(service.listAll().stream().map(ProductResponse::from).toList());
	}

	/** POST /admin/products {name, type, annualRate, termMonths}: 상품 등록. 201 */
	@PostMapping("/admin/products")
	public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateRequest request) {
		DepositProduct product = switch (request.typeOrDefault()) {
			case DEMAND -> service.create(request.name(), request.annualRate());
			case TIME -> service.createTimeDeposit(request.name(), request.annualRate(), request.termMonths());
		};
		return ResponseEntity.created(URI.create("/admin/products/" + product.getId())).body(ProductResponse.from(product));
	}

	/** POST /admin/products/{id}/discontinuation: 판매 중지. 이미 연 계좌는 그대로 이자가 붙는다 */
	@PostMapping("/admin/products/{id}/discontinuation")
	public ProductResponse discontinue(@PathVariable long id) {
		return ProductResponse.from(service.discontinue(id));
	}

	/**
	 * POST /admin/products/{id}/rate-changes {annualRate}: 금리 변경. 오늘부터 적용한다.
	 * 이력이 하나 생기므로 POST 다. 같은 금리로 다시 보내도 이력이 한 줄 더 남을 뿐 이자는 같다.
	 */
	@PostMapping("/admin/products/{id}/rate-changes")
	public ProductResponse changeRate(@PathVariable long id, @Valid @RequestBody RateChangeRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		return ProductResponse.from(service.changeRate(id, request.annualRate(), Actor.from(jwt).username()));
	}

	/** GET /admin/products/{id}/rates: 금리 이력(최신순) */
	@GetMapping("/admin/products/{id}/rates")
	public Rates rates(@PathVariable long id) {
		return new Rates(id, service.rateHistory(id).stream().map(Rate::from).toList());
	}

}
