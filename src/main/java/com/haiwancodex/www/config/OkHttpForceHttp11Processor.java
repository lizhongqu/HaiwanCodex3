package com.haiwancodex.www.config;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import java.lang.reflect.Field;
import java.util.Collections;

@Component
public class OkHttpForceHttp11Processor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 匹配 SDK 版的 HTTP 客户端 Bean
        if (bean.getClass().getName().contains("SpringAiOpenAiHttpClient")) {
            try {
                // 递归查找类中所有 OkHttpClient 类型的字段
                Field okHttpField = findOkHttpField(bean.getClass());
                if (okHttpField != null) {
                    okHttpField.setAccessible(true);
                    OkHttpClient originalClient = (OkHttpClient) okHttpField.get(bean);
                    
                    // 在原有配置基础上重新构建，强制 HTTP/1.1
                    OkHttpClient newClient = originalClient.newBuilder()
                            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                            .build();
                    
                    okHttpField.set(bean, newClient);
                }
            } catch (Exception ignored) {
                // 静默失败，不影响项目启动
            }
        }
        return bean;
    }

    private Field findOkHttpField(Class<?> clazz) {
        // 先找当前类的字段
        for (Field field : clazz.getDeclaredFields()) {
            if (OkHttpClient.class.isAssignableFrom(field.getType())) {
                return field;
            }
        }
        // 递归找父类
        if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
            return findOkHttpField(clazz.getSuperclass());
        }
        return null;
    }
}