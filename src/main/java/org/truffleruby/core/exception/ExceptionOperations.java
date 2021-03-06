/*
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.exception;

import com.oracle.truffle.api.CompilerDirectives;
import org.truffleruby.RubyContext;
import org.truffleruby.core.klass.RubyClass;
import org.truffleruby.core.module.ModuleFields;
import org.truffleruby.core.proc.RubyProc;
import org.truffleruby.core.thread.ThreadNodes.ThreadGetExceptionNode;
import org.truffleruby.language.Nil;
import org.truffleruby.language.backtrace.Backtrace;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.Shape;
import org.truffleruby.language.library.RubyStringLibrary;

public abstract class ExceptionOperations {

    public static final String SUPER_METHOD_ERROR = "SUPER_METHOD_ERROR";
    public static final String PROTECTED_METHOD_ERROR = "PROTECTED_METHOD_ERROR";
    public static final String PRIVATE_METHOD_ERROR = "PRIVATE_METHOD_ERROR";
    public static final String NO_METHOD_ERROR = "NO_METHOD_ERROR";
    public static final String NO_LOCAL_VARIABLE_OR_METHOD_ERROR = "NO_LOCAL_VARIABLE_OR_METHOD_ERROR";

    @TruffleBoundary
    public static String getMessage(Throwable throwable) {
        return throwable.getMessage();
    }

    @TruffleBoundary
    private static String messageFieldToString(RubyException exception) {
        Object message = exception.message;
        RubyStringLibrary strings = RubyStringLibrary.getUncached();
        if (message == null || message == Nil.INSTANCE) {
            final ModuleFields exceptionClass = exception.getLogicalClass().fields;
            return exceptionClass.getName(); // What Exception#message would return if no message is set
        } else if (strings.isRubyString(message)) {
            return strings.getJavaString(message);
        } else {
            return message.toString();
        }
    }

    @TruffleBoundary
    public static String messageToString(RubyContext context, RubyException exception) {
        try {
            final Object messageObject = context.send(exception, "message");

            final RubyStringLibrary libString = RubyStringLibrary.getUncached();
            if (libString.isRubyString(messageObject)) {
                return libString.getJavaString(messageObject);
            }
        } catch (Throwable e) {
            // Fall back to the internal message field
        }
        return messageFieldToString(exception);
    }

    public static RubyException createRubyException(RubyContext context, RubyClass rubyClass, Object message,
            Node node, Throwable javaException) {
        final Backtrace backtrace = context.getCallStack().getBacktrace(node, 0, javaException);
        return createRubyException(context, rubyClass, message, backtrace);
    }

    @TruffleBoundary
    public static RubyException createRubyException(RubyContext context, RubyClass rubyClass, Object message,
            Backtrace backtrace) {
        final Object cause = ThreadGetExceptionNode.getLastException(context);
        context.getCoreExceptions().showExceptionIfDebug(rubyClass, message, backtrace);
        final Shape shape = rubyClass.instanceShape;
        return new RubyException(rubyClass, shape, message, backtrace, cause);
    }

    @TruffleBoundary
    public static RubySystemCallError createSystemCallError(RubyContext context, RubyClass rubyClass,
            Object message, int errno, Backtrace backtrace) {
        final Object cause = ThreadGetExceptionNode.getLastException(context);
        context.getCoreExceptions().showExceptionIfDebug(rubyClass, message, backtrace);
        final Shape shape = rubyClass.instanceShape;
        return new RubySystemCallError(rubyClass, shape, message, backtrace, cause, errno);
    }

    public static RubyProc getFormatter(String name, RubyContext context) {
        return (RubyProc) context.getCoreLibrary().truffleExceptionOperationsModule.fields.getConstant(name).getValue();
    }

    /** @see org.truffleruby.cext.CExtNodes.RaiseExceptionNode */
    public static RuntimeException rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException) {
            throw (RuntimeException) throwable;
        } else if (throwable instanceof Error) {
            throw (Error) throwable;
        } else {
            throw CompilerDirectives.shouldNotReachHere("Checked Java Throwable rethrown", throwable);
        }
    }

}
