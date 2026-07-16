package com.example.algoyweb.controller;

import com.example.algoyweb.service.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 홈 화면 컨트롤러
 * 로그인 성공 이후 화면을 위해 작성
 * @author jooyoung
 */

@Controller
@RequiredArgsConstructor
@Slf4j
public class HomeController {

    private final UserService userService;

    // ai-backend.url 설정값을 저장하는 변수입니다.
    @Value("${ai-backend.url}")
    private String backendUrl;


    /**
     * 홈 화면에서 추천 문제를 표시
     *
     * @author 조아라
     * @param userDetails 인증 정보를 포함한 Authentication 객체
     * @param model 뷰에 데이터를 전달하기 위한 Model 객체
     * @return 홈 화면 뷰의 이름 (html)
     */
    //원본
    @GetMapping("/algoy/home")
    public String home(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        long start = System.nanoTime();

        // 사용자가 인증되지 않았다면 로그인 페이지로 리다이렉트
        if (userDetails == null) {
            model.addAttribute("problem", null);
            model.addAttribute("backendUrl", backendUrl);

            log.info(
                    "[PERF_HOME] userEmail={} totalMs={} scenario=anonymous",
                    null,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            );

            return "home";
        }

        // 현재 로그인한 사용자 이름 가져오기
        String userEamil = userDetails.getUsername();


        // 로그인한 사용자의 solvedACUserName 가져오기
        Boolean CheckedSolvedACUserName = userService.checkSolvedACUserNameByUsername(userEamil);

        // solvedACUserName이 null인 경우 홈 화면 유지
        if (!CheckedSolvedACUserName) {
            model.addAttribute("problem", null);
            model.addAttribute("backendUrl", backendUrl);

            log.info(
                    "[PERF_HOME] userEmail={} totalMs={} scenario=no_solvedac",
                    userEamil,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            );

            return "home"; // SolvedAC username이 없는 경우, 홈 화면에 머무름
        }
        // 로그인한 사용자 solvedAC username 기반 추천 문제 가져오기
        String problemToShow = userService.getRandomProblemsByUsername(userEamil);


        // 추천 문제가 존재할 경우에만 전달
        model.addAttribute("problem", problemToShow);
        model.addAttribute("backendUrl", backendUrl);

        log.info(
                "[PERF_HOME] userEmail={} totalMs={} scenario=recommendation",
                userEamil,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        );

        return "home"; // view name
    }

    /**
     * 로그 찍기 위해 사용했던 임시 함수
     *
     * @author 조아라
     * @since 2026.04.22
     */
//    @GetMapping("/algoy/home")
//    public String home(@AuthenticationPrincipal UserDetails userDetails, Model model) {
//        long start = System.nanoTime();
//
//        if (userDetails == null) {
//            model.addAttribute("problem", null);
//            model.addAttribute("backendUrl", backendUrl);
//
//            // 로그인하지 않은 사용자의 홈 응답시간
//            log.info(
//                    "[PERF_HOME] userEmail={} totalMs={}",
//                    null,
//                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
//            );
//            return "home";
//        }
//
//        String userEamil = userDetails.getUsername();
//        Boolean CheckedSolvedACUserName = userService.checkSolvedACUserNameByUsername(userEamil);
//
//        if (!CheckedSolvedACUserName) {
//            model.addAttribute("problem", null);
//            model.addAttribute("backendUrl", backendUrl);
//
//            // solved.ac 계정이 없는 사용자의 홈 응답시간
//            log.info(
//                    "[PERF_HOME] userEmail={} totalMs={}",
//                    userEamil,
//                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
//            );
//            return "home";
//        }
//
//        String problemToShow = userService.getRandomProblemsByUsername(userEamil);
//        model.addAttribute("problem", problemToShow);
//        model.addAttribute("backendUrl", backendUrl);
//
//// 추천 문제 조회까지 포함한 최종 홈 응답시간
//        log.info(
//                "[PERF_HOME] userEmail={} totalMs={}",
//                userEamil,
//                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
//        );
//        return "home";
//
//    }


}