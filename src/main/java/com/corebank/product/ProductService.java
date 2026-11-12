package com.corebank.product;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductService {

	private final DepositProductRepository repository;

	public ProductService(DepositProductRepository repository) {
		this.repository = repository;
	}

	/** 판매 중인 상품(고객이 계좌를 열 때 고른다) */
	@Transactional(readOnly = true)
	public List<DepositProduct> listActive() {
		return repository.findByStatusOrderById(ProductStatus.ACTIVE);
	}

	/** 판매 중지 포함 전체(관리자) */
	@Transactional(readOnly = true)
	public List<DepositProduct> listAll() {
		return repository.findAllByOrderById();
	}

	/**
	 * 상품 등록. 이름 중복은 DB 유니크 제약으로 확인한다(고객 가입 아이디와 같은 이유: 조회 후 저장은 동시 요청에 뚫린다).
	 *
	 * @throws ProductNameTakenException 같은 이름
	 */
	@Transactional
	public DepositProduct create(String name, BigDecimal annualRate) {
		try {
			return repository.saveAndFlush(DepositProduct.of(name.strip(), annualRate));
		} catch (DataIntegrityViolationException e) {
			throw new ProductNameTakenException(name.strip());
		}
	}

	/**
	 * 판매 중지. 기본 상품은 중지할 수 없다(관리자가 상품을 고르지 않고 여는 계좌가 갈 곳이 없어진다).
	 * 변경은 변경 감지(dirty checking)로 커밋 때 UPDATE 된다. save 를 부를 필요가 없다.
	 *
	 * @throws ProductNotAvailableException 기본 상품, 이미 중지된 상품
	 */
	@Transactional
	public DepositProduct discontinue(Long id) {
		if (id == DepositProduct.DEFAULT_ID) {
			throw new ProductNotAvailableException("기본 상품은 판매 중지할 수 없습니다.");
		}
		DepositProduct product = get(id);
		product.discontinue();
		return product;
	}

	/**
	 * 새 계좌에 쓸 상품. null 이면 기본 상품이다.
	 *
	 * @throws ProductNotFoundException     없는 상품
	 * @throws ProductNotAvailableException 판매 중지된 상품
	 */
	@Transactional(readOnly = true)
	public DepositProduct forNewAccount(Long id) {
		DepositProduct product = get(id == null ? DepositProduct.DEFAULT_ID : id);
		if (!product.isActive()) {
			throw new ProductNotAvailableException(product.getId());
		}
		return product;
	}

	@Transactional(readOnly = true)
	public DepositProduct get(Long id) {
		return repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
	}

}
