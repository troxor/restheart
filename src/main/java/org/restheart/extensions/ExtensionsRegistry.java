/*
 * RESTHeart - the Web API for MongoDB
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.extensions;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import java.lang.reflect.InvocationTargetException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.bson.BsonDocument;
import org.restheart.Bootstrapper;
import static org.restheart.ConfigurationKeys.EXTENSION_ARGS_KEY;
import org.restheart.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.restheart.ConfigurationKeys.EXTENSION_DISABLED_KEY;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 */
public class ExtensionsRegistry {
    private static final Logger LOGGER = LoggerFactory
            .getLogger(ExtensionsRegistry.class);

    private final Set<ExtensionRecord> initializers = new LinkedHashSet<>();

    private final Map<String, BsonDocument> confs = consumeConfiguration();

    private final ScanResult scanResult = new ClassGraph()
            .enableAnnotationInfo()
            .scan();

    private ExtensionsRegistry() {
        findInitializers();
    }

    public static ExtensionsRegistry getInstance() {
        return ExtensionsRegistryHolder.INSTANCE;
    }

    private static class ExtensionsRegistryHolder {
        private static final ExtensionsRegistry INSTANCE = new ExtensionsRegistry();
    }

    /**
     *
     * @return the initializers sorted by priority as map(name -> instance)
     */
    public Set<ExtensionRecord> getInitializers() {
        return initializers;
    }

    private Map<String, BsonDocument> consumeConfiguration() {
        Map<String, Map<String, Object>> extensions = Bootstrapper.getConfiguration()
                .getExtensions();

        Map<String, BsonDocument> confs = new HashMap<>();

        extensions.forEach((name, params) -> {
            if (!params.containsKey(EXTENSION_DISABLED_KEY)) {

                BsonDocument args;
                Object _args = params.get(EXTENSION_ARGS_KEY);

                if (_args instanceof Map) {
                    args = JsonUtils.toBsonDocument((Map) _args);
                } else {
                    args = new BsonDocument();
                }

                confs.put(name, args);
            }
        });

        return confs;
    }

    /**
     * runs the initializers defined via @Initializer annotation
     */
    @SuppressWarnings("unchecked")
    private void findInitializers() {
        String annotationClassName = "org.restheart.extensions.Initializer";

        try (this.scanResult) {
            var cil = scanResult
                    .getClassesWithAnnotation(annotationClassName);

            // sort @Initializers by priority
            cil.sort(new Comparator<ClassInfo>() {
                @Override
                public int compare(ClassInfo ci1, ClassInfo ci2) {
                    int p1 = annotationParam(ci1, annotationClassName, "priority");

                    int p2 = annotationParam(ci2, annotationClassName, "priority");

                    return Integer.compare(p1, p2);
                }
            });

            for (var ci : cil) {
                // confs does not contain names of disabled extensions
                Object i;

                try {
                    i = ci.loadClass(false)
                            .getConstructor()
                            .newInstance();

                    String name = annotationParam(ci, annotationClassName, "name");
                    String description = annotationParam(ci, annotationClassName, "description");

                    if (i instanceof Consumer) {
                        this.initializers.add(new ExtensionRecord(
                                name,
                                description,
                                ci.getName(),
                                (Consumer) i,
                                confs.get(name)));
                    } else {
                        LOGGER.error("Extension class {} annotated with "
                                + "@Initializer must implement interface Consumer",
                                ci.getName());
                    }

                } catch (InstantiationException
                        | IllegalAccessException
                        | InvocationTargetException
                        | NoSuchMethodException t) {
                    LOGGER.error("Error instantiating initializer {}",
                            ci.getName(),
                            t);
                }
            }
        }
    }

    private static <T extends Object> T annotationParam(ClassInfo ci,
            String annotationClassName,
            String param) {
        var annotationInfo = ci.getAnnotationInfo(annotationClassName);
        var annotationParamVals = annotationInfo.getParameterValues();

        // The Route annotation has a parameter named "path"
        return (T) annotationParamVals.getValue(param);
    }
}