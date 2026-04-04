# 문제 추천 기능 Redis 캐시 + 비동기 적용 정리

## 1. 대화 정리

### 현재 구조에 대한 판단
- 현재 문제 추천 기능은 이미 DB 저장 기반으로 동작한다.
- 로그인 성공 시 `UserAuthenticationSuccessHandler`가 `AllenService.sovledacCall(...)`을 바로 호출한다.
- 추천 결과는 `SolvedACResponseEntity`에 사용자별로 저장되고, 홈 화면에서는 DB에서 추천 리스트를 읽은 뒤 랜덤 1개를 보여준다.
- Redis 의존성과 기본 설정은 이미 프로젝트에 포함되어 있지만, 추천 기능에서 실질적으로 사용되는 코드는 아직 없다.

### Redis 적용에 대한 결론
- Redis를 붙이는 것 자체는 적절하다.
- 다만 현재 구조에서는 "홈 화면 조회 성능 개선"보다 "로그인 시 외부 추천 API 재호출을 줄이는 목적"이 더 중요하다.
- 즉, Redis의 1차 역할은 단순 조회 캐시보다 `추천 생성 결과 캐시`와 `중복 생성 방지`에 가깝다.

### 비동기 적용에 대한 결론
- 추천 생성 로직을 로그인 요청-응답 경로에서 분리하는 것이 가장 효과적이다.
- 사용자는 로그인 후 즉시 홈으로 이동하고, 추천 생성은 백그라운드에서 수행하는 방식이 적절하다.
- 현재 프로젝트 규모와 구조를 고려하면 Kafka/RabbitMQ 같은 메시지 큐보다 Spring `@Async` 방식이 가장 현실적이다.

## 2. 현재 코드 기준 근거

### 확인된 사실
- Redis 의존성이 이미 존재한다.
  - 근거: `build.gradle`
- Redis 연결 설정이 이미 존재한다.
  - 근거: `src/main/resources/application.yml`
  - 근거: `src/main/java/com/example/algoyweb/config/redis/RedisConfig.java`
- 로그인 성공 시 추천 API 호출이 동기적으로 실행된다.
  - 근거: `src/main/java/com/example/algoyweb/util/user/UserAuthenticationSuccessHandler.java`
- 추천 결과는 MySQL(JPA 엔티티)로 저장된다.
  - 근거: `src/main/java/com/example/algoyweb/model/entity/allen/SolvedACResponseEntity.java`
  - 근거: `src/main/java/com/example/algoyweb/repository/allen/SolvedACResponseRepository.java`
- 홈 화면은 저장된 추천 결과를 읽어 노출한다.
  - 근거: `src/main/java/com/example/algoyweb/controller/HomeController.java`
  - 근거: `src/main/java/com/example/algoyweb/service/user/UserService.java`
- 스케줄링은 활성화되어 있지만 비동기 실행은 아직 활성화되어 있지 않다.
  - 근거: `src/main/java/com/example/algoyweb/AlgoyWebApplication.java`

## 3. 권장 아키텍처

### 목표
- 로그인 응답 시간을 외부 AI API latency로부터 분리
- 동일 사용자에 대한 불필요한 추천 재생성 방지
- 추천 조회는 빠르게 처리하고, 실패 시 기존 데이터로 fallback
- 최종 영속 저장소는 DB로 유지

### 권장 역할 분리
- MySQL:
  - 최종 저장소
  - 추천 결과 영속 보관
  - Redis 장애 시 fallback source
- Redis:
  - 추천 결과 캐시
  - 추천 생성 중복 방지용 lock
  - 필요 시 "생성 중" 상태 표시용 짧은 상태값
- Async executor:
  - 외부 추천 API 호출
  - Redis/DB 갱신

### 권장 흐름
1. 로그인 성공
2. 로그인 핸들러는 외부 API를 직접 기다리지 않고 비동기 갱신 작업만 요청
3. 홈 조회 시 Redis에서 추천 결과를 먼저 조회
4. Redis miss이면 DB 조회
5. DB에도 없으면 "추천 생성 중" 또는 기본 안내 문구 표시
6. 백그라운드 작업이 완료되면 Redis와 DB를 함께 갱신

## 4. Redis 키 설계

### 추천 결과 키
- 키: `recommendation:{userEmail}`
- 값: 추천 문제 리스트(JSON 직렬화 또는 Redis Hash/List 중 하나)
- TTL: 6시간~24시간 권장

### 추천 생성 락 키
- 키: `recommendation:refresh-lock:{userEmail}`
- 값: 임의 문자열 또는 timestamp
- TTL: 1분~3분 권장

### 선택적 상태 키
- 키: `recommendation:status:{userEmail}`
- 값: `PROCESSING`, `READY`, `FAILED`
- TTL: 짧게 운영 가능

## 5. 왜 이 방식이 현재 코드에 맞는가

### 장점
- 로그인 시 외부 API 응답 대기를 제거할 수 있다.
- 같은 사용자가 짧은 시간 내 여러 번 로그인해도 Redis lock으로 중복 호출을 줄일 수 있다.
- 홈 화면은 Redis hit 시 DB보다 더 빠르게 응답할 수 있다.
- Redis miss 시 DB fallback이 있어 안정적이다.
- 현재 이미 존재하는 `SolvedACResponseEntity`를 그대로 활용할 수 있어 도입 비용이 낮다.

### 주의점
- Redis를 붙여도 추천 생성 자체가 빨라지는 것은 아니다. 빨라지는 것은 "반복 생성 방지"와 "조회 경로 단축"이다.
- 캐시 TTL 정책이 너무 짧으면 외부 API 호출 절감 효과가 약해진다.
- 로그인 시마다 무조건 async 작업을 던지면 중복 생성이 생기므로 lock이 필요하다.

## 6. 구현 방법

### 6-1. 비동기 실행 활성화
- `AlgoyWebApplication` 또는 별도 설정 클래스에 `@EnableAsync` 추가
- 추천 생성 전용 `TaskExecutor` 빈 생성

예상 수정 파일:
- `src/main/java/com/example/algoyweb/AlgoyWebApplication.java`
- 또는 `src/main/java/com/example/algoyweb/config/async/AsyncConfig.java`

권장 구현 포인트:
- 추천 생성 작업은 외부 API I/O가 포함되므로 별도 executor를 두는 편이 안전하다.
- 스레드풀 크기는 초기에 작게 시작하고 운영 로그를 보고 조정한다.

### 6-2. Redis 접근 계층 추가
- 추천 캐시 전용 service 또는 repository 성격의 클래스를 추가
- `RedisTemplate<String, String>` 또는 `StringRedisTemplate` 사용
- 추천 리스트 저장/조회, lock 획득/해제 기능을 한곳에 모은다

권장 신규 클래스 예시:
- `src/main/java/com/example/algoyweb/service/allen/RecommendationCacheService.java`

권장 메서드 예시:
- `Optional<List<String>> getRecommendations(String userEmail)`
- `void cacheRecommendations(String userEmail, List<String> recommendations, Duration ttl)`
- `boolean tryAcquireRefreshLock(String userEmail, Duration ttl)`
- `void releaseRefreshLock(String userEmail)`
- `boolean isCacheFresh(String userEmail)` 또는 TTL 기반 확인

### 6-3. 추천 생성 오케스트레이션 서비스 추가
- 기존 `AllenService`가 외부 호출과 DB 저장을 모두 담당하고 있으므로, 그 위에 orchestration service를 하나 두는 것이 좋다.
- 이 서비스가 "캐시 확인 -> 락 확인 -> 비동기 생성 -> DB/Redis 저장" 순서를 관리한다.

권장 신규 클래스 예시:
- `src/main/java/com/example/algoyweb/service/allen/RecommendationRefreshService.java`

권장 책임:
- refresh 필요 여부 판단
- Redis lock 획득
- 비동기 생성 실행
- 성공 시 DB + Redis 갱신
- 실패 시 로그 기록, 기존 캐시 유지

### 6-4. 비동기 작업 메서드 분리
- 외부 AI API 호출은 `@Async` 메서드로 분리
- 로그인 핸들러는 이 메서드를 fire-and-forget 방식으로 호출

권장 신규 클래스 예시:
- `src/main/java/com/example/algoyweb/service/allen/RecommendationAsyncService.java`

권장 메서드 예시:
- `@Async("recommendationTaskExecutor")`
- `public void refreshRecommendations(String algoyUsername, String userEmail, String solvedAcUsername)`

처리 순서:
1. Redis lock 확보 확인
2. `AllenService`를 이용해 외부 추천 조회
3. 추천 결과를 DB에 저장
4. 같은 결과를 Redis에 캐시
5. lock 정리

### 6-5. AllenService 역할 정리
- 현재 `AllenService.sovledacCall(...)`은 외부 호출 + DB 저장이 결합되어 있다.
- 유지보수성을 위해 다음 중 하나로 정리하는 것이 좋다.

권장 방향:
- `AllenService`는 "외부 API 호출 후 추천 리스트 반환"에 집중
- DB 저장은 orchestration service가 수행

가능한 메서드 분리 예시:
- `List<String> fetchSolvedAcRecommendations(String algoyUsername, String solvedAcUsername)`
- `void saveResponse(String username, List<String> responseList)`

이렇게 나누면 async service에서 재사용하기 쉽다.

### 6-6. 로그인 성공 핸들러 수정
- 로그인 성공 시 외부 API를 직접 호출하지 않는다.
- 대신 아래 조건일 때만 비동기 갱신 요청을 보낸다.
  - solved.ac username이 존재함
  - Redis 캐시가 없거나 TTL이 만료됨
  - 또는 DB 데이터가 오래됨

대상 파일:
- `src/main/java/com/example/algoyweb/util/user/UserAuthenticationSuccessHandler.java`

권장 변경 후 흐름:
1. 사용자 정보 조회
2. solved.ac username 없으면 기존 안내 메시지 유지
3. 있으면 `recommendationRefreshService.triggerRefreshIfNeeded(...)` 호출
4. 바로 `/algoy/home`으로 redirect

### 6-7. 홈 조회 로직 수정
- 홈 화면은 DB보다 Redis를 먼저 본다.
- Redis에 있으면 즉시 랜덤 문제 1개 반환
- Redis miss면 DB 조회 후, DB 결과를 Redis에 재적재할 수 있다
- 둘 다 없으면 안내 문구 또는 "추천 생성 중" 문구를 반환한다

대상 파일:
- `src/main/java/com/example/algoyweb/service/user/UserService.java`
- `src/main/java/com/example/algoyweb/controller/HomeController.java`

권장 조회 순서:
1. Redis cache hit
2. DB fallback
3. 문구 fallback

### 6-8. DB fallback 정책
- Redis는 캐시이므로 단독 source of truth로 쓰지 않는다.
- 추천 결과 저장 성공 시 DB와 Redis를 함께 갱신한다.
- Redis 장애 시에도 최소한 DB 조회 경로는 유지한다.

## 7. 구체적인 변경 포인트

### 추가가 필요한 항목
- `@EnableAsync`
- recommendation 전용 executor
- Redis 캐시 service
- refresh orchestration service
- async refresh service
- 홈 조회 시 Redis 우선 로직
- 로그인 성공 시 비동기 트리거 로직

### 변경이 필요한 기존 파일
- `src/main/java/com/example/algoyweb/AlgoyWebApplication.java`
- `src/main/java/com/example/algoyweb/util/user/UserAuthenticationSuccessHandler.java`
- `src/main/java/com/example/algoyweb/service/user/UserService.java`
- `src/main/java/com/example/algoyweb/service/allen/AllenService.java`

### 재사용 가능한 기존 파일
- `src/main/java/com/example/algoyweb/config/redis/RedisConfig.java`
- `src/main/java/com/example/algoyweb/repository/allen/SolvedACResponseRepository.java`
- `src/main/java/com/example/algoyweb/model/entity/allen/SolvedACResponseEntity.java`

## 8. 권장 pseudo flow

```text
[로그인 성공]
  -> UserAuthenticationSuccessHandler
  -> solved.ac username 확인
  -> RecommendationRefreshService.triggerRefreshIfNeeded(...)
  -> 즉시 /algoy/home redirect

[triggerRefreshIfNeeded]
  -> Redis에서 recommendation:{userEmail} 확인
  -> 캐시가 신선하면 종료
  -> lock 키 획득 시도
  -> 성공하면 RecommendationAsyncService.refreshRecommendations(...) 호출

[refreshRecommendations @Async]
  -> AllenService로 외부 API 호출
  -> 추천 리스트 생성
  -> DB 저장
  -> Redis 저장
  -> lock 정리

[홈 조회]
  -> Redis 조회
  -> 있으면 랜덤 1개 반환
  -> 없으면 DB 조회
  -> DB 결과 있으면 Redis 재적재 후 반환
  -> 없으면 기본 문구 반환
```

## 9. 실패 처리 방안

### 외부 AI API 실패
- 로그인 실패로 번지지 않게 한다.
- 비동기 작업에서 예외를 삼키지 말고 로그로 남긴다.
- 기존 DB/Redis 데이터가 있으면 유지한다.

### Redis 장애
- 홈 조회 시 DB fallback 사용
- 추천 생성은 DB 저장 우선으로라도 유지 가능하게 설계
- lock이 동작하지 않을 수 있으므로 중복 호출 가능성은 감수해야 한다

### DB 저장 실패
- Redis만 성공하고 DB 저장이 실패하면 source of truth가 흔들린다.
- 가능하면 DB 저장 성공 후 Redis 갱신 순서를 권장한다.

## 10. TTL 및 갱신 정책 제안

### 기본안
- 추천 캐시 TTL: 12시간
- refresh lock TTL: 2분

### 갱신 트리거
- 로그인 시 캐시 miss
- 로그인 시 캐시 만료
- solved.ac username 변경 시 강제 무효화

### 선택적 확장
- 하루 1회 배치 갱신
- 사용자가 직접 "새 추천 받기" 버튼을 눌렀을 때 강제 refresh

## 11. 단계별 구현 순서

1. `@EnableAsync`와 executor 추가
2. Redis 캐시 service 추가
3. `AllenService`를 "조회"와 "저장" 책임으로 분리
4. refresh orchestration service 추가
5. async refresh service 추가
6. 로그인 성공 핸들러를 비동기 트리거 방식으로 수정
7. 홈 조회 로직을 Redis 우선으로 수정
8. 캐시 miss / 장애 / 실패 로그 검증

## 12. 최종 권장안 요약

- 현재 구조에서는 Redis를 단순 조회 캐시보다 "추천 생성 결과 캐시 + 중복 생성 제어"에 쓰는 것이 맞다.
- 비동기는 Spring `@Async` 기반이 가장 현실적이다.
- 로그인 경로에서는 외부 API를 기다리지 않고, 홈 조회는 Redis 우선으로 처리한다.
- DB는 최종 저장소로 유지하고 Redis는 성능 및 안정성 보조 계층으로 둔다.

## 13. 관련 파일 목록

- `build.gradle`
- `src/main/resources/application.yml`
- `src/main/java/com/example/algoyweb/AlgoyWebApplication.java`
- `src/main/java/com/example/algoyweb/config/redis/RedisConfig.java`
- `src/main/java/com/example/algoyweb/util/user/UserAuthenticationSuccessHandler.java`
- `src/main/java/com/example/algoyweb/service/allen/AllenService.java`
- `src/main/java/com/example/algoyweb/service/user/UserService.java`
- `src/main/java/com/example/algoyweb/controller/HomeController.java`
- `src/main/java/com/example/algoyweb/model/entity/allen/SolvedACResponseEntity.java`
- `src/main/java/com/example/algoyweb/repository/allen/SolvedACResponseRepository.java`

## 14. 추가 의사결정 정리

### Redis only를 선택할 때
- 추천 결과를 완전한 임시 데이터로 보고 싶을 때 적합하다.
- Redis가 비워지면 추천 결과도 함께 사라진다.
- 이 경우에는 외부 추천 API를 다시 호출해 재생성해야 한다.
- 외부 추천 API가 1분 이상 걸리는 현재 상황에서는 Redis 유실 시 홈 화면 UX가 약해질 수 있다.

### Redis + MySQL fallback을 선택할 때
- 평소 추천 동작은 Redis를 중심으로 처리한다.
- Redis가 비워지면 MySQL에 저장된 마지막 추천 1세트를 fallback으로 즉시 보여줄 수 있다.
- 이후 외부 추천 API를 비동기로 다시 호출해 Redis와 MySQL을 최신 상태로 갱신한다.
- MySQL은 추천 이력을 계속 누적 저장하는 용도가 아니라, 사용자별 마지막 추천 스냅샷 1세트만 overwrite 저장하는 용도로 사용한다.

### MySQL 중심 구조를 유지할 때
- 변경 폭은 가장 작다.
- 다만 추천 결과를 임시 데이터로 취급하려는 제품 방향과는 덜 맞는다.
- Redis를 도입하는 의미도 lock이나 일부 보조 기능 수준으로 줄어든다.

### 최종 의견
- 현재 서비스 상황에서는 `Redis + MySQL fallback`이 가장 현실적이다.
- 이유는 추천 결과 자체는 임시 데이터에 가깝지만, Redis 유실 시 외부 추천 API 호출 시간이 1분 이상 걸려 화면 공백이 길어질 수 있기 때문이다.
- 따라서 운영 중심은 Redis로 두고, MySQL은 과거 이력 누적이 아닌 "마지막 추천 1세트 백업본"으로만 유지하는 구성이 적절하다.
- 이 경우 Redis에는 다음 데이터를 둔다.
  - `active 추천 리스트`
  - `seen 추천 이력`
  - `refresh lock`
- MySQL에는 사용자별 마지막 추천 결과 1세트만 저장하고, 새 추천이 생성될 때마다 overwrite 하는 방식이 바람직하다.
