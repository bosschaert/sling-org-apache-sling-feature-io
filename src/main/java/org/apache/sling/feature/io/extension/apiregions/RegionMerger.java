/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.feature.io.extension.apiregions;

import org.apache.sling.feature.Extension;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.FeatureExtensionHandler;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.json.stream.JsonGenerator;

public class RegionMerger implements FeatureExtensionHandler {

    @Override
    public boolean canMerge(Extension ext) {
        return "api-regions".equals(ext.getName());
    }

    @Override
    public void merge(Feature target, Feature source, Extension targetExtension) {
        /* */ System.out.println("**** MErging regions");

        try {
            Extension sourceExtension = source.getExtensions().getByName("api-regions");
            if (sourceExtension == null)
                return;

            JsonReader sourceEx = Json.createReader(new StringReader(sourceExtension.getJSON()));
            JsonArray sourceJA = sourceEx.readArray();

            JsonReader targetEx = Json.createReader(new StringReader(targetExtension.getJSON()));
            JsonArray targetJA = targetEx.readArray();

            StringWriter sw = new StringWriter();
            JsonGenerator gen = Json.createGenerator(sw);
            gen.writeStartArray();
            for (JsonValue jv : targetJA) {
                gen.write(jv);
            }

            for (int i=0; i < sourceJA.size(); i++) {
                gen.writeStartObject();
                JsonObject jo = sourceJA.getJsonObject(i);
                for (Map.Entry<String, JsonValue> entry : jo.entrySet()) {
                    gen.write(entry.getKey(), entry.getValue());
                }
                gen.write("org-feature", source.getId().toMvnId());
                gen.writeEnd();
            }
            gen.writeEnd();
            gen.close();
            // System.out.println("$$$" + sw);
            targetExtension.setJSON(sw.toString());
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    @Override
    public void postProcess(Feature feat, Extension ex) {
        StringWriter sw = new StringWriter();
        JsonGenerator gen = Json.createGenerator(sw);
        gen.writeStartArray();

        JsonReader jr = Json.createReader(new StringReader(ex.getJSON()));
        JsonArray ja = jr.readArray();
        for (JsonValue jv : ja) {
            if (jv instanceof JsonObject) {
                JsonObject jo = (JsonObject) jv;
                if (jo.containsKey("org-feature")) {
                    gen.write(jv);
                } else {
                    gen.writeStartObject();
                    for (Map.Entry<String, JsonValue> entry : jo.entrySet()) {
                        gen.write(entry.getKey(), entry.getValue());
                    }
                    gen.write("org-feature", feat.getId().toMvnId());
                    gen.writeEnd();
                }
            } else {
                gen.write(jv);
            }
        }

        gen.writeEnd();
        gen.close();
        ex.setJSON(sw.toString());
    }
}
