package com.kb.sessionbot.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import static com.kb.sessionbot.commands.CommandConstants.*;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DynamicParameters {
    private final Map<String, String> params;
    
    public static DynamicParameters create(Map<String, String> params) {
        return new DynamicParameters(Collections.unmodifiableMap(Objects.requireNonNull(params, "Params is null.")));
    }

    public static DynamicParameters empty() {
        return new DynamicParameters(Collections.emptyMap());
    }

    public String getParam(String param) {
        return params.get(param);
    }

    public boolean hasParam(String param) {
        return params.containsKey(param);
    }

    public boolean isEmpty() {
        return params.isEmpty();
    }

    public boolean needRefreshContext() {
        return params.containsKey(REFRESH_CONTEXT_DYNAMIC_PARAM);
    }

    public boolean canScipAnswer(int index) {
        if (!params.containsKey(SCIP_ANSWER_DYNAMIC_PARAM)) {
            return false;
        }
        var allowedIndex = Integer.parseInt(params.get(SCIP_ANSWER_DYNAMIC_PARAM));
        return allowedIndex >= index;
    }

    public boolean commandApproved() {
        return params.containsKey(APPROVED_DYNAMIC_PARAM);
    }

    public String getInitiator() {
        return params.get(INITIATOR_DYNAMIC_PARAM);
    }
}
