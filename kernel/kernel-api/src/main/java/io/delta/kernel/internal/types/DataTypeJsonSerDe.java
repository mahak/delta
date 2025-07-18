/*
 * Copyright (2023) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.delta.kernel.internal.types;

import static io.delta.kernel.internal.util.Preconditions.checkArgument;
import static java.lang.String.format;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.delta.kernel.exceptions.KernelException;
import io.delta.kernel.internal.DeltaErrors;
import io.delta.kernel.internal.util.SchemaIterable;
import io.delta.kernel.types.*;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serialize and deserialize Delta data types {@link DataType} to JSON and from JSON class based on
 * the <a
 * href="https://github.com/delta-io/delta/blob/master/PROTOCOL.md#primitive-types">serialization
 * rules </a> outlined in the Delta Protocol.
 */
public class DataTypeJsonSerDe {
  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(
              new SimpleModule().addSerializer(StructType.class, new StructTypeSerializer()));

  private DataTypeJsonSerDe() {}

  /**
   * Serializes a {@link StructType} to a JSON string
   *
   * @param structType
   * @return
   */
  public static String serializeStructType(StructType structType) {
    try {
      return OBJECT_MAPPER.writeValueAsString(structType);
    } catch (JsonProcessingException ex) {
      throw new KernelException("Could not serialize StructType to JSON", ex);
    }
  }

  /**
   * Serializes a {@link DataType} to a JSON string according to the Delta Protocol. TODO: Only
   * reason why this API added was due to Flink-Kernel dependency. Currently Flink-Kernel uses the
   * Kernel DataType.toJson and Standalone DataType.fromJson to convert between types.
   *
   * @param dataType
   * @return JSON string representing the data type
   */
  public static String serializeDataType(DataType dataType) {
    try {
      StringWriter stringWriter = new StringWriter();
      JsonGenerator generator = OBJECT_MAPPER.createGenerator(stringWriter);
      writeDataType(generator, dataType);
      generator.flush();
      return stringWriter.toString();
    } catch (IOException ex) {
      throw new KernelException("Could not serialize DataType to JSON", ex);
    }
  }

  /**
   * Deserializes a JSON string representing a Delta data type to a {@link DataType}.
   *
   * @param structTypeJson JSON string representing a {@link StructType} data type
   */
  public static StructType deserializeStructType(String structTypeJson) {
    try {
      DataType parsedType = parseDataType(OBJECT_MAPPER.reader().readTree(structTypeJson));
      if (parsedType instanceof StructType) {
        return (StructType) parsedType;
      } else {
        throw new IllegalArgumentException(
            String.format(
                "Could not parse the following JSON as a valid StructType:\n%s", structTypeJson));
      }
    } catch (JsonProcessingException ex) {
      throw new KernelException(
          format("Could not parse schema given as JSON string: %s", structTypeJson), ex);
    }
  }

  /**
   * Parses a Delta data type from JSON. Data types can either be serialized as strings (for
   * primitive types) or as objects (for complex types).
   *
   * <p>For example:
   *
   * <pre>
   * // Map type field is serialized as:
   * {
   *   "name" : "f",
   *   "type" : {
   *     "type" : "map",
   *     "keyType" : "string",
   *     "valueType" : "string",
   *     "valueContainsNull" : true
   *   },
   *   "nullable" : true,
   *   "metadata" : { }
   * }
   *
   * // Integer type field serialized as:
   * {
   *   "name" : "a",
   *   "type" : "integer",
   *   "nullable" : false,
   *   "metadata" : { }
   * }
   *
   *
   * </pre>
   *
   * Note: Metadata for individual schema element (e.g. collations are handled when parsing
   * StructField)
   */
  static DataType parseDataType(JsonNode json) {
    switch (json.getNodeType()) {
      case STRING:
        // simple types are stored as just a string
        return nameToType(json.textValue());
      case OBJECT:
        // complex types (array, map, or struct are stored as JSON objects)
        String type = getStringField(json, "type");
        switch (type) {
          case "struct":
            return parseStructType(json);
          case "array":
            return parseArrayType(json);
          case "map":
            return parseMapType(json);
            // No default case here; fall through to the following error when no match
        }
      default:
        throw new IllegalArgumentException(
            String.format(
                "Could not parse the following JSON as a valid Delta data type:\n%s", json));
    }
  }

  /**
   * Parses an <a href="https://github.com/delta-io/delta/blob/master/PROTOCOL.md#array-type">array
   * type </a>
   */
  private static ArrayType parseArrayType(JsonNode json) {
    checkArgument(
        json.isObject() && json.size() == 3,
        "Expected JSON object with 3 fields for array data type but got:\n%s",
        json);
    boolean containsNull = getBooleanField(json, "containsNull");
    DataType dataType = parseDataType(getNonNullField(json, "elementType"));
    return new ArrayType(dataType, containsNull);
  }

  /**
   * Parses an <a href="https://github.com/delta-io/delta/blob/master/PROTOCOL.md#map-type">map type
   * </a>
   */
  private static MapType parseMapType(JsonNode json) {
    checkArgument(
        json.isObject() && json.size() == 4,
        "Expected JSON object with 4 fields for map data type but got:\n%s",
        json);
    boolean valueContainsNull = getBooleanField(json, "valueContainsNull");
    DataType keyType = parseDataType(getNonNullField(json, "keyType"));
    DataType valueType = parseDataType(getNonNullField(json, "valueType"));
    return new MapType(keyType, valueType, valueContainsNull);
  }

  /**
   * Parses an <a href="https://github.com/delta-io/delta/blob/master/PROTOCOL.md#struct-type">
   * struct type </a>
   */
  private static StructType parseStructType(JsonNode json) {
    checkArgument(
        json.isObject() && json.size() == 2,
        "Expected JSON object with 2 fields for struct data type but got:\n%s",
        json);
    JsonNode fieldsNode = getNonNullField(json, "fields");
    checkArgument(fieldsNode.isArray(), "Expected array for fieldName=%s in:\n%s", "fields", json);
    Iterator<JsonNode> fields = fieldsNode.elements();
    List<StructField> parsedFields = new ArrayList<>();
    while (fields.hasNext()) {
      parsedFields.add(parseStructField(fields.next()));
    }
    return new StructType(parsedFields);
  }

  /**
   * Parses an <a href="https://github.com/delta-io/delta/blob/master/PROTOCOL.md#struct-field">
   * struct field </a>
   */
  private static StructField parseStructField(JsonNode json) {
    checkArgument(json.isObject(), "Expected JSON object for struct field");
    String name = getStringField(json, "name");
    FieldMetadata metadata = parseFieldMetadata(json.get("metadata"));
    DataType type = parseDataType(getNonNullField(json, "type"));
    boolean nullable = getBooleanField(json, "nullable");

    StructField structField = new StructField(name, type, nullable, metadata);
    return fixupFieldLevelMetadata(structField, metadata);
  }

  /**
   *
   *
   * <pre>
   * // Collated string type field serialized as:
   * {
   *   "name" : "s",
   *   "type" : "string",
   *   "nullable", false,
   *   "metadata" : {
   *     "__COLLATIONS": { "s": "ICU.de_DE" }
   *   }
   * }
   *
   * // Array with collated strings field serialized as:
   * {
   *   "name" : "arr",
   *   "type" : {
   *     "type" : "array",
   *     "elementType" : "string",
   *     "containsNull" : false
   *   }
   *   "nullable" : false,
   *   "metadata" : {
   *     "__COLLATIONS": { "arr.element": "ICU.de_DE" }
   *   }
   * }
   * </pre>
   */
  private static StructField fixupFieldLevelMetadata(
      StructField structField, FieldMetadata metadata) {
    if (structField.getMetadata().contains(StructField.COLLATIONS_METADATA_KEY)
        || structField.getMetadata().contains(StructField.DELTA_TYPE_CHANGES_KEY)) {
      FieldMetadata collationsMetadata = metadata.getMetadata(StructField.COLLATIONS_METADATA_KEY);
      if (collationsMetadata == null) {
        collationsMetadata = FieldMetadata.empty();
      }
      Map<String, List<TypeChange>> pathToTypeChanges = parseTypeChangesFromMetadata(metadata);
      // Don't recurse on Structs because they would have already been constructed/fixed up in this
      // code path when assembling the child structs.
      SchemaIterable iterable =
          SchemaIterable.newSchemaIterableWithIgnoredRecursion(
              new StructType().add(structField), new Class<?>[] {StructType.class});
      Iterator<SchemaIterable.MutableSchemaElement> elements = iterable.newMutableIterator();
      String fieldName = structField.getName();

      while (elements.hasNext()) {
        SchemaIterable.MutableSchemaElement element = elements.next();
        String pathFromAncestor = element.getPathFromNearestStructFieldAncestor("");
        String pathWithFieldName =
            pathFromAncestor.isEmpty()
                ? fieldName
                : String.format("%s.%s", fieldName, pathFromAncestor);
        if (collationsMetadata.contains(pathWithFieldName)) {
          updateCollation(element, collationsMetadata, pathWithFieldName);
        }
        List<TypeChange> changes = pathToTypeChanges.get(pathFromAncestor);
        if (changes != null) {
          if (element.getField().getDataType().isNested()) {
            throw new KernelException(
                format("Invalid data type for type change: \"%s\"", element.getField()));
          }
          element.updateField(element.getField().withTypeChanges(changes));
        }
      }

      structField =
          iterable
              .getSchema()
              .at(0)
              .withNewMetadata(
                  FieldMetadata.builder()
                      .fromMetadata(metadata)
                      .remove(StructField.COLLATIONS_METADATA_KEY)
                      .remove(StructField.DELTA_TYPE_CHANGES_KEY)
                      .build());
    }
    return structField;
  }

  private static void updateCollation(
      SchemaIterable.MutableSchemaElement element,
      FieldMetadata collationsMetadata,
      String pathFromAncestor) {
    StructField field = element.getField();
    DataType fieldType = field.getDataType();
    if (!(fieldType instanceof StringType)) {
      throw new IllegalArgumentException(
          format("Invalid data type for collations: \"%s\"", fieldType));
    }
    StringType collatedType = new StringType(collationsMetadata.getString(pathFromAncestor));
    element.updateField(field.withDataType(collatedType));
  }

  /**
   * Processes a FieldMetadata and a map of paths to type changes to create TypeChange objects. This
   * function parses the type changes from the metadata and organizes them by path.
   */
  private static Map<String, List<TypeChange>> parseTypeChangesFromMetadata(
      FieldMetadata metadata) {
    Map<String, List<TypeChange>> pathToTypeChanges = new HashMap<>();
    if (metadata == null || !metadata.contains(StructField.DELTA_TYPE_CHANGES_KEY)) {
      return pathToTypeChanges;
    }
    FieldMetadata[] typeChangesArray =
        metadata.getMetadataArray(StructField.DELTA_TYPE_CHANGES_KEY);
    if (typeChangesArray == null) {
      return pathToTypeChanges;
    }
    for (FieldMetadata changeMetadata : typeChangesArray) {
      String fromTypeStr = changeMetadata.getString(StructField.FROM_TYPE_KEY);
      String toTypeStr = changeMetadata.getString(StructField.TO_TYPE_KEY);
      DataType fromType = nameToType(fromTypeStr);
      DataType toType = nameToType(toTypeStr);
      TypeChange typeChange = new TypeChange(fromType, toType);
      // Empty string is default because it means type change is directly on struct field.
      String path = "";
      if (changeMetadata.contains(StructField.FIELD_PATH_KEY)) {
        path = changeMetadata.getString(StructField.FIELD_PATH_KEY);
      }
      pathToTypeChanges.computeIfAbsent(path, k -> new ArrayList<>()).add(typeChange);
    }
    return pathToTypeChanges;
  }

  /** Parses a {@link FieldMetadata}. */
  private static FieldMetadata parseFieldMetadata(JsonNode json) {
    if (json == null || json.isNull()) {
      return FieldMetadata.empty();
    }

    checkArgument(json.isObject(), "Expected JSON object for struct field metadata");
    final Iterator<Map.Entry<String, JsonNode>> iterator = json.fields();
    final FieldMetadata.Builder builder = FieldMetadata.builder();
    while (iterator.hasNext()) {
      Map.Entry<String, JsonNode> entry = iterator.next();
      JsonNode value = entry.getValue();
      String key = entry.getKey();

      if (value.isNull()) {
        builder.putNull(key);
      } else if (value.isIntegralNumber()) { // covers both int and long
        builder.putLong(key, value.longValue());
      } else if (value.isDouble()) {
        builder.putDouble(key, value.doubleValue());
      } else if (value.isBoolean()) {
        builder.putBoolean(key, value.booleanValue());
      } else if (value.isTextual()) {
        builder.putString(key, value.textValue());
      } else if (value.isObject()) {
        builder.putFieldMetadata(key, parseFieldMetadata(value));
      } else if (value.isArray()) {
        final Iterator<JsonNode> fields = value.elements();
        if (!fields.hasNext()) {
          // If it is an empty array, we cannot infer its element type.
          // We put an empty Array[Long].
          builder.putLongArray(key, new Long[0]);
        } else {
          final JsonNode head = fields.next();
          if (head.isInt()) {
            builder.putLongArray(
                key, buildList(value, node -> (long) node.intValue()).toArray(new Long[0]));
          } else if (head.isDouble()) {
            builder.putDoubleArray(
                key, buildList(value, JsonNode::doubleValue).toArray(new Double[0]));
          } else if (head.isBoolean()) {
            builder.putBooleanArray(
                key, buildList(value, JsonNode::booleanValue).toArray(new Boolean[0]));
          } else if (head.isTextual()) {
            builder.putStringArray(
                key, buildList(value, JsonNode::textValue).toArray(new String[0]));
          } else if (head.isObject()) {
            builder.putFieldMetadataArray(
                key,
                buildList(value, DataTypeJsonSerDe::parseFieldMetadata)
                    .toArray(new FieldMetadata[0]));
          } else {
            throw new IllegalArgumentException(
                String.format("Unsupported type for Array as field metadata value: %s", value));
          }
        }
      } else {
        throw new IllegalArgumentException(
            String.format("Unsupported type for field metadata value: %s", value));
      }
    }
    return builder.build();
  }

  /**
   * For an array JSON node builds a {@link List} using the provided {@code accessor} for each
   * element.
   */
  private static <T> List<T> buildList(JsonNode json, Function<JsonNode, T> accessor) {
    List<T> result = new ArrayList<>();
    Iterator<JsonNode> elements = json.elements();
    while (elements.hasNext()) {
      result.add(accessor.apply(elements.next()));
    }
    return result;
  }

  private static String FIXED_DECIMAL_REGEX = "decimal\\(\\s*(\\d+)\\s*,\\s*(\\-?\\d+)\\s*\\)";
  private static Pattern FIXED_DECIMAL_PATTERN = Pattern.compile(FIXED_DECIMAL_REGEX);

  /** Parses primitive string type names to a {@link DataType} */
  private static DataType nameToType(String name) {
    if (BasePrimitiveType.isPrimitiveType(name)) {
      return BasePrimitiveType.createPrimitive(name);
    } else if (name.equals("decimal")) {
      return DecimalType.USER_DEFAULT;
    } else if ("void".equalsIgnoreCase(name)) {
      // Earlier versions of Delta had VOID type which is not specified in Delta Protocol.
      // It is not readable or writable. Throw a user-friendly error message.
      throw DeltaErrors.voidTypeEncountered();
    } else {
      // decimal has a special pattern with a precision and scale
      Matcher decimalMatcher = FIXED_DECIMAL_PATTERN.matcher(name);
      if (decimalMatcher.matches()) {
        int precision = Integer.parseInt(decimalMatcher.group(1));
        int scale = Integer.parseInt(decimalMatcher.group(2));
        return new DecimalType(precision, scale);
      }

      // We have encountered a type that is beyond the specification of the protocol
      // checks. This must be an invalid type (according to protocol) and
      // not an unsupported data type by Kernel.
      throw new IllegalArgumentException(
          String.format("%s is not a supported delta data type", name));
    }
  }

  private static JsonNode getNonNullField(JsonNode rootNode, String fieldName) {
    JsonNode node = rootNode.get(fieldName);
    if (node == null || node.isNull()) {
      throw new IllegalArgumentException(
          String.format("Expected non-null for fieldName=%s in:\n%s", fieldName, rootNode));
    }
    return node;
  }

  private static String getStringField(JsonNode rootNode, String fieldName) {
    JsonNode node = getNonNullField(rootNode, fieldName);
    checkArgument(
        node.isTextual(), "Expected string for fieldName=%s in:\n%s", fieldName, rootNode);
    return node.textValue(); // double check this only works for string values! and isTextual()!
  }

  private static boolean getBooleanField(JsonNode rootNode, String fieldName) {
    JsonNode node = getNonNullField(rootNode, fieldName);
    checkArgument(
        node.isBoolean(), "Expected boolean for fieldName=%s in:\n%s", fieldName, rootNode);
    return node.booleanValue();
  }

  protected static class StructTypeSerializer extends StdSerializer<StructType> {
    public StructTypeSerializer() {
      super(StructType.class);
    }

    @Override
    public void serialize(StructType structType, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      writeDataType(gen, structType);
    }
  }

  private static void writeDataType(JsonGenerator gen, DataType dataType) throws IOException {
    if (dataType instanceof StructType) {
      writeStructType(gen, (StructType) dataType);
    } else if (dataType instanceof ArrayType) {
      writeArrayType(gen, (ArrayType) dataType);
    } else if (dataType instanceof MapType) {
      writeMapType(gen, (MapType) dataType);
    } else if (dataType instanceof DecimalType) {
      DecimalType decimalType = (DecimalType) dataType;
      gen.writeString(format("decimal(%d,%d)", decimalType.getPrecision(), decimalType.getScale()));
    } else {
      gen.writeString(dataType.toString());
    }
  }

  private static void writeArrayType(JsonGenerator gen, ArrayType arrayType) throws IOException {
    gen.writeStartObject();
    gen.writeStringField("type", "array");
    gen.writeFieldName("elementType");
    writeDataType(gen, arrayType.getElementType());
    gen.writeBooleanField("containsNull", arrayType.containsNull());
    gen.writeEndObject();
  }

  private static void writeMapType(JsonGenerator gen, MapType mapType) throws IOException {
    gen.writeStartObject();
    gen.writeStringField("type", "map");
    gen.writeFieldName("keyType");
    writeDataType(gen, mapType.getKeyType());
    gen.writeFieldName("valueType");
    writeDataType(gen, mapType.getValueType());
    gen.writeBooleanField("valueContainsNull", mapType.isValueContainsNull());
    gen.writeEndObject();
  }

  private static void writeStructType(JsonGenerator gen, StructType structType) throws IOException {
    gen.writeStartObject();
    gen.writeStringField("type", "struct");
    gen.writeArrayFieldStart("fields");
    for (StructField field : structType.fields()) {
      writeStructField(gen, field);
    }
    gen.writeEndArray();
    gen.writeEndObject();
  }

  private static void writeStructField(JsonGenerator gen, StructField field) throws IOException {
    gen.writeStartObject();
    gen.writeStringField("name", field.getName());
    gen.writeFieldName("type");
    writeDataType(gen, field.getDataType());
    gen.writeBooleanField("nullable", field.isNullable());
    gen.writeFieldName("metadata");
    writeFieldMetadata(gen, field.getMetadata());
    gen.writeEndObject();
  }

  private static void writeFieldMetadata(JsonGenerator gen, FieldMetadata metadata)
      throws IOException {
    gen.writeStartObject();
    for (Map.Entry<String, Object> entry : metadata.getEntries().entrySet()) {
      gen.writeFieldName(entry.getKey());
      Object value = entry.getValue();
      if (value instanceof Long) {
        gen.writeNumber((Long) value);
      } else if (value instanceof Double) {
        gen.writeNumber((Double) value);
      } else if (value instanceof Boolean) {
        gen.writeBoolean((Boolean) value);
      } else if (value instanceof String) {
        gen.writeString((String) value);
      } else if (value instanceof FieldMetadata) {
        writeFieldMetadata(gen, (FieldMetadata) value);
      } else if (value instanceof Long[]) {
        gen.writeStartArray();
        for (Long v : (Long[]) value) {
          gen.writeNumber(v);
        }
        gen.writeEndArray();
      } else if (value instanceof Double[]) {
        gen.writeStartArray();
        for (Double v : (Double[]) value) {
          gen.writeNumber(v);
        }
        gen.writeEndArray();
      } else if (value instanceof Boolean[]) {
        gen.writeStartArray();
        for (Boolean v : (Boolean[]) value) {
          gen.writeBoolean(v);
        }
        gen.writeEndArray();
      } else if (value instanceof String[]) {
        gen.writeStartArray();
        for (String v : (String[]) value) {
          gen.writeString(v);
        }
        gen.writeEndArray();
      } else if (value instanceof FieldMetadata[]) {
        gen.writeStartArray();
        for (FieldMetadata v : (FieldMetadata[]) value) {
          writeFieldMetadata(gen, v);
        }
        gen.writeEndArray();
      } else if (value == null) {
        gen.writeNull();
      } else {
        throw new IllegalArgumentException(
            format("Unsupported type for field metadata value: %s", value));
      }
    }
    gen.writeEndObject();
  }
}
