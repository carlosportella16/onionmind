package com.onionmind.ai.provider;

import com.onionmind.ai.routing.ProviderQuotaTracker;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Registers one {@link OpenAiCompatibleProvider} bean per {@code ai.openai-compatible.<id>}
 * entry, so a new provider of that shape is configuration, not code. Only the ids are read
 * here; each entry is bound when its bean is created, so properties added later in startup
 * (tests' dynamic properties) still apply.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
class OpenAiCompatibleProviders implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanFactoryAware {

    private static final String PREFIX = "ai.openai-compatible";

    private Environment environment;
    private BeanFactory beanFactory;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        Binder.get(environment).bind(PREFIX, Bindable.mapOf(String.class, Object.class)).orElse(Map.of())
            .keySet()
            .forEach(id -> registry.registerBeanDefinition(id + "Provider", BeanDefinitionBuilder
                .genericBeanDefinition(OpenAiCompatibleProvider.class, () -> new OpenAiCompatibleProvider(
                    id, settings(id), beanFactory.getBean(ProviderQuotaTracker.class)))
                .getBeanDefinition()));
    }

    private OpenAiCompatibleProvider.Settings settings(String id) {
        return Binder.get(environment).bind(PREFIX + "." + id, OpenAiCompatibleProvider.Settings.class).get();
    }
}
