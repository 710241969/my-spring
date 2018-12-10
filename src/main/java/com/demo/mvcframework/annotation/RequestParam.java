package com.demo.mvcframework.annotation;

import java.lang.annotation.*;

@Target(value = {ElementType.PARAMETER})
@Retention(value = RetentionPolicy.RUNTIME)
@Documented
public @interface RequestParam {
    String value() default "";
}
