package com.coxautodev.graphql.tools;

import graphql.language.Definition;

import java.util.List;

public interface TypeDefinitionFactory {

    List<Definition<?>> create(final List<Definition<?>> existing);

}
