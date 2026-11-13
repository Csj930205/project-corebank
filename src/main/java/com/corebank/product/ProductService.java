package com.corebank.product;

import com.corebank.common.DbTime;
import org.hibernate.Hibernate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class ProductService {

	private final DepositProductRepository repository;
	private final DepositProductRateRepository rates;

	public ProductService(DepositProductRepository repository, DepositProductRateRepository rates) {
		this.repository = repository;
		this.rates = rates;
	}

	/** 판매 중인 상품(고객이 계좌를 열 때 고른다). 두 종류가 함께 나온다 */
	@Transactional(readOnly = true)
	public List<DepositProduct> listActive() {
		return repository.findByStatusOrderById(ProductStatus.ACTIVE);
	}

	/** 판매 중지 포함 전체(관리자) */
	@Transactional(readOnly = true)
	public List<DepositProduct> listAll() {
		return repository.findAllByOrderById();
	}

	/** 입출금 상품 등록. */
	@Transactional
	public DepositProduct create(String name, BigDecimal annualRate) {
		return save(DepositProduct.of(name.strip(), annualRate));
	}

	/** 정기예금 상품 등록. */
	@Transactional
	public DepositProduct createTimeDeposit(String name, BigDecimal annualRate, int termMonths) {
		return save(TimeDepositProduct.of(name.strip(), annualRate, termMonths));
	}

	/**
	 * 이름 중복은 DB 유니크 제약으로 확인한다(고객 가입 아이디와 같은 이유: 조회 후 저장은 동시 요청에 뚫린다).
	 * 첫 금리도 이력에 남긴다. 그래야 "그날의 금리" 를 이력에서만 찾으면 된다.
	 *
	 * @throws ProductNameTakenException 같은 이름
	 */
	private DepositProduct save(DepositProduct product) {
		DepositProduct saved;
		try {
			saved = repository.saveAndFlush(product);
		} catch (DataIntegrityViolationException e) {
			throw new ProductNameTakenException(product.getName());
		}
		rates.save(DepositProductRate.of(saved.getId(), saved.getAnnualRate(), today(), null));
		return saved;
	}

	/**
	 * 금리를 바꾼다. 오늘부터 새 금리다.
	 * <ul>
	 *   <li>입출금 상품: 오늘 마감 잔액부터 새 금리로 적수가 쌓인다. 어제까지 쌓인 적수는 그날 금리를 저장해 두었으므로 그대로다.</li>
	 *   <li>정기예금 상품: 오늘부터 가입하는 예금에 새 금리가 붙는다. 이미 든 예금은 약정이율이 계약에 복사돼 있어 그대로다.</li>
	 * </ul>
	 * 지난 날짜부터 적용하는 변경은 받지 않는다. 이미 쌓인 적수·지급한 이자와 이력이 어긋난다.
	 *
	 * @throws ProductNotFoundException 없는 상품
	 */
	@Transactional
	public DepositProduct changeRate(Long id, BigDecimal annualRate, String changedBy) {
		DepositProduct product = repository.findByIdForUpdate(id).orElseThrow(() -> new ProductNotFoundException(id));
		product.changeRate(annualRate);
		rates.save(DepositProductRate.of(id, annualRate, today(), changedBy));
		return product;
	}

	/** 금리 이력(최신순) */
	@Transactional(readOnly = true)
	public List<DepositProductRate> rateHistory(Long id) {
		get(id);
		return rates.findByProductIdOrderByEffectiveFromDescIdDesc(id);
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
	 * 새 입출금 계좌에 쓸 상품. null 이면 기본 상품이다.
	 *
	 * @throws ProductNotFoundException     없는 상품
	 * @throws ProductTypeMismatchException 정기예금 상품(정기예금은 POST /time-deposits 로 가입한다)
	 * @throws ProductNotAvailableException 판매 중지된 상품
	 */
	@Transactional(readOnly = true)
	public DepositProduct forNewAccount(Long id) {
		return forNew(id == null ? DepositProduct.DEFAULT_ID : id, ProductType.DEMAND);
	}

	/**
	 * 새 정기예금에 쓸 상품.
	 *
	 * @throws ProductNotFoundException     없는 상품
	 * @throws ProductTypeMismatchException 입출금 상품
	 * @throws ProductNotAvailableException 판매 중지된 상품
	 */
	@Transactional(readOnly = true)
	public TimeDepositProduct forNewTimeDeposit(Long id) {
		// 바로 (TimeDepositProduct) 로 형변환하지 않는다. 같은 트랜잭션에서 이 상품을 이미 지연 로딩 프록시로 만난 적이 있으면
		// findById 도 그 프록시를 돌려주는데, 프록시는 DepositProduct 의 자식일 뿐이라 형변환이 ClassCastException 으로 실패한다.
		// Hibernate.unproxy 는 프록시면 실제 객체를 꺼내고, 이미 실제 객체면 그대로 돌려준다. 실제 객체는 TimeDepositProduct 다.
		return (TimeDepositProduct) Hibernate.unproxy(forNew(id, ProductType.TIME));
	}

	private DepositProduct forNew(Long id, ProductType type) {
		DepositProduct product = get(id);
		if (product.getType() != type) {
			throw new ProductTypeMismatchException(id, type);
		}
		if (!product.isActive()) {
			throw new ProductNotAvailableException(product.getId());
		}
		return product;
	}

	@Transactional(readOnly = true)
	public DepositProduct get(Long id) {
		return repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
	}

	/**
	 * 금리 이력의 날짜. 원장·개설 시각과 같은 시계(DbTime, 서버 시각)를 쓴다. 적수는 원장 시각으로 그날 마감 잔액을 찾으므로,
	 * 금리 적용일도 같은 시계여야 "오늘 바꾼 금리가 오늘 마감 잔액부터" 가 맞는다.
	 */
	private static LocalDate today() {
		return DbTime.now().toLocalDate();
	}

}
