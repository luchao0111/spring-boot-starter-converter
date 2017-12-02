package com.github.liaochong.converter.context;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import com.github.liaochong.converter.annoation.Converter;
import com.github.liaochong.converter.configuration.ConverterProperties;
import com.github.liaochong.converter.exception.ConverterDisabledException;
import com.github.liaochong.converter.exception.InvalidConfigurationException;
import com.github.liaochong.converter.exception.NoConverterException;
import com.github.liaochong.converter.exception.NonUniqueConverterException;
import com.github.liaochong.ratel.tools.core.builder.MapBuilder;
import com.github.liaochong.ratel.tools.core.utils.ClassUtil;
import com.github.liaochong.ratel.tools.core.validator.BooleanValidator;
import com.github.liaochong.ratel.tools.core.validator.ObjectValidator;
import lombok.extern.slf4j.Slf4j;

/**
 * 转换上下文
 *
 * @author liaochong
 * @version V1.0
 */
@Slf4j
public final class ConverterContext {

    private static final Map<Condition, Handler> ACTION_MAP = MapBuilder.concurrentHashMap();

    /**
     * 是否已经初始化标志
     */
    private static boolean isInitialized = false;

    /**
     * 是否开启starter标志，默认未启用
     */
    private static boolean isDisable = true;

    /**
     * 初始化上下文环境
     * 
     * @param converterProperties 转换上下文属性对象
     * @param converterBeans spring扫描到的bean
     */
    public static void initialize(ConverterProperties converterProperties, Map<String, Object> converterBeans) {
        // 不允许使用该接口手动初始化
        BooleanValidator.ifTrueThrow(isInitialized, () -> new UnsupportedOperationException(
                "It is not allowed to initialize directly with the initialize interface"));

        log.info("Check user-defined configuration");
        checkProperties(converterProperties);
        log.info("Start initialize conversion environment");
        // 开启转换上下文标志
        isDisable = false;
        if (!converterProperties.isOnlyScanNonStaticMethod()) {
            initStaticActionMap(converterProperties.getScanPackages());
        }
        if (!converterProperties.isOnlyScanStaticMethod()) {
            initNonStaticActionMap(converterBeans);
        }
        // 严格模式下，必须存在转换器
        boolean isStrictFail = converterProperties.isStrictMode() && MapUtils.isEmpty(ACTION_MAP);
        BooleanValidator.ifTrueThrow(isStrictFail, () -> NoConverterException.of("There is no any converter exist"));

        isInitialized = true;
        log.info("Conversion environment initialization completed");
    }

    /**
     * 校验属性文件合法性
     * 
     * @param properties 转换上下文属性对象
     */
    private static void checkProperties(ConverterProperties properties) {
        boolean isIllegal = properties.isOnlyScanNonStaticMethod() && properties.isOnlyScanStaticMethod();
        BooleanValidator.ifTrueThrow(isIllegal, () -> InvalidConfigurationException
                .of("Only scanning static methods or scanning only non static methods can only select one"));
    }

    /**
     * 初始化静态操作集合
     *
     * @param scanPackages 扫描路径集合
     */
    private static void initStaticActionMap(Set<String> scanPackages) {
        Set<Class<?>> set;
        if (CollectionUtils.isEmpty(scanPackages)) {
            set = collectConverterClass(StringUtils.EMPTY);
        } else {
            Function<String, Stream<Class<?>>> function = scanPackage -> collectConverterClass(scanPackage).stream();
            set = scanPackages.parallelStream().flatMap(function).collect(Collectors.toSet());
        }
        if (CollectionUtils.isEmpty(set)) {
            log.warn("There is no any static conversion object");
            return;
        }
        set.parallelStream().forEach(clz -> packagingAction(clz.getDeclaredMethods(), null));
    }

    /**
     * 初始化非静态操作集合
     * 
     * @param converterBeans 转换对象
     */
    private static void initNonStaticActionMap(Map<String, Object> converterBeans) {
        if (MapUtils.isEmpty(converterBeans)) {
            log.info("There is no any non-static conversion object");
            return;
        }
        Stream<Object> objectStream = converterBeans.values().parallelStream();
        objectStream.forEach(bean -> packagingAction(bean.getClass().getDeclaredMethods(), bean));
    }

    /**
     * 收集转换对象
     *
     * @return 列表集
     */
    private static Set<Class<?>> collectConverterClass(String scanPackageName) {
        Set<Class<?>> set = ClassUtil.getClassSet(scanPackageName);
        if (CollectionUtils.isEmpty(set)) {
            return Collections.emptySet();
        }
        Predicate<Class<?>> predicate = clazz -> clazz.isAnnotationPresent(Converter.class);
        return set.parallelStream().filter(predicate).collect(Collectors.toSet());
    }

    /**
     * 包装action
     * 
     * @param methods 方法
     * @param handlerBean 处理者
     */
    private static void packagingAction(Method[] methods, Object handlerBean) {
        if (ArrayUtils.isEmpty(methods)) {
            return;
        }
        // 参数唯一，且为public
        Predicate<Method> commonFilter = method -> Modifier.isPublic(method.getModifiers())
                && method.getParameterCount() == 1
                && Objects.isNull(handlerBean) == Modifier.isStatic(method.getModifiers());

        Arrays.stream(methods).filter(commonFilter).forEach(method -> ConverterContext.setAction(method, handlerBean));
    }

    /**
     * 设置action
     * 
     * @param method 转换方法
     * @param handlerBean 转换对象
     */
    private static void setAction(Method method, Object handlerBean) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Class<?> returnType = method.getReturnType();
        Condition condition = Condition.newInstance(paramTypes[0], returnType);

        Handler existHandler = ACTION_MAP.get(condition);
        if (Objects.nonNull(existHandler)) {
            String message = "\n{method：" + method.getDeclaringClass().getName() + "." + method.getName()
                    + "}\n{method：" + existHandler.getMethod().getDeclaringClass().getName() + "."
                    + existHandler.getMethod().getName() + "} convert source and target is the same ";
            throw NonUniqueConverterException.of(message);
        }

        log.info("Mapped \"{sourceClass = " + condition.getSourceClass() + ",targetClass = " + returnType + "\"} onto "
                + method);
        Handler handler = Handler.newInstance(handlerBean, method);
        ACTION_MAP.put(condition, handler);
    }

    /**
     * 根据源类以及目标类获取对应的handler
     * 
     * @param sourceClass 源类
     * @param targetClass 目标类
     * @return handler
     */
    public static Handler getActionHandler(Class<?> sourceClass, Class<?> targetClass) {
        BooleanValidator.ifTrueThrow(isDisable,
                () -> ConverterDisabledException.of("@EnableConverter annotation not enabled"));

        Condition condition = Condition.newInstance(sourceClass, targetClass);
        Handler handler = ACTION_MAP.get(condition);
        ObjectValidator.ifNullThrow(handler,
                () -> NoConverterException.of("The conversion method of matching \"" + condition + "\" was not found"));
        return handler;
    }

}
