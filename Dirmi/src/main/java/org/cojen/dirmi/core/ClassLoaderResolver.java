/*
 *  Copyright 2009-2010 Brian S O'Neill
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

import org.cojen.dirmi.ClassResolver;

import org.cojen.util.SoftValueCache;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class ClassLoaderResolver implements ClassResolver {
    static final ClassResolver DEFAULT = new ClassLoaderResolver(null);

    private final ClassLoader mLoader;
    private final SoftValueCache<String, Class<?>> mCache;

    ClassLoaderResolver(ClassLoader loader) {
        mLoader = loader;
        mCache = new SoftValueCache<String, Class<?>>(101);
    }

    public Class<?> resolveClass(String name) throws ClassNotFoundException {
        Class<?> clazz = mCache.get(name);
        if (clazz != null) {
            return clazz;
        }

        if (mLoader == null) {
            /*
              By default, classes described by ObjectStreamClass are loaded
              against the nearest class loader on the call stack. While this is
              the more "correct" strategy, it is not compatible with how remote
              interfaces are loaded. They are described by name, and not by
              ObjectStreamClass.

              One trick is to call resolveProxyClass and extract the interface
              from the proxy, and then discard the proxy. The problem is that
              remote interfaces are saved for the duration of the session. If
              the session is shared by different threads, each with different
              class loaders on the call stack, hilarity ensues. It will appear
              as if classes cannot be cast to exactly what they are. Classic
              class loader hell.

              Instead, simply fallback to good ol' Class.forName, which uses
              the class loader that loaded this class. It will likely be the
              system class loader, but at least the runtime behavior will be
              consistent.
            */
			try {
    			ClassLoader loader = Thread.currentThread().getContextClassLoader();
				clazz = Class.forName(name, true, loader);
			} catch (ClassNotFoundException cnfe) {
            	clazz = Class.forName(name);
			}
        } else {
            clazz = Class.forName(name, true, mLoader);
        }

        Class<?> existing = mCache.putIfAbsent(name, clazz);
        return existing != null ? existing : clazz;
    }
}
