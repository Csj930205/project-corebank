package com.corebank.idempotency;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 멱등성 키 확인과 기록. 업무 처리와 같은 트랜잭션 안에서만 실행되어, 업무가 롤백되면 기록도 함께 사라진다.
 *
 * <p>호출 순서가 중요하다. 호출자는 반드시 <b>계좌를 잠근 뒤</b> {@link #findCompleted} 를 부른다.
 * 같은 요청 두 개가 동시에 오면 두 번째는 계좌 잠금에서 기다리고, 첫 번째가 키 기록과 함께 커밋한 뒤에
 * 키를 확인하므로 저장된 결과를 돌려받는다. 잠그기 전에 확인하면 둘 다 "처음 보는 키"로 판단해 두 번 처리한다.
 */
@Service
public class IdempotencyService {

	private final IdempotencyRecordRepository repository;

	public IdempotencyService(IdempotencyRecordRepository repository) {
		this.repository = repository;
	}

	/**
	 * 이미 성공한 요청이면 그때 만든 거래 번호를 돌려준다. 처음 보는 키면 빈 값이다.
	 *
	 * @throws IdempotencyKeyReusedException 같은 키로 다른 내용의 요청을 처리한 적이 있을 때
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Long> findCompleted(IdempotencyKey key, String requestFingerprint) {
		return repository.findByKey(key.value())
				.map(record -> {
					if (!record.getRequestFingerprint().equals(requestFingerprint)) {
						throw new IdempotencyKeyReusedException(key);
					}
					return record.getResourceId();
				});
	}

	/**
	 * 성공한 요청을 기록한다. 바로 INSERT 해서(saveAndFlush) 키 중복을 이 자리에서 확인한다.
	 *
	 * <p>여기서 중복이 나는 경우는 같은 키로 <b>다른 계좌</b>를 대상으로 한 요청이 동시에 들어온 경우뿐이다.
	 * 서로 다른 계좌라 잠금으로 줄을 세울 수 없어 둘 다 확인을 통과하지만, 유니크 제약이 하나만 남기고
	 * 나머지는 키 재사용으로 거부한다. 예외가 나면 업무 처리도 함께 롤백된다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(IdempotencyKey key, IdempotentOperation operation, String requestFingerprint, Long resourceId) {
		try {
			repository.saveAndFlush(new IdempotencyRecord(key, operation, requestFingerprint, resourceId));
		} catch (DataIntegrityViolationException e) {
			throw new IdempotencyKeyReusedException(key);
		}
	}

}
