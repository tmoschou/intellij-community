/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.openapi.options;

import org.jetbrains.annotations.NotNull;

@Deprecated
public class SharedScheme<E extends ExternalizableScheme> {
  private final String myUserName;
  private final String myDescription;
  private final E myScheme;

  public SharedScheme(@NotNull String userName, final String description, @NotNull E scheme) {
    myUserName = userName;
    myDescription = description;
    myScheme = scheme;
  }

  @NotNull
  public String getUserName() {
    return myUserName;
  }

  public String getDescription() {
    return myDescription;
  }

  @NotNull
  public E getScheme() {
    return myScheme;
  }
}
