package com.demo.utils;

/**
 * 字符串工具类
 */
public class StringUtil {

    /**
     * 首字母小写
     *
     * @param className
     * @return
     */
    public static String lowerCaseFirst(String className) {
        char chars[] = className.toCharArray();
        if (chars[0] > 'A' && chars[0] < 'Z') {
            chars[0] += 32;
            return String.valueOf(chars);
        }
        return className;
    }
}
