package com.sap.olingo.jpa.processor.core.processor.deserializer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.olingo.commons.api.Constants;
import org.apache.olingo.commons.api.data.ComplexValue;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.data.Linked;
import org.apache.olingo.commons.api.data.Parameter;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.EdmAction;
import org.apache.olingo.commons.api.edm.EdmComplexType;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmEnumType;
import org.apache.olingo.commons.api.edm.EdmMapping;
import org.apache.olingo.commons.api.edm.EdmNavigationProperty;
import org.apache.olingo.commons.api.edm.EdmParameter;
import org.apache.olingo.commons.api.edm.EdmPrimitiveType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeException;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.EdmProperty;
import org.apache.olingo.commons.api.edm.EdmStructuredType;
import org.apache.olingo.commons.api.edm.EdmType;
import org.apache.olingo.commons.api.edm.EdmTypeDefinition;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.constants.EdmTypeKind;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.deserializer.DeserializerException;
import org.apache.olingo.server.api.deserializer.DeserializerException.MessageKeys;
import org.apache.olingo.server.api.deserializer.DeserializerResult;
import org.apache.olingo.server.api.deserializer.ODataDeserializer;
import org.apache.olingo.server.core.deserializer.DeserializerResultImpl;
import org.apache.olingo.server.core.deserializer.helper.ExpandTreeBuilder;
import org.apache.olingo.server.core.deserializer.helper.ExpandTreeBuilderImpl;
import org.apache.olingo.server.core.serializer.utils.ContentTypeHelper;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class JsonDeserializer implements ODataDeserializer {

  private static final String ODATA_ANNOTATION_MARKER = "@";
  private static final String ODATA_CONTROL_INFORMATION_PREFIX = "@odata.";

  private final boolean isIEEE754Compatible;
  private ServiceMetadata serviceMetadata;
  private final ExpandTreeBuilder expandBuilder;

  public JsonDeserializer(final ContentType contentType) {
    this(contentType, null);
  }

  public JsonDeserializer(final ContentType contentType, final ServiceMetadata serviceMetadata) {
    isIEEE754Compatible = ContentTypeHelper.isODataIEEE754Compatible(contentType);
    this.serviceMetadata = serviceMetadata;
    this.expandBuilder = ExpandTreeBuilderImpl.create();
  }

  @Override
  public DeserializerResult entityCollection(final InputStream stream, final EdmEntityType edmEntityType)
      throws DeserializerException {
    try {
      return DeserializerResultImpl.with()
          .entityCollection(consumeEntityCollectionNode(edmEntityType, parseJsonTree(stream), null)).build();
    } catch (final IOException e) {
      throw wrapParseException(e);
    }
  }

  private EntityCollection consumeEntityCollectionNode(final EdmEntityType edmEntityType, final ObjectNode tree,
      final ExpandTreeBuilder expandBuilder) throws DeserializerException {
    EntityCollection entitySet = new EntityCollection();

    // Consume entities
    JsonNode jsonNode = tree.get(Constants.VALUE);
    if (jsonNode != null) {
      entitySet.getEntities().addAll(consumeEntitySetArray(edmEntityType, jsonNode, expandBuilder));
      tree.remove(Constants.VALUE);
    } else {
      throw new DeserializerException("Could not find value array.",
          DeserializerException.MessageKeys.VALUE_ARRAY_NOT_PRESENT);
    }

    if (tree.isObject()) {
      removeAnnotations(tree);
    }
    assertJsonNodeIsEmpty(tree);

    return entitySet;
  }

  private List<Entity> consumeEntitySetArray(final EdmEntityType edmEntityType, final JsonNode jsonNode,
      final ExpandTreeBuilder expandBuilder) throws DeserializerException {
    if (jsonNode.isArray()) {
      List<Entity> entities = new ArrayList<Entity>();
      for (JsonNode arrayElement : jsonNode) {
        if (arrayElement.isArray() || arrayElement.isValueNode()) {
          throw new DeserializerException(
              "Nested Arrays and primitive values are not allowed for an entity value.",
              DeserializerException.MessageKeys.INVALID_ENTITY);
        }
        EdmEntityType derivedEdmEntityType = (EdmEntityType) getDerivedType(edmEntityType, arrayElement);
        entities.add(consumeEntityNode(derivedEdmEntityType, (ObjectNode) arrayElement, expandBuilder));
      }
      return entities;
    } else {
      throw new DeserializerException("The content of the value tag must be an Array but is not.",
          DeserializerException.MessageKeys.VALUE_TAG_MUST_BE_AN_ARRAY);
    }
  }

  @Override
  public DeserializerResult entity(final InputStream stream, final EdmEntityType edmEntityType)
      throws DeserializerException {
    try {
      final ObjectNode tree = parseJsonTree(stream);

      EdmEntityType derivedEdmEntityType = (EdmEntityType) getDerivedType(edmEntityType, tree);

      return DeserializerResultImpl.with().entity(consumeEntityNode(derivedEdmEntityType, tree, expandBuilder))
          .expandOption(expandBuilder.build()).build();
    } catch (final IOException e) {
      throw wrapParseException(e);
    }
  }

  private Entity consumeEntityNode(final EdmEntityType edmEntityType, final ObjectNode tree,
      final ExpandTreeBuilder expandBuilder) throws DeserializerException {
    Entity entity = new Entity();
    entity.setType(edmEntityType.getFullQualifiedName().getFullQualifiedNameAsString());

    // Check and consume all Properties
    consumeEntityProperties(edmEntityType, tree, entity);

    // Check and consume all expanded Navigation Properties
    consumeExpandedNavigationProperties(edmEntityType, tree, entity, expandBuilder);

    // consume remaining json node fields
    consumeRemainingJsonNodeFields(edmEntityType, tree, entity);

    assertJsonNodeIsEmpty(tree);

    return entity;
  }

  @Override
  public DeserializerResult actionParameters(final InputStream stream, final EdmAction edmAction)
      throws DeserializerException {
    try {
      ObjectNode tree = parseJsonTree(stream);
      Map<String, Parameter> parameters = consumeParameters(edmAction, tree);

      if (tree.isObject()) {
        removeAnnotations(tree);
      }
      assertJsonNodeIsEmpty(tree);
      return DeserializerResultImpl.with().actionParameters(parameters).build();

    } catch (final IOException e) {
      throw wrapParseException(e);
    }
  }

  private ObjectNode parseJsonTree(final InputStream stream) throws IOException, DeserializerException {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.configure(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, true);
    JsonParser parser = new JsonFactory(objectMapper).createParser(stream);
    final JsonNode tree = parser.getCodec().readTree(parser);
    if (tree == null || !tree.isObject()) {
      throw new DeserializerException("Invalid JSON syntax.",
          DeserializerException.MessageKeys.JSON_SYNTAX_EXCEPTION);
    }
    return (ObjectNode) tree;
  }

  private Map<String, Parameter> consumeParameters(final EdmAction edmAction, final ObjectNode node)
      throws DeserializerException {
    List<String> parameterNames = edmAction.getParameterNames();
    if (edmAction.isBound()) {
      // The binding parameter must not occur in the payload.
      parameterNames = parameterNames.subList(1, parameterNames.size());
    }
    Map<String, Parameter> parameters = new LinkedHashMap<String, Parameter>();
    for (final String paramName : parameterNames) {
      final EdmParameter edmParameter = edmAction.getParameter(paramName);

      switch (edmParameter.getType().getKind()) {
      case PRIMITIVE:
      case DEFINITION:
      case ENUM:
      case COMPLEX:
      case ENTITY:
        Parameter parameter = createParameter(node.get(paramName), paramName, edmParameter);
        parameters.put(paramName, parameter);
        node.remove(paramName);
        break;
      default:
        throw new DeserializerException(
            "Invalid type kind " + edmParameter.getType().getKind() + " for action parameter: " + paramName,
            DeserializerException.MessageKeys.INVALID_ACTION_PARAMETER_TYPE, paramName);
      }
    }
    return parameters;
  }

  private Parameter createParameter(final JsonNode node, final String paramName, final EdmParameter edmParameter)
      throws DeserializerException {
    Parameter parameter = new Parameter();
    parameter.setName(paramName);
    if (node == null || node.isNull()) {
      if (!edmParameter.isNullable()) {
        throw new DeserializerException("Non-nullable parameter not present or null: " + paramName,
            MessageKeys.INVALID_NULL_PARAMETER, paramName);
      }
      if (edmParameter.isCollection()) {
        throw new DeserializerException("Collection must not be null for parameter: " + paramName,
            MessageKeys.INVALID_NULL_PARAMETER, paramName);
      }
      parameter.setValue(ValueType.PRIMITIVE, null);
    } else if (edmParameter.getType().getKind() == EdmTypeKind.ENTITY) {
      if (edmParameter.isCollection()) {
        EntityCollection entityCollection = new EntityCollection();
        entityCollection.getEntities()
            .addAll(consumeEntitySetArray((EdmEntityType) edmParameter.getType(), node, null));
        parameter.setValue(ValueType.COLLECTION_ENTITY, entityCollection);
      } else {
        final Entity entity = consumeEntityNode((EdmEntityType) edmParameter.getType(), (ObjectNode) node,
            null);
        parameter.setValue(ValueType.ENTITY, entity);
      }
    } else {
      final Property property = consumePropertyNode(edmParameter.getName(), edmParameter.getType(),
          edmParameter.isCollection(), edmParameter.isNullable(), edmParameter.getMaxLength(),
          edmParameter.getPrecision(), edmParameter.getScale(), true, edmParameter.getMapping(), node);
      parameter.setValue(property.getValueType(), property.getValue());
      parameter.setType(property.getType());
    }
    return parameter;
  }

  /** Reads a parameter value from a String. */
  public Parameter parameter(final String content, final EdmParameter parameter) throws DeserializerException {
    try {
      JsonParser parser = new JsonFactory(
          new ObjectMapper().configure(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, true))
              .createParser(content);
      JsonNode node = parser.getCodec().readTree(parser);
      if (node == null) {
        throw new DeserializerException("Invalid JSON syntax.",
            DeserializerException.MessageKeys.JSON_SYNTAX_EXCEPTION);
      }
      final Parameter result = createParameter(node, parameter.getName(), parameter);
      if (node.isObject()) {
        removeAnnotations((ObjectNode) node);
        assertJsonNodeIsEmpty(node);
      }
      return result;
    } catch (final IOException e) {
      throw wrapParseException(e);
    }
  }

  /**
   * Consumes all remaining fields of Json ObjectNode and tries to map found
   * values to according Entity fields and omits OData fields to be ignored
   * (e.g., control information).
   *
   * @param edmEntityType
   * edm entity type which for which the json node is consumed
   * @param node
   * json node which is consumed
   * @param entity
   * entity instance which is filled
   * @throws DeserializerException
   * if an exception during consumation occurs
   */
  private void consumeRemainingJsonNodeFields(final EdmEntityType edmEntityType, final ObjectNode node,
      final Entity entity) throws DeserializerException {
    final List<String> toRemove = new ArrayList<String>();
    Iterator<Entry<String, JsonNode>> fieldsIterator = node.fields();
    while (fieldsIterator.hasNext()) {
      Entry<String, JsonNode> field = fieldsIterator.next();

      if (field.getKey().contains(Constants.JSON_BIND_LINK_SUFFIX)) {
        Link bindingLink = consumeBindingLink(field.getKey(), field.getValue(), edmEntityType);
        entity.getNavigationBindings().add(bindingLink);
        toRemove.add(field.getKey());
      }
    }
    // remove here to avoid iterator issues.
    node.remove(toRemove);

    removeAnnotations(node);
  }

  private void consumeEntityProperties(final EdmEntityType edmEntityType, final ObjectNode node, final Entity entity)
      throws DeserializerException {
    List<String> propertyNames = edmEntityType.getPropertyNames();
    for (String propertyName : propertyNames) {
      JsonNode jsonNode = node.get(propertyName);
      if (jsonNode != null) {
        EdmProperty edmProperty = (EdmProperty) edmEntityType.getProperty(propertyName);
        if (jsonNode.isNull() && !edmProperty.isNullable()) {
          throw new DeserializerException("Property: " + propertyName + " must not be null.",
              DeserializerException.MessageKeys.INVALID_NULL_PROPERTY, propertyName);
        }
        Property property = consumePropertyNode(edmProperty.getName(), edmProperty.getType(),
            edmProperty.isCollection(), edmProperty.isNullable(), edmProperty.getMaxLength(),
            edmProperty.getPrecision(), edmProperty.getScale(), edmProperty.isUnicode(),
            edmProperty.getMapping(), jsonNode);
        entity.addProperty(property);
        node.remove(propertyName);
      }
    }
  }

  /**
   * Changed from EdmEntityType to EdmStructuredType. To be able to consume
   * Navigation Links also at complex properties.
   * 
   * @param edmStructuredType
   * @param node
   * @param entity
   * @param expandBuilder
   * @throws DeserializerException
   */
  private void consumeExpandedNavigationProperties(final EdmStructuredType edmStructuredType, final ObjectNode node,
      final Linked entity, final ExpandTreeBuilder expandBuilder) throws DeserializerException {

    List<String> navigationPropertyNames = edmStructuredType.getNavigationPropertyNames();
    for (String navigationPropertyName : navigationPropertyNames) {
      // read expanded navigation property
      JsonNode jsonNode = node.get(navigationPropertyName);
      if (jsonNode != null) {
        EdmNavigationProperty edmNavigationProperty = edmStructuredType
            .getNavigationProperty(navigationPropertyName);
        checkNotNullOrValidNull(jsonNode, edmNavigationProperty);

        Link link = createLink(expandBuilder, navigationPropertyName, jsonNode, edmNavigationProperty);
        entity.getNavigationLinks().add(link);
        node.remove(navigationPropertyName);
      }
    }
  }

  /**
   * Check if jsonNode is not null or if null but nullable or collection
   * navigationProperty
   *
   * @param jsonNode
   * related json node
   * @param edmNavigationProperty
   * related navigation property
   * @throws DeserializerException
   * if jsonNode is not null or if null but nullable or collection
   * navigationProperty
   */
  private void checkNotNullOrValidNull(final JsonNode jsonNode, final EdmNavigationProperty edmNavigationProperty)
      throws DeserializerException {
    boolean isNullable = edmNavigationProperty.isNullable();
    if ((jsonNode.isNull() && !isNullable) || (jsonNode.isNull() && edmNavigationProperty.isCollection())) {
      throw new DeserializerException("Property: " + edmNavigationProperty.getName() + " must not be null.",
          MessageKeys.INVALID_NULL_PROPERTY, edmNavigationProperty.getName());
    }
  }

  private Link createLink(final ExpandTreeBuilder expandBuilder, final String navigationPropertyName,
      final JsonNode jsonNode, final EdmNavigationProperty edmNavigationProperty) throws DeserializerException {
    Link link = new Link();
    link.setTitle(navigationPropertyName);
    final ExpandTreeBuilder childExpandBuilder = (expandBuilder != null)
        ? expandBuilder.expand(edmNavigationProperty) : null;
    EdmEntityType derivedEdmEntityType = (EdmEntityType) getDerivedType(edmNavigationProperty.getType(), jsonNode);
    if (jsonNode.isArray() && edmNavigationProperty.isCollection()) {
      link.setType(Constants.ENTITY_SET_NAVIGATION_LINK_TYPE);
      EntityCollection inlineEntitySet = new EntityCollection();
      inlineEntitySet.getEntities()
          .addAll(consumeEntitySetArray(derivedEdmEntityType, jsonNode, childExpandBuilder));
      link.setInlineEntitySet(inlineEntitySet);
    } else if (!jsonNode.isArray() && (!jsonNode.isValueNode() || jsonNode.isNull())
        && !edmNavigationProperty.isCollection()) {
      link.setType(Constants.ENTITY_NAVIGATION_LINK_TYPE);
      if (!jsonNode.isNull()) {
        Entity inlineEntity = consumeEntityNode(derivedEdmEntityType, (ObjectNode) jsonNode,
            childExpandBuilder);
        link.setInlineEntity(inlineEntity);
      }
    } else {
      throw new DeserializerException(
          "Invalid value: " + jsonNode.getNodeType() + " for expanded navigation property: "
              + navigationPropertyName,
          MessageKeys.INVALID_VALUE_FOR_NAVIGATION_PROPERTY, navigationPropertyName);
    }
    return link;
  }

  private Link consumeBindingLink(final String key, final JsonNode jsonNode, final EdmEntityType edmEntityType)
      throws DeserializerException {
    String[] splitKey = key.split(ODATA_ANNOTATION_MARKER);
    String navigationPropertyName = splitKey[0];
    EdmNavigationProperty edmNavigationProperty = edmEntityType.getNavigationProperty(navigationPropertyName);
    if (edmNavigationProperty == null) {
      throw new DeserializerException("Invalid navigationPropertyName: " + navigationPropertyName,
          DeserializerException.MessageKeys.NAVIGATION_PROPERTY_NOT_FOUND, navigationPropertyName);
    }
    Link bindingLink = new Link();
    bindingLink.setTitle(navigationPropertyName);

    if (edmNavigationProperty.isCollection()) {
      assertIsNullNode(key, jsonNode);
      if (!jsonNode.isArray()) {
        throw new DeserializerException("Binding annotation: " + key + " must be an array.",
            DeserializerException.MessageKeys.INVALID_ANNOTATION_TYPE, key);
      }
      List<String> bindingLinkStrings = new ArrayList<String>();
      for (JsonNode arrayValue : jsonNode) {
        assertIsNullNode(key, arrayValue);
        if (!arrayValue.isTextual()) {
          throw new DeserializerException("Binding annotation: " + key + " must have string valued array.",
              DeserializerException.MessageKeys.INVALID_ANNOTATION_TYPE, key);
        }
        bindingLinkStrings.add(arrayValue.asText());
      }
      bindingLink.setType(Constants.ENTITY_COLLECTION_BINDING_LINK_TYPE);
      bindingLink.setBindingLinks(bindingLinkStrings);
    } else {
      if (!jsonNode.isValueNode()) {
        throw new DeserializerException("Binding annotation: " + key + " must be a string value.",
            DeserializerException.MessageKeys.INVALID_ANNOTATION_TYPE, key);
      }
      if (edmNavigationProperty.isNullable() && jsonNode.isNull()) {
        bindingLink.setBindingLink(null);
      } else {
        assertIsNullNode(key, jsonNode);
        bindingLink.setBindingLink(jsonNode.asText());
      }
      bindingLink.setType(Constants.ENTITY_BINDING_LINK_TYPE);
    }
    return bindingLink;
  }

  private void assertIsNullNode(final String key, final JsonNode jsonNode) throws DeserializerException {
    if (jsonNode.isNull()) {
      throw new DeserializerException("Annotation: " + key + "must not have a null value.",
          DeserializerException.MessageKeys.INVALID_NULL_ANNOTATION, key);
    }
  }

  private Property consumePropertyNode(final String name, final EdmType type, final boolean isCollection,
      final boolean isNullable, final Integer maxLength, final Integer precision, final Integer scale,
      final boolean isUnicode, final EdmMapping mapping, final JsonNode jsonNode) throws DeserializerException {
    Property property = new Property();
    property.setName(name);
    property.setType(type.getFullQualifiedName().getFullQualifiedNameAsString());
    if (isCollection) {
      consumePropertyCollectionNode(name, type, isNullable, maxLength, precision, scale, isUnicode, mapping,
          jsonNode, property);
    } else {
      consumePropertySingleNode(name, type, isNullable, maxLength, precision, scale, isUnicode, mapping, jsonNode,
          property);
    }
    return property;
  }

  private void consumePropertySingleNode(final String name, final EdmType type, final boolean isNullable,
      final Integer maxLength, final Integer precision, final Integer scale, final boolean isUnicode,
      final EdmMapping mapping, final JsonNode jsonNode, final Property property) throws DeserializerException {
    switch (type.getKind()) {
    case PRIMITIVE:
    case DEFINITION:
    case ENUM:
      Object value = readPrimitiveValue(name, (EdmPrimitiveType) type, isNullable, maxLength, precision, scale,
          isUnicode, mapping, jsonNode);
      property.setValue(type.getKind() == EdmTypeKind.ENUM ? ValueType.ENUM : ValueType.PRIMITIVE, value);
      break;
    case COMPLEX:
      EdmType derivedType = getDerivedType((EdmComplexType) type, jsonNode);
      property.setType(derivedType.getFullQualifiedName().getFullQualifiedNameAsString());

      value = readComplexNode(name, derivedType, isNullable, jsonNode);
      property.setValue(ValueType.COMPLEX, value);
      break;
    default:
      throw new DeserializerException("Invalid Type Kind for a property found: " + type.getKind(),
          DeserializerException.MessageKeys.INVALID_JSON_TYPE_FOR_PROPERTY, name);
    }
  }

  private Object readComplexNode(final String name, final EdmType type, final boolean isNullable,
      final JsonNode jsonNode) throws DeserializerException {
    // read and add all complex properties
    ComplexValue value = readComplexValue(name, type, isNullable, jsonNode);

    if (jsonNode.isObject()) {
      removeAnnotations((ObjectNode) jsonNode);
    }
    // Afterwards the node must be empty
    assertJsonNodeIsEmpty(jsonNode);

    return value;
  }

  private void consumePropertyCollectionNode(final String name, final EdmType type, final boolean isNullable,
      final Integer maxLength, final Integer precision, final Integer scale, final boolean isUnicode,
      final EdmMapping mapping, final JsonNode jsonNode, final Property property) throws DeserializerException {
    if (!jsonNode.isArray()) {
      throw new DeserializerException("Value for property: " + name + " must be an array but is not.",
          DeserializerException.MessageKeys.INVALID_JSON_TYPE_FOR_PROPERTY, name);
    }
    List<Object> valueArray = new ArrayList<Object>();
    Iterator<JsonNode> iterator = jsonNode.iterator();
    switch (type.getKind()) {
    case PRIMITIVE:
    case DEFINITION:
    case ENUM:
      while (iterator.hasNext()) {
        JsonNode arrayElement = iterator.next();
        Object value = readPrimitiveValue(name, (EdmPrimitiveType) type, isNullable, maxLength, precision,
            scale, isUnicode, mapping, arrayElement);
        valueArray.add(value);
      }
      property.setValue(
          type.getKind() == EdmTypeKind.ENUM ? ValueType.COLLECTION_ENUM : ValueType.COLLECTION_PRIMITIVE,
          valueArray);
      break;
    case COMPLEX:
      while (iterator.hasNext()) {
        // read and add all complex properties
        Object value = readComplexNode(name, type, isNullable, iterator.next());
        valueArray.add(value);
      }
      property.setValue(ValueType.COLLECTION_COMPLEX, valueArray);
      break;
    default:
      throw new DeserializerException("Invalid Type Kind for a property found: " + type.getKind(),
          DeserializerException.MessageKeys.INVALID_JSON_TYPE_FOR_PROPERTY, name);
    }
  }

  private ComplexValue readComplexValue(final String name, final EdmType type, final boolean isNullable,
      final JsonNode jsonNode) throws DeserializerException {
    if (isValidNull(name, isNullable, jsonNode)) {
      return null;
    }
    if (jsonNode.isArray() || !jsonNode.isContainerNode()) {
      throw new DeserializerException(
          "Invalid value for property: " + name + " must not be an array or primitive value.",
          DeserializerException.MessageKeys.INVALID_JSON_TYPE_FOR_PROPERTY, name);
    }
    // Even if there are no properties defined we have to give back an empty
    // list
    ComplexValue complexValue = new ComplexValue();
    EdmComplexType edmType = (EdmComplexType) type;
    // Check and consume all Properties
    for (String propertyName : edmType.getPropertyNames()) {
      JsonNode subNode = jsonNode.get(propertyName);
      if (subNode != null) {
        EdmProperty edmProperty = (EdmProperty) edmType.getProperty(propertyName);
        if (subNode.isNull() && !edmProperty.isNullable()) {
          throw new DeserializerException("Property: " + propertyName + " must not be null.",
              DeserializerException.MessageKeys.INVALID_NULL_PROPERTY, propertyName);
        }
        Property property = consumePropertyNode(edmProperty.getName(), edmProperty.getType(),
            edmProperty.isCollection(), edmProperty.isNullable(), edmProperty.getMaxLength(),
            edmProperty.getPrecision(), edmProperty.getScale(), edmProperty.isUnicode(),
            edmProperty.getMapping(), subNode);
        complexValue.getValue().add(property);
        ((ObjectNode) jsonNode).remove(propertyName);
      }
      consumeExpandedNavigationProperties(edmType, (ObjectNode) jsonNode, complexValue, expandBuilder);
    }

    return complexValue;
  }

  private Object readPrimitiveValue(final String name, final EdmPrimitiveType type, final boolean isNullable,
      final Integer maxLength, final Integer precision, final Integer scale, final boolean isUnicode,
      final EdmMapping mapping, final JsonNode jsonNode) throws DeserializerException {
    checkForValueNode(name, jsonNode);
    if (isValidNull(name, isNullable, jsonNode)) {
      return null;
    }
    checkJsonTypeBasedOnPrimitiveType(name, type, jsonNode);
    Class<?> javaClass = getJavaClassForPrimitiveType(mapping, type);
    try {
      return type.valueOfString(jsonNode.asText(), isNullable, maxLength, precision, scale, isUnicode, javaClass);
    } catch (final EdmPrimitiveTypeException e) {
      throw new DeserializerException("Invalid value: " + jsonNode.asText() + " for property: " + name, e,
          DeserializerException.MessageKeys.INVALID_VALUE_FOR_PROPERTY, name);
    }
  }

  private boolean isValidNull(final String name, final boolean isNullable, final JsonNode jsonNode)
      throws DeserializerException {
    if (jsonNode.isNull()) {
      if (isNullable) {
        return true;
      } else {
        throw new DeserializerException("Property: " + name + " must not be null.",
            DeserializerException.MessageKeys.INVALID_NULL_PROPERTY, name);
      }
    }
    return false;
  }

  /**
   * Returns the primitive type's default class or the manually mapped class
   * if present.
   * 
   * @param mapping
   * @param edmPrimitiveType
   * @return the java class to be used during deserialization
   */
  private Class<?> getJavaClassForPrimitiveType(final EdmMapping mapping, final EdmPrimitiveType type) {
    final EdmPrimitiveType edmPrimitiveType = type.getKind() == EdmTypeKind.ENUM
        ? ((EdmEnumType) type).getUnderlyingType()
        : type.getKind() == EdmTypeKind.DEFINITION ? ((EdmTypeDefinition) type).getUnderlyingType() : type;
    return mapping == null || mapping.getMappedJavaClass() == null ? edmPrimitiveType.getDefaultType()
        : mapping.getMappedJavaClass();
  }

  /**
   * Check if JsonNode is a value node (<code>jsonNode.isValueNode()</code>)
   * and if not throw an DeserializerException.
   * 
   * @param name
   * name of property which is checked
   * @param jsonNode
   * node which is checked
   * @throws DeserializerException
   * is thrown if json node is not a value node
   */
  private void checkForValueNode(final String name, final JsonNode jsonNode) throws DeserializerException {
    if (!jsonNode.isValueNode()) {
      throw new DeserializerException("Invalid value for property: " + name + " must not be an object or array.",
          DeserializerException.MessageKeys.INVALID_JSON_TYPE_FOR_PROPERTY, name);
    }
  }

  private void removeAnnotations(final ObjectNode tree) throws DeserializerException {
    List<String> toRemove = new ArrayList<String>();
    Iterator<Entry<String, JsonNode>> fieldsIterator = tree.fields();
    while (fieldsIterator.hasNext()) {
      Map.Entry<String, JsonNode> field = fieldsIterator.next();

      if (field.getKey().contains(ODATA_CONTROL_INFORMATION_PREFIX)) {
        // Control Information is ignored for requests as per
        // specification chapter "4.5 Control Information"
        toRemove.add(field.getKey());
      } else if (field.getKey().contains(ODATA_ANNOTATION_MARKER)) {
        toRemove.add(field.getKey());
      }
    }
    // remove here to avoid iterator issues.
    tree.remove(toRemove);
  }

  /**
   * Validates that node is empty (<code>node.size() == 0</code>).
   * 
   * @param node
   * node to be checked
   * @throws DeserializerException
   * if node is not empty
   */
  private void assertJsonNodeIsEmpty(final JsonNode node) throws DeserializerException {
    if (node.size() != 0) {
      final String unknownField = node.fieldNames().next();
      throw new DeserializerException("Tree should be empty but still has content left: " + unknownField,
          DeserializerException.MessageKeys.UNKNOWN_CONTENT, unknownField);
    }
  }

  private void checkJsonTypeBasedOnPrimitiveType(final String propertyName, final EdmPrimitiveType edmPrimitiveType,
      final JsonNode jsonNode) throws DeserializerException {
    boolean valid = true;
    if (edmPrimitiveType.getKind() == EdmTypeKind.DEFINITION) {
      checkJsonTypeBasedOnPrimitiveType(propertyName, ((EdmTypeDefinition) edmPrimitiveType).getUnderlyingType(),
          jsonNode);
    } else if (edmPrimitiveType.getKind() == EdmTypeKind.ENUM) {
      // Enum values must be strings.
      valid = jsonNode.isTextual();
    } else {
      final String name = edmPrimitiveType.getName();
      EdmPrimitiveTypeKind primKind;
      try {
        primKind = EdmPrimitiveTypeKind.valueOf(name);
      } catch (final IllegalArgumentException e) {
        throw new DeserializerException("Unknown Primitive Type: " + name, e,
            DeserializerException.MessageKeys.UNKNOWN_PRIMITIVE_TYPE, name, propertyName);
      }
      valid = matchTextualCase(jsonNode, primKind) || matchNumberCase(jsonNode, primKind)
          || matchBooleanCase(jsonNode, primKind) || matchIEEENumberCase(jsonNode, primKind);
    }
    if (!valid) {
      throw new DeserializerException("Invalid json type: " + jsonNode.getNodeType() + " for " + edmPrimitiveType
          + " property: " + propertyName, DeserializerException.MessageKeys.INVALID_VALUE_FOR_PROPERTY,
          propertyName);
    }
  }

  private boolean matchIEEENumberCase(final JsonNode node, final EdmPrimitiveTypeKind primKind) {
    return (isIEEE754Compatible ? node.isTextual() : node.isNumber())
        && (primKind == EdmPrimitiveTypeKind.Int64 || primKind == EdmPrimitiveTypeKind.Decimal);
  }

  private boolean matchBooleanCase(final JsonNode node, final EdmPrimitiveTypeKind primKind) {
    return node.isBoolean() && primKind == EdmPrimitiveTypeKind.Boolean;
  }

  private boolean matchNumberCase(final JsonNode node, final EdmPrimitiveTypeKind primKind) {
    return node.isNumber() && (primKind == EdmPrimitiveTypeKind.Int16 || primKind == EdmPrimitiveTypeKind.Int32
        || primKind == EdmPrimitiveTypeKind.Byte || primKind == EdmPrimitiveTypeKind.SByte
        || primKind == EdmPrimitiveTypeKind.Single || primKind == EdmPrimitiveTypeKind.Double);
  }

  private boolean matchTextualCase(final JsonNode node, final EdmPrimitiveTypeKind primKind) {
    return node.isTextual() && (primKind == EdmPrimitiveTypeKind.String || primKind == EdmPrimitiveTypeKind.Binary
        || primKind == EdmPrimitiveTypeKind.Date || primKind == EdmPrimitiveTypeKind.DateTimeOffset
        || primKind == EdmPrimitiveTypeKind.Duration || primKind == EdmPrimitiveTypeKind.Guid
        || primKind == EdmPrimitiveTypeKind.TimeOfDay);
  }

  @Override
  public DeserializerResult property(final InputStream stream, final EdmProperty edmProperty)
      throws DeserializerException {
    try {
      final ObjectNode tree = parseJsonTree(stream);

      final Property property;
      JsonNode jsonNode = tree.get(Constants.VALUE);
      if (jsonNode != null) {
        property = consumePropertyNode(edmProperty.getName(), edmProperty.getType(), edmProperty.isCollection(),
            edmProperty.isNullable(), edmProperty.getMaxLength(), edmProperty.getPrecision(),
            edmProperty.getScale(), edmProperty.isUnicode(), edmProperty.getMapping(), jsonNode);
        tree.remove(Constants.VALUE);
      } else {
        property = consumePropertyNode(edmProperty.getName(), edmProperty.getType(), edmProperty.isCollection(),
            edmProperty.isNullable(), edmProperty.getMaxLength(), edmProperty.getPrecision(),
            edmProperty.getScale(), edmProperty.isUnicode(), edmProperty.getMapping(), tree);
      }
      return DeserializerResultImpl.with().property(property).build();
    } catch (final IOException e) {
      throw wrapParseException(e);
    }
  }

  @Override
  public DeserializerResult entityReferences(final InputStream stream) throws DeserializerException {
    try {
      List<URI> parsedValues = new ArrayList<URI>();
      final ObjectNode tree = parseJsonTree(stream);
      final String key = Constants.JSON_ID;
      JsonNode jsonNode = tree.get(Constants.VALUE);
      if (jsonNode != null) {
        if (jsonNode.isArray()) {
          ArrayNode arrayNode = (ArrayNode) jsonNode;
          Iterator<JsonNode> it = arrayNode.iterator();
          while (it.hasNext()) {
            final JsonNode next = it.next();
            if (next.has(key)) {
              parsedValues.add(new URI(next.get(key).asText()));
            }
          }
        } else {
          throw new DeserializerException("Value must be an array",
              DeserializerException.MessageKeys.UNKNOWN_CONTENT);
        }
        tree.remove(Constants.VALUE);
        return DeserializerResultImpl.with().entityReferences(parsedValues).build();
      }
      if (tree.get(key) != null) {
        parsedValues.add(new URI(tree.get(key).asText()));
      } else {
        throw new DeserializerException("Missing entity reference",
            DeserializerException.MessageKeys.UNKNOWN_CONTENT);
      }
      return DeserializerResultImpl.with().entityReferences(parsedValues).build();
    } catch (final IOException e) {
      throw wrapParseException(e);
    } catch (final URISyntaxException e) {
      throw new DeserializerException("failed to read @odata.id", e,
          DeserializerException.MessageKeys.UNKNOWN_CONTENT);
    }
  }

  private DeserializerException wrapParseException(final IOException e) {
    if (e instanceof JsonParseException) {
      return new DeserializerException("A JsonParseException occurred.", e,
          DeserializerException.MessageKeys.JSON_SYNTAX_EXCEPTION);
    } else if (e instanceof JsonMappingException) {
      return new DeserializerException("Duplicate json property detected.", e,
          DeserializerException.MessageKeys.DUPLICATE_PROPERTY);
    } else {
      return new DeserializerException("An IOException occurred.", e,
          DeserializerException.MessageKeys.IO_EXCEPTION);
    }
  }

  private EdmType getDerivedType(final EdmStructuredType edmType, final JsonNode jsonNode)
      throws DeserializerException {
    JsonNode odataTypeNode = jsonNode.get(Constants.JSON_TYPE);
    if (odataTypeNode != null) {
      String odataType = odataTypeNode.asText();
      if (!odataType.isEmpty()) {
        odataType = odataType.substring(1);

        if (odataType.equalsIgnoreCase(edmType.getFullQualifiedName().getFullQualifiedNameAsString())) {
          return edmType;
        } else if (this.serviceMetadata == null) {
          throw new DeserializerException(
              "Failed to resolve Odata type " + odataType + " due to metadata is not available",
              DeserializerException.MessageKeys.UNKNOWN_CONTENT);
        }

        EdmStructuredType currentEdmType = null;
        if (edmType instanceof EdmEntityType) {
          currentEdmType = serviceMetadata.getEdm().getEntityType(new FullQualifiedName(odataType));
        } else {
          currentEdmType = serviceMetadata.getEdm().getComplexType(new FullQualifiedName(odataType));
        }
        if (!isAssignable(edmType, currentEdmType)) {
          throw new DeserializerException("Odata type " + odataType + " not allowed here",
              DeserializerException.MessageKeys.UNKNOWN_CONTENT);
        }

        return currentEdmType;
      }
    }
    return edmType;
  }

  private boolean isAssignable(final EdmStructuredType edmStructuredType,
      final EdmStructuredType edmStructuredTypeToAssign) {
    if (edmStructuredTypeToAssign == null) {
      return false;
    } else if (edmStructuredType.getFullQualifiedName().equals(edmStructuredTypeToAssign.getFullQualifiedName())) {
      return true;
    } else {
      return isAssignable(edmStructuredType, edmStructuredTypeToAssign.getBaseType());
    }
  }
}
