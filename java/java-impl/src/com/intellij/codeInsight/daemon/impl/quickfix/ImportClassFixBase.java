// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.CodeInsightUtil;
import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.ImportFilter;
import com.intellij.codeInsight.completion.JavaCompletionUtil;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings;
import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInsight.daemon.impl.ShowAutoImportPass;
import com.intellij.codeInsight.daemon.impl.actions.AddImportAction;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.QuestionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.codeInspection.HintAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.packageDependencies.DependencyRule;
import com.intellij.packageDependencies.DependencyValidationManager;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleSettings;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * @author peter
 */
public abstract class ImportClassFixBase<T extends PsiElement, R extends PsiReference> implements HintAction, PriorityAction {
  @NotNull
  private final T myReferenceElement;
  @NotNull
  private final R myReference;
  private final PsiClass[] myClassesToImport;

  protected ImportClassFixBase(@NotNull T referenceElement, @NotNull R reference) {
    myReferenceElement = referenceElement;
    myReference = reference;
    myClassesToImport = calcClassesToImport();
  }

  @Override
  public @NotNull Priority getPriority() {
    return PriorityAction.Priority.TOP;
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, @NotNull PsiFile file) {
    PsiElement element;
    if (!myReferenceElement.isValid() || (element = myReference.getElement()) != myReferenceElement && !element.isValid()) {
      return false;
    }

    PsiElement parent = myReferenceElement.getParent();
    if (parent instanceof PsiNewExpression && ((PsiNewExpression)parent).getQualifier() != null) {
      return false;
    }

    if (parent instanceof PsiReferenceExpression) {
      PsiExpression expression = ((PsiReferenceExpression)parent).getQualifierExpression();
      if (expression != null && expression != myReferenceElement) {
        return false;
      }
    }

    if (myReference instanceof PsiJavaCodeReferenceElement &&
        ((PsiJavaCodeReferenceElement)myReference).advancedResolve(false).isValidResult()) {
      return false;
    }

    if (file instanceof PsiJavaCodeReferenceCodeFragment && !((PsiJavaCodeReferenceCodeFragment)file).isClassesAccepted()) {
      return false;
    }

    return !getClassesToImport(true).isEmpty();
  }

  @Nullable
  protected abstract String getReferenceName(@NotNull R reference);
  protected abstract PsiElement getReferenceNameElement(@NotNull R reference);
  protected abstract boolean hasTypeParameters(@NotNull R reference);

  @NotNull
  public List<? extends PsiClass> getClassesToImport() {
    return getClassesToImport(false);
  }

  @NotNull
  public List<? extends PsiClass> getClassesToImport(boolean acceptWrongNumberOfTypeParams) {
    if (!myReferenceElement.isValid() || ContainerUtil.exists(myClassesToImport, c -> !c.isValid())) {
      return Collections.emptyList();
    }
    if (!acceptWrongNumberOfTypeParams && hasTypeParameters(myReference)) {
      return ContainerUtil.findAll(myClassesToImport, PsiTypeParameterListOwner::hasTypeParameters);
    }
    return Arrays.asList(myClassesToImport);
  }

  private PsiClass @NotNull [] calcClassesToImport() {
    if (myReference instanceof PsiJavaReference) {
      JavaResolveResult result = ((PsiJavaReference)myReference).advancedResolve(true);
      PsiElement element = result.getElement();
      // already imported
      // can happen when e.g., class name happened to be in a method position
      if (element instanceof PsiClass && (result.isValidResult() || result.getCurrentFileResolveScope() instanceof PsiImportStatement)) {
        return PsiClass.EMPTY_ARRAY;
      }
    }

    String name = getReferenceName(myReference);
    GlobalSearchScope scope = myReferenceElement.getResolveScope();
    if (name == null) {
      return PsiClass.EMPTY_ARRAY;
    }

    if (!canReferenceClass(myReference)) {
      return PsiClass.EMPTY_ARRAY;
    }

    PsiFile file = myReferenceElement.getContainingFile();
    if (file == null) return PsiClass.EMPTY_ARRAY;
    Project project = file.getProject();
    PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(name, scope);
    if (classes.length == 0) return PsiClass.EMPTY_ARRAY;
    Collection<PsiClass> classList = new LinkedHashSet<>(classes.length);
    boolean isAnnotationReference = myReferenceElement.getParent() instanceof PsiAnnotation;
    for (PsiClass aClass : classes) {
      if (isAnnotationReference && !aClass.isAnnotationType()) continue;
      if (qualifiedNameAllowsAutoImport(file, aClass)) {
        classList.add(aClass);
      }
    }

    boolean anyAccessibleFound = classList.stream().anyMatch(aClass -> isAccessible(aClass, myReferenceElement));
    PsiManager manager = myReferenceElement.getManager();
    JavaPsiFacade facade = JavaPsiFacade.getInstance(manager.getProject());
    classList.removeIf(aClass -> (anyAccessibleFound ||
                                  !BaseIntentionAction.canModify(aClass) ||
                                  facade.arePackagesTheSame(aClass, myReferenceElement)) && !isAccessible(aClass, myReferenceElement));

    classList = filterByRequiredMemberName(classList);

    Collection<PsiClass> filtered = filterByContext(classList, myReferenceElement);
    if (!filtered.isEmpty()) {
      classList = filtered;
    }

    filerByPackageName(classList, file);

    return classList.toArray(PsiClass.EMPTY_ARRAY);
  }

  public static boolean qualifiedNameAllowsAutoImport(@NotNull PsiFile placeFile, @NotNull PsiClass aClass) {
    if (JavaCompletionUtil.isInExcludedPackage(aClass, false)) {
      return false;
    }
    String qName = aClass.getQualifiedName();
    if (qName != null) { //filter local classes
      if (qName.indexOf('.') == -1 || !PsiNameHelper.getInstance(placeFile.getProject()).isQualifiedName(qName)) return false;
      return ImportFilter.shouldImport(placeFile, qName);
    }
    return false;
  }

  private void filerByPackageName(@NotNull Collection<? extends PsiClass> classList, @NotNull PsiFile file) {
    String packageName = StringUtil.getPackageName(getQualifiedName(myReferenceElement));
    if (!packageName.isEmpty() &&
        file instanceof PsiJavaFile &&
        Arrays.binarySearch(((PsiJavaFile)file).getImplicitlyImportedPackages(), packageName) < 0) {
      for (Iterator<? extends PsiClass> iterator = classList.iterator(); iterator.hasNext(); ) {
        String classQualifiedName = iterator.next().getQualifiedName();
        if (classQualifiedName != null && !packageName.equals(StringUtil.getPackageName(classQualifiedName))) {
          iterator.remove();
        }
      }
    }
  }

  protected boolean canReferenceClass(@NotNull R ref) {
    return true;
  }

  private @NotNull Collection<PsiClass> filterByRequiredMemberName(@NotNull Collection<PsiClass> classList) {
    String memberName = getRequiredMemberName(myReferenceElement);
    if (memberName != null) {
      List<PsiClass> filtered = ContainerUtil.findAll(classList, psiClass -> {
        PsiField field = psiClass.findFieldByName(memberName, true);
        if (field != null && field.hasModifierProperty(PsiModifier.STATIC) && isAccessible(field, myReferenceElement)) return true;

        PsiClass inner = psiClass.findInnerClassByName(memberName, true);
        if (inner != null && isAccessible(inner, myReferenceElement)) return true;

        for (PsiMethod method : psiClass.findMethodsByName(memberName, true)) {
          if (method.hasModifierProperty(PsiModifier.STATIC) && isAccessible(method, myReferenceElement)) return true;
        }
        return false;
      });
      if (!filtered.isEmpty()) {
        classList = filtered;
      }
    }
    return classList;
  }

  private @NotNull List<? extends PsiClass> filterAlreadyImportedButUnresolved(@NotNull List<? extends PsiClass> list) {
    PsiElement element = myReference.getElement();
    PsiFile containingFile = element.getContainingFile();
    if (!(containingFile instanceof PsiJavaFile)) return list;
    PsiJavaFile javaFile = (PsiJavaFile)containingFile;
    PsiImportList importList = javaFile.getImportList();
    PsiImportStatementBase[] importStatements = importList == null ? PsiImportStatementBase.EMPTY_ARRAY : importList.getAllImportStatements();
    Set<String> unresolvedImports = new HashSet<>(importStatements.length);
    for (PsiImportStatementBase statement : importStatements) {
      PsiJavaCodeReferenceElement ref = statement.getImportReference();
      String name = ref == null ? null : ref.getReferenceName();
      if (name != null && ref.resolve() == null) unresolvedImports.add(name);
    }
    if (unresolvedImports.isEmpty()) return list;
    List<PsiClass> result = new ArrayList<>(list.size());
    for (int i = list.size() - 1; i >= 0; i--) {
      PsiClass aClass = list.get(i);
      String className = aClass.getName();
      if (className == null || !unresolvedImports.contains(className)) {
        result.add(aClass);
      }
    }
    return result;
  }

  @Nullable
  protected String getRequiredMemberName(@NotNull T referenceElement) {
    return null;
  }

  protected @NotNull Collection<PsiClass> filterByContext(@NotNull Collection<PsiClass> candidates, @NotNull T referenceElement) {
    return candidates;
  }

  protected abstract boolean isAccessible(@NotNull PsiMember member, @NotNull T referenceElement);

  protected abstract String getQualifiedName(@NotNull T referenceElement);

  @NotNull
  protected static Collection<PsiClass> filterAssignableFrom(@NotNull PsiType type, @NotNull Collection<PsiClass> candidates) {
    PsiClass actualClass = PsiUtil.resolveClassInClassTypeOnly(type);
    if (actualClass != null) {
      return ContainerUtil.findAll(candidates, psiClass -> InheritanceUtil.isInheritorOrSelf(actualClass, psiClass, true));
    }
    return candidates;
  }

  @NotNull
  protected static Collection<PsiClass> filterBySuperMethods(@NotNull PsiParameter parameter, @NotNull Collection<PsiClass> candidates) {
    PsiElement parent = parameter.getParent();
    if (parent instanceof PsiParameterList) {
      PsiElement granny = parent.getParent();
      if (granny instanceof PsiMethod) {
        PsiMethod method = (PsiMethod)granny;
        if (method.getModifierList().hasAnnotation(CommonClassNames.JAVA_LANG_OVERRIDE)) {
          PsiClass aClass = method.getContainingClass();
          Set<PsiClass> probableTypes = new HashSet<>();
          InheritanceUtil.processSupers(aClass, false, psiClass -> {
            for (PsiMethod psiMethod : psiClass.findMethodsByName(method.getName(), false)) {
              for (PsiParameter psiParameter : psiMethod.getParameterList().getParameters()) {
                ContainerUtil.addIfNotNull(probableTypes, PsiUtil.resolveClassInClassTypeOnly(psiParameter.getType()));
              }
            }
            return true;
          });
          List<PsiClass> filtered = ContainerUtil.filter(candidates, psiClass -> probableTypes.contains(psiClass));
          if (!filtered.isEmpty()) {
            return filtered;
          }
        }
      }
    }
    return candidates;
  }

  public enum Result {
    POPUP_SHOWN,
    CLASS_AUTO_IMPORTED,
    POPUP_NOT_SHOWN
  }

  @Override
  public boolean fixSilently(@NotNull Editor editor) {
    PsiFile file = myReferenceElement.isValid() ? myReferenceElement.getContainingFile() : null;
    if (file == null || !ShowAutoImportPass.isAddUnambiguousImportsOnTheFlyEnabled(file)) return false;
    return doFix(editor, false, false, true) == Result.CLASS_AUTO_IMPORTED;
  }

  @NotNull
  public Result doFix(@NotNull Editor editor, boolean allowPopup, boolean allowCaretNearRef, boolean mayAddUnambiguousImportsSilently) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    //do not show popups for already imported classes when library is missing (show them for explicit action)
    List<? extends PsiClass> result = filterAlreadyImportedButUnresolved(getClassesToImport());
    if (result.isEmpty()) return Result.POPUP_NOT_SHOWN;

    try {
      String name = getQualifiedName(myReferenceElement);
      if (name != null) {
        Pattern pattern = Pattern.compile(DaemonCodeAnalyzerSettings.getInstance().NO_AUTO_IMPORT_PATTERN);
        Matcher matcher = pattern.matcher(name);
        if (matcher.matches()) {
          return Result.POPUP_NOT_SHOWN;
        }
      }
    }
    catch (PatternSyntaxException e) {
      //ignore
    }
    PsiFile psiFile = myReferenceElement.getContainingFile();
    if (result.size() > 1) {
      result = reduceSuggestedClassesBasedOnDependencyRuleViolation(psiFile, result);
    }
    PsiClass[] classes = result.toArray(PsiClass.EMPTY_ARRAY);
    Project project = myReferenceElement.getProject();
    CodeInsightUtil.sortIdenticalShortNamedMembers(classes, myReference);

    QuestionAction action = createAddImportAction(classes, project, editor);

    boolean canImportHere = true;

    if (classes.length == 1 &&
        (canImportHere = canImportHere(allowCaretNearRef, editor, psiFile, classes[0].getName())) &&
        mayAddUnambiguousImportsSilently &&
        !autoImportWillInsertUnexpectedCharacters(classes[0])) {
      CommandProcessor.getInstance().runUndoTransparentAction(() -> action.execute());
      return Result.CLASS_AUTO_IMPORTED;
    }

    if (allowPopup && canImportHere) {
      if (!ApplicationManager.getApplication().isUnitTestMode() && !HintManager.getInstance().hasShownHintsThatWillHideByOtherHint(true)) {
        String hintText = ShowAutoImportPass.getMessage(classes.length > 1, classes[0].getQualifiedName());
        HintManager.getInstance().showQuestionHint(editor, hintText, getStartOffset(myReferenceElement, myReference),
                                                   getEndOffset(myReferenceElement, myReference), action);
      }
      return Result.POPUP_SHOWN;
    }
    return Result.POPUP_NOT_SHOWN;
  }

  protected int getStartOffset(@NotNull T element, @NotNull R ref) {
    return element.getTextOffset();
  }

  protected int getEndOffset(@NotNull T element, @NotNull R ref) {
    return element.getTextRange().getEndOffset();
  }

  private static boolean autoImportWillInsertUnexpectedCharacters(@NotNull PsiClass aClass) {
    PsiClass containingClass = aClass.getContainingClass();
    // when importing inner class, the reference might be qualified with the outer class name, and it can be confusing
    return containingClass != null &&
           !CodeStyle.getSettings(aClass.getContainingFile()).getCustomSettings(JavaCodeStyleSettings.class).INSERT_INNER_CLASS_IMPORTS;
  }

  private boolean canImportHere(boolean allowCaretNearRef, @NotNull Editor editor, PsiFile psiFile, String exampleClassName) {
    return (allowCaretNearRef || !isCaretNearRef(editor, myReference)) &&
           !hasUnresolvedImportWhichCanImport(psiFile, exampleClassName);
  }

  protected abstract boolean isQualified(@NotNull R reference);

  @Override
  public boolean showHint(@NotNull Editor editor) {
    if (isQualified(myReference)) {
      return false;
    }
    PsiFile file = myReferenceElement.isValid() ? myReferenceElement.getContainingFile() : null;
    if (file == null) return false;

    Result result = doFix(editor, true, false, ShowAutoImportPass.mayAutoImportNow(file));
    return result == Result.POPUP_SHOWN || result == Result.CLASS_AUTO_IMPORTED;
  }

  @Override
  @NotNull
  public String getText() {
    return QuickFixBundle.message("import.class.fix");
  }

  @Override
  @NotNull
  public String getFamilyName() {
    return QuickFixBundle.message("import.class.fix");
  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }

  protected abstract boolean hasUnresolvedImportWhichCanImport(PsiFile psiFile, String name);

  private static @NotNull List<? extends PsiClass> reduceSuggestedClassesBasedOnDependencyRuleViolation(@NotNull PsiFile file, @NotNull List<? extends PsiClass> availableClasses) {
    Project project = file.getProject();
    DependencyValidationManager validationManager = DependencyValidationManager.getInstance(project);
    List<PsiClass> result = null;
    for (int i = availableClasses.size() - 1; i >= 0; i--) {
      PsiClass psiClass = availableClasses.get(i);
      PsiFile targetFile = psiClass.getContainingFile();
      if (targetFile == null) continue;
      DependencyRule[] violated = validationManager.getViolatorDependencyRules(file, targetFile);
      if (violated.length != 0) {
        if (result == null) {
          result = new ArrayList<>(availableClasses.size());
          result.addAll(availableClasses.subList(i+1, availableClasses.size()));
          Collections.reverse(result);
        }
        if (i == 1 && result.isEmpty()) {
          result.add(availableClasses.get(0));
          break;
        }
      }
      else if (result != null) {
        result.add(psiClass);
      }
    }

    if (result == null) {
      return availableClasses;
    }
    Collections.reverse(result);
    return result;
  }

  private boolean isCaretNearRef(@NotNull Editor editor, @NotNull R ref) {
    PsiElement nameElement = getReferenceNameElement(ref);
    if (nameElement == null) return false;
    TextRange range = nameElement.getTextRange();
    int offset = editor.getCaretModel().getOffset();

    return offset == range.getEndOffset();
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
    if (!FileModificationService.getInstance().prepareFileForWrite(file)) return;
    ApplicationManager.getApplication().runWriteAction(() -> {
      PsiClass[] classes = getClassesToImport(true).toArray(PsiClass.EMPTY_ARRAY);
      if (classes.length == 0) return;

      AddImportAction action = createAddImportAction(classes, project, editor);
      action.execute();
    });
  }

  protected void bindReference(@NotNull PsiReference reference, @NotNull PsiClass targetClass) {
    reference.bindToElement(targetClass);
  }

  @NotNull
  protected AddImportAction createAddImportAction(PsiClass @NotNull [] classes, @NotNull Project project, @NotNull Editor editor) {
    return new AddImportAction(project, myReference, editor, classes) {
      @Override
      protected void bindReference(@NotNull PsiReference ref, @NotNull PsiClass targetClass) {
        ImportClassFixBase.this.bindReference(ref, targetClass);
      }
    };
  }
}
