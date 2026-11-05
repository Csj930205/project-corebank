package com.corebank.closing;

import java.util.List;

/** 계좌 하나를 대사한 결과. 불일치가 없는 계좌는 만들지 않는다(프로세서가 null 을 돌려 걸러 낸다). */
public record AccountReconciliation(Long accountId, List<ClosingMismatch> mismatches) {
}
