package com.flipkart.krystal.vajram.exec;

import static com.flipkart.krystal.vajram.VajramID.vajramID;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.flipkart.krystal.tags.ElementTags;
import com.flipkart.krystal.vajram.Annos;
import com.flipkart.krystal.vajram.ComputeDelegationType;
import com.flipkart.krystal.vajram.ComputeVajram;
import com.flipkart.krystal.vajram.IOVajram;
import com.flipkart.krystal.vajram.Output;
import com.flipkart.krystal.vajram.Vajram;
import com.flipkart.krystal.vajram.VajramDef;
import com.flipkart.krystal.vajram.VajramID;
import com.flipkart.krystal.vajram.facets.DefaultInputResolverDefinition;
import com.flipkart.krystal.vajram.facets.DependencyDef;
import com.flipkart.krystal.vajram.facets.FacetContainer;
import com.flipkart.krystal.vajram.facets.InputDef;
import com.flipkart.krystal.vajram.facets.QualifiedInputs;
import com.flipkart.krystal.vajram.facets.Using;
import com.flipkart.krystal.vajram.facets.VajramFacetDefinition;
import com.flipkart.krystal.vajram.facets.resolution.InputResolverDefinition;
import com.flipkart.krystal.vajram.facets.resolution.sdk.Resolve;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class Vajrams {

  /**
   * Returns the exact class in the class hierarchy which has the @VajramDef annotation
   *
   * @param aClass the class in whose class hierarcgy the vajram def class is to be searched for.
   */
  @SuppressWarnings("unchecked")
  static Class<? extends Vajram<?>> getVajramDefClass(
      @SuppressWarnings("rawtypes") Class<? extends Vajram> aClass) {
    return (Class<? extends Vajram<?>>) _getVajramDefClass(aClass);
  }

  static VajramID parseVajramId(Vajram<?> vajram) {
    Class<?> vajramDefClass = getVajramDefClass(vajram.getClass());
    return vajramID(
        Optional.ofNullable(vajramDefClass.getAnnotation(VajramDef.class))
            .map(VajramDef::id)
            .filter(id -> !id.isEmpty())
            // Empty id means we must infer vajram id from class name
            .orElseGet(vajramDefClass::getSimpleName));
  }

  static ElementTags parseVajramTags(VajramID inferredVajramId, Vajram<?> vajram) {
    return ElementTags.of(
        Arrays.stream(getVajramDefClass(vajram.getClass()).getAnnotations())
            .map(a -> enrich(a, inferredVajramId, vajram))
            .toList());
  }

  static ImmutableList<InputResolverDefinition> parseInputResolvers(Vajram<?> vajram) {
    List<InputResolverDefinition> inputResolvers =
        new ArrayList<>(vajram.getSimpleInputResolvers());
    @SuppressWarnings("unchecked")
    Class<? extends Vajram<?>> aClass = (Class<? extends Vajram<?>>) vajram.getClass();
    ImmutableSet<Method> resolverMethods =
        Arrays.stream(getVajramDefClass(aClass).getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Resolve.class))
            .collect(toImmutableSet());

    ImmutableMap<String, DependencyDef<?>> dependencyDefinitions =
        vajram.getFacetDefinitions().stream()
            .filter(vi -> vi instanceof DependencyDef)
            .map(vi -> (DependencyDef<?>) vi)
            .collect(toImmutableMap(VajramFacetDefinition::name, Function.identity()));

    for (Method resolverMethod : resolverMethods) {
      Resolve resolver = resolverMethod.getAnnotation(Resolve.class);
      if (resolver == null) {
        throw new AssertionError();
      }
      String targetDependency = resolver.depName();
      DependencyDef<?> dependencyDef = dependencyDefinitions.get(targetDependency);
      if (dependencyDef == null) {
        throw new IllegalStateException(
            "Could not find dependency with name %s".formatted(targetDependency));
      }
      String[] targetInputs = resolver.depInputs();
      ImmutableSet<String> sources =
          Arrays.stream(resolverMethod.getParameters())
              .map(Vajrams::inferFacetName)
              .collect(toImmutableSet());
      inputResolvers.add(
          new DefaultInputResolverDefinition(
              sources,
              new QualifiedInputs(
                  targetDependency,
                  dependencyDef.dataAccessSpec(),
                  ImmutableSet.copyOf(targetInputs))));
    }
    return ImmutableList.copyOf(inputResolvers);
  }

  private static String inferFacetName(Parameter parameter) {
    return Arrays.stream(parameter.getAnnotations())
        .filter(annotation -> annotation instanceof Using)
        .map(annotation -> (Using) annotation)
        .findAny()
        .map(Using::value)
        .orElseGet(parameter::getName);
  }

  static ElementTags parseOutputLogicTags(Vajram<?> vajram) {
    return ElementTags.of(
        Arrays.stream(getVajramDefClass(vajram.getClass()).getDeclaredMethods())
            .filter(method -> method.getAnnotation(Output.class) != null)
            .findFirst()
            .stream()
            .flatMap(method -> Arrays.stream(method.getAnnotations()))
            .toList());
  }

  private static Annotation enrich(
      Annotation annotation, VajramID inferredVajramId, Vajram<?> vajram) {
    if (annotation instanceof VajramDef vajramDef) {
      if (!vajramDef.id().isEmpty()) {
        throw new IllegalArgumentException(
            """
                Custom vajramIds are not supported. \
                Please remove the vajramId field from the VajramDef annotation on class %s. \
                It will be auto-inferred from the class name."""
                .formatted(inferredVajramId));
      }
      if (!vajramDef.computeDelegationType().equals(ComputeDelegationType.DEFAULT)) {
        throw new IllegalArgumentException(
            """
                Please remove the 'computeDelegationType' field from the VajramDef annotation on class %s. \
                It will be auto-inferred from the class hierarchy."""
                .formatted(vajram.getClass()));
      }
      annotation =
          Annos.vajramDef(vajramDef, inferredVajramId.vajramId(), getComputeDelegationType(vajram));
    }

    return annotation;
  }

  private static ComputeDelegationType getComputeDelegationType(Vajram<?> vajram) {
    if (vajram instanceof ComputeVajram<?>) {
      return ComputeDelegationType.NO_DELEGATION;
    } else if (vajram instanceof IOVajram<?>) {
      return ComputeDelegationType.SYNC_DELEGATION;
    } else {
      throw new IllegalStateException(
          "Unable infer compute delegation type of vajram %s".formatted(vajram.getClass()));
    }
  }

  private static Class<?> _getVajramDefClass(Class<?> aClass) {
    if (!Vajram.class.isAssignableFrom(aClass)) {
      throw new IllegalArgumentException(
          "VajramDef annotation is not present in the class hierarchy");
    }
    Annotation annotation = aClass.getAnnotation(VajramDef.class);
    if (annotation != null) {
      return aClass;
    }
    Class<?> superclass = aClass.getSuperclass();
    if (Object.class.equals(superclass) || superclass == null) {
      throw new IllegalArgumentException();
    }
    return _getVajramDefClass(superclass);
  }

  private Vajrams() {}

  static ImmutableSet<String> parseOutputLogicSources(Vajram<?> vajram) {
    Optional<Method> outputLogicMethod =
        Arrays.stream(getVajramDefClass(vajram.getClass()).getDeclaredMethods())
            .filter(method -> method.getAnnotation(Output.class) != null)
            .findFirst();
    ImmutableSet<String> allFacetNames =
        vajram.getFacetDefinitions().stream()
            .map(VajramFacetDefinition::name)
            .collect(toImmutableSet());
    if (outputLogicMethod.isPresent()
        && vajram.getFacetDefinitions().stream()
            .noneMatch(f -> f instanceof InputDef<?> input && input.isBatched())) {
      // This is a vajram which doesn't have batched facets. So we can infer the output logic's
      // sources from the parameters
      Parameter[] outputLogicParams = outputLogicMethod.get().getParameters();
      if (outputLogicParams.length == 1
          && FacetContainer.class.isAssignableFrom(outputLogicParams[0].getType())) {
        /*
         This means the output logic is consuming the auto-generated Facets class which implies it
         consumes all the facets
        */
        return allFacetNames;
      } else {
        return Arrays.stream(outputLogicParams)
            .map(Vajrams::inferFacetName)
            .collect(toImmutableSet());
      }
    } else {
      // The output logic consumes all facets
      return allFacetNames;
    }
  }
}
