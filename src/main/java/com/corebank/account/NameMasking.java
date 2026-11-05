package com.corebank.account;

/**
 * 예금주 이름 가리기. 수취인 조회에서 보내는 사람이 "받는 사람이 맞는지" 확인할 만큼만 보여 준다.
 *
 * <p>규칙(국내 은행 화면에서 흔한 방식): 첫 글자와 마지막 글자만 남기고 가운데를 * 로 바꾼다.
 * 두 글자는 마지막 글자를 가린다. 홍길동 → 홍*동, 남궁민수 → 남**수, 이산 → 이*.
 * 글자 수는 코드 포인트로 센다(이모지·확장 한자도 한 글자).
 */
public final class NameMasking {

	private NameMasking() {
	}

	public static String mask(String name) {
		String trimmed = name.strip();
		int[] cps = trimmed.codePoints().toArray();
		if (cps.length <= 1) {
			return trimmed;
		}
		StringBuilder sb = new StringBuilder().appendCodePoint(cps[0]);
		if (cps.length == 2) {
			return sb.append('*').toString();
		}
		sb.append("*".repeat(cps.length - 2));
		return sb.appendCodePoint(cps[cps.length - 1]).toString();
	}

}
