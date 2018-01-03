package com.blueapron.connect.protobuf;


import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.google.protobuf.util.Timestamps;

class ProtobufData {
  private final Class<? extends com.google.protobuf.GeneratedMessageV3> clazz;
  private final Method newBuilder;
  private final Schema schema;

  private GeneratedMessageV3.Builder getBuilder() {
    try {
      return (GeneratedMessageV3.Builder) newBuilder.invoke(Object.class);
    } catch (Exception e) {
      throw new ConnectException("Not a valid proto3 builder", e);
    }
  }

  private Message getMessage(byte[] value) {
    try {
      return getBuilder().mergeFrom(value).build();
    } catch (InvalidProtocolBufferException e) {
      throw new DataException("Invalid protobuf data", e);
    }
  }

  ProtobufData(Class<? extends com.google.protobuf.GeneratedMessageV3> clazz) {
    this.clazz = clazz;

    try {
      this.newBuilder = clazz.getDeclaredMethod("newBuilder");
    } catch (NoSuchMethodException e) {
      throw new ConnectException("Proto class " + clazz.getCanonicalName() + " is not a valid proto3 message class", e);
    }

    this.schema = toConnectSchema(getBuilder().getDefaultInstanceForType());
  }

  SchemaAndValue toConnectData(byte[] value) {
    Message message = getMessage(value);
    if (message == null) {
      return SchemaAndValue.NULL;
    }

    return new SchemaAndValue(this.schema, toConnectData(this.schema, message));
  }

  private Schema toConnectSchema(Message message) {
    final SchemaBuilder builder = SchemaBuilder.struct();
    final List<Descriptors.FieldDescriptor> fieldDescriptorList = message.getDescriptorForType().getFields();
    for (Descriptors.FieldDescriptor descriptor : fieldDescriptorList) {
      builder.field(descriptor.getName(), toConnectSchema(descriptor));
    }

    return builder.build();
  }

  private boolean isTimestampDescriptor(Descriptors.FieldDescriptor descriptor) {
    return descriptor.getMessageType().getFullName().equals("google.protobuf.Timestamp");
  }

  private boolean isDateDescriptor(Descriptors.FieldDescriptor descriptor) {
    return descriptor.getMessageType().getFullName().equals("google.type.Date");
  }

  private Schema toConnectSchema(Descriptors.FieldDescriptor descriptor) {
    final SchemaBuilder builder;

    switch (descriptor.getType()) {
      case INT32: {
        builder = SchemaBuilder.int32();
        break;
      }

      case INT64: {
        builder = SchemaBuilder.int64();
        break;
      }

      case FLOAT: {
        builder = SchemaBuilder.float32();
        break;
      }

      case DOUBLE: {
        builder = SchemaBuilder.float64();
        break;
      }

      case BOOL: {
        builder = SchemaBuilder.bool();
        break;
      }

      // TODO - Do we need to support byte or short?
      /*case INT8:
        // Encoded as an Integer
        converted = value == null ? null : ((Integer) value).byteValue();
        break;
      case INT16:
        // Encoded as an Integer
        converted = value == null ? null : ((Integer) value).shortValue();
        break;*/

      case STRING:
        builder = SchemaBuilder.string();
        break;

      case BYTES:
        builder = SchemaBuilder.bytes();
        break;

      case ENUM:
        builder = SchemaBuilder.string();
        break;

      case MESSAGE: {
        if (isTimestampDescriptor(descriptor)) {
          builder = Timestamp.builder();
          break;
        }

        if (isDateDescriptor(descriptor)) {
          builder = Date.builder();
          break;
        }

        builder = SchemaBuilder.struct();
        for (Descriptors.FieldDescriptor fieldDescriptor : descriptor.getMessageType().getFields()) {
          builder.field(fieldDescriptor.getName(), toConnectSchema(fieldDescriptor));
        }

        break;
      }

      default:
        throw new DataException("Unknown Connect schema type: " + descriptor.getType());
    }

    builder.optional();
    Schema schema = builder.build();

    if (descriptor.isRepeated()) {
      final SchemaBuilder arrayBuilder = SchemaBuilder.array(schema);
      arrayBuilder.optional();
      schema = arrayBuilder.build();
    }

    return schema;
  }

  private boolean isProtobufTimestamp(Schema schema) {
    return Timestamp.SCHEMA.name().equals(schema.name());
  }

  private boolean isProtobufDate(Schema schema) {
    return Date.SCHEMA.name().equals(schema.name());
  }

  Object toConnectData(Schema schema, Object value) {
    if (value == null) {
      return null;
    }

    try {
      if (isProtobufTimestamp(schema)) {
        com.google.protobuf.Timestamp timestamp = (com.google.protobuf.Timestamp) value;
        return Timestamp.toLogical(schema, Timestamps.toMillis(timestamp));
      }

      if (isProtobufDate(schema)) {
        com.google.type.Date date = (com.google.type.Date) value;
        return ProtobufUtils.convertFromGoogleDate(date);
      }

      Object converted = null;
      switch (schema.type()) {
        // Pass through types
        case INT32: {
          Integer intValue = (Integer) value; // Validate type
          converted = value;
          break;
        }

        case INT64: {
          Long longValue = (Long) value; // Validate type
          converted = value;
          break;
        }

        case FLOAT32: {
          Float floatValue = (Float) value; // Validate type
          converted = value;
          break;
        }

        case FLOAT64: {
          Double doubleValue = (Double) value; // Validate type
          converted = value;
          break;
        }

        case BOOLEAN: {
          Boolean boolValue = (Boolean) value; // Validate type
          converted = value;
          break;
        }

        // TODO - Do we need to support byte or short?
        /*case INT8:
          // Encoded as an Integer
          converted = value == null ? null : ((Integer) value).byteValue();
          break;
        case INT16:
          // Encoded as an Integer
          converted = value == null ? null : ((Integer) value).shortValue();
          break;*/

        case STRING:
          if (value instanceof String) {
            converted = value;
          } else if (value instanceof CharSequence
            || value instanceof Enum
            || value instanceof Descriptors.EnumValueDescriptor) {
            converted = value.toString();
          } else {
            throw new DataException("Invalid class for string type, expecting String or "
              + "CharSequence but found " + value.getClass());
          }
          break;

        case BYTES:
          if (value instanceof byte[]) {
            converted = ByteBuffer.wrap((byte[]) value);
          } else if (value instanceof ByteBuffer) {
            converted = value;
          } else {
            throw new DataException("Invalid class for bytes type, expecting byte[] or ByteBuffer "
              + "but found " + value.getClass());
          }
          break;

        // Used for repeated types
        case ARRAY: {
          final Schema valueSchema = schema.valueSchema();
          final Collection<Object> original = (Collection<Object>) value;
          final List<Object> result = new ArrayList<Object>(original.size());
          for (Object elem : original) {
            result.add(toConnectData(valueSchema, elem));
          }
          converted = result;
          break;
        }

        case STRUCT: {
          final Message message = (Message) value; // Validate type
          final Struct result = new Struct(schema.schema());
          final Map<Descriptors.FieldDescriptor, Object> fieldsMap = message.getAllFields();
          for (Map.Entry<Descriptors.FieldDescriptor, Object> pair : fieldsMap.entrySet()) {
            final Descriptors.FieldDescriptor fieldDescriptor = pair.getKey();
            final String fieldName = fieldDescriptor.getName();
            final Field field = schema.field(fieldName);
            final Object obj = pair.getValue();
            result.put(fieldName, toConnectData(field.schema(), obj));
          }

          converted = result;
          break;
        }

        default:
          throw new DataException("Unknown Connect schema type: " + schema.type());
      }

      return converted;
    } catch (ClassCastException e) {
      throw new DataException("Invalid type for " + schema.type() + ": " + value.getClass());
    }
  }

  byte[] fromConnectData(Object value) {
    final com.google.protobuf.GeneratedMessageV3.Builder builder = getBuilder();
    final Struct struct = (Struct) value;

    for (Field field : this.schema.fields()) {
      fromConnectData(builder, field, struct.get(field));
    }

    return builder.build().toByteArray();
  }

  private void fromConnectData(com.google.protobuf.GeneratedMessageV3.Builder builder, Field field, Object value) {
    final Descriptors.FieldDescriptor fieldDescriptor = builder.getDescriptorForType().findFieldByName(field.name());
    if (fieldDescriptor == null) {
      // Ignore unknown fields
      return;
    }

    final Schema schema = field.schema();
    final Schema.Type schemaType = schema.type();

    try {
      switch (schemaType) {
        case INT32: {
          if (isProtobufDate(schema)) {
            final java.util.Date date = (java.util.Date) value;
            builder.setField(fieldDescriptor, ProtobufUtils.convertToGoogleDate(date));
            return;
          }

          final Integer intValue = (Integer) value; // Check for correct type
          builder.setField(fieldDescriptor, intValue);
          return;
        }

        case INT64: {
          if (isProtobufTimestamp(schema)) {
            final java.util.Date timestamp = (java.util.Date) value;
            builder.setField(fieldDescriptor, Timestamps.fromMillis(Timestamp.fromLogical(schema, timestamp)));
            return;
          }

          final Long longValue = (Long) value; // Check for correct type
          builder.setField(fieldDescriptor, longValue);
          return;
        }

        case FLOAT32: {
          final Float floatValue = (Float) value; // Check for correct type
          builder.setField(fieldDescriptor, floatValue);
          return;
        }

        case FLOAT64: {
          final Double doubleValue = (Double) value; // Check for correct type
          builder.setField(fieldDescriptor, doubleValue);
          return;
        }

        case BOOLEAN: {
          final Boolean boolValue = (Boolean) value; // Check for correct type
          builder.setField(fieldDescriptor, boolValue);
          return;
        }

        case STRING: {
          final String stringValue = (String) value; // Check for correct type
          builder.setField(fieldDescriptor, stringValue);
          return;
        }

        case BYTES: {
          final ByteBuffer bytesValue = value instanceof byte[] ? ByteBuffer.wrap((byte[]) value) :
            (ByteBuffer) value;
          builder.setField(fieldDescriptor, ByteString.copyFrom(bytesValue));
          return;
        }

        default:
          throw new DataException("Unknown schema type: " + schema.type());
      }
    } catch (ClassCastException e) {
      throw new DataException("Invalid type for " + schema.type() + ": " + value.getClass());
    }
  }
}
