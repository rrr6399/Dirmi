/*
 *  Copyright 2006-2013 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.dirmi.core;

import java.util.Map;

import org.cojen.util.KeyFactory;
import org.cojen.util.WeakValuedHashMap;

/**
 * Factory for creating bridge class loaders that can delegate to either the application class
 * loader or the Dirmi class loader.
 *
 * @author Rune Glerup
 */
final class BridgeLoaderFactory {
    private static final BridgeLoaderFactory INSTANCE = new BridgeLoaderFactory();

    public static BridgeLoaderFactory getInstance() {
        return INSTANCE;
    }

    private static final String FRAMEWORK_PACKAGE_PREFIX = "org.cojen.dirmi";
    private static final ClassLoader FRAMEWORK_CLASS_LOADER = getFrameworkClassLoader();

    private static ClassLoader getFrameworkClassLoader() {
        ClassLoader result = BridgeLoaderFactory.class.getClassLoader();
        if (result == null) {
            result = ClassLoader.getSystemClassLoader();
        }
        return result;
    }

    private final Map<Object, ClassLoader> classLoaders =
        new WeakValuedHashMap<Object, ClassLoader>();
    private final Object lock = new Object();

    private BridgeLoaderFactory() { }

    /**
     * Acquire a bridge loader for a given application class loader.
     *
     * The returned bridge class loader will delegate to the Dirmi class loader when loading a
     * framework class and to the application class loader in all other cases.
     *
     * @param applicationClassLoader The application class loader 
     * @return The bridge class loader
     */
    public ClassLoader getBridgeLoader(final ClassLoader applicationClassLoader) {
        final Object key = KeyFactory.createKey( applicationClassLoader );
        ClassLoader bridgeClassLoader;
        synchronized (lock) {
            bridgeClassLoader = classLoaders.get(key);
            if (bridgeClassLoader == null) {
                bridgeClassLoader = new BridgeClassLoader( applicationClassLoader );
                classLoaders.put(key, bridgeClassLoader);
            }
        }
        return bridgeClassLoader;
    }

    private static final class BridgeClassLoader extends ClassLoader {
        public BridgeClassLoader(final ClassLoader parent) {
            super(parent);
        }

        @Override
        protected synchronized Class<?> loadClass(final String name, final boolean resolve)
            throws ClassNotFoundException
        {
            if (isInFrameworkPackage(name)) {
                try {
                    final Class<?> result = FRAMEWORK_CLASS_LOADER.loadClass(name);
                    if (resolve) {
                        resolveClass(result);
                    }
                    return result;
                } catch (final Throwable t) {
                    // ignore error
                }
            }

            // use regular class loading
            return super.loadClass(name, resolve);
        }

        private boolean isInFrameworkPackage(final String name) {
            return name != null && name.startsWith(FRAMEWORK_PACKAGE_PREFIX);
        }
    }
}
