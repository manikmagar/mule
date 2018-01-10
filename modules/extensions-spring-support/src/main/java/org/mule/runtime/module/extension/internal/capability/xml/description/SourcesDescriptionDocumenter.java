/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.capability.xml.description;

import static org.mule.runtime.extension.api.util.NameUtils.hyphenize;

import org.mule.runtime.api.meta.model.declaration.fluent.SourceDeclaration;
import org.mule.runtime.api.meta.model.declaration.fluent.WithSourcesDeclaration;
import org.mule.runtime.extension.api.annotation.Sources;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

import java.util.List;
import java.util.Optional;

/**
 * {@link AbstractDescriptionDocumenter} implementation that fills {@link WithSourcesDeclaration}s
 *
 * @since 4.0
 */
final class SourcesDescriptionDocumenter extends AbstractDescriptionDocumenter<WithSourcesDeclaration<?>> {

  private final ParameterDescriptionDocumenter parameterDeclarer;

  SourcesDescriptionDocumenter(ProcessingEnvironment processingEnv) {
    super(processingEnv);
    this.parameterDeclarer = new ParameterDescriptionDocumenter(processingEnv);
  }

  void document(WithSourcesDeclaration<?> declaration, TypeElement element) {
    getSourceClasses(processingEnv, element)
        .forEach(sourceElement -> findMatchingSource(declaration, sourceElement)
            .ifPresent(source -> {
              source.setDescription(processor.getJavaDocSummary(processingEnv, sourceElement));
              parameterDeclarer.document(source, sourceElement);
            }));
  }

  private Optional<SourceDeclaration> findMatchingSource(WithSourcesDeclaration<?> declaration, Element element) {
    return declaration.getMessageSources().stream()
        .filter(provider -> {
          String name = provider.getName();
          String defaultNaming = hyphenize(element.getSimpleName().toString());
          return name.equals(defaultNaming) || getAlias(element).map(name::equals).orElse(false);
        })
        .findAny();
  }

  private List<TypeElement> getSourceClasses(ProcessingEnvironment processingEnv, Element element) {
    return processor.getArrayClassAnnotationValue(element, Sources.class, VALUE_PROPERTY, processingEnv);
  }
}
