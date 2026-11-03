/*
 * #%L
 * ZETA Testsuite
 * %%
 * (C) achelos GmbH, 2025, licensed for gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 * #L%
 */

package de.gematik.zeta.services;

import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.keyword.EnumValidator;
import com.networknt.schema.keyword.Keyword;
import com.networknt.schema.keyword.KeywordValidator;
import com.networknt.schema.keyword.TypeValidator;
import tools.jackson.databind.JsonNode;

/**
 * Compatibility correction for networknt 3.0.7's nullable propagation and enum widening.
 * OpenAPI 3.0.3 section 4.7.24.2 adds null only to an explicit type in the same Schema Object.
 * All other constraints, including enum and composed schemas, retain their normal behavior.
 */
final class OpenApi30Dialect {
  static final Dialect INSTANCE = Dialect.builder(Dialects.getOpenApi30())
      .keyword(new NullableKeyword("type"))
      .keyword(new NullableKeyword("enum"))
      .build();

  private OpenApi30Dialect() {
  }

  private record NullableKeyword(String value) implements Keyword {
    @Override
    public String getValue() {
      return value;
    }

    @Override
    public KeywordValidator newValidator(SchemaLocation location, JsonNode node,
        Schema parent, SchemaContext context) {
      // These two validators do not resolve refs. Retain registry options but disable the
      // library's nullable shortcut by evaluating their base rules with a Draft-4 context.
      var base = new SchemaContext(Dialects.getDraft4(), context.getSchemaRegistry());
      if ("enum".equals(value)) {
        return new EnumValidator(location, node, parent, base);
      }
      boolean nullable = parent.getSchemaNode().path("nullable").isBoolean()
          && parent.getSchemaNode().path("nullable").asBoolean();
      return new TypeValidator(location, node, parent, base) {
        @Override
        public boolean equalsToSchemaType(JsonNode instance, ExecutionContext execution) {
          return instance.isNull() && nullable && node.isString()
              || super.equalsToSchemaType(instance, execution);
        }
      };
    }
  }
}
