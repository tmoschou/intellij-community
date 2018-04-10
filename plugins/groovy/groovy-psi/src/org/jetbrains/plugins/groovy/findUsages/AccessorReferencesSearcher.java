// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.plugins.groovy.findUsages;

import com.intellij.openapi.application.QueryExecutorBase;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrField;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrAccessorMethod;

/**
 * author ven
 */
public class AccessorReferencesSearcher extends QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters> {

  public AccessorReferencesSearcher() {
    super(true);
  }

  @Override
  public void processQuery(@NotNull ReferencesSearch.SearchParameters queryParameters, @NotNull Processor<PsiReference> consumer) {
    final PsiElement element = queryParameters.getElementToSearch();
    if (element instanceof GrField) {
      for (GrAccessorMethod method : ((GrField)element).getGetters()) {
        MethodReferencesSearch.search(method, queryParameters.getEffectiveSearchScope(), true).forEach(consumer);
      }

      final GrAccessorMethod setter = ((GrField)element).getSetter();
      if (setter != null) {
        MethodReferencesSearch.search(setter, queryParameters.getEffectiveSearchScope(), true).forEach(consumer);
      }
    }
  }
}
