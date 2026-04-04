package com.example.algoyweb.service.allen;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RecommendationRedisService {
    private final StringRedisTemplate stringRedisTemplate;
    private final Gson gson = new Gson();

    @Value("${recommendation.cache.ttl-hours}")
    private long recommendationCacheTtlHours;

    @Value("${recommendation.seen.ttl-days}")
    private long recommendationSeenTtlDays;

    /**
     * 사용자별 active 추천 리스트의 Redis 키를 만든다.
     *
     * @author 조아라
     * @return String
     * 사용자별 키 값을 리턴하는 메서드
     */
    private String activeKey(String userEmail) {
        return "recommendation:active:" + userEmail;
    }

    /**
     * 같은 문제 재노출 방지를 위해 Set에 저장할 키 생성
     *
     * @author 조아라
     * @return String
     * userEmail을 식별값으로 키 생성
     */
    private String seenKey(String userEmail) {
        return "recommendation:seen:" + userEmail;
    }


    /**
     * 추천 리스트를 Redis List로 다시 채우고 TTL을 설정한다.
     *
     * @author 조아라
     * @return void
     * 최초 사용자 또는 기존 사용자 모두 추천 리스트를 active 키에 push하고 TTL을 설정한다.
     */
//    public void pushActiveRecommendations(String userEmail, List<String> recommendations) {
//        if (recommendations == null || recommendations.isEmpty()) {
//            return;
//        }
//
//        String key = activeKey(userEmail);
//        stringRedisTemplate.opsForList().rightPushAll(key, recommendations);
//        stringRedisTemplate.expire(key, Duration.ofHours(recommendationCacheTtlHours));
//    }

    /**
     * active 추천 키를 현재 추천 1세트로 교체하고 TTL을 설정한다.
     *
     * @author 조아라
     * @return void
     * 기존 키에 있던 데이터를 지우고 추천받은 문제들을 키에 삽입한다.
     */
    public void replaceActiveRecommendations(String userEmail, List<String> recommendations) {
        if (recommendations == null || recommendations.isEmpty()) {
            return;
        }

        String key = activeKey(userEmail);
        stringRedisTemplate.delete(key);
        stringRedisTemplate.opsForList().rightPushAll(key, recommendations);
        stringRedisTemplate.expire(key, Duration.ofHours(recommendationCacheTtlHours));
    }

    /**
     * 현재 추천 키에서 다음 문제 1개를 꺼낸다.
     *
     * @author 조아라
     * @return Optional
     * 화면에 반환할 다음 문제를 추출한다.
     */
    public Optional<String> popNextRecommendation(String userEmail) {
        String problem = stringRedisTemplate.opsForList().leftPop(activeKey(userEmail));
        return Optional.ofNullable(problem);
    }

    /**
     * 사용자의 active 추천 키를 명시적으로 삭제한다.
     *
     * @author 조아라
     * @return void
     * 사용자 탈퇴 또는 추천 초기화 시 active 추천 전체를 제거한다.
     */
    public void deleteActiveRecommendations(String userEmail) {
        stringRedisTemplate.delete(activeKey(userEmail));
    }


    /**
     * 현재 Redis List에 남아 있는 추천 개수를 확인한다.
     *
     * @author 조아라
     * @return long
     * pop 이후 List에 남아 있는 추천 문제 개수를 확인한다.
     * 3개 이하이면 새로운 문제들을 push한다.
     */
    public long getActiveRecommendationCount(String userEmail) {
        Long size = stringRedisTemplate.opsForList().size(activeKey(userEmail));
        return size == null ? 0 : size;
    }

    /**
     * 현재 추천 개수가 3개 이하이면 refresh가 필요하다고 판단한다.
     *
     * @author 조아라
     * @return boolean
     * 키에 남은 추천 문제 개수가 3개 이하이면 refresh한다.
     */
    public boolean needsRefresh(String userEmail) {
        return getActiveRecommendationCount(userEmail) <= 3;
    }



    /**
     * seen(set)에 문제 번호를 저장한다.
     *
     * @author 조아라
     * @return void
     * TTL을 설정하여 일정 기간만 데이터를 보관한다.
     * 호출 시점: 홈 화면에 보여줄 문제를 최종적으로 pop해서 사용자에게 반환하는 시점
     * 이유: "실제로 노출된 문제"만 seen에 넣는다.
     */
    public void markAsSeen(String userEmail, String problemNo) {

        stringRedisTemplate.opsForSet().add(seenKey(userEmail), problemNo);
        stringRedisTemplate.expire(seenKey(userEmail), Duration.ofDays(recommendationSeenTtlDays));
    }

    /**
     * 문제가 기존에 노출된 중복 문제인지 체크한다.
     *
     * @author 조아라
     * @return boolean
     * 호출 시점: 새 추천 리스트를 외부 API 또는 DB fallback으로 받아 Redis active list에 저장하기 전에 각 문제를 필터링할 때
     * 이유: 이미 본 문제를 다음 추천 세트에 다시 넣지 않기 위해
     */
    public boolean isSeen(String userEmail, String problemNo) {
        Boolean result = stringRedisTemplate.opsForSet().isMember(seenKey(userEmail), problemNo);
        return Boolean.TRUE.equals(result);
    }



    /**
     * seen 저장을 위해 문제 번호 추출하기
     *
     * @author 조아라
     * @return String
     * solvedACJsonResponse에서 problemNo 추출
     */
    public String extractProblemNo(String problem) {
        Pattern pattern = Pattern.compile("\\((\\d+)\\)");
        Matcher matcher = pattern.matcher(problem);

        if (matcher.find()) {
            return matcher.group(1);
        }

        throw new IllegalArgumentException("problemNo를 찾을 수 없습니다: " + problem);
    }
}
