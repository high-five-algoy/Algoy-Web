package com.example.algoyweb.service.openai;


import com.example.algoyweb.model.entity.user.User;
import com.example.algoyweb.repository.user.UserRepository;
import com.example.algoyweb.service.redis.RecommendationRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecommendationAsyncService {

    private final UserRepository userRepository;
    private final OpenAIRecommendationService openAIRecommendationService;
    private final RecommendationRedisService recommendationRedisService;


    /**
     * 추천 문제 refresh를 백그라운드에서 수행한다.
     *
     * 동작 순서:
     * 1. userEmail로 사용자 정보를 조회한다.
     * 2. solved.ac username이 없으면 추천 생성 없이 종료한다.
     * 3. OpenAI 추천 API를 호출해 새 추천 목록을 생성한다.
     * 4. 생성된 추천 결과를 Redis와 MySQL에 저장한다.
     * 5. 성공 여부와 관계없이 finally에서 refresh lock을 해제한다.
     *
     * 특징:
     * - @Async("recommendationTaskExecutor")로 요청 스레드와 분리되어 실행된다.
     * - 예외가 발생해도 로그만 남기고, 사용자 요청 흐름을 직접 막지 않는다.
     * - lock을 반드시 해제해서 동일 사용자 refresh가 장시간 막히지 않도록 한다.
     *
     * @param userEmail 추천 refresh 대상 사용자 이메일
     * @author 조아라
     * @since 2026.04.23
     */
    //복구
//    @Async("recommendationTaskExecutor")
//    public void refreshRecommendations(String userEmail) {
//        try {
//            User user = userRepository.findOptionalByEmail(userEmail)
//                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
//
//            if (user.getSolvedacUserName() == null) {
//                return;
//            }
//
//            openAIRecommendationService.fetchAndStoreRecommendations(
//                    user.getUsername(),
//                    user.getSolvedacUserName()
//            );
//        } catch (Exception e) {
//            log.error("Async recommendation refresh failed. userEmail={}", userEmail, e);
//        } finally {
//            recommendationRedisService.releaseRefreshLock(userEmail);
//        }
//    }


    @Async("recommendationTaskExecutor")
    public void refreshRecommendations(String userEmail) {
        long start = System.nanoTime();
        boolean success = false;
        boolean skippedNoSolvedac = false;

        try {
            User user = userRepository.findOptionalByEmail(userEmail)
                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

            if (user.getSolvedacUserName() == null) {
                skippedNoSolvedac = true;
                return;
            }

            openAIRecommendationService.fetchAndStoreRecommendations(
                    user.getUsername(),
                    user.getSolvedacUserName()
            );
            success = true;
        } catch (Exception e) {
            log.error("Async recommendation refresh failed. userEmail={}", userEmail, e);
        } finally {
            log.info(
                    "[PERF_ASYNC_REFRESH] userEmail={} totalMs={} success={} skippedNoSolvedac={}",
                    userEmail,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                    success,
                    skippedNoSolvedac
            );
            recommendationRedisService.releaseRefreshLock(userEmail);
        }
    }
}
