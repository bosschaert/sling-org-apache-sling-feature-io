/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.feature.io.json;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.stream.JsonParsingException;

import org.apache.felix.utils.resource.CapabilityImpl;
import org.apache.felix.utils.resource.RequirementImpl;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.Include;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;

/**
 * This class offers a method to read a {@code Feature} using a {@code Reader} instance.
 */
public class FeatureJSONReader extends JSONReaderBase {

    /**
     * Read a new feature from the reader
     * The reader is not closed. It is up to the caller to close the reader.
     *
     * @param reader The reader for the feature
     * @param location Optional location
     * @return The read feature
     * @throws IOException If an IO errors occurs or the JSON is invalid.
     */
    public static Feature read(final Reader reader, final String location)
    throws IOException {
        try {
            final FeatureJSONReader mr = new FeatureJSONReader(location);
            return mr.readFeature(reader);
        } catch (final IllegalStateException | IllegalArgumentException | JsonParsingException e) {
            throw new IOException(e);
        }
    }

    /** The read feature. */
    private Feature feature;

    /**
     * Private constructor
     * @param location Optional location
     */
    FeatureJSONReader(final String location) {
        super(location);
    }

    /**
     * Read a full feature
     * @param reader The reader
     * @return The feature object
     * @throws IOException If an IO error occurs or the JSON is not valid.
     */
    private Feature readFeature(final Reader reader)
    throws IOException {
        final JsonObject json = Json.createReader(new StringReader(minify(reader))).readObject();
        final Map<String, Object> map = getJsonMap(json);

        checkModelVersion(map);

        if ( !map.containsKey(JSONConstants.FEATURE_ID) ) {
            throw new IOException(this.exceptionPrefix + "Feature id is missing");
        }
        final Object idObj = map.get(JSONConstants.FEATURE_ID);
        checkType(JSONConstants.FEATURE_ID, idObj, String.class);
        this.feature = new Feature(ArtifactId.parse(idObj.toString()));
        this.feature.setLocation(this.location);

        // title, description, vendor and license
        this.feature.setTitle(getProperty(map, JSONConstants.FEATURE_TITLE));
        this.feature.setDescription(getProperty(map, JSONConstants.FEATURE_DESCRIPTION));
        this.feature.setVendor(getProperty(map, JSONConstants.FEATURE_VENDOR));
        this.feature.setLicense(getProperty(map, JSONConstants.FEATURE_LICENSE));

        this.readVariables(map, feature.getVariables());
        this.readBundles(map, feature.getBundles(), feature.getConfigurations());
        this.readFrameworkProperties(map, feature.getFrameworkProperties());
        this.readConfigurations(map, feature.getConfigurations());

        this.readCapabilities(map);
        this.readRequirements(map);
        this.readIncludes(map);

        this.readExtensions(map,
                JSONConstants.FEATURE_KNOWN_PROPERTIES,
                this.feature.getExtensions(), this.feature.getConfigurations());

        return feature;
    }

    private void checkModelVersion(final Map<String, Object> map) throws IOException {
        String modelVersion = getProperty(map, JSONConstants.FEATURE_MODEL_VERSION);
        if (modelVersion == null) {
            modelVersion = "1";
        }
        if (!"1".equals(modelVersion)) {
            throw new IOException("Unsupported model version: " + modelVersion);
        }
    }

    private void readIncludes(final Map<String, Object> map) throws IOException {
        if ( map.containsKey(JSONConstants.FEATURE_INCLUDE)) {
            final Object includeObj = map.get(JSONConstants.FEATURE_INCLUDE);
            checkType(JSONConstants.FEATURE_INCLUDE, includeObj, Map.class, String.class);

            @SuppressWarnings("unchecked")
            final Include include;
            if ( includeObj instanceof String ) {
                final ArtifactId id = ArtifactId.parse(includeObj.toString());
                include = new Include(id);
            } else {
                @SuppressWarnings("unchecked")
                final Map<String, Object> obj = (Map<String, Object>) includeObj;
                if ( !obj.containsKey(JSONConstants.ARTIFACT_ID) ) {
                    throw new IOException(exceptionPrefix + " include is missing required artifact id");
                }
                checkType("Include " + JSONConstants.ARTIFACT_ID, obj.get(JSONConstants.ARTIFACT_ID), String.class);
                final ArtifactId id = ArtifactId.parse(obj.get(JSONConstants.ARTIFACT_ID).toString());
                include = new Include(id);

                if ( obj.containsKey(JSONConstants.INCLUDE_REMOVALS) ) {
                    checkType("Include removals", obj.get(JSONConstants.INCLUDE_REMOVALS), Map.class);
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> removalObj = (Map<String, Object>) obj.get(JSONConstants.INCLUDE_REMOVALS);
                    if ( removalObj.containsKey(JSONConstants.FEATURE_BUNDLES) ) {
                        checkType("Include removal bundles", removalObj.get(JSONConstants.FEATURE_BUNDLES), List.class);
                        @SuppressWarnings("unchecked")
                        final List<Object> list = (List<Object>)removalObj.get(JSONConstants.FEATURE_BUNDLES);
                        for(final Object val : list) {
                            checkType("Include removal bundles", val, String.class);
                            if ( val.toString().startsWith("#")) {
                                continue;
                            }
                            include.getBundleRemovals().add(ArtifactId.parse(val.toString()));
                        }
                    }
                    if ( removalObj.containsKey(JSONConstants.FEATURE_CONFIGURATIONS) ) {
                        checkType("Include removal configuration", removalObj.get(JSONConstants.FEATURE_CONFIGURATIONS), List.class);
                        @SuppressWarnings("unchecked")
                        final List<Object> list = (List<Object>)removalObj.get(JSONConstants.FEATURE_CONFIGURATIONS);
                        for(final Object val : list) {
                            checkType("Include removal configuration", val, String.class);
                            include.getConfigurationRemovals().add(val.toString());
                        }
                    }
                    if ( removalObj.containsKey(JSONConstants.FEATURE_FRAMEWORK_PROPERTIES) ) {
                        checkType("Include removal framework properties", removalObj.get(JSONConstants.FEATURE_FRAMEWORK_PROPERTIES), List.class);
                        @SuppressWarnings("unchecked")
                        final List<Object> list = (List<Object>)removalObj.get(JSONConstants.FEATURE_FRAMEWORK_PROPERTIES);
                        for(final Object val : list) {
                            checkType("Include removal framework properties", val, String.class);
                            include.getFrameworkPropertiesRemovals().add(val.toString());
                        }
                    }
                    if ( removalObj.containsKey(JSONConstants.INCLUDE_EXTENSION_REMOVALS) ) {
                        checkType("Include removal extensions", removalObj.get(JSONConstants.INCLUDE_EXTENSION_REMOVALS), List.class);
                        @SuppressWarnings("unchecked")
                        final List<Object> list = (List<Object>)removalObj.get(JSONConstants.INCLUDE_EXTENSION_REMOVALS);
                        for(final Object val : list) {
                            checkType("Include removal extension", val, String.class, Map.class);
                            if ( val instanceof String ) {
                                if ( val.toString().startsWith("#")) {
                                    continue;
                                }
                                include.getExtensionRemovals().add(val.toString());
                            } else {
                                @SuppressWarnings("unchecked")
                                final Map<String, Object> removalMap = (Map<String, Object>)val;
                                final Object nameObj = removalMap.get("name");
                                checkType("Include removal extension", nameObj, String.class);
                                if ( removalMap.containsKey("artifacts") ) {
                                    checkType("Include removal extension artifacts", removalMap.get("artifacts"), List.class);
                                    @SuppressWarnings("unchecked")
                                    final List<Object> artifactList = (List<Object>)removalMap.get("artifacts");
                                    final List<ArtifactId> ids = new ArrayList<>();
                                    for(final Object aid : artifactList) {
                                        checkType("Include removal extension artifact", aid, String.class);
                                        ids.add(ArtifactId.parse(aid.toString()));
                                    }
                                    include.getArtifactExtensionRemovals().put(nameObj.toString(), ids);
                                } else {
                                    include.getExtensionRemovals().add(nameObj.toString());
                                }
                            }
                        }
                    }

                }
            }
            feature.setInclude(include);
        }
    }

    private void readRequirements(Map<String, Object> map) throws IOException {
        if ( map.containsKey(JSONConstants.FEATURE_REQUIREMENTS)) {
            final Object reqObj = map.get(JSONConstants.FEATURE_REQUIREMENTS);
            checkType(JSONConstants.FEATURE_REQUIREMENTS, reqObj, List.class);

            @SuppressWarnings("unchecked")
            final List<Object> requirements = (List<Object>)reqObj;
            for(final Object req : requirements) {
                checkType("Requirement", req, Map.class);
                @SuppressWarnings("unchecked")
                final Map<String, Object> obj = (Map<String, Object>) req;

                if ( !obj.containsKey(JSONConstants.REQCAP_NAMESPACE) ) {
                    throw new IOException(this.exceptionPrefix + "Namespace is missing for requirement");
                }
                checkType("Requirement namespace", obj.get(JSONConstants.REQCAP_NAMESPACE), String.class);

                Map<String, Object> attrMap = new HashMap<>();
                if ( obj.containsKey(JSONConstants.REQCAP_ATTRIBUTES) ) {
                    checkType("Requirement attributes", obj.get(JSONConstants.REQCAP_ATTRIBUTES), Map.class);
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> attrs = (Map<String, Object>)obj.get(JSONConstants.REQCAP_ATTRIBUTES);
                    attrs.forEach(rethrowBiConsumer((key, value) -> ManifestUtils.unmarshalAttribute(key, value, attrMap::put)));
                }

                Map<String, String> dirMap = new HashMap<>();
                if ( obj.containsKey(JSONConstants.REQCAP_DIRECTIVES) ) {
                    checkType("Requirement directives", obj.get(JSONConstants.REQCAP_DIRECTIVES), Map.class);
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> dirs = (Map<String, Object>)obj.get(JSONConstants.REQCAP_DIRECTIVES);
                    dirs.forEach(rethrowBiConsumer((key, value) -> ManifestUtils.unmarshalDirective(key, value, dirMap::put)));
                }

                final Requirement r = new RequirementImpl(null, obj.get(JSONConstants.REQCAP_NAMESPACE).toString(), dirMap, attrMap);
                feature.getRequirements().add(r);
            }
        }
    }

    private void readCapabilities(Map<String, Object> map) throws IOException {
        if ( map.containsKey(JSONConstants.FEATURE_CAPABILITIES)) {
            final Object capObj = map.get(JSONConstants.FEATURE_CAPABILITIES);
            checkType(JSONConstants.FEATURE_CAPABILITIES, capObj, List.class);

            @SuppressWarnings("unchecked")
            final List<Object> capabilities = (List<Object>)capObj;
            for(final Object cap : capabilities) {
                checkType("Capability", cap, Map.class);
                @SuppressWarnings("unchecked")
                final Map<String, Object> obj = (Map<String, Object>) cap;

                if ( !obj.containsKey(JSONConstants.REQCAP_NAMESPACE) ) {
                    throw new IOException(this.exceptionPrefix + "Namespace is missing for capability");
                }
                checkType("Capability namespace", obj.get(JSONConstants.REQCAP_NAMESPACE), String.class);

                Map<String, Object> attrMap = new HashMap<>();
                if ( obj.containsKey(JSONConstants.REQCAP_ATTRIBUTES) ) {
                    checkType("Capability attributes", obj.get(JSONConstants.REQCAP_ATTRIBUTES), Map.class);
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> attrs = (Map<String, Object>)obj.get(JSONConstants.REQCAP_ATTRIBUTES);
                    attrs.forEach(rethrowBiConsumer((key, value) -> ManifestUtils.unmarshalAttribute(key, value, attrMap::put)));
                }

                Map<String, String> dirMap = new HashMap<>();
                if ( obj.containsKey(JSONConstants.REQCAP_DIRECTIVES) ) {
                    checkType("Capability directives", obj.get(JSONConstants.REQCAP_DIRECTIVES), Map.class);
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> dirs = (Map<String, Object>) obj.get(JSONConstants.REQCAP_DIRECTIVES);
                    dirs.forEach(rethrowBiConsumer((key, value) -> ManifestUtils.unmarshalDirective(key, value, dirMap::put)));
                }

                final Capability c = new CapabilityImpl(null, obj.get(JSONConstants.REQCAP_NAMESPACE).toString(), dirMap, attrMap);
                feature.getCapabilities().add(c);
            }
        }
    }

    @FunctionalInterface
    private interface BiConsumer_WithExceptions<T, V, E extends Exception> {
        void accept(T t, V u) throws E;
    }

    private static <T, V, E extends Exception> BiConsumer<T, V> rethrowBiConsumer(BiConsumer_WithExceptions<T, V, E> biConsumer) {
        return (t, u) -> {
            try {
                biConsumer.accept(t, u);
            } catch (Exception exception) {
                throwAsUnchecked(exception);
            }
        };
    }

    @SuppressWarnings ("unchecked")
    private static <E extends Throwable> void throwAsUnchecked(Exception exception) throws E {
        throw (E) exception;
    }
}


