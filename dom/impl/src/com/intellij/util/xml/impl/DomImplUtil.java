/*
 * Copyright (c) 2000-2006 JetBrains s.r.o. All Rights Reserved.
 */
package com.intellij.util.xml.impl;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ReflectionCache;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.xml.*;
import com.intellij.psi.xml.XmlTag;
import org.apache.xerces.parsers.SAXParser;
import org.apache.xerces.xni.parser.XMLEntityResolver;
import org.apache.xerces.xni.parser.XMLInputSource;
import org.apache.xerces.xni.XMLResourceIdentifier;
import org.apache.xerces.xni.XNIException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NonNls;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

/**
 * @author peter
 */
public class DomImplUtil {
  private static final Logger LOG = Logger.getInstance("#com.intellij.util.xml.impl.DomImplUtil");
  private static final SAXParser ourParser = new SAXParser();

  public static boolean isTagValueGetter(final JavaMethod method) {
    if (!isGetter(method)) {
      return false;
    }
    if (hasTagValueAnnotation(method)) {
      return true;
    }
    if ("getValue".equals(method.getName())) {
      final JavaMethodSignature signature = method.getSignature();
      final Class<?> declaringClass = method.getDeclaringClass();
      if (signature.findAnnotation(SubTag.class, declaringClass) != null) return false;
      if (signature.findAnnotation(SubTagList.class, declaringClass) != null) return false;
      if (signature.findAnnotation(Convert.class, declaringClass) != null ||
          signature.findAnnotation(Resolve.class, declaringClass) != null) {
        return !ReflectionCache.isAssignable(GenericDomValue.class, method.getReturnType());
      }
      if (ReflectionCache.isAssignable(DomElement.class, method.getReturnType())) return false;
      return true;
    }
    return false;
  }

  private static boolean hasTagValueAnnotation(final JavaMethod method) {
    return method.getAnnotation(TagValue.class) != null;
  }

  public static boolean isGetter(final JavaMethod method) {
    @NonNls final String name = method.getName();
    if (method.getGenericParameterTypes().length != 0) {
      return false;
    }
    final Type returnType = method.getGenericReturnType();
    if (name.startsWith("get")) {
      return returnType != void.class;
    }
    return name.startsWith("is") && DomReflectionUtil.canHaveIsPropertyGetterPrefix(returnType);
  }


  public static boolean isTagValueSetter(final JavaMethod method) {
    boolean setter = method.getName().startsWith("set") && method.getGenericParameterTypes().length == 1 && method.getReturnType() == void.class;
    return setter && (hasTagValueAnnotation(method) || "setValue".equals(method.getName()));
  }

  @Nullable
  public static DomNameStrategy getDomNameStrategy(final Class<?> rawType, boolean isAttribute) {
    Class aClass = null;
    if (isAttribute) {
      NameStrategyForAttributes annotation = DomReflectionUtil.findAnnotationDFS(rawType, NameStrategyForAttributes.class);
      if (annotation != null) {
        aClass = annotation.value();
      }
    }
    if (aClass == null) {
      NameStrategy annotation = DomReflectionUtil.findAnnotationDFS(rawType, NameStrategy.class);
      if (annotation != null) {
        aClass = annotation.value();
      }
    }
    if (aClass != null) {
      if (HyphenNameStrategy.class.equals(aClass)) return DomNameStrategy.HYPHEN_STRATEGY;
      if (JavaNameStrategy.class.equals(aClass)) return DomNameStrategy.JAVA_STRATEGY;
      try {
        return (DomNameStrategy)aClass.newInstance();
      }
      catch (InstantiationException e) {
        LOG.error(e);
      }
      catch (IllegalAccessException e) {
        LOG.error(e);
      }
    }
    return null;
  }

  public static List<XmlTag> findSubTags(XmlTag tag, final EvaluatedXmlName name, final DomInvocationHandler handler) {
    return ContainerUtil.findAll(tag.getSubTags(), new Condition<XmlTag>() {
      public boolean value(XmlTag childTag) {
        return isNameSuitable(name, childTag, handler);
      }
    });
  }

  public static boolean isNameSuitable(final XmlName name, final XmlTag tag, final DomInvocationHandler handler) {
    return isNameSuitable(name.createEvaluatedXmlName(handler), tag, handler);
  }

  public static boolean isNameSuitable(final EvaluatedXmlName evaluatedXmlName, final XmlTag tag, final DomInvocationHandler handler) {
    return isNameSuitable(evaluatedXmlName, tag.getLocalName(), tag.getName(), tag.getNamespace(), handler);
  }

  public static boolean isNameSuitable(final EvaluatedXmlName evaluatedXmlName,
                                        final String localName,
                                        final String qName,
                                        final String namespace,
                                        final DomInvocationHandler handler) {
    final String localName1 = evaluatedXmlName.getLocalName();
    return (localName1.equals(localName) || localName1.equals(qName)) && evaluatedXmlName.isNamespaceAllowed(handler, namespace);
  }

  public static boolean containsTagName(final Set<XmlName> qnames1, final XmlTag subTag, final DomInvocationHandler handler) {
    return ContainerUtil.find(qnames1, new Condition<XmlName>() {
      public boolean value(XmlName name) {
        return isNameSuitable(name, subTag, handler);
      }
    }) != null;
  }

@NotNull
  public static Pair<String, String> getRootTagAndNamespace(final InputSource source) throws IOException {
    synchronized (ourParser) {
      final Ref<String> localNameRef = new Ref<String>();
      final Ref<String> nsRef = new Ref<String>("");
      final DefaultHandler handler = new DefaultHandler() {

        public InputSource resolveEntity(final String publicId, final String systemId) throws IOException, SAXException {
          nsRef.set(systemId);
          return super.resolveEntity(publicId, systemId);
        }

        public void startElement(final String uri, final String localName, final String qName, final Attributes attributes)
          throws SAXException {
          localNameRef.set(localName);
          if (StringUtil.isNotEmpty(uri) && StringUtil.isEmpty(nsRef.get())) {
            nsRef.set(uri);
          }
          throw new SAXException();
        }
      };
      ourParser.setContentHandler(handler);
      ourParser.setEntityResolver(handler);
      ourParser.setErrorHandler(handler);
      try {
        ourParser.setProperty("http://apache.org/xml/properties/internal/entity-resolver", new XMLEntityResolver() {
          public XMLInputSource resolveEntity(final XMLResourceIdentifier resourceIdentifier) throws XNIException, IOException {
            return null;
          }
        });

        ourParser.setFeature("http://xml.org/sax/features/namespaces", true);
        ourParser.setFeature("http://apache.org/xml/features/allow-java-encodings", true);

        ourParser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        ourParser.setFeature("http://xml.org/sax/features/external-general-entities", false);
        ourParser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      }
      catch (SAXNotRecognizedException e) {
      }
      catch (SAXNotSupportedException e) {
      }
      try {
        ourParser.parse(source);
      }
      catch (SAXException e) {
      }
      finally {
        ourParser.reset();
        closeStream(source.getByteStream());
        closeStream(source.getCharacterStream());
        ourParser.setContentHandler(null);
        ourParser.setEntityResolver(null);
        ourParser.setErrorHandler(null);
      }
      return Pair.create(localNameRef.get(), nsRef.get());
    }
  }

  private static void closeStream(final Closeable stream) throws IOException {
    if (stream != null) {
      stream.close();
    }
  }
}
