/*
 * Copyright (c) 2015, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.objects;

import java.util.Set;

public interface ObjectGraphNode {

    /** Only {@link ObjectGraph#isSymbolOrDynamicObject(Object)} objects should be added to the set. */
    void getAdjacentObjects(Set<Object> reachable);

}
