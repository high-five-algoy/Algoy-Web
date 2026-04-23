package com.example.algoyweb.service.openai;

import com.example.algoyweb.model.entity.allen.SolvedACResponseEntity;
import com.example.algoyweb.model.entity.user.User;
import com.example.algoyweb.repository.allen.SolvedACResponseRepository;
import com.example.algoyweb.repository.user.UserRepository;
import com.example.algoyweb.service.redis.RecommendationRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;


@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationRefreshService {


    private final RecommendationAsyncService recommendationAsyncService;
    private final RecommendationRedisService recommendationRedisService;
    private final SolvedACResponseRepository solvedACResponseRepository;
    private final UserRepository userRepository;
    private final OpenAIRecommendationService openAIRecommendationService;
    private ReactiveRedisOperations<Object, Object> stringRedisTemplate;


    /**
     * Allen api 호출 지연으로 인한 데이터 공백을 채우기 위한 로직
     * Redis 내 데이터가 기준치 미달일 때 Allen api를 호출 하여 데이터를 다시 적재한다.
     * Redis 내 데이터가 공백일때, fallback 데이터인 mysql에서 데이터를 가져와서 적재한다.
     *
     * @author 조아라
     * @return boolean
     * @since 2026.04
     * 호출 : 홈화면에서 추천 문제를 꺼낼 때 호출한다
     * 1.Redis에 적재된 데이터가 기준치 미달인지 확인
     * 2.미달이라면 Mysql DB에서 fallback 해둔 데이터를 가져와서 큐에 적재한다
     * 3.AI 외부 API를 호출하여 새 추천 세트로 DB와 Redis를 다시 갱신한다
     *
     * 동작 순서:
     *  1. Redis active 추천 개수가 임계치 이하인지 확인
     *  2. 필요하면 MySQL fallback 데이터를 Redis에 다시 적재
     *  3. 사용자 정보를 조회한 뒤 OpenAI 추천 API를 호출
     *  4. 새 추천 결과를 Redis와 MySQL에 저장
     *
     * 반환값:
     *  - true: refresh 수행 성공
     *  - false: refresh가 필요 없거나, 수행 중 예외가 발생함
     */

    public boolean refreshIfNeeded(String userEmail) {
        //큐에 적재된 데이터가 기준치 미달인지 확인
        if (!recommendationRedisService.needsRefresh(userEmail)) {
            return false;
        }
        //기준치 미달이면 DB에서 fallback 해둔 데이터 가져와서 큐에 적재(api 호출 지연시 대비책)
        solvedACResponseRepository.findByUserEmail(userEmail)
                .map(SolvedACResponseEntity::getResponse)
                .filter(list -> list != null && !list.isEmpty())
                .ifPresent(list -> recommendationRedisService.replaceActiveRecommendations(userEmail, list));

        //AI 외부 API 호출하여 새 추천 세트로 DB와 Redis를 다시 갱신
        try {
            User user = userRepository.findOptionalByEmail(userEmail)
                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

            openAIRecommendationService.fetchAndStoreRecommendations(user.getUsername(), user.getSolvedacUserName());
            //allenService.solvedacCall(user.getUsername(), user.getSolvedacUserName());
            return true;
        } catch (Exception e) {
            log.error("API 호출(refresh)에 실패 : userEmail={}", userEmail, e);
            return false;
        }
    }

    /**
     * 추천 목록이 부족한 경우 refresh를 "비동기 작업으로 트리거"한다.
     *
     * 동작 순서:
     * 1. Redis active 추천 개수가 임계치 이하인지 확인한다.
     * 2. refresh가 필요하면 MySQL fallback 데이터를 Redis에 먼저 다시 적재한다.
     * 3. Redis refresh lock 획득에 성공한 경우에만 비동기 추천 갱신 작업을 시작한다.
     *
     * 주의:
     * - 이 메서드는 OpenAI 호출을 직접 기다리지 않는다.
     * - lock 획득에 실패해도, 이미 다른 요청이 refresh 중일 수 있으므로 false를 반환하지 않는다.
     *
     * 반환값:
     * - true: 추천 보충이 필요한 상태여서 trigger 로직을 수행함
     * - false: 현재 추천 개수가 충분해서 refresh가 필요 없음
     */
    //복구*****
//    public boolean triggerRefreshIfNeeded(String userEmail) {
//        // redis에 데이터가 임계치 이하인지 check
//        if (!recommendationRedisService.needsRefresh(userEmail)) {
//            return false;
//        }
//
//        //임계치 이하라면 MySQL fallback 데이터를 Redis에 먼저 다시 적재
//        solvedACResponseRepository.findByUserEmail(userEmail)
//                .map(SolvedACResponseEntity::getResponse)
//                .filter(list -> list != null && !list.isEmpty())
//                .ifPresent(list -> recommendationRedisService.replaceActiveRecommendations(userEmail, list));
//
//        //Redis refresh lock 획득에 성공한 경우에만 비동기 추천 갱신 작업을 시작한다.
//        if (recommendationRedisService.tryAcquireRefreshLock(userEmail)) {
//            recommendationAsyncService.refreshRecommendations(userEmail);
//        }
//
//        return true;
//    }

    public boolean triggerRefreshIfNeeded(String userEmail) {
        long start = System.nanoTime();
        boolean fallbackUsed = false;
        boolean lockAcquired = false;
        boolean asyncTriggered = false;

        if (!recommendationRedisService.needsRefresh(userEmail)) {
            log.info(
                    "[PERF_TRIGGER] userEmail={} totalMs={} needsRefresh=false fallbackUsed=false lockAcquired=false asyncTriggered=false fallbackOnly=false",
                    userEmail,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            );
            return false;
        }

        Optional<List<String>> fallback = solvedACResponseRepository.findByUserEmail(userEmail)
                .map(SolvedACResponseEntity::getResponse)
                .filter(list -> list != null && !list.isEmpty());

        if (fallback.isPresent()) {
            recommendationRedisService.replaceActiveRecommendations(userEmail, fallback.get());
            fallbackUsed = true;
        }

        lockAcquired = recommendationRedisService.tryAcquireRefreshLock(userEmail);
        if (lockAcquired) {
            recommendationAsyncService.refreshRecommendations(userEmail);
            asyncTriggered = true;
        }

        log.info(
                "[PERF_TRIGGER] userEmail={} totalMs={} needsRefresh=true fallbackUsed={} lockAcquired={} asyncTriggered={} fallbackOnly={}",
                userEmail,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                fallbackUsed,
                lockAcquired,
                asyncTriggered,
                fallbackUsed && !lockAcquired
        );
        return true;
    }

    /**
     * 로그 찍기 위해 사용했던 임시 함수
     *
     * @author 조아라
     * @since 2026.04.22
     */
//    public boolean refreshIfNeeded(String userEmail) {
//        long start = System.nanoTime();
//        boolean needsRefresh = recommendationRedisService.needsRefresh(userEmail);
//
//        if (!needsRefresh) {
//            log.info(
//                    "[PERF_REFRESH] userEmail={} totalMs={} needsRefresh=false fallbackUsed=false success=false",
//                    userEmail,
//                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
//            );
//            return false;
//        }
//
//        boolean fallbackUsed = false;
//        Optional<List<String>> fallback = solvedACResponseRepository.findByUserEmail(userEmail)
//                .map(SolvedACResponseEntity::getResponse)
//                .filter(list -> list != null && !list.isEmpty());
//
//        if (fallback.isPresent()) {
//            recommendationRedisService.replaceActiveRecommendations(userEmail, fallback.get());
//            fallbackUsed = true;
//        }
//
//        try {
//            User user = userRepository.findOptionalByEmail(userEmail)
//                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
//
//            openAIRecommendationService.fetchAndStoreRecommendations(user.getUsername(), user.getSolvedacUserName());
//
//            log.info(
//                    "[PERF_REFRESH] userEmail={} totalMs={} needsRefresh=true fallbackUsed={} success=true",
//                    userEmail,
//                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
//                    fallbackUsed
//            );
//            return true;
//        } catch (Exception e) {
//            log.error("API refresh 실패: userEmail={}", userEmail, e);
//
//            log.info(
//                    "[PERF_REFRESH] userEmail={} totalMs={} needsRefresh=true fallbackUsed={} success=false",
//                    userEmail,
//                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
//                    fallbackUsed
//            );
//            return false;
//        }
//    }

}