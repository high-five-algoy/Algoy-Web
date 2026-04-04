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

            allenService.sovledacCall(user.getUsername(), user.getSolvedacUserName());
            return true;
        } catch (Exception e) {
            log.error("API 호출(refresh)에 실패 : userEmail={}", userEmail, e);
            return false;
        }
    }
}