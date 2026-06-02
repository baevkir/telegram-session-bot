package com.kb.sessionbot.commands.dispatcher.annotations;


public @interface RenderingOption {
    String value();
    String displayValue() default "";
}
