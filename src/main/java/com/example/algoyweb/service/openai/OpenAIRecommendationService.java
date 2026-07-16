package com.example.algoyweb.service.openai;

import com.example.algoyweb.model.dto.openai.OpenAIRecommendationItem;
import com.example.algoyweb.model.entity.allen.SolvedACResponseEntity;
import com.example.algoyweb.model.entity.user.User;
import com.example.algoyweb.repository.allen.SolvedACResponseRepository;
import com.example.algoyweb.repository.user.UserRepository;
import com.example.algoyweb.service.redis.RecommendationRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OpenAIRecommendationService {

    private final WebClient.Builder webClientBuilder;
    private final UserRepository userRepository;
    private final SolvedACResponseRepository solvedACResponseRepository;
    private final RecommendationRedisService recommendationRedisService;

    @Value("${askopenai.url}")
    private String askOpenaiUrl;


    /**
     * OpenAi api를 호출하여 응답 받아오고 DB에 저장하는 로직
     * 응답 데이터를 redis(Queue), seen(Set), Mysql에 저장
     *
     * @author 조아라
     * @return ResponseEntity
     * @since 2026.04
     *
     * 1. OpenAI api 호출하여 응답 데이터 받아옴(JSON)
     * 2. seen에 저장된 문제인지 검수 후 seen에 데이터 저장
     * 3.
     */


    // solvedacCall의 책임을 그대로 가져온 새 메서드
    // 복구*********
//    public ResponseEntity<String> fetchAndStoreRecommendations(String algoyUserName, String solvedACUserName) throws Exception {
//        try {
//            log.info("OpenAI recommendation start. username={}, solvedacusername={}", algoyUserName, solvedACUserName);
//            //OpenAI api로 부터 받아온 응답 데이터
//            List<OpenAIRecommendationItem> responses =
//                    fetchRecommendations(algoyUserName, solvedACUserName);
//
//            log.info("OpenAI response received. username={}, solvedacusername={}, count={}",
//                    algoyUserName, solvedACUserName, responses.size());
//
//            User user = userRepository.findByUsername(algoyUserName)
//                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
//
//            List<OpenAIRecommendationItem> filteredResponses = new ArrayList<>();
//
//            //Json 형태의 데이터를 redis에 저장하는 로직
//            for (OpenAIRecommendationItem response : responses) {
//                if (response == null || !response.isValid()) {
//                    log.warn("Invalid recommendation skipped. username={}, item={}", algoyUserName, response);
//                    continue;
//                }
//
//                // seen에 저장된 문제인지 확인
//                if (recommendationRedisService.isSeen(user.getEmail(), response.getProblemNo())) {
//                    log.info("Seen recommendation skipped. email={}, problemNo={}",
//                            user.getEmail(), response.getProblemNo());
//                    continue;
//                }
//
//                // seen에 데이터 저장
//                recommendationRedisService.markAsSeen(user.getEmail(), response.getProblemNo());
//                filteredResponses.add(response);
//            }
//
//            List<String> responseList = convertToListString(filteredResponses);
//            log.info("Filtered recommendation count. username={}, count={}",
//                    algoyUserName, responseList.size());
//
//            if (responseList.isEmpty()) {
//                return ResponseEntity.ok("추천 문제가 없습니다.");
//            }
//
//            //mysql과 redis에 데이터 적재
//            saveMysqlAndRedisResponse(user, responseList);
//            log.info("Recommendations saved. username={}, savedCount={}",
//                    algoyUserName, responseList.size());
//            return ResponseEntity.ok("성공");
//
//        } catch (WebClientResponseException e) {
//            log.error("OpenAI API response error. username={}, solvedacusername={}, status={}, body={}",
//                    algoyUserName, solvedACUserName, e.getStatusCode(), e.getResponseBodyAsString(), e);
//            throw new Exception("OpenAI API 호출 실패: " + e.getStatusCode(), e);
//        } catch (Exception e) {
//            log.error("Recommendation processing failed. username={}, solvedacusername={}",
//                    algoyUserName, solvedACUserName, e);
//            throw new Exception("추천 갱신 실패", e);
//        }
//    }

    public ResponseEntity<String> fetchAndStoreRecommendations(String algoyUserName, String solvedACUserName) throws Exception {
        long totalStart = System.nanoTime();
        long apiStart = System.nanoTime();
        long apiMs = 0L;
        int responseCount = 0;
        int filteredCount = 0;
        int savedCount = 0;

        try {
            log.info("OpenAI recommendation start. username={}, solvedacusername={}", algoyUserName, solvedACUserName);

            List<OpenAIRecommendationItem> responses =
                    fetchRecommendations(algoyUserName, solvedACUserName);
            apiMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - apiStart);
            responseCount = responses.size();

            log.info("OpenAI response received. username={}, solvedacusername={}, count={}",
                    algoyUserName, solvedACUserName, responses.size());

            User user = userRepository.findByUsername(algoyUserName)
                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

            List<OpenAIRecommendationItem> filteredResponses = new ArrayList<>();

            for (OpenAIRecommendationItem response : responses) {
                if (response == null || !response.isValid()) {
                    log.warn("Invalid recommendation skipped. username={}, item={}", algoyUserName, response);
                    continue;
                }

                if (recommendationRedisService.isSeen(user.getEmail(), response.getProblemNo())) {
                    log.info("Seen recommendation skipped. email={}, problemNo={}",
                            user.getEmail(), response.getProblemNo());
                    continue;
                }

                recommendationRedisService.markAsSeen(user.getEmail(), response.getProblemNo());
                filteredResponses.add(response);
            }

            List<String> responseList = convertToListString(filteredResponses);
            filteredCount = filteredResponses.size();
            log.info("Filtered recommendation count. username={}, count={}",
                    algoyUserName, responseList.size());

            if (responseList.isEmpty()) {
                log.info(
                        "[PERF_OPENAI] username={} solvedacusername={} apiMs={} totalMs={} responseCount={} filteredCount={} savedCount={}",
                        algoyUserName,
                        solvedACUserName,
                        apiMs,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStart),
                        responseCount,
                        filteredCount,
                        savedCount
                );
                return ResponseEntity.ok("추천 문제가 없습니다.");
            }

            saveMysqlAndRedisResponse(user, responseList);
            savedCount = responseList.size();
            log.info("Recommendations saved. username={}, savedCount={}",
                    algoyUserName, responseList.size());

            log.info(
                    "[PERF_OPENAI] username={} solvedacusername={} apiMs={} totalMs={} responseCount={} filteredCount={} savedCount={}",
                    algoyUserName,
                    solvedACUserName,
                    apiMs,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStart),
                    responseCount,
                    filteredCount,
                    savedCount
            );
            return ResponseEntity.ok("성공");

        } catch (WebClientResponseException e) {
            log.error("OpenAI API response error. username={}, solvedacusername={}, status={}, body={}",
                    algoyUserName, solvedACUserName, e.getStatusCode(), e.getResponseBodyAsString(), e);

            log.info(
                    "[PERF_OPENAI] username={} solvedacusername={} apiMs={} totalMs={} responseCount={} filteredCount={} savedCount={}",
                    algoyUserName,
                    solvedACUserName,
                    apiMs,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStart),
                    responseCount,
                    filteredCount,
                    savedCount
            );
            throw new Exception("OpenAI API 호출 실패: " + e.getStatusCode(), e);

        } catch (Exception e) {
            log.error("Recommendation processing failed. username={}, solvedacusername={}",
                    algoyUserName, solvedACUserName, e);

            log.info(
                    "[PERF_OPENAI] username={} solvedacusername={} apiMs={} totalMs={} responseCount={} filteredCount={} savedCount={}",
                    algoyUserName,
                    solvedACUserName,
                    apiMs,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStart),
                    responseCount,
                    filteredCount,
                    savedCount
            );
            throw new Exception("추천 갱신 실패", e);
        }
    }
    /**
     * 로그 찍기 위해 사용했던 임시 함수
     *
     * @author 조아라
     * @since 2026.04.22
     */
//    public ResponseEntity<String> fetchAndStoreRecommendations(String algoyUserName, String solvedACUserName) throws Exception {
//        long totalStart = System.nanoTime();
//        long apiStart = System.nanoTime();
//        long apiMs = 0L;
//
//        try {
//            List<OpenAIRecommendationItem> responses = fetchRecommendations(algoyUserName, solvedACUserName);
//            apiMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - apiStart);
//
//            User user = userRepository.findByUsername(algoyUserName)
//                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
//
//            List<OpenAIRecommendationItem> filteredResponses = new ArrayList<>();
//
//            for (OpenAIRecommendationItem response : responses) {
//                if (response == null || !response.isValid()) {
//                    continue;
//                }
//
//                if (recommendationRedisService.isSeen(user.getEmail(), response.getProblemNo())) {
//                    continue;
//                }
//
//                recommendationRedisService.markAsSeen(user.getEmail(), response.getProblemNo());
//                filteredResponses.add(response);
//            }
//
//            List<String> responseList = convertToListString(filteredResponses);
//
//            if (responseList.isEmpty()) {
//                log.info(
//                        "[PERF_OPENAI] username={} solvedacusername={} apiMs={} totalMs={} responseCount={} filteredCount={} savedCount=0",
//                        algoyUserName,
//                        solvedACUserName,
//                        apiMs,
//                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStart),
//                        responses.size(),
//                        filteredResponses.size()
//                );
//                return ResponseEntity.ok("추천 문제를 준비 중입니다.");
//            }
//
//            saveMysqlAndRedisResponse(user, responseList);
//
//            log.info(
//                    "[PERF_OPENAI] username={} solvedacusername={} apiMs={} totalMs={} responseCount={} filteredCount={} savedCount={}",
//                    algoyUserName,
//                    solvedACUserName,
//                    apiMs,
//                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStart),
//                    responses.size(),
//                    filteredResponses.size(),
//                    responseList.size()
//            );
//            return ResponseEntity.ok("성공");
//
//        } catch (WebClientResponseException e) {
//            log.error("OpenAI API response error. username={}, solvedacusername={}, status={}, body={}",
//                    algoyUserName, solvedACUserName, e.getStatusCode(), e.getResponseBodyAsString(), e);
//
//            log.info(
//                    "[PERF_OPENAI] username={} solvedacusername={} apiMs={} totalMs={} responseCount=0 filteredCount=0 savedCount=0",
//                    algoyUserName,
//                    solvedACUserName,
//                    apiMs,
//                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStart)
//            );
//            throw new Exception("OpenAI API 호출 실패: " + e.getStatusCode(), e);
//
//        } catch (Exception e) {
//            log.error("Recommendation processing failed. username={}, solvedacusername={}",
//                    algoyUserName, solvedACUserName, e);
//
//            log.info(
//                    "[PERF_OPENAI] username={} solvedacusername={} apiMs={} totalMs={} responseCount=0 filteredCount=0 savedCount=0",
//                    algoyUserName,
//                    solvedACUserName,
//                    apiMs,
//                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - totalStart)
//            );
//            throw new Exception("추천 생성 실패", e);
//        }
//    }

    // 기존 httpEx.get + gson.fromJson 을 WebClient JSON 매핑으로 바꾼 부분
    private List<OpenAIRecommendationItem> fetchRecommendations(
            String algoyUserName,
            String solvedACUserName
    ) {
        return webClientBuilder
                .baseUrl(askOpenaiUrl)
                .build()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .queryParam("algoyusername", algoyUserName)
                        .queryParam("solvedacusername", solvedACUserName)
                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<OpenAIRecommendationItem>>() {})
                .blockOptional()
                .orElse(List.of());
    }

    // 기존 convertJsonToListString 역할
    private List<String> convertToListString(List<OpenAIRecommendationItem> responses) {
        List<String> titlesList = new ArrayList<>();

        for (OpenAIRecommendationItem response : responses) {
            titlesList.add(response.toDisplayText());
        }

        return titlesList;
    }

    // mysql에 저장하는 로직
    private void saveMysqlAndRedisResponse(User user, List<String> responseList) {
        SolvedACResponseEntity entity = solvedACResponseRepository.findByUserUsername(user.getUsername())
                .orElse(
                        SolvedACResponseEntity.builder()
                                .user(user)
                                .userEmail(user.getEmail())
                                .response(responseList)
                                .updatedAt(LocalDateTime.now())
                                .build()
                );

        entity.updateResponse(responseList);
        solvedACResponseRepository.save(entity);
        recommendationRedisService.replaceActiveRecommendations(user.getEmail(), responseList);
    }
}
