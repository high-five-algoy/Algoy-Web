package com.example.algoyweb.util.user;

import com.example.algoyweb.model.entity.user.User;
import com.example.algoyweb.service.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class UserAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;


    public UserAuthenticationSuccessHandler(UserService userService) {
        this.userService = userService;
    }

    //복구***
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String username = userDetails.getUsername();

        // UserService를 통해 사용자 정보 가져오기
        User user = userService.findByEmail(username);

        // SolvedAC 유저네임이 null인지 확인하고 로직 추가
        if (user.getSolvedacUserName() == null) {
            // SolvedAC 유저네임이 없으면 세션에 특정 메시지 저장
            request.getSession().setAttribute("showSolvedAcMessage", true);
        }


        // 사용자 삭제 여부 확인 후 리다이렉트 URL 결정
        String redirectUrl = user.getIsDeleted() ? "/algoy/user/restore" : "/algoy/home";
        response.sendRedirect(redirectUrl);
    }

    /**
     * 로그 찍기 위해 사용했던 임시 함수
     *
     * @author 조아라
     * @since 2026.04.22
     */
//    @Override
//    public void onAuthenticationSuccess(HttpServletRequest request,
//                                        HttpServletResponse response,
//                                        Authentication authentication) throws IOException {
//        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
//        String username = userDetails.getUsername();
//        User user = userService.findByEmail(username);
//
//        long loginStart = System.nanoTime();
//        boolean hasSolvedAc = user.getSolvedacUserName() != null;
//        boolean aiCalled = false;
//        boolean aiSuccess = false;
//        long aiMs = 0L;
//
//        if (!hasSolvedAc) {
//            request.getSession().setAttribute("showSolvedAcMessage", true);
//        } else {
//            aiCalled = true;
//            long aiStart = System.nanoTime();
//
//            try {
//                openAIRecommendationService.fetchAndStoreRecommendations(user.getUsername(), user.getSolvedacUserName());
//                aiSuccess = true;
//            } catch (Exception e) {
//                e.printStackTrace();
//                request.getSession().setAttribute("solvedacErrorMessage", "Problem recommendation failed");
//            } finally {
//                aiMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - aiStart);
//                log.info(
//                        "[PERF_LOGIN_AI] userEmail={} aiMs={} success={}",
//                        user.getEmail(),
//                        aiMs,
//                        aiSuccess
//                );
//            }
//        }
//
//        String redirectUrl = user.getIsDeleted() ? "/algoy/user/restore" : "/algoy/home";
//
//        log.info(
//                "[PERF_LOGIN] userEmail={} totalMs={} hasSolvedAc={} aiCalled={} aiSuccess={} redirectUrl={}",
//                user.getEmail(),
//                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loginStart),
//                hasSolvedAc,
//                aiCalled,
//                aiSuccess,
//                redirectUrl
//        );
//
//        response.sendRedirect(redirectUrl);
//    }

}
