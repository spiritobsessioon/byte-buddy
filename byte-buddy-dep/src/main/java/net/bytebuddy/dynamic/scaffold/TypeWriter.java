package net.bytebuddy.dynamic.scaffold;

import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.asm.ClassVisitorWrapper;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.instrumentation.Instrumentation;
import net.bytebuddy.instrumentation.TypeInitializer;
import net.bytebuddy.instrumentation.attribute.FieldAttributeAppender;
import net.bytebuddy.instrumentation.attribute.MethodAttributeAppender;
import net.bytebuddy.instrumentation.attribute.TypeAttributeAppender;
import net.bytebuddy.instrumentation.field.FieldDescription;
import net.bytebuddy.instrumentation.method.MethodDescription;
import net.bytebuddy.instrumentation.method.bytecode.ByteCodeAppender;
import net.bytebuddy.instrumentation.type.TypeDescription;
import net.bytebuddy.modifier.MethodManifestation;
import org.objectweb.asm.*;

/**
 * A type writer allows an easier creation of a dynamic type by enforcing the writing order
 * (type, annotations, fields, methods) that is required by ASM in order to successfully creating a Java type.
 * <p>&nbsp;</p>
 * Note: This type represents a mutable data structure since it is a wrapper around an ASM
 * {@link org.objectweb.asm.ClassWriter}. Once a phase of this type writer is left the old instances must not longer
 * be used.
 *
 * @param <T> The best known loaded type for the dynamically created type.
 */
public interface TypeWriter<T> {

    /**
     * The ASM flag for deactivating the manual computation of stack map frames or operand stack
     * and local variable array sizes.
     */
    static final int ASM_MANUAL_FLAG = 0;

    /**
     * Creates the dynamic type.
     *
     * @return An unloaded dynamic type that is the outcome of the type writing
     */
    DynamicType.Unloaded<T> make();

    /**
     * An field pool that allows a lookup for how to implement a field.
     */
    static interface FieldPool {

        /**
         * Returns the field attribute appender that matches a given field description or a default field
         * attribute appender if no appender was registered for the given field.
         *
         * @param fieldDescription The field description of interest.
         * @return The registered field attribute appender for the given field or the default appender if no such
         * appender was found.
         */
        Entry target(FieldDescription fieldDescription);

        /**
         * An entry of a field pool that describes how a field is implemented.
         *
         * @see net.bytebuddy.dynamic.scaffold.TypeWriter.FieldPool
         */
        static interface Entry {

            /**
             * Returns the field attribute appender factory for a given field.
             *
             * @return The attribute appender factory to be applied on the given field.
             */
            FieldAttributeAppender.Factory getFieldAppenderFactory();

            /**
             * A default implementation of a compiled field registry that simply returns a no-op
             * {@link net.bytebuddy.instrumentation.attribute.FieldAttributeAppender.Factory}
             * for any field.
             */
            static enum NoOp implements Entry {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public FieldAttributeAppender.Factory getFieldAppenderFactory() {
                    return FieldAttributeAppender.NoOp.INSTANCE;
                }
            }

            /**
             * A simple entry that creates a specific
             * {@link net.bytebuddy.instrumentation.attribute.FieldAttributeAppender.Factory}
             * for any field.
             */
            static class Simple implements Entry {

                /**
                 * The field attribute appender factory that is represented by this entry.
                 */
                private final FieldAttributeAppender.Factory attributeAppenderFactory;

                /**
                 * Creates a new simple entry for a given attribute appender factory.
                 *
                 * @param attributeAppenderFactory The attribute appender factory to be returned.
                 */
                public Simple(FieldAttributeAppender.Factory attributeAppenderFactory) {
                    this.attributeAppenderFactory = attributeAppenderFactory;
                }

                @Override
                public FieldAttributeAppender.Factory getFieldAppenderFactory() {
                    return attributeAppenderFactory;
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && attributeAppenderFactory.equals(((Simple) other).attributeAppenderFactory);
                }

                @Override
                public int hashCode() {
                    return attributeAppenderFactory.hashCode();
                }

                @Override
                public String toString() {
                    return "TypeWriter.FieldPool.Entry.Simple{attributeAppenderFactory=" + attributeAppenderFactory + '}';
                }
            }
        }
    }

    /**
     * An method pool that allows a lookup for how to implement a method.
     */
    static interface MethodPool {

        /**
         * Looks up a handler entry for a given method.
         *
         * @param methodDescription The method being processed.
         * @return A handler entry for the given method.
         */
        Entry target(MethodDescription methodDescription);

        /**
         * An entry of a method pool that describes how a method is implemented.
         *
         * @see net.bytebuddy.dynamic.scaffold.TypeWriter.MethodPool
         */
        static interface Entry {

            /**
             * Determines if this entry requires a method to be defined for a given instrumentation.
             *
             * @return {@code true} if a method should be defined for a given instrumentation.
             */
            boolean isDefineMethod();

            /**
             * The byte code appender to be used for the instrumentation by this entry. Must not
             * be called if {@link net.bytebuddy.dynamic.scaffold.TypeWriter.MethodPool.Entry#isDefineMethod()}
             * returns {@code false}.
             *
             * @return The byte code appender that is responsible for the instrumentation of a method matched for
             * this entry.
             */
            ByteCodeAppender getByteCodeAppender();

            /**
             * The method attribute appender that is to be used for the instrumentation by this entry.  Must not
             * be called if {@link net.bytebuddy.dynamic.scaffold.TypeWriter.MethodPool.Entry#isDefineMethod()}
             * returns {@code false}.
             *
             * @return The method attribute appender that is responsible for the instrumentation of a method matched for
             * this entry.
             */
            MethodAttributeAppender getAttributeAppender();

            /**
             * A skip entry that instructs to ignore a method.
             */
            static enum Skip implements Entry {

                /**
                 * The singleton instance.
                 */
                INSTANCE;

                @Override
                public boolean isDefineMethod() {
                    return false;
                }

                @Override
                public ByteCodeAppender getByteCodeAppender() {
                    throw new IllegalStateException();
                }

                @Override
                public MethodAttributeAppender getAttributeAppender() {
                    throw new IllegalStateException();
                }
            }

            /**
             * A default implementation of {@link net.bytebuddy.dynamic.scaffold.TypeWriter.MethodPool.Entry}
             * that is not to be ignored but is represented by a tuple of a byte code appender and a method attribute appender.
             */
            static class Simple implements Entry {

                /**
                 * The byte code appender that is represented by this entry.
                 */
                private final ByteCodeAppender byteCodeAppender;

                /**
                 * The method attribute appender that is represented by this entry.
                 */
                private final MethodAttributeAppender methodAttributeAppender;

                /**
                 * Creates a new simple entry of a method pool.
                 *
                 * @param byteCodeAppender        The byte code appender that is represented by this entry.
                 * @param methodAttributeAppender The method attribute appender that is represented by this entry.
                 */
                public Simple(ByteCodeAppender byteCodeAppender, MethodAttributeAppender methodAttributeAppender) {
                    this.byteCodeAppender = byteCodeAppender;
                    this.methodAttributeAppender = methodAttributeAppender;
                }

                @Override
                public boolean isDefineMethod() {
                    return true;
                }

                @Override
                public ByteCodeAppender getByteCodeAppender() {
                    return byteCodeAppender;
                }

                @Override
                public MethodAttributeAppender getAttributeAppender() {
                    return methodAttributeAppender;
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && byteCodeAppender.equals(((Simple) other).byteCodeAppender)
                            && methodAttributeAppender.equals(((Simple) other).methodAttributeAppender);
                }

                @Override
                public int hashCode() {
                    return 31 * byteCodeAppender.hashCode() + methodAttributeAppender.hashCode();
                }

                @Override
                public String toString() {
                    return "Default{" +
                            "byteCodeAppender=" + byteCodeAppender +
                            ", methodAttributeAppender=" + methodAttributeAppender +
                            '}';
                }
            }
        }
    }

    /**
     * Describes a phase that can transition into a phase for writing fields to a type.
     *
     * @param <T> The best known loaded type for the dynamically created type.
     */
    static interface FieldPhaseTransitional<T> {

        /**
         * Moves to the field phase.
         *
         * @return This type writer in its field phase.
         */
        InFieldPhase<T> fields();
    }

    /**
     * Describes a phase that can transition into a phase for writing methods to a type.
     *
     * @param <T> The best known loaded type for the dynamically created type.
     */
    static interface MethodPhaseTransitional<T> {

        /**
         * Moves to the method phase.
         *
         * @return This type writer in its method phase.
         */
        InMethodPhase<T> methods();
    }

    /**
     * Describes a type writer currently in the general phase, i.e. in the phase before fields or methods
     * are written to the type.
     *
     * @param <T> The best known loaded type for the dynamically created type.
     */
    static interface InGeneralPhase<T> extends TypeWriter<T>, FieldPhaseTransitional<T>, MethodPhaseTransitional<T> {

        /**
         * Writes an attribute to the type that is created by this type writer.
         *
         * @param typeAttributeAppender The type attribute appender to be applied to the type that is represented by
         *                              this type writer.
         * @return This type writer.
         */
        InGeneralPhase<T> attributeType(TypeAttributeAppender typeAttributeAppender);
    }

    /**
     * Describes a type writer currently in the field phase, i.e. in the phase before methods are applied but
     * after any type meta information are written to the type.
     *
     * @param <T> The best known loaded type for the dynamically created type.
     */
    static interface InFieldPhase<T> extends TypeWriter<T>, MethodPhaseTransitional<T> {

        /**
         * Adds a number of fields as described by the argument to the type that is created by this type
         * writer where the annotations are received from the given compiled field registry.
         *
         * @param fieldDescriptions The fields to be added to the type that is created by this type writer.
         * @param fieldPool         The field pool that is queried for finding annotations for written fields.
         * @return This type writer.
         */
        InFieldPhase<T> write(Iterable<? extends FieldDescription> fieldDescriptions, FieldPool fieldPool);
    }

    /**
     * Describes a type writer currently in the method phase, i.e. in the phase after any type meta information
     * is added to a type and after its fields are written.
     *
     * @param <T> The best known loaded type for the dynamically created type.
     */
    static interface InMethodPhase<T> extends TypeWriter<T> {

        /**
         * Adds a number of methods as described by the argument to the type that is created by this type
         * writer where the implementations and annotations are received from the given compiled method registry.
         *
         * @param methodDescriptions The methods to be added to the type that is created by this type writer.
         * @param methodPool         The method pool that is queried for creating implementations for these methods.
         * @return This type writer.
         */
        InMethodPhase<T> write(Iterable<? extends MethodDescription> methodDescriptions, MethodPool methodPool);
    }

    /**
     * A builder that creates a new type writer for given arguments.
     *
     * @param <T> The best known loaded type for the dynamically created type.
     */
    static class Builder<T> {

        /**
         * The type description of the instrumented type that is represented by this builder.
         */
        private final TypeDescription instrumentedType;

        /**
         * The type initializer of the instrumented type that is represented by this builder.
         */
        private final TypeInitializer typeInitializer;

        /**
         * An extractable view of the instrumentation context that is represented by this builder.
         */
        private final Instrumentation.Context.ExtractableView instrumentationContext;

        /**
         * The class file version this instrumented type is to be written in by this builder.
         */
        private final ClassFileVersion classFileVersion;

        /**
         * Creates a new builder.
         *
         * @param instrumentedType       The type description of the instrumented type that is to be created.
         * @param typeInitializer        The type initializer of the instrumented type that is to be created.
         * @param instrumentationContext An extractable view of the instrumentation context.
         * @param classFileVersion       The class file version this instrumented type is to be written in.
         */
        public Builder(TypeDescription instrumentedType,
                       TypeInitializer typeInitializer,
                       Instrumentation.Context.ExtractableView instrumentationContext,
                       ClassFileVersion classFileVersion) {
            this.instrumentedType = instrumentedType;
            this.typeInitializer = typeInitializer;
            this.instrumentationContext = instrumentationContext;
            this.classFileVersion = classFileVersion;
        }

        /**
         * Alters the modifiers of a method to reflect the actual implementation of a method to be {@code abstract}
         * if a method is not implemented or to be non-abstract if it is implemented.
         *
         * @param methodDescription The method being processed.
         * @param appendsCode       {@code true} if the method does append code, i.e. is non-abstract.
         * @return The filter modifiers of this method depending on whether it is {@code abstract} or not.
         */
        private static int overrideModifiers(MethodDescription methodDescription, boolean appendsCode) {
            if (appendsCode && (methodDescription.isAbstract() || methodDescription.isNative())) {
                return methodDescription.getModifiers() & ~MethodManifestation.ABSTRACTION_MASK;
            } else if (!appendsCode && !methodDescription.isAbstract() && !methodDescription.isNative()) {
                return methodDescription.getModifiers() | Opcodes.ACC_ABSTRACT;
            } else {
                return methodDescription.getModifiers();
            }
        }

        /**
         * Creates a new type writer and moves it to its general phase.
         *
         * @param classVisitorWrapper A class visitor wrapper to be applied to the ASM class writing process.
         * @return A new type writer for the given type.
         */
        public InGeneralPhase<T> build(ClassVisitorWrapper classVisitorWrapper) {
            ClassWriter classWriter = new ClassWriter(ASM_MANUAL_FLAG);
            ClassVisitor classVisitor = classVisitorWrapper.wrap(classWriter);
            classVisitor.visit(classFileVersion.getVersionNumber(),
                    instrumentedType.getModifiers(),
                    instrumentedType.getInternalName(),
                    null,
                    instrumentedType.getSupertype() == null ? null : instrumentedType.getSupertype().getInternalName(),
                    instrumentedType.getInterfaces().toInternalNames());
            return new Handler<T>(classWriter, classVisitor);
        }

        @Override
        public String toString() {
            return "TypeWriter.Builder{" +
                    "instrumentedType=" + instrumentedType +
                    ", typeInitializer=" + typeInitializer +
                    ", instrumentationContext=" + instrumentationContext +
                    ", classFileVersion=" + classFileVersion +
                    '}';
        }

        /**
         * An implementation of a handler that is capable of writing a type while enforcing the order that is required
         * by the ASM library that is used for carrying out this task.
         *
         * @param <S> The most specific type of the class that is being created by this type writer.
         */
        private class Handler<S> implements TypeWriter<S>, InGeneralPhase<S>, InFieldPhase<S>, InMethodPhase<S> {

            /**
             * The class writer that is writing the class.
             */
            protected final ClassWriter classWriter;

            /**
             * The top most class visitor any
             */
            protected final ClassVisitor classVisitor;

            /**
             * Creates a new handler.
             *
             * @param classWriter  the class writer that is writing the currently created type.
             * @param classVisitor The top-most class visitor that all instructions are delegated to.
             */
            protected Handler(ClassWriter classWriter, ClassVisitor classVisitor) {
                this.classWriter = classWriter;
                this.classVisitor = classVisitor;
            }

            @Override
            public InFieldPhase<S> write(Iterable<? extends FieldDescription> fieldDescriptions, FieldPool fieldPool) {
                for (FieldDescription fieldDescription : fieldDescriptions) {
                    FieldVisitor fieldVisitor = classVisitor.visitField(fieldDescription.getModifiers(),
                            fieldDescription.getInternalName(),
                            fieldDescription.getDescriptor(),
                            null,
                            null);
                    fieldPool.target(fieldDescription)
                            .getFieldAppenderFactory()
                            .make(instrumentedType)
                            .apply(fieldVisitor, fieldDescription);
                    fieldVisitor.visitEnd();
                }
                return this;
            }

            @Override
            public InGeneralPhase<S> attributeType(TypeAttributeAppender typeAttributeAppender) {
                typeAttributeAppender.apply(classVisitor, instrumentedType);
                return this;
            }

            @Override
            public InFieldPhase<S> fields() {
                return this;
            }

            @Override
            public InMethodPhase<S> methods() {
                return this;
            }

            @Override
            public InMethodPhase<S> write(Iterable<? extends MethodDescription> methodDescriptions, MethodPool methodPool) {
                for (MethodDescription methodDescription : methodDescriptions) {
                    MethodPool.Entry entry = methodPool.target(methodDescription);
                    if (entry.isDefineMethod()) {
                        boolean appendsCode = entry.getByteCodeAppender().appendsCode();
                        MethodVisitor methodVisitor = classVisitor.visitMethod(
                                overrideModifiers(methodDescription, appendsCode),
                                methodDescription.getInternalName(),
                                methodDescription.getDescriptor(),
                                null,
                                methodDescription.getExceptionTypes().toInternalNames());
                        entry.getAttributeAppender().apply(methodVisitor, methodDescription);
                        if (appendsCode) {
                            methodVisitor.visitCode();
                            ByteCodeAppender.Size size = entry.getByteCodeAppender().apply(methodVisitor,
                                    instrumentationContext,
                                    methodDescription);
                            methodVisitor.visitMaxs(size.getOperandStackSize(), size.getLocalVariableSize());
                        }
                        methodVisitor.visitEnd();
                    }
                }
                return this;
            }

            @Override
            public DynamicType.Unloaded<S> make() {
                classVisitor.visitEnd();
                return new DynamicType.Default.Unloaded<S>(instrumentedType,
                        classWriter.toByteArray(),
                        typeInitializer,
                        instrumentationContext.getRegisteredAuxiliaryTypes());
            }

            @Override
            public String toString() {
                return "TypeWriter.Builder.Handler{" +
                        "builder=" + Builder.this +
                        ", classWriter=" + classWriter +
                        ", classVisitor=" + classVisitor +
                        '}';
            }
        }
    }
}