package com.kb.sessionbot.commands;

public interface CommandConstants {
    String COMMAND_START = "/";
    String COMMAND_PARAMETERS_SEPARATOR_REGEX = "\\?";
    String COMMAND_PARAMETERS_SEPARATOR = "?";
    String PARAMETER_SEPARATOR = "&";
    String KEY_VALUE_SEPARATOR = ":";
    String DYNAMIC_PARAMETERS_SEPARATOR = "#";

    int MAX_CALLBACK_BYTES = 64;

    String REFRESH_CONTEXT_DYNAMIC_PARAM = "refreshContext";
    String SCIP_ANSWER_DYNAMIC_PARAM = "scipAnswer";
    String APPROVED_DYNAMIC_PARAM = "approved";
    String INITIATOR_DYNAMIC_PARAM = "initiator";
}
