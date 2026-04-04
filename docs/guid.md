# Redis 적용 가이드

이 문서는 `Algoy-Web`의 문제 추천 기능에 Redis를 단계적으로 적용하기 위한 코드 가이드다.
직접 코드를 수정하지 않고, 어떤 파일을 어떤 순서로 수정해야 하는지와 예시 코드를 제공한다.

진행 원칙:
- 한 번에 전부 바꾸지 않는다.
- 먼저 Redis 연결과 기본 동작을 붙인다.
- 그 다음 추천 조회/저장 경로를 Redis에 연결한다.
- 마지막에 비동기와 fallback을 붙인다.

권장 구현 순서:
1. Redis 설정 정리
2. Redis 직렬화 설정 추가
3. 추천 캐시 전용 서비스 추가
4. 홈 조회에서 Redis 읽기 적용
5. 추천 저장 시 Redis 쓰기 적용
6. Redis TTL 정책 추가
7. 비동기 refresh 추가
8. Redis + MySQL fallback 적용

---

## 1. 가장 먼저 할 일: 설정값 분리

### 수정 파일
- `src/main/resources/application.yml`

### 목적
- Redis host/port를 하드코딩하지 않고 환경변수로 받기 쉽게 만든다.
- 추천 캐시 TTL도 설정값으로 분리한다.

### 권장 수정 예시
```yml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

recommendation:
  cache:
    ttl-hours: ${RECOMMENDATION_CACHE_TTL_HOURS:12}
  seen:
    ttl-days: ${RECOMMENDATION_SEEN_TTL_DAYS:30}
```

### 이유
- 로컬과 운영 환경을 나누기 쉽다.
- TTL 정책을 코드 수정 없이 바꿀 수 있다.

### 로컬 실행용 `application-local.yml` 예시
- 파일 경로: `src/main/resources/application-local.yml`
- 파일명은 `applications-local.yml`이 아니라 `application-local.yml`이어야 한다.
- 아래 예시는 로컬에서 바로 값을 채워 넣기 쉽게 placeholder를 넣어둔 형태다.

```yml
server:
  port: 8081

ai-backend:
  url: http://localhost:8082

solvedac:
  url: https://solved.ac/api/v3/user/top_100?handle=

askallen:
  url: http://localhost:8082/ai/allenapi

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/CHANGE_ME_DB_NAME?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: CHANGE_ME_DB_USERNAME
    password: CHANGE_ME_DB_PASSWORD

  security:
    oauth2:
      client:
        registration:
          google:
            client-id: CHANGE_ME_GOOGLE_CLIENT_ID
            client-secret: CHANGE_ME_GOOGLE_CLIENT_SECRET
            redirect-uri: http://localhost:8081/login/oauth2/code/google

  data:
    redis:
      host: localhost
      port: 6379

recommendation:
  cache:
    ttl-hours: 12
  seen:
    ttl-days: 30
```

### 로컬 프로필 적용 방법
- `application-local.yml`만 만들어서는 자동 적용되지 않는다.
- 실행할 때 `local` 프로필을 활성화해야 한다.

예시:

```powershell
$env:SPRING_PROFILES_ACTIVE="local"
.\gradlew bootRun
```

또는:

```powershell
.\gradlew bootRun --args="--spring.profiles.active=local"
```

### 참고
- `application.yml`의 공통 설정 위에 `application-local.yml` 값이 덮어써진다.
- Redis는 로컬 기본값 localhost:6379로 두면 된다.
- 이 프로젝트는 Redis 외에도 DB, OAuth, AI backend 관련 값이 필요하므로, 해당 값도 함께 채워야 정상 실행 가능하다.

### 로컬 실행 시 실제로 필요한 것
- Java 17
- MySQL
- Redis
- `application-local.yml`에 들어갈 DB/OAuth/API 값
- 추천 기능까지 확인하려면 `askallen.url`이 가리키는 API 서버 또는 mock 서버

### 로컬에서는 Nginx가 꼭 필요하지 않다
- 로컬 1차 실행 목표는 Spring Boot 앱 자체가 뜨는지 확인하는 것이다.
- 이 단계에서는 Nginx를 두지 말고 Spring Boot를 직접 띄우는 편이 단순하다.
- 권장 로컬 구성은 아래와 같다.

```text
Algoy-Web: localhost:8081
Allen 또는 mock API: localhost:8082
Redis: localhost:6379
MySQL: localhost:3306
```

### Nginx가 필요한 경우
- 운영과 비슷한 reverse proxy 경로까지 재현하고 싶을 때
- `/algoy` 같은 path prefix 동작을 로컬에서도 검증하고 싶을 때
- HTTPS, 프록시 헤더, 정적 리소스 프록시까지 확인하고 싶을 때

### Allen API가 없을 때 권장 방식
- 빌드만 확인할 때는 Allen API가 없어도 된다.
- 로그인 후 추천 기능까지 확인하려면 `UserAuthenticationSuccessHandler`에서 Allen API 호출이 발생하므로 대체 수단이 필요하다.
- 이 단계에서는 ChatGPT API를 바로 붙이기보다, 현재 Allen API 계약을 흉내 내는 로컬 mock 서버가 더 단순하다.
- 즉, `http://localhost:8082/ai/allenapi/response?...` 형태 요청을 받아 고정 응답을 주는 mock을 두는 편이 빠르다.

### 왜 ChatGPT API 직접 대체보다 mock이 먼저인가
- 현재 웹 애플리케이션은 OpenAI SDK를 직접 호출하지 않는다.
- 지금 구조는 별도 HTTP API를 호출하는 방식이므로, OpenAI로 바꾸려면 중간 adapter 또는 별도 서비스가 추가로 필요하다.
- 로컬 검증 1차 목표가 실행 확인이라면 mock이 가장 빠르다.

### 권장 로컬 확인 순서
1. `application-local.yml` 값을 채운다.
2. MySQL과 Redis를 로컬에서 띄운다.
3. Allen API 대신 로컬 mock 서버를 준비한다.
4. `local` 프로필로 `bootRun`을 실행한다.
5. 앱이 뜬 뒤 로그인과 홈 진입을 확인한다.
6. 추천 큐 동작이 확인되면 그 다음에 실제 AI 대체 연동을 검토한다.

### 빌드 확인 참고
- 현재 저장소 기준으로 `./gradlew test`를 실행하면 컴파일은 통과한다.
- 다만 테스트는 일부 기존 실패가 있어 전체 초록 상태는 아니다.
- 따라서 1차 목표는 `bootRun`으로 로컬 실행 확인을 먼저 하는 편이 현실적이다.
---

## 2. RedisConfig를 먼저 정리한다

현재 `RedisConfig`는 `RedisTemplate<?, ?>` 기반이고 value serializer도 문자열 직렬화만 설정되어 있다.
문제 추천 리스트를 다루려면 문자열 JSON 저장 전략이 가장 단순하다.

### 수정 파일
- `src/main/java/com/example/algoyweb/config/redis/RedisConfig.java`

### 목표
- `StringRedisTemplate` 또는 `RedisTemplate<String, String>` 중심으로 쓴다.
- 추천 리스트는 JSON 문자열로 저장한다.

### 권장 방향
- 가장 단순하게는 `StringRedisTemplate`를 그대로 사용한다.
- 그러면 추천 리스트는 JSON 문자열로 저장하고, 읽을 때 `Gson`으로 `List<String>`로 변환한다.

### 예시 코드
```java
package com.example.algoyweb.config.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

@Configuration
@RequiredArgsConstructor
@EnableRedisRepositories
public class RedisConfig {

    private final RedisProperties redisProperties;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(redisProperties.getHost(), redisProperties.getPort());
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }
}
```

### 왜 이렇게 하는가
- 추천 데이터는 구조가 단순하다.
- Java 객체 직렬화보다 JSON 문자열 저장이 디버깅하기 쉽다.
- Redis CLI로 데이터 확인도 쉽다.

---

## 3. 추천 캐시 전용 서비스를 만든다

Redis를 직접 컨트롤러나 `UserService`에서 다루지 말고, 전용 서비스로 감싼다.
이 서비스가 있어야 이후 TTL, seen 처리도 한곳에서 관리할 수 있다.

### 새로 만들 파일
- `src/main/java/com/example/algoyweb/service/allen/RecommendationRedisService.java`

### 이 단계에서 담당할 기능
- 현재 추천 리스트를 Redis List에 저장
- 현재 추천 리스트에서 다음 문제 1개 pop
- 사용자 탈퇴 또는 초기화 시 active 추천 키 전체 삭제
- 현재 추천 리스트 길이 확인

### 추천 Redis 키
- active 추천 리스트: `recommendation:active:{userEmail}`
- seen 추천 이력: `recommendation:seen:{userEmail}`

### 1차 버전 예시 코드
```java
package com.example.algoyweb.service.allen;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RecommendationRedisService {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${recommendation.cache.ttl-hours}")
    private long recommendationCacheTtlHours;

    // 사용자별 active 추천 큐 Redis 키를 만든다.
    private String activeKey(String userEmail) {
        return "recommendation:active:" + userEmail;
    }

    // active 추천 큐를 현재 추천 1세트로 통째로 교체하고 TTL을 설정한다.
    public void replaceActiveRecommendations(String userEmail, List<String> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) {
            return;
        }

        String key = activeKey(userEmail);
        stringRedisTemplate.delete(key);
        stringRedisTemplate.opsForList().rightPushAll(key, recommendations);
        stringRedisTemplate.expire(key, Duration.ofHours(recommendationCacheTtlHours));
    }

    // 현재 추천 큐에서 다음 문제 1개를 꺼낸다.
    public Optional<String> popNextRecommendation(String userEmail) {
        String problem = stringRedisTemplate.opsForList().leftPop(activeKey(userEmail));
        return Optional.ofNullable(problem);
    }

    // 사용자가 탈퇴하거나 추천 큐를 초기화해야 할 때 active 추천 키 전체를 삭제한다.
    public void deleteActiveRecommendations(String userEmail) {
        stringRedisTemplate.delete(activeKey(userEmail));
    }

    // 현재 Redis List에 남아 있는 추천 개수를 확인한다.
    public long getActiveRecommendationCount(String userEmail) {
        Long size = stringRedisTemplate.opsForList().size(activeKey(userEmail));
        return size == null ? 0 : size;
    }

    // 남은 추천 개수가 3개 이하이면 refresh가 필요하다고 판단한다.
    public boolean needsRefresh(String userEmail) {
        return getActiveRecommendationCount(userEmail) <= 3;
    }
}
```

### 이 단계에서 확인할 것
- Redis에 List 키가 정상 생성되는지
- TTL이 들어가는지
- 문제 5개가 List 원소로 저장되는지
- `leftPop()` 호출 시 순서대로 빠지는지

---

## 4. UserService의 홈 조회 로직을 Redis 우선으로 바꾼다

이 단계에서는 아직 비동기나 fallback을 붙이지 않는다.
먼저 홈 조회가 Redis에서 읽히는지만 확인한다.

### 수정 파일
- `src/main/java/com/example/algoyweb/service/user/UserService.java`

### 현재 관련 메서드
- `getRandomProblemsByUsername(String userEmail)`
- `checkSolvedACUserNameByUsername(String userEmail)`

### 수정 방향
- `getRandomProblemsByUsername`가 먼저 Redis List에서 문제를 `pop`하도록 바꾼다.
- `pop`할 데이터가 없으면 그때 외부 API refresh를 트리거하도록 다음 단계에서 연결한다.
- 이 단계에서는 우선 DB fallback을 유지해도 된다.

### 권장 코드 예시
```java
private final RecommendationRedisService recommendationRedisService;
```

생성자 주입에 추가한다.

```java
public String getRandomProblemsByUsername(String userEmail) {
    Optional<String> nextProblem = recommendationRedisService.popNextRecommendation(userEmail);

    if (nextProblem.isPresent()) {
        return nextProblem.get();
    }

    Optional<SolvedACResponseEntity> optionalResponseEntity = solvedACResponseRepository.findByUserEmail(userEmail);
    if (optionalResponseEntity.isPresent()) {
        List<String> recommendedProblems = optionalResponseEntity.get().getResponse();
        recommendationRedisService.replaceActiveRecommendations(userEmail, recommendedProblems);
        return recommendationRedisService.popNextRecommendation(userEmail).orElse(null);
    }

    return null;
}
```

### 왜 이렇게 시작하는가
- Redis 큐에서 하나씩 소비하는 구조를 먼저 검증할 수 있다.
- DB fallback을 당장 제거하지 않아도 된다.
- 이후 외부 API refresh 연결 전까지도 최소 동작을 유지할 수 있다.

---

## 5. AllenService에서 추천 저장 시 Redis도 함께 쓴다

이 단계에서는 외부 추천 API 결과를 받은 직후 Redis active 큐도 함께 갱신한다.
핵심은 MySQL을 중심 저장소로 되돌리는 것이 아니라, Redis를 운영 중심으로 두고 MySQL은 마지막 추천 1세트 fallback 스냅샷으로 유지하는 것이다.

### 수정 파일
- `src/main/java/com/example/algoyweb/service/allen/AllenService.java`
- `src/main/java/com/example/algoyweb/service/allen/RecommendationRedisService.java`
- `src/main/java/com/example/algoyweb/service/user/UserService.java`

### 최신 설계 기준
- `seen`의 기준은 "실제로 화면에 노출한 문제"가 아니라 "이미 추천 세트에 포함시킨 문제"로 본다.
- 따라서 AI 외부 API 응답을 저장할 때 `problemNo` 기준으로 `seen`을 검사하고, 통과한 문제는 바로 `seen`에도 저장한다.
- 홈 화면에서 큐를 `pop`할 때는 더 이상 `markAsSeen(...)`를 호출하지 않는다.
- DB fallback 세트를 Redis에 임시로 넣을 때는 `seen` 검사를 하지 않는다.

### 권장 코드 예시
`RecommendationRedisService` 예시:
```java
public void markAsSeen(String userEmail, String problemNo) {
    stringRedisTemplate.opsForSet().add(seenKey(userEmail), problemNo);
    stringRedisTemplate.expire(seenKey(userEmail), Duration.ofDays(recommendationSeenTtlDays));
}

public boolean isSeen(String userEmail, String problemNo) {
    Boolean result = stringRedisTemplate.opsForSet().isMember(seenKey(userEmail), problemNo);
    return Boolean.TRUE.equals(result);
}
```

`sovledacCall(...)` 예시:
```java
public ResponseEntity<String> sovledacCall(String algoyUserName, String solvedACUserName) throws Exception {
    String requestUrl = askAllenUrl + "/response?algoyusername=" + algoyUserName
            + "&solvedacusername=" + solvedACUserName;

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");

    try {
        String allenResponse = httpEx.get(requestUrl, headers);
        String temp = extractJsonFromMarkdown(allenResponse);
        Gson gson = new Gson();
        SolvedACJsonResponse[] responses = gson.fromJson(temp, SolvedACJsonResponse[].class);

        User user = userRepository.findByUsername(algoyUserName)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        List<SolvedACJsonResponse> filteredResponses = new ArrayList<>();
        for (SolvedACJsonResponse response : responses) {
            if (response.getTitle() == null || response.getProblemNo() == null) {
                continue;
            }

            if (recommendationRedisService.isSeen(user.getEmail(), response.getProblemNo())) {
                continue;
            }

            recommendationRedisService.markAsSeen(user.getEmail(), response.getProblemNo());
            filteredResponses.add(response);
        }

        List<String> responseList = convertJsonToListString(filteredResponses);
        if (responseList.isEmpty()) {
            return ResponseEntity.ok("새 추천 문제가 없습니다.");
        }

        saveResponse(algoyUserName, responseList);
        return ResponseEntity.ok("성공");
    } catch (Exception e) {
        throw new Exception("solvedAC 호출 실패", e);
    }
}
```

`saveResponse(...)` 예시:
```java
@Transactional
public void saveResponse(String username, List<String> responseList) {
    User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

    SolvedACResponseEntity solvedACResponseEntity = solvedACResponseRepository.findByUserUsername(username)
            .orElse(SolvedACResponseEntity.builder()
                    .user(user)
                    .userEmail(user.getEmail())
                    .response(responseList)
                    .updatedAt(LocalDateTime.now())
                    .build());

    solvedACResponseEntity.updateResponse(responseList);
    solvedACResponseRepository.save(solvedACResponseEntity);
    recommendationRedisService.replaceActiveRecommendations(user.getEmail(), responseList);
}
```

`convertJsonToListString(...)` 오버로드 예시:
```java
public List<String> convertJsonToListString(List<SolvedACJsonResponse> responses) {
    List<String> titlesList = new ArrayList<>();

    for (SolvedACJsonResponse response : responses) {
        if (response.getTitle() != null) {
            String text = response.getSite() + " - " + response.getTitle()
                    + " (" + response.getProblemNo() + ")\n" + response.getDetails();
            titlesList.add(text);
        }
    }
    return titlesList;
}
```

### 포인트
- `extractProblemNo(...)` 같은 문자열 파싱은 더 이상 필요 없다.
- `SolvedACJsonResponse.getProblemNo()`를 원천 식별자로 바로 사용한다.
- AI 외부 API 결과 저장 시 `seen` 검사와 `seen` 저장을 같이 한다.
- 홈 조회에서는 `markAsSeen(...)`를 호출하지 않고 큐 소비와 refresh 트리거만 담당한다.
- DB fallback 세트는 임시 주입용이므로 `seen` 검사를 하지 않는다.

---
## 6. 추천 중복 방지를 위해 seen 이력을 붙인다

여기서부터 Redis를 쓰는 가치가 커진다.
단순 캐시만으로는 이전에 추천한 문제를 또 추천할 수 있기 때문이다.

### 수정 파일
- `src/main/java/com/example/algoyweb/service/allen/RecommendationRedisService.java`
- `src/main/java/com/example/algoyweb/service/allen/AllenService.java`
- `src/main/java/com/example/algoyweb/service/user/UserService.java`

### 추천 방식
- AI 외부 API 응답을 저장할 때 `SolvedACJsonResponse.getProblemNo()`로 `seen`을 검사한다.
- `seen`에 없는 문제만 이번 추천 세트에 포함한다.
- 이번 추천 세트에 포함한 문제는 바로 `seen` set에도 저장한다.
- 홈 화면에서는 Redis active 큐에서 `pop`만 하고, `seen`을 추가로 기록하지 않는다.

### 각 함수는 언제 호출하나
- `isSeen(userEmail, problemNo)`
  - AI 외부 API 응답에서 각 문제를 추천 세트에 포함시킬지 결정할 때 호출한다.
- `markAsSeen(userEmail, problemNo)`
  - AI 외부 API 응답에서 `seen` 통과한 문제를 이번 추천 세트에 포함시키는 순간 호출한다.
- `replaceActiveRecommendations(userEmail, recommendations)`
  - `seen` 검사를 통과한 새 추천 세트를 Redis active List에 저장할 때 호출한다.
- `popNextRecommendation(userEmail)`
  - 홈 화면에서 사용자에게 보여줄 문제 1개를 꺼낼 때 호출한다.
- `needsRefresh(userEmail)`
  - 큐에 남은 추천 개수가 3개 이하인지 확인할 때 호출한다.

### UserService에서 호출하는 흐름 예시
```java
public String getRandomProblemsByUsername(String userEmail) {
    Optional<String> nextProblem = recommendationRedisService.popNextRecommendation(userEmail);

    if (nextProblem.isPresent()) {
        String selectedProblem = nextProblem.get();
        recommendationRefreshService.refreshIfNeeded(userEmail);
        return selectedProblem;
    }

    boolean refreshed = recommendationRefreshService.refreshIfNeeded(userEmail);
    if (refreshed) {
        return recommendationRedisService.popNextRecommendation(userEmail)
                .orElse("추천 문제를 준비 중입니다.");
    }

    return "추천 문제를 준비 중입니다.";
}
```

### refresh 쪽에서 동작하는 흐름 예시
```java
public boolean refreshIfNeeded(String userEmail) {
    if (!recommendationRedisService.needsRefresh(userEmail)) {
        return false;
    }

    solvedACResponseRepository.findByUserEmail(userEmail)
            .map(SolvedACResponseEntity::getResponse)
            .filter(list -> list != null && !list.isEmpty())
            .ifPresent(list -> recommendationRedisService.replaceActiveRecommendations(userEmail, list));

    User user = userRepository.findOptionalByEmail(userEmail)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

    allenService.sovledacCall(user.getUsername(), user.getSolvedacUserName());
    return true;
}
```

### 현실적인 1차 방향
- 추천 화면에 보여줄 값은 지금처럼 문자열 그대로 유지한다.
- 중복 판단은 문자열 파싱이 아니라 `SolvedACJsonResponse.getProblemNo()` 기준으로 한다.
- `seen`의 의미는 "이미 추천 세트에 포함한 문제"다.
- TTL이 끝난 뒤에는 같은 문제가 다시 추천돼도 괜찮다.

---

## 7. Redis List에 남은 추천이 3개 이하일 때 외부 API refresh를 트리거한다

이 구조에서는 active 추천을 큐처럼 소비하므로, 큐가 완전히 바닥나기 전에 미리 보충하는 편이 낫다.
남은 추천 개수가 3개 이하가 되면 외부 API를 호출해 다음 추천을 준비한다.

### 기준
- active 추천이 남아 있으면 그대로 `pop`
- active 추천 개수가 3개 이하이면 refresh

### RecommendationRedisService에 추가 가능한 메서드 예시
```java
public boolean needsRefresh(String userEmail) {
    return getActiveRecommendationCount(userEmail) <= 3;
}
```


### RecommendationRefreshService 예시 클래스
- 파일 경로: `src/main/java/com/example/algoyweb/service/allen/RecommendationRefreshService.java`
- 역할: refresh 필요 여부를 판단하고, DB 마지막 추천 세트를 먼저 Redis 큐에 넣은 뒤 AI 외부 API를 반드시 호출한다.

```java
package com.example.algoyweb.service.allen;

import com.example.algoyweb.model.entity.allen.SolvedACResponseEntity;
import com.example.algoyweb.model.entity.user.User;
import com.example.algoyweb.repository.allen.SolvedACResponseRepository;
import com.example.algoyweb.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationRefreshService {

    private final RecommendationRedisService recommendationRedisService;
    private final SolvedACResponseRepository solvedACResponseRepository;
    private final UserRepository userRepository;
    private final AllenService allenService;

    public boolean refreshIfNeeded(String userEmail) {
        if (!recommendationRedisService.needsRefresh(userEmail)) {
            return false;
        }

        solvedACResponseRepository.findByUserEmail(userEmail)
                .map(SolvedACResponseEntity::getResponse)
                .filter(list -> list != null && !list.isEmpty())
                .ifPresent(list -> recommendationRedisService.replaceActiveRecommendations(userEmail, list));

        try {
            User user = userRepository.findOptionalByEmail(userEmail)
                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

            allenService.sovledacCall(user.getUsername(), user.getSolvedacUserName());
            return true;
        } catch (Exception e) {
            log.error("Failed to refresh recommendations for userEmail={}", userEmail, e);
            return false;
        }
    }
}
```

### 이 클래스에서 각 메서드가 호출되는 순서
- `needsRefresh(userEmail)`
  - 먼저 호출해서 지금 refresh가 필요한지 판단한다.
- `replaceActiveRecommendations(userEmail, recommendations)`
  - DB에 저장된 마지막 추천 1세트를 Redis 큐에 먼저 넣어서 빈 구간을 막는다.
- `allenService.sovledacCall(...)`
  - refresh가 필요하다고 판단되면 AI 외부 API를 반드시 호출한다.

### UserService에서 이 서비스를 호출하는 예시
```java
public String getRandomProblemsByUsername(String userEmail) {
    Optional<String> nextProblem = recommendationRedisService.popNextRecommendation(userEmail);

    if (nextProblem.isPresent()) {
        String selectedProblem = nextProblem.get();
        recommendationRefreshService.refreshIfNeeded(userEmail);
        return selectedProblem;
    }

    boolean refreshed = recommendationRefreshService.refreshIfNeeded(userEmail);
    if (refreshed) {
        return recommendationRedisService.popNextRecommendation(userEmail)
                .orElse("추천 문제를 준비 중입니다.");
    }

    return "추천 문제를 준비 중입니다.";
}
```
### 적용 위치 후보
- `UserService`
- 별도 `RecommendationRefreshService`

### 권장
- 화면에서 문제를 가져오는 시점에 먼저 `pop`
- `pop` 이후 남은 개수가 3개 이하이면 refresh service를 호출
- refresh는 백그라운드에서 수행하고, 현재 요청은 기존 큐 데이터를 그대로 사용한다

---

## 8. 그 다음에 비동기를 붙인다

Redis가 먼저 붙고 읽기/쓰기 동작이 검증된 뒤에 `@Async`를 붙인다.
처음부터 Redis와 비동기를 동시에 넣으면 어디서 깨졌는지 찾기 어렵다.

### 먼저 수정할 파일
- `src/main/java/com/example/algoyweb/AlgoyWebApplication.java`

### 예시 코드
```java
package com.example.algoyweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class AlgoyWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlgoyWebApplication.class, args);
    }
}
```

### 그 다음 새 파일 생성
- `src/main/java/com/example/algoyweb/config/async/AsyncConfig.java`

### 예시 코드
```java
package com.example.algoyweb.config.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "recommendationTaskExecutor")
    public Executor recommendationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("recommendation-");
        executor.initialize();
        return executor;
    }
}
```

---

## 9. 로그인 시 외부 API 직접 호출을 비동기로 바꾼다

이 단계에서 체감 개선이 가장 크다.
현재는 로그인 성공 시 외부 API를 직접 기다리고 있다.
이걸 비동기로 넘기면 로그인 응답이 빨라진다.

### 수정 파일
- `src/main/java/com/example/algoyweb/util/user/UserAuthenticationSuccessHandler.java`
- 새 파일: `src/main/java/com/example/algoyweb/service/allen/RecommendationAsyncService.java`

### RecommendationAsyncService 예시 코드
```java
package com.example.algoyweb.service.allen;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationAsyncService {

    private final AllenService allenService;

    @Async("recommendationTaskExecutor")
    public void refreshRecommendations(String algoyUsername, String solvedAcUsername) {
        try {
            allenService.sovledacCall(algoyUsername, solvedAcUsername);
        } catch (Exception e) {
            log.error("Failed to refresh recommendations for user={}", algoyUsername, e);
        }
    }
}
```

### UserAuthenticationSuccessHandler 수정 방향
기존:
```java
allenService.sovledacCall(user.getUsername(), user.getSolvedacUserName());
```

변경:
```java
recommendationAsyncService.refreshRecommendations(user.getUsername(), user.getSolvedacUserName());
```

### 이 단계에서 주의할 점
- 로그인 시점이 아니라 Redis List가 비었을 때 refresh하는 구조가 더 자연스럽다.
- 즉, 홈 조회에서 `pop`이 실패했을 때만 refresh를 태우는 쪽이 현재 요구사항에 맞다.
- 같은 시점에 여러 요청이 들어올 수 있으므로, 중복 refresh가 문제로 보이면 그때 lock을 추가하면 된다.

---

## 11. 마지막으로 Redis + MySQL fallback을 붙인다

이 단계는 Redis만 잘 동작한 뒤에 붙이는 것이 좋다.
너처럼 외부 추천 API가 1분 이상 걸리는 구조에서는 fallback이 UX를 많이 살린다.

### 의미
- 평소엔 Redis active List에서 문제를 `pop`한다.
- `needsRefresh()`가 `true`가 되면 DB의 마지막 추천 1세트를 먼저 Redis 큐에 다시 넣는다.
- 그 다음 AI 외부 API를 반드시 호출해서 새 추천 세트를 만든다.
- AI 응답이 오면 새 추천 세트로 MySQL과 Redis를 다시 갱신한다.

### 수정 파일
- `src/main/java/com/example/algoyweb/service/user/UserService.java`
- `src/main/java/com/example/algoyweb/service/allen/RecommendationRefreshService.java`

### 최종 조회 흐름 예시
```java
public String getRandomProblemsByUsername(String userEmail) {
    Optional<String> nextProblem = recommendationRedisService.popNextRecommendation(userEmail);
    if (nextProblem.isPresent()) {
        String selectedProblem = nextProblem.get();
        recommendationRefreshService.refreshIfNeeded(userEmail);
        return selectedProblem;
    }

    recommendationRefreshService.refreshIfNeeded(userEmail);

    return recommendationRedisService.popNextRecommendation(userEmail)
            .orElse("추천 문제를 준비 중입니다.");
}
```

### 이 구조의 의미
- Redis List는 현재 화면 노출용 추천 큐다.
- MySQL의 마지막 추천 1세트는 AI 응답이 오기 전 임시 fallback 용도다.
- refresh가 필요하면 AI 외부 API는 반드시 호출한다.
- seen Set은 홈 노출 이력이 아니라 이미 추천 세트에 포함한 문제를 기록하는 용도다.
- `deleteActiveRecommendations(...)`는 사용자 탈퇴 시 active 추천 키 전체를 지우는 용도로 유지한다.

---
## 12. 가장 현실적인 구현 순서 요약

처음부터 full 구조를 한 번에 넣지 말고 아래 순서대로 하는 것이 좋다.

### 1단계
- `application.yml` 설정값 정리
- `RedisConfig` 정리
- `RecommendationRedisService` 생성

### 2단계
- `AllenService`에서 Redis 저장
- `UserService`에서 Redis 조회
- Redis 데이터가 실제로 읽히는지 확인

### 3단계
- seen 이력 추가
- 중복 추천 방지 시작

### 4단계
- `@EnableAsync`
- `AsyncConfig`
- 로그인 시 비동기 refresh

### 5단계
- 필요할 때만 refresh 호출
- 중복 refresh가 실제 문제로 보일 때만 lock 추가 검토

### 6단계
- MySQL fallback 추가
- Redis 유실 시 마지막 추천 1세트 사용

---

## 13. 구현하면서 스스로 체크할 질문

- Redis 키 기준은 `username`인가 `email`인가?
  - 현재 홈 조회 로직 기준으로는 `email`이 더 일관적이다.
- active 추천 리스트는 몇 개를 유지할 것인가?
  - 지금처럼 5개 유지로 시작하는 것이 안전하다.
- seen은 문자열 전체를 저장할 것인가, problemNo를 저장할 것인가?
  - 지금 단계에서는 문자열에서 `problemNo`를 추출해서 저장하는 방식이 가장 현실적이다.
- refresh 트리거는 언제인가?
  - 로그인 시, TTL 만료 시, active 개수 부족 시 중 하나 또는 조합을 선택해야 한다.
- Redis 유실 시 사용자에게 어떤 문구를 보여줄 것인가?
  - fallback이 없다면 이 문구가 중요하다.

---

## 14. 추천 결론

현재 상황에서는 아래 구조를 추천한다.

- Redis:
  - active 추천 리스트
  - seen 추천 이력
  
- MySQL:
  - 마지막 추천 결과 1세트만 overwrite 저장
- 비동기:
  - 로그인 성공 시 필요할 때만 외부 추천 API refresh

하지만 구현은 반드시 아래 순서로 간다.

1. Redis 연결 및 저장/조회
2. Redis 기반 추천 읽기
3. 중복 추천 방지
4. 비동기 refresh
5. fallback

즉, 지금은 `Redis 적용부터 차근차근`이라는 목표에 맞춰 1단계부터 시작하는 것이 맞다.

























## 15. 현재 결정된 seen 설계 메모

이 섹션은 기존 가이드 중간에 있는 예시보다 **더 최신으로 합의한 설계**를 따로 정리한 메모다.
구현 시에는 이 섹션을 우선 기준으로 본다.

### 현재 합의한 핵심
- `seen`의 의미는 "실제로 홈 화면에 노출된 문제"가 아니다.
- `seen`의 의미는 "추천 후보로 한 번 처리한 문제"다.
- 따라서 `seen` 저장 시점은 홈 화면 `pop` 시점이 아니라, **AI 외부 API 호출 직후 active 큐에 넣기 전에 검수하는 시점**이다.
- 기준 키는 문제 문자열 전체가 아니라 `problemNo`다.

### 왜 이렇게 바꾸는가
- 홈 화면에서 문자열을 다시 파싱해 `problemNo`를 추출하는 방식은 불필요하게 늦다.
- `AllenService.convertJsonToListString(...)` 단계에서는 이미 `SolvedACJsonResponse[]`를 다루고 있으므로, 여기서 `response.getProblemNo()`를 직접 사용할 수 있다.
- 즉, 추천 후보를 문자열로 만든 뒤 다시 `extractProblemNo(...)`로 역파싱할 필요가 없다.

### 현재 설계에서 바꿔야 할 방향
1. `RecommendationRedisService.markAsSeen(...)`
- 지금처럼 `String problem` 전체를 받지 말고 `String problemNo`를 직접 받는 방향이 맞다.

2. `RecommendationRedisService.isSeen(...)`
- 지금처럼 `String problem` 전체를 받지 말고 `String problemNo`를 직접 받는 방향이 맞다.

3. `RecommendationRedisService.extractProblemNo(...)`
- 새 설계가 적용되면 문자열 역파싱 용도는 사실상 불필요해진다.
- 최종적으로 제거 후보로 봐도 된다.

4. `AllenService.convertJsonToListString(...)`
- 여기서 `SolvedACJsonResponse[] responses`를 순회할 때 `response.getProblemNo()`로 중복 검수를 수행하는 구조가 자연스럽다.
- 검수를 통과한 응답만 최종 `List<String>`에 넣는다.

5. `UserService.getRandomProblemsByUsername(...)`
- 홈에서 `pop`할 때는 더 이상 `markAsSeen(...)`를 호출하지 않는다.
- 홈에서는 큐 소비와 refresh 트리거만 담당한다.

### 추천 흐름 요약
1. AI 외부 API 호출
2. JSON 응답을 `SolvedACJsonResponse[]`로 역직렬화
3. 각 `response.getProblemNo()`로 `seen` 검수
4. 통과한 문제만 `seen`에 즉시 저장
5. 통과한 문제만 문자열로 조합해 `List<String>` 생성
6. 그 결과를 Redis active list와 MySQL fallback에 저장
7. 홈 화면에서는 active list에서 `pop`만 수행

### 이 설계의 장점
- `problemNo`를 원본 DTO에서 바로 쓰므로 더 안전하다.
- 문자열 포맷 변경에 덜 취약하다.
- 추천 후보 단계에서 중복을 걸러내므로 active 큐 오염을 줄일 수 있다.
- `seen`의 의미가 "이미 추천 세트에 포함한 문제"로 분명해진다.

### 주의할 점
- 이 설계에서는 `seen`이 더 이상 "실제 노출 이력"이 아니다.
- 따라서 아직 홈에 안 보였더라도, 추천 후보에 한번 들어갔다면 이후 재추천에서 제외될 수 있다.
- 이게 현재 요구사항에는 맞지만, 나중에 정책이 바뀌면 `seen` 의미도 다시 바뀔 수 있다.

### 실제 수정 포인트 메모
- `AllenService.sovledacCall(...)`
  - API 응답 직후 `SolvedACJsonResponse[]`를 순회하며 `problemNo` 기준 검수
- `AllenService.convertJsonToListString(...)`
  - 문자열 변환 전 DTO 단계에서 필터링 반영
- `RecommendationRedisService.markAsSeen(...)`
  - 인자를 `problem` -> `problemNo`로 변경하는 방향 검토
- `RecommendationRedisService.isSeen(...)`
  - 인자를 `problem` -> `problemNo`로 변경하는 방향 검토
- `RecommendationRedisService.extractProblemNo(...)`
  - 제거 후보
- `UserService.getRandomProblemsByUsername(...)`
  - 홈 노출 시점 `markAsSeen(...)` 호출 제거

### 권장 코드 예시

#### 1. RecommendationRedisService

```java
// seen에는 문제 문자열 전체가 아니라 problemNo만 저장한다.
// 이렇게 하면 문자열 역파싱 없이 DTO에서 바로 꺼낸 식별자를 사용할 수 있다.
public void markAsSeen(String userEmail, String problemNo) {
    stringRedisTemplate.opsForSet().add(seenKey(userEmail), problemNo);
    stringRedisTemplate.expire(seenKey(userEmail), Duration.ofDays(recommendationSeenTtlDays));
}

// 추천 후보를 active 큐에 넣기 전에 problemNo 기준으로 중복 검수한다.
public boolean isSeen(String userEmail, String problemNo) {
    Boolean result = stringRedisTemplate.opsForSet().isMember(seenKey(userEmail), problemNo);
    return Boolean.TRUE.equals(result);
}
```

설명:
- `problem` 문자열 전체를 받지 않고 `problemNo`를 직접 받는다.
- 이 구조로 바꾸면 `extractProblemNo(String problem)`는 제거 후보가 된다.

#### 2. AllenService.sovledacCall(...)

```java
public ResponseEntity<String> sovledacCall(String algoyUserName, String solvedACUserName) throws Exception {

    String requestUrl = askAllenUrl + "/response?algoyusername=" + algoyUserName
            + "&solvedacusername=" + solvedACUserName;

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");

    try {
        String allenResponse = httpEx.get(requestUrl, headers);
        String temp = extractJsonFromMarkdown(allenResponse);

        // JSON 응답을 문자열로 바로 바꾸지 않고 DTO 배열로 먼저 받는다.
        // 이 단계에서 problemNo를 꺼내 seen 검수를 해야 문자열 역파싱이 필요 없다.
        Gson gson = new Gson();
        SolvedACJsonResponse[] responses = gson.fromJson(temp, SolvedACJsonResponse[].class);

        User user = userRepository.findByUsername(algoyUserName)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        List<SolvedACJsonResponse> filteredResponses = new ArrayList<>();
        for (SolvedACJsonResponse response : responses) {
            // title 또는 problemNo가 없으면 추천 후보로 쓰지 않는다.
            if (response.getTitle() == null || response.getProblemNo() == null) {
                continue;
            }

            // 이미 추천 후보로 처리한 problemNo면 이번 세트에서 제외한다.
            if (recommendationRedisService.isSeen(user.getEmail(), response.getProblemNo())) {
                continue;
            }

            // 이번 세트에 포함시키는 순간 seen에도 바로 기록한다.
            recommendationRedisService.markAsSeen(user.getEmail(), response.getProblemNo());
            filteredResponses.add(response);
        }

        // 검수를 통과한 DTO만 화면 표시용 문자열로 변환한다.
        List<String> responseList = convertJsonToListString(filteredResponses);
        if (responseList.isEmpty()) {
            return ResponseEntity.ok("새 추천 문제가 없습니다.");
        }

        // 최종 세트만 MySQL fallback과 Redis active 큐에 저장한다.
        saveResponse(algoyUserName, responseList);
        return ResponseEntity.ok("성공");

    } catch (Exception e) {
        throw new Exception("solvedAC 호출 실패", e);
    }
}
```

설명:
- AI 응답을 문자열 리스트로 만들기 전에 `problemNo` 기준으로 검수한다.
- 검수를 통과한 문제만 `seen`에 넣고, 그 문제만 최종 추천 세트에 포함한다.

#### 3. AllenService.convertJsonToListString(...)

```java
// 여기서는 검수 로직을 넣지 않고, 검수를 통과한 DTO를 화면용 문자열로만 바꾼다.
public List<String> convertJsonToListString(List<SolvedACJsonResponse> responses) {
    List<String> titlesList = new ArrayList<>();

    for (SolvedACJsonResponse response : responses) {
        if (response.getTitle() != null) {
            String text = response.getSite() + " - "
                    + response.getTitle() + " (" + response.getProblemNo() + ")\n"
                    + response.getDetails();
            titlesList.add(text);
        }
    }
    return titlesList;
}
```

설명:
- 기존 `String jsonResponse` 버전을 유지해도 되지만, 새 설계에서는 DTO 배열 필터링 후 `List<SolvedACJsonResponse>`를 넘기는 오버로드가 더 자연스럽다.

#### 4. AllenService.saveResponse(...)

```java
@Transactional
public void saveResponse(String username, List<String> responseList) {
    User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

    SolvedACResponseEntity solvedACResponseEntity = solvedACResponseRepository.findByUserUsername(username)
            .orElse(SolvedACResponseEntity.builder()
                    .user(user)
                    .userEmail(user.getEmail())
                    .response(responseList)
                    .updatedAt(LocalDateTime.now())
                    .build());

    // 이 메서드는 검수된 최종 추천 세트만 저장한다.
    // seen 검수와 seen 저장은 이 메서드에 오기 전에 끝나 있어야 한다.
    solvedACResponseEntity.updateResponse(responseList);
    solvedACResponseRepository.save(solvedACResponseEntity);
    recommendationRedisService.replaceActiveRecommendations(user.getEmail(), responseList);
}
```

설명:
- `saveResponse(...)`는 검수된 최종 추천 세트를 MySQL과 Redis에 반영하는 역할만 맡긴다.
- `seen` 검수와 `seen` 저장은 이 메서드 전에 끝내는 게 맞다.

#### 5. UserService.getRandomProblemsByUsername(...)

```java
public String getRandomProblemsByUsername(String userEmail) {
    Optional<String> nextProblem = recommendationRedisService.popNextRecommendation(userEmail);

    if (nextProblem.isPresent()) {
        String selectedProblem = nextProblem.get();
        // 홈 화면에서는 큐 소비와 refresh만 담당한다.
        // seen 저장은 이미 API 응답 처리 단계에서 끝났다고 가정한다.
        recommendationRefreshService.refreshIfNeeded(userEmail);
        return selectedProblem;
    }

    boolean refreshed = recommendationRefreshService.refreshIfNeeded(userEmail);
    if (refreshed) {
        return recommendationRedisService.popNextRecommendation(userEmail)
                .orElse("추천 문제를 준비 중입니다.");
    }

    return "추천 문제를 준비 중입니다.";
}
```

설명:
- 홈 노출 시점에는 `markAsSeen(...)`를 호출하지 않는다.
- 홈에서는 큐 소비와 refresh 트리거만 담당한다.

