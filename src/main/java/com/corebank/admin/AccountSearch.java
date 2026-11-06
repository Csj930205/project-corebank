package com.corebank.admin;

import com.corebank.account.AccountStatus;
import com.corebank.common.DateRange;

/**
 * 관리자 계좌 검색 조건. null 인 조건은 쓰지 않는다.
 *
 * @param query      계좌번호 앞자리 또는 예금주 이름 일부
 * @param status     계좌 상태
 * @param opened     개설일 기간(날짜, 양 끝 포함)
 * @param minBalance 잔액 하한(원, 포함)
 * @param maxBalance 잔액 상한(원, 포함)
 */
public record AccountSearch(String query, AccountStatus status, DateRange opened, Long minBalance, Long maxBalance) {

	public AccountSearch {
		opened = opened == null ? DateRange.ALL : opened;
	}

	/** 검색어와 상태만으로 찾는다. */
	public AccountSearch(String query, AccountStatus status) {
		this(query, status, DateRange.ALL, null, null);
	}

}
