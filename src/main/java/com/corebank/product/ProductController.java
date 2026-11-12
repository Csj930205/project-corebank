package com.corebank.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
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
	 * @param annualRate 연이율(%). 0 이상 20 이하, 소수 둘째 자리까지(2.35). 셋째 자리가 있으면 400 이다.
	 *                   잘라서 받으면 관리자가 넣은 금리와 다른 금리로 이자가 나간다.
	 */
	public record CreateRequest(
			@NotBlank @Size(max = 50) String name,
			@NotNull @DecimalMin("0") @DecimalMax("20") @Digits(integer = 2, fraction = 2) BigDecimal annualRate) {
	}

	public record Products(List<ProductResponse> content) {
	}

	/** GET /products: 판매 중인 상품 */
	@GetMapping("/products")
	public Products active() {
		return new Products(service.listActive().stream().map(ProductResponse::from).toList());
	}

	/** GET /admin/products: 판매 중지 포함 전체 */
	@GetMapping("/admin/products")
	public Products all() {
		return new Products(service.listAll().stream().map(ProductResponse::from).toList());
	}

	/** POST /admin/products {name, annualRate}: 상품 등록. 201 */
	@PostMapping("/admin/products")
	public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateRequest request) {
		DepositProduct product = service.create(request.name(), request.annualRate());
		return ResponseEntity.created(URI.create("/admin/products/" + product.getId())).body(ProductResponse.from(product));
	}

	/** POST /admin/products/{id}/discontinuation: 판매 중지. 이미 연 계좌는 그대로 이자가 붙는다 */
	@PostMapping("/admin/products/{id}/discontinuation")
	public ProductResponse discontinue(@PathVariable long id) {
		return ProductResponse.from(service.discontinue(id));
	}

}
