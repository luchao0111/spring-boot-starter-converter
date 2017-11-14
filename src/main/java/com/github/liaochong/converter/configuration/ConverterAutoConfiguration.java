package com.github.liaochong.converter.configuration;

import java.util.Map;

import javax.annotation.Resource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.liaochong.converter.annoation.Converter;
import com.github.liaochong.converter.core.BeanConverter;
import com.github.liaochong.converter.core.ConversionContext;
import com.github.liaochong.converter.core.ConverterCollector;
import com.github.liaochong.converter.core.Handler;


/**
 * 自动配置
 *
 * @author liaochong
 * @version V1.0
 */
@Configuration
@EnableConfigurationProperties(ConversionProperties.class)
@ConditionalOnClass(Converter.class)
public class ConverterAutoConfiguration {

    @Resource
    private ConversionProperties conversionProperties;

    @Bean
    public BeanConverter getConverter() {
        //获取扫描路径
        String scanPackageName = conversionProperties.getScanPackageName();
        Map<ConversionContext, Handler> actionMap = ConverterCollector.getActionMap(scanPackageName);

        BeanConverter beanConverter = new BeanConverter();
        beanConverter.setActionMap(actionMap);
        return beanConverter;
    }
}
