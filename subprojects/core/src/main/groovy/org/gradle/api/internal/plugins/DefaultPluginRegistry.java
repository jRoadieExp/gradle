/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.plugins;

import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.gradle.api.Nullable;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.plugins.InvalidPluginException;
import org.gradle.internal.Cast;
import org.gradle.internal.Factories;
import org.gradle.internal.Factory;
import org.gradle.internal.UncheckedException;
import org.gradle.plugin.internal.PluginId;
import org.gradle.util.GUtil;

import java.util.concurrent.ExecutionException;

public class DefaultPluginRegistry implements PluginRegistry {

    private final PluginRegistry parent;
    private final PluginInspector pluginInspector;
    private final Factory<? extends ClassLoader> classLoaderFactory;

    private final LoadingCache<Class<?>, PluginImplementation<?>> classMappings;
    private final LoadingCache<PluginIdLookupCacheKey, Optional<PluginImplementation<?>>> idMappings;

    public DefaultPluginRegistry(PluginInspector pluginInspector, ClassLoader classLoader) {
        this(null, pluginInspector, Factories.constant(classLoader));
    }

    private DefaultPluginRegistry(PluginRegistry parent, final PluginInspector pluginInspector, final Factory<? extends ClassLoader> classLoaderFactory) {
        this.parent = parent;
        this.pluginInspector = pluginInspector;
        this.classLoaderFactory = classLoaderFactory;
        this.classMappings = CacheBuilder.newBuilder().build(new PotentialPluginCacheLoader(pluginInspector));
        this.idMappings = CacheBuilder.newBuilder().build(new CacheLoader<PluginIdLookupCacheKey, Optional<PluginImplementation<?>>>() {
            @Override
            public Optional<PluginImplementation<?>> load(@SuppressWarnings("NullableProblems") PluginIdLookupCacheKey key) throws Exception {
                final PluginId pluginId = key.getId();
                ClassLoader classLoader = key.getClassLoader();

                PluginDescriptorLocator locator = new ClassloaderBackedPluginDescriptorLocator(classLoader);

                PluginDescriptor pluginDescriptor = locator.findPluginDescriptor(pluginId.toString());
                if (pluginDescriptor == null) {
                    return Optional.absent();
                }

                String implClassName = pluginDescriptor.getImplementationClassName();
                if (!GUtil.isTrue(implClassName)) {
                    throw new InvalidPluginException(String.format("No implementation class specified for plugin '%s' in %s.", pluginId, pluginDescriptor));
                }

                final Class<?> implClass;
                try {
                    implClass = classLoader.loadClass(implClassName);
                } catch (ClassNotFoundException e) {
                    throw new InvalidPluginException(String.format(
                            "Could not find implementation class '%s' for plugin '%s' specified in %s.", implClassName, pluginId,
                            pluginDescriptor), e);
                }

                PotentialPlugin<?> potentialPlugin = pluginInspector.inspect(implClass);
                PluginImplementation<Object> withId = new RegistryAwarePluginImplementation(pluginId, potentialPlugin);
                return Cast.uncheckedCast(Optional.of(withId));
            }
        });
    }

    public PluginRegistry createChild(final ClassLoaderScope lookupScope) {
        return new DefaultPluginRegistry(this, pluginInspector, new Factory<ClassLoader>() {
            public ClassLoader create() {
                return lookupScope.getLocalClassLoader();
            }
        });
    }

    public <T> PluginImplementation<T> inspect(Class<T> clazz) {
        // Don't go up the parent chain.
        // Don't want to risk classes crossing “scope” boundaries and being non collectible.
        return Cast.uncheckedCast(uncheckedGet(classMappings, clazz));
    }

    @Nullable
    @Override
    public PluginImplementation<?> lookup(PluginId pluginId) {
        PluginImplementation lookup;
        if (parent != null) {
            lookup = parent.lookup(pluginId);
            if (lookup != null) {
                return lookup;
            }
        }

        return lookupSelf(pluginId);
    }

    private PluginImplementation<?> lookupSelf(PluginId pluginId) {
        return lookup(pluginId, classLoaderFactory.create());
    }

    @Nullable
    @Override
    public PluginImplementation<?> lookup(PluginId pluginId, ClassLoader classLoader) {
        // Don't go up the parent chain.
        // Don't want to risk classes crossing “scope” boundaries and being non collectible.

        PluginImplementation lookup;
        if (!pluginId.isQualified()) {
            PluginId qualified = pluginId.maybeQualify(DefaultPluginManager.CORE_PLUGIN_NAMESPACE);
            lookup = uncheckedGet(idMappings, new PluginIdLookupCacheKey(qualified, classLoader)).orNull();
            if (lookup != null) {
                return lookup;
            }
        }

        return uncheckedGet(idMappings, new PluginIdLookupCacheKey(pluginId, classLoader)).orNull();
    }

    private static <K, V> V uncheckedGet(LoadingCache<K, V> cache, K key) {
        try {
            return cache.get(key);
        } catch (ExecutionException e) {
            throw UncheckedException.throwAsUncheckedException(e.getCause());
        } catch (UncheckedExecutionException e) {
            throw UncheckedException.throwAsUncheckedException(e.getCause());
        }
    }

    static class PluginIdLookupCacheKey {

        private final ClassLoader classLoader;
        private final PluginId id;

        PluginIdLookupCacheKey(PluginId id, ClassLoader classLoader) {
            this.classLoader = classLoader;
            this.id = id;
        }

        public PluginId getId() {
            return id;
        }

        public ClassLoader getClassLoader() {
            return classLoader;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            PluginIdLookupCacheKey that = (PluginIdLookupCacheKey) o;

            return classLoader.equals(that.classLoader) && id.equals(that.id);
        }

        @Override
        public int hashCode() {
            int result = classLoader.hashCode();
            result = 31 * result + id.hashCode();
            return result;
        }
    }

    private class PotentialPluginCacheLoader extends CacheLoader<Class<?>, PluginImplementation<?>> {
        private final PluginInspector pluginInspector;

        public PotentialPluginCacheLoader(PluginInspector pluginInspector) {
            this.pluginInspector = pluginInspector;
        }

        @Override
        public PluginImplementation<?> load(@SuppressWarnings("NullableProblems") Class<?> key) throws Exception {
            return new RegistryAwarePluginImplementation(null, pluginInspector.inspect(key));
        }
    }

    private class RegistryAwarePluginImplementation extends DefaultPotentialPluginWithId<Object> {
        private final PluginId pluginId;

        public RegistryAwarePluginImplementation(PluginId pluginId, PotentialPlugin<?> potentialPlugin) {
            super(pluginId, potentialPlugin);
            this.pluginId = pluginId;
        }

        @Override
        public boolean isAlsoKnownAs(PluginId id) {
            if (id.equals(pluginId)) {
                return true;
            }
            PluginImplementation<?> other = lookupSelf(id);
            return other != null && other.asClass().equals(asClass());
        }
    }
}