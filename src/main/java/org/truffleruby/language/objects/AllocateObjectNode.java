/*
 * Copyright (c) 2015, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.objects;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.instrumentation.AllocationReporter;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.DynamicObjectFactory;
import com.oracle.truffle.api.source.SourceSection;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.Layouts;
import org.truffleruby.core.objectspace.ObjectSpaceManager;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.control.RaiseException;

@ReportPolymorphism
@GenerateUncached
public abstract class AllocateObjectNode extends Node {

    public static AllocateObjectNode create() {
        return AllocateObjectNodeGen.create();
    }

    public DynamicObject allocate(DynamicObject classToAllocate, Object... values) {
        return executeAllocate(classToAllocate, values);
    }

    public DynamicObject allocateArray(
            DynamicObject classToAllocate,
            Object store,
            int size) {
        return allocate(classToAllocate, store, size);
    }

    protected abstract DynamicObject executeAllocate(DynamicObject classToAllocate, Object[] values);

    @Specialization(guards = {
            "cachedClassToAllocate == classToAllocate",
            "!cachedIsSingleton",
            "!isTracing(rubyContext)"
    }, assumptions = "getTracingAssumption()", limit = "getCacheLimit()")
    public DynamicObject allocateCached(
            DynamicObject classToAllocate,
            Object[] values,
            @Cached("classToAllocate") DynamicObject cachedClassToAllocate,
            @Cached("isSingleton(classToAllocate)") boolean cachedIsSingleton,
            @Cached("getInstanceFactory(classToAllocate)") DynamicObjectFactory factory,
            @CachedContext(RubyLanguage.class) RubyContext rubyContext) {
        return allocate(rubyContext, factory, values);
    }

    @CompilerDirectives.TruffleBoundary
    @Specialization(
            replaces = "allocateCached",
            guards = { "!isSingleton(classToAllocate)", "!isTracing(rubyContext)" },
            assumptions = "getTracingAssumption()")
    public DynamicObject allocateUncached(
            DynamicObject classToAllocate, Object[] values,
            @CachedContext(RubyLanguage.class) RubyContext rubyContext) {
        return allocate(rubyContext, getInstanceFactory(classToAllocate), values);
    }

    @CompilerDirectives.TruffleBoundary
    @Specialization(guards = {"!isSingleton(classToAllocate)", "isTracing(rubyContext)"},
                    assumptions = "getTracingAssumption()")
    public DynamicObject allocateTracing(DynamicObject classToAllocate, Object[] values,
            @CachedContext(RubyLanguage.class) RubyContext rubyContext) {
        final DynamicObject object = allocate(rubyContext, getInstanceFactory(classToAllocate), values);

        final ObjectSpaceManager objectSpaceManager = rubyContext.getObjectSpaceManager();
        if (!objectSpaceManager.isTracingPaused()) {
            objectSpaceManager.setTracingPaused(true);
            try {
                callTraceAllocation(rubyContext, object);
            } finally {
                objectSpaceManager.setTracingPaused(false);
            }
        }

        return object;
    }

    @CompilerDirectives.TruffleBoundary
    private void callTraceAllocation(RubyContext rubyContext, DynamicObject object) {
        final SourceSection allocatingSourceSection = rubyContext.getCallStack().getTopMostUserSourceSection(getEncapsulatingSourceSection());

        final Frame allocatingFrame = getContext().getCallStack().getCurrentFrame(FrameAccess.READ_ONLY);

        final Object allocatingSelf = RubyArguments.getSelf(allocatingFrame);
        final String allocatingMethod = RubyArguments.getMethod(allocatingFrame).getName();

        rubyContext.send(rubyContext.getCoreLibrary().getObjectSpaceModule(),
                "trace_allocation",
                object,
                string(rubyContext, Layouts.CLASS.getFields(rubyContext.getCoreLibrary().getLogicalClass(allocatingSelf)).getName()),
                rubyContext.getSymbolTable().getSymbol(allocatingMethod),
                string(rubyContext, rubyContext.getPath(allocatingSourceSection.getSource())),
                allocatingSourceSection.getStartLine(),
                ObjectSpaceManager.getCollectionCount());
    }

    protected DynamicObjectFactory getInstanceFactory(DynamicObject classToAllocate) {
        return Layouts.CLASS.getInstanceFactory(classToAllocate);
    }

    private DynamicObject string(RubyContext rubyContext, String value) {
        // No point to use MakeStringNode (which uses AllocateObjectNode) here, as we should not trace the allocation
        // of Strings used for tracing allocations.
        return StringOperations.createString(rubyContext, StringOperations.encodeRope(value, UTF8Encoding.INSTANCE));
    }

    @Specialization(guards = "isSingleton(classToAllocate)")
    public DynamicObject allocateSingleton(
            DynamicObject classToAllocate, Object[] values,
            @CachedContext(RubyLanguage.class) RubyContext rubyContext) {
        throw new RaiseException(
                rubyContext,
                rubyContext.getCoreExceptions().typeErrorCantCreateInstanceOfSingletonClass(this));
    }

    protected Assumption getTracingAssumption() {
        return lookupContextReference(RubyLanguage.class).get().getObjectSpaceManager().getTracingAssumption();
    }

    protected static boolean isTracing(RubyContext rubyContext) {
        return rubyContext.getObjectSpaceManager().isTracing();
    }

    protected static boolean isSingleton(DynamicObject classToAllocate) {
        return Layouts.CLASS.getIsSingleton(classToAllocate);
    }

    protected int getCacheLimit() {
        // TODO (pitr-ch 06-Jun-2019): ok?
        return RubyLanguage.getCurrentContext().getOptions().ALLOCATE_CLASS_CACHE;
    }

    private DynamicObject allocate(RubyContext rubyContext, DynamicObjectFactory factory, Object[] values) {
        final AllocationReporter allocationReporter = rubyContext.getAllocationReporter();

        if (allocationReporter.isActive()) {
            allocationReporter.onEnter(null, 0, AllocationReporter.SIZE_UNKNOWN);
        }

        final DynamicObject object = factory.newInstance(values);

        if (allocationReporter.isActive()) {
            allocationReporter.onReturnValue(object, 0, AllocationReporter.SIZE_UNKNOWN);
        }

        return object;
    }

}
