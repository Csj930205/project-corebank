# 서버 이미지. 같은 이미지로 서버와 DB 마이그레이션을 모두 실행한다(docker/entrypoint.sh).
#   서버:         docker run <이미지>            (앱 계정만 필요)
#   마이그레이션: docker run <이미지> migrate    (테이블 소유자 계정 필요)
#
# 컴파일은 이미지 안에서 하지 않는다. CI(또는 deploy/deploy.sh --local)가 테스트를 통과한 jar 를 ./gradlew bootJar 로 만들고
# 그 jar 를 복사한다. 그래서 테스트한 바로 그 jar 가 이미지에 들어가고, 이미지 빌드는 몇 초면 끝난다.
FROM eclipse-temurin:25-jre-noble

# 일 마감·"오늘 거래" 는 서버 시간대를 따른다(ClockConfig). 컨테이너 기본값 UTC 로 두면 한국 시각 0~9시의 영업일이 하루 밀린다.
ENV TZ=Asia/Seoul

# root 가 아닌 전용 사용자로 실행한다.
RUN useradd --system --no-create-home --uid 10001 corebank

WORKDIR /app
COPY build/libs/project-corebank.jar app.jar
# 실행 권한은 저장소의 파일 권한(755)을 그대로 쓴다(COPY --chmod 는 BuildKit 이 있어야 해서 쓰지 않는다).
COPY docker/entrypoint.sh entrypoint.sh

USER corebank
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["server"]
