package com.example.algoyweb.model.dto.openai;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

@Getter
@NoArgsConstructor
public class OpenAIRecommendationItem {
    private static final String SITE = "BOJ";

    private String problemNo;
    private String title;
    private String details;

    public boolean isValid() {
        return StringUtils.hasText(problemNo)
                && StringUtils.hasText(title)
                && StringUtils.hasText(details);
    }

    public String toDisplayText() {
        return SITE + " - " + title + " (" + problemNo + ")\n" + details;
    }
}
