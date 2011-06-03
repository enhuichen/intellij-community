package com.jetbrains.python.actions;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.psi.*;
import org.jetbrains.annotations.NotNull;

/**
 * Created by IntelliJ IDEA.
 * User: Alexey.Ivanov
 * Date: 16.02.2010
 * Time: 21:33:28
 */
public class ConvertSetLiteralQuickFix implements LocalQuickFix {
  @NotNull
  @Override
  public String getName() {
    return PyBundle.message("INTN.convert.set.literal.to");
  }

  @NotNull
  public String getFamilyName() {
    return PyBundle.message("INTN.Family.convert.set.literal");
  }

  @Override
  public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
    PsiElement setLiteral = descriptor.getPsiElement();
    if (setLiteral instanceof PySetLiteralExpression) {
      PyExpression[] expressions = ((PySetLiteralExpression)setLiteral).getElements();
      PyElementGenerator elementGenerator = PyElementGenerator.getInstance(project);
      assert expressions.length != 0;
      StringBuilder stringBuilder = new StringBuilder(expressions[0].getText());
      for (int i = 1; i < expressions.length; ++i) {
        stringBuilder.append(", ");
        stringBuilder.append(expressions[i].getText());
      }
      PyStatement newElement = elementGenerator.createFromText(LanguageLevel.getDefault(), PyExpressionStatement.class, "set([" + stringBuilder.toString() + "])");
      setLiteral.replace(newElement);
    }
  }
}
