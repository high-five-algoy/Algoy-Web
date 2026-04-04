# Comment Recovery Notes

이 문서는 **현재 `UserService.java`에서 아직 인코딩이 깨져 있는 주석이 남아 있는 코드만** 정리한 버전이다.
이미 수정된 주석 코드는 넣지 않았다.

원칙:
- 애플리케이션 코드 파일은 수정하지 않았다.
- 이 문서는 `docs/comment-recovery.md`만 갱신한다.
- `Confirmed`는 `git show HEAD:src/main/java/com/example/algoyweb/service/user/UserService.java`에서 회수한 원문이다.
- `Inferred`는 현재 로직은 유지하고, 주석과 깨진 문자열만 의미 기준으로 복구한 버전이다.

## 1. UserService.java

파일:
- `src/main/java/com/example/algoyweb/service/user/UserService.java`

### 함수: `signUpUser(UserDto userDto)`
신뢰도: `Confirmed`

```java
/**
 * 회원가입 처리
 *
 * @author yuseok
 * @param userDto 회원가입 정보를 담고 있는 DTO
 *
 * @author 조아라
 * solvedAC username db 저장하는 로직 추가
 * @since 24. 09. 08
 */
@Transactional
public void signUpUser(UserDto userDto) {
    // SolvedAC username 유효성 확인 되면 db에 저장하기 위한 기능
    if (userDto.getSolvedacUserName() != null && !userDto.getSolvedacUserName().isEmpty()) {
        boolean isValid = isUsernameValid(userDto.getSolvedacUserName());
        if (!isValid) {
            throw new CustomException(UserErrorCode.INVALID_SOLVEDAC_USERNAME);
        }
    }

    // User 엔티티 생성
    User user = User.builder()
        .username(userDto.getUsername())
        .nickname(userDto.getNickname())
        .email(userDto.getEmail())
        .password(passwordEncoder.encode(userDto.getPassword())) // 비밀번호 암호화
        .solvedacUserName(userDto.getSolvedacUserName()) // solvedAC username 저장(유효성을 확인하는 로직 필요)
        .role(Role.NORMAL)
        .isDeleted(false)
        .createdAt(LocalDateTime.now())
        .banCount(0)
        .build();

    // 저장
    userRepository.save(user);
}
```

### 함수: `findByEmail(String email)` / `findByNickname(String nickname)`
신뢰도: `Confirmed`

```java
// 이메일로 사용자 찾기
@Transactional
public User findByEmail(String email) {
    return userRepository.findByEmail(email);
}

// 닉네임으로 사용자 찾기
@Transactional
public User findByNickname(String nickname) {
    return userRepository.findByNickname(nickname);
}
```

### 함수: `getUserByUsername(String username)`
신뢰도: `Confirmed`

```java
/**
 * @author JSW
 *
 * 주어진 사용자 이름(이메일)을 기준으로 사용자 정보를 가져옵니다.
 *
 * @param username 검색할 사용자의 사용자 이름(이메일)
 * @return UserDto 사용자 정보를 담은 DTO 객체
 */
@Transactional(readOnly = true)
public UserDto getUserByUsername(String username) {
    User user = userRepository.findByEmail(username);
    if (user == null) {
        throw new RuntimeException("User not found");
    }
    return ConvertUtils.convertUserToDto(user);
}
```

### 함수: `loadUserByUsername(String email)`
신뢰도: `Confirmed`

```java
/**
 * 로그인
 *
 * @param email 로그인시 email로 로그인
 * @return 저장된 사용자 정보를 담은 UserDto
 * @author jooyoung
 */
@Override
public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    User user = userRepository.findByEmail(email);
    if (user == null) {
        throw new UsernameNotFoundException("User not found with email: " + email);
    }

    return org.springframework.security.core.userdetails.User
        .withUsername(user.getEmail())
        .password(user.getPassword())
        .authorities(new SimpleGrantedAuthority(user.getRole().getKey())) // 권한 추가
        .build();
}
```

### 함수: `update(UserDto userDto, String email)`
신뢰도: `Inferred`

```java
@Transactional
public UserDto update(UserDto userDto, String email) {
    User findUser = userRepository.findByEmail(email);

    if (findUser == null) {
        throw new NoSuchElementException("No user found with the given email: " + email);
    }

    if (!Objects.equals(userDto.getEmail(), email)) {
        throw new CustomException(UserErrorCode.USER_NOT_EQUAL_EMAIL);
    }

    // 비밀번호 암호화 처리
    String encodedPassword = null;
    if (userDto.getPassword() != null && !userDto.getPassword().isEmpty()) {
        encodedPassword = passwordEncoder.encode(userDto.getPassword());
    }

    // SolvedAC username 유효성 확인 되면 db에 저장하기 위한 기능
    if (userDto.getSolvedacUserName() != null && !userDto.getSolvedacUserName().isEmpty()) {
        boolean isValid = isUsernameValid(userDto.getSolvedacUserName());
        if (!isValid) {
            throw new CustomException(UserErrorCode.INVALID_SOLVEDAC_USERNAME);
        }
    }

    // UserDto에서 업데이트 정보를 반영
    findUser.updateUser(userDto, encodedPassword);

    // Save the updated user entity
    userRepository.save(findUser);

    return ConvertUtils.convertUserToDto(findUser);
}
```

### 함수: `setDeleted(String email, HttpServletRequest request, HttpServletResponse response)`
신뢰도: `Confirmed`

```java
/**
 * 탈퇴 신청
 *
 * @param email 로그인시 email로 로그인
 * @return user를 repository에 저장
 * @author jooyoung
 */
@Transactional
public void setDeleted(String email, HttpServletRequest request, HttpServletResponse response) {
    User user = userRepository.findByEmail(email);
    if (user != null) {
        user.setDeleted();
        userRepository.save(user); // 변경 사항 저장

        // 로그아웃 처리
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            new SecurityContextLogoutHandler().logout(request, response, auth);
        }
        SecurityContextHolder.clearContext();

        // 명시적으로 쿠키 삭제
        Cookie rememberMeCookie = new Cookie("remember-me", null);
        rememberMeCookie.setPath("/");
        rememberMeCookie.setMaxAge(0);
        response.addCookie(rememberMeCookie);

        Cookie sessionCookie = new Cookie("JSESSIONID", null);
        sessionCookie.setPath("/");
        sessionCookie.setMaxAge(0);
        response.addCookie(sessionCookie);
    }
}
```

### 함수: `deleteScheduledUsers()`
신뢰도: `Confirmed`

```java
/**
 * 계정 삭제 스케줄러
 *
 * @return user를 삭제
 * @author jooyoung
 * 확인 필요합니다.
 */
@Transactional
//@Scheduled(cron = "0 0 0 * * ?") // 매일 자정에 실행
@Scheduled(fixedRate = 86400000) // 매일 실행 (24시간 = 86400000 ms)
public void deleteScheduledUsers() {
    List<User> usersToDelete = userRepository.findByIsDeletedTrueAndDeletedAtBefore(LocalDateTime.now());
    for (User user : usersToDelete) {
        userRepository.delete(user);
    }
}
```

### 함수: `restoreAccount(String email)`
신뢰도: `Confirmed`

```java
/**
 * 계정 복구
 *
 * @param email 로그인시 email로 로그인
 * @return user를 repository에 저장
 * @author jooyoung
 */
public void restoreAccount(String email) {
    User user = userRepository.findByEmail(email);
    if (user != null) {
        user.restore();
        userRepository.save(user); // 변경 사항 저장
    }
}
```

### 함수: `findPassword(String email, String username)`
신뢰도: `Confirmed`

```java
/**
 * 사용자의 이메일과 사용자 이름으로 비밀번호 재설정 토큰 생성
 *
 * @author yuseok
 * @param email 사용자의 이메일
 * @param username 사용자의 사용자 이름
 * @return 비밀번호 재설정에 사용할 토큰, 사용자 정보가 일치하지 않으면 null 반환
 */
public String findPassword(String email, String username) {
    // 이메일과 사용자 이름으로 해당 유저 찾기
    Optional<User> userOptional = userRepository.findByEmailAndUsername(email, username);

    // 유저를 찾으면
    if (userOptional.isPresent()) { // isPresent(): Optional 객체가 값을 포함하고 있는지 확인 (객체에 값이 있으면 true, 비어있으면 false 반환)
        // 비밀번호 재설정을 위한 임시 토큰 생성
        String token = UUID.randomUUID().toString();
        User user = userOptional.get();
        user.updatePassword(token); // updatePassword 메서드를 사용해 토큰을 임시 비밀번호로 저장
        userRepository.save(user);
        return token;
    }
    return null;
}
```

### 함수: `resetPassword(String token, String newPassword)`
신뢰도: `Confirmed`

```java
/**
 * 주어진 토큰을 사용하여 사용자의 비밀번호 재설정
 *
 * @author yuseok
 * @param token 비밀번호 재설정에 필요한 토큰
 * @param newPassword 사용자가 입력한 새 비밀번호
 * @return 비밀번호 재설정에 성공하면 true, 실패하면 false 반환
 */
public boolean resetPassword(String token, String newPassword) {
    // 임시 비밀번호(토큰)으로 유저 찾기
    Optional<User> userOptional = userRepository.findByPassword(token);

    if (userOptional.isPresent()) { // 유저가 존재하면
        User user = userOptional.get();
        user.updatePassword(passwordEncoder.encode(newPassword)); // 새로운 비밀번호로 업데이트
        userRepository.save(user);
        return true;
    }

    return false; // 유저가 없거나 토큰이 잘못된 경우 실패
}
```

### 함수: `getAllUsers()`
신뢰도: `Confirmed`

```java
/**
 * 모든 사용자 정보 조회
 *
 * @author yuseok
 * @return 모든 사용자의 정보가 담긴 UserDto 객체들의 리스트
 */
public List<UserDto> getAllUsers() {
    // 모든 User 엔티티를 데이터베이스에서 조회 후 리스트에 저장
    List<User> users = userRepository.findAll();

    // UserDto 객체들을 저장할 리스트 초기화
    List<UserDto> userDtos = new ArrayList<>();

    // 각 User 엔티티를 UserDto로 변환 후 리스트에 추가
    for (User user : users) {
        UserDto userDto = ConvertUtils.convertUserToDto(user);
        userDtos.add(userDto);
    }

    // UserDto 리스트 반환
    return userDtos;
}
```

### 함수: `isUsernameValid(String solvedacUsername)`
신뢰도: `Confirmed`

```java
/**
 * SolvedAC username 유효성 확인
 *
 * @author 조아라
 * @return username의 유효성을 체크하는 boolean
 * js에서 구현했을때 CORS에러로 인해 서버에서 로직을 처리함.
 */
public boolean isUsernameValid(String solvedacUsername) {
    String SOLVEDAC_USERNAME_VALID = "https://solved.ac/api/v3/user/show?handle=";

    try {
        RestTemplate restTemplate = new RestTemplate();
        String apiUrl = SOLVEDAC_USERNAME_VALID + solvedacUsername;

        ResponseEntity<String> response = restTemplate.getForEntity(apiUrl, String.class);
        // username이 존재하면 true 반환
        return response.getStatusCode().is2xxSuccessful();

    } catch (Exception e) {
        // 존재하지 않는다면 false 반환
        return false;
    }
}
```

### 함수: `getRandomProblemsByUsername(String userEmail)`
신뢰도: `Inferred`

```java
/**
 * home 화면에 출력할 문제 리스트에서 추출
 *
 * @author 조아라
 * @return 추천 문제 String 반환
 * 화면에 보여줄 다음 추천 문제를 가져온다
 */
public String getRandomProblemsByUsername(String userEmail) {
    Optional<String> nextProblem = recommendationRedisService.popNextRecommendation(userEmail);

    // redis에서 pop 한 문제가 있으면 1. seen 데이터 저장 2. refresh 실행 3. 화면 데이터로 반환
    if (nextProblem.isPresent()) {
        String selectedProblem = nextProblem.get();
        recommendationRedisService.markAsSeen(userEmail, recommendationRedisService.extractProblemNo(selectedProblem));
        recommendationRefreshService.refreshIfNeeded(userEmail);
        return selectedProblem;
    }

    // redis에 데이터가 없으면 1. refresh로 다시 채우기 2. redis에서 pop 3. seen 저장 4. 홈 화면에 표시
    boolean refreshed = recommendationRefreshService.refreshIfNeeded(userEmail);
    if (refreshed) {
        return recommendationRedisService.popNextRecommendation(userEmail)
            .map(problem -> {
                recommendationRedisService.markAsSeen(userEmail, recommendationRedisService.extractProblemNo(problem));
                return problem;
            })
            .orElse("추천 문제를 준비 중입니다.");
    }

    return "추천 문제를 준비 중입니다.";
}
```

### 함수: `getRandomProblem(List<String> problems)`
신뢰도: `Confirmed`

```java
/**
 * home 화면에 출력할 문제 리스트에서 추출
 *
 * @author 조아라
 * @return 추천 문제 String 반환
 * 랜덤으로 한 문제 고르는 메서드
 */
private String getRandomProblem(List<String> problems) {
    if (problems == null || problems.isEmpty()) {
        return "추천 문제를 가져올 수 없습니다."; // 문제가 없을 때의 처리
    }
    Random random = new Random();
    return problems.get(random.nextInt(problems.size()));
}
```

### 함수: `checkSolvedACUserNameByUsername(String userEmail)`
신뢰도: `Confirmed`

```java
/**
 * SolvedAC 문제 추천 리스트 있는지 확인
 *
 * @author 조아라
 * @return 추천받은 문제 리스트가 있는지 확인하는 boolean
 * 홈 화면 호출을 위한 체크
 */
public Boolean checkSolvedACUserNameByUsername(String userEmail) {
    // User 엔티티에서 solvedACUserName을 사용하여 추천 문제 리스트 가져오기
    Optional<SolvedACResponseEntity> optionalResponseEntity = solvedACResponseRepository.findByUserEmail(userEmail);

    // 사용자가 SolvedAC 문제 추천 리스트를 가지고 있지 않은 경우
    if (optionalResponseEntity.isEmpty()) {
        return false; // 또는 null을 반환하여 처리
    } else {
        return true;
    }
}
```

## 2. AllenService.java

파일:
- `src/main/java/com/example/algoyweb/service/allen/AllenService.java`

### 함수: `saveResponse(String username, List<String> responseList)`
신뢰도: `Inferred`

```java
/**
 * 로그인 이후 추천받은 문제 5개(List)를 redis에 저장하는 메서드
 *
 * @author 조아라
 * @return void
 * 추천받은 문제 5개(List)를 Mysql에 fallback 용도로 1세트만 저장한다
 * redis에도 add 하여 화면에 보여준다
 */
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

    // MySQL에는 사용자별 마지막 추천 1세트만 overwrite 저장한다.
    solvedACResponseEntity.updateResponse(responseList);
    solvedACResponseRepository.save(solvedACResponseEntity);

    // Redis active 키도 새 추천 1세트로 교체한다.
    recommendationRedisService.replaceActiveRecommendations(user.getEmail(), responseList);
}
```

### 함수: `sovledacCall(String algoyUserName, String solvedACUserName)`
신뢰도: `Confirmed`

```java
/**
 * SolvedAC username을 기반으로 앨런AI에게 문제 추천을 요청한다(5문제)
 *
 * @author 조아라
 * @return String
 * 앨런AI에게 질문하기 위해 username 정보를 RESTFUL API를 통해 전송한다
 * AI(8082) 애플리케이션에 API 통신한다
 */
public ResponseEntity<String> sovledacCall(String algoyUserName, String solvedACUserName) throws Exception {

    String requestUrl = askAllenUrl + "/response?algoyusername=" + algoyUserName + "&solvedacusername=" + solvedACUserName;

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");

    String allenResponse = ""; //앨런에게 바로 받은 답변
    String temp = ""; // 마크다운을 제거한 Json 형식 답변
    List<String> responseList= new ArrayList<>(); //Json에서 텍스트 형식으로 변환한 화면에 띄울 최종 답변
    try{
        //API 응답을 받는다(Json 형태)
        allenResponse = httpEx.get(requestUrl, headers);
        temp = extractJsonFromMarkdown(allenResponse);

        //Json을 파싱해서 문제 제목을 list에 넣고 반환하는 메서드 호출
        responseList = convertJsonToListString(temp);
        System.out.println(responseList);

        //변경사항을 저장한다.
        saveResponse(algoyUserName, responseList);

        return ResponseEntity.ok("성공");

    } catch (Exception e){
        throw new Exception("solvedAC 호출 실패", e); //예외 발생시 상위로 전달
    }
}
```

### 함수: `convertJsonToListString(String jsonResponse)`
신뢰도: `Confirmed`

```java
/**
 * (5문제)
 *
 * @author 조아라
 * @return String
 *
 */
//호출한 정보를 리스트에 넣는다 (5가지 문제 추천)
public List<String> convertJsonToListString(String jsonResponse){
    //Gson 객체 생성
    Gson gson = new Gson();

    // Json을 배열 형태로 파싱
    SolvedACJsonResponse[] responses = gson.fromJson(jsonResponse, SolvedACJsonResponse[].class);
    //결과를 담을 리스트 생성
    List<String> titlesList = new ArrayList<>();


    // 응답 배열에서 각 항목을 순회하며 제목을 추출하여 리스트에 추가
    for (SolvedACJsonResponse response : responses) {
        if (response.getTitle() != null) {
            String tmp = response.getSite() + " - " + response.getTitle() + " (" + response.getProblemNo() + ")\n" + response.getDetails();
            titlesList.add(tmp);
        }
    }
    return titlesList;
}
```

### 함수: `askAllen(String algoyUserName, String solvedACUserName)`
신뢰도: `Confirmed`

```java
//질문을 allen API에 묻고 답변을 받아온다
public String askAllen(String algoyUserName, String solvedACUserName) throws Exception {
    String askUrl = askAllenUrl + "?algoyusername=" + algoyUserName + "&solvedacusername=" + solvedACUserName;
    System.out.println(askUrl);

    Map<String, String> headers = new HashMap<>();
    headers.put("Content-Type", "application/json");

    String allenResponse = ""; //앨런에게 바로 받은 답변
    String temp = ""; // 마크다운을 제거한 Json 형식 답변
    String responseToUser = ""; //Json에서 텍스트 형식으로 변환한 화면에 띄울 최종 답변
    try{
        allenResponse = httpEx.get(askUrl, headers);
        //System.out.println(allenResponse);
        temp = extractJsonFromMarkdown(allenResponse);
        responseToUser = convertJsonToFormattedString(temp);

    }catch (Exception e){
        throw new Exception("allen에게 답변 받기 실패", e);
    }
    return responseToUser;
}
```

### 함수: `extractJsonFromMarkdown(String markdown)`
신뢰도: `Confirmed`

```java
//앨런에게 받은 답변에서 마크다운 제거하기
public String extractJsonFromMarkdown(String markdown){
    //마트다운 블록 시작과 끝을 제거하여 순수한 Json을 추출
    String json = markdown.replaceAll("```json", "")
            .replaceAll("```", "")
            .trim();
    return json;
}
```

### 함수: `convertJsonToFormattedString(String json)`
신뢰도: `Confirmed`

```java
//Json 형식을 String(화면에 보여줄 형식)으로 변환
public String convertJsonToFormattedString(String json){
    Gson gson = new Gson();
    JsonObject jsonObject = gson.fromJson(json, JsonObject.class);

    //필요한 형식으로 변환
    String formattedString = jsonObject.get("site").getAsString() + " - " +
                            jsonObject.get("title").getAsString() + " (" +
                            jsonObject.get("problemNo").getAsString() + ")\n" +
                            jsonObject.get("details").getAsString();
    return formattedString;
}
```

## 3. RecommendationRedisService.java

파일:
- `src/main/java/com/example/algoyweb/service/allen/RecommendationRedisService.java`

주의:
- 이 파일은 `HEAD` 원문이 없어서 아래는 모두 `Inferred`다.

### 함수: `activeKey(String userEmail)`
신뢰도: `Inferred`

```java
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
```

### 함수: `seenKey(String userEmail)`
신뢰도: `Inferred`

```java
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
```

### 함수: `replaceActiveRecommendations(String userEmail, List<String> recommendations)`
신뢰도: `Inferred`

```java
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
```

### 함수: `popNextRecommendation(String userEmail)`
신뢰도: `Inferred`

```java
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
```

### 함수: `deleteActiveRecommendations(String userEmail)`
신뢰도: `Inferred`

```java
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
```

### 함수: `getActiveRecommendationCount(String userEmail)`
신뢰도: `Inferred`

```java
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
```

### 함수: `needsRefresh(String userEmail)`
신뢰도: `Inferred`

```java
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
```

### 함수: `markAsSeen(String userEmail, String problem)`
신뢰도: `Inferred`

```java
/**
 * seen(set)에 문제 번호를 저장한다.
 *
 * @author 조아라
 * @return void
 * TTL을 설정하여 일정 기간만 데이터를 보관한다.
 * 호출 시점: 홈 화면에 보여줄 문제를 최종적으로 pop해서 사용자에게 반환하는 시점
 * 이유: "실제로 노출된 문제"만 seen에 넣는다.
 */
public void markAsSeen(String userEmail, String problem) {
    String problemNo = extractProblemNo(problem);
    stringRedisTemplate.opsForSet().add(seenKey(userEmail), problemNo);
    stringRedisTemplate.expire(seenKey(userEmail), Duration.ofDays(recommendationSeenTtlDays));
}
```

### 함수: `isSeen(String userEmail, String problem)`
신뢰도: `Inferred`

```java
/**
 * 문제가 기존에 노출된 중복 문제인지 체크한다.
 *
 * @author 조아라
 * @return boolean
 * 호출 시점: 새 추천 리스트를 외부 API 또는 DB fallback으로 받아 Redis active list에 저장하기 전에 각 문제를 필터링할 때
 * 이유: 이미 본 문제를 다음 추천 세트에 다시 넣지 않기 위해
 */
public boolean isSeen(String userEmail, String problem) {
    String problemNo = extractProblemNo(problem);
    Boolean result = stringRedisTemplate.opsForSet().isMember(seenKey(userEmail), problemNo);
    return Boolean.TRUE.equals(result);
}
```

### 함수: `extractProblemNo(String problem)`
신뢰도: `Inferred`

```java
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
```
