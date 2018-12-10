package com.demo.mvcframework.annotation;

import java.lang.annotation.*;

@Target(value = {ElementType.FIELD})
@Retention(value = RetentionPolicy.RUNTIME)
@Documented
public @interface Autowired {
    String value() default "";
}
