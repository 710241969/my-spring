package com.demo.service.impl;

import com.demo.mvcframework.annotation.Service;
import com.demo.service.BaseService;

@Service
public class HelloService extends BaseService {
    public Integer getAge() {
        return 24;
    }
}
