package com.blogspot.mydailyjava.bytebuddy.instrumentation.method.bytecode;

import com.blogspot.mydailyjava.bytebuddy.instrumentation.Instrumentation;
import com.blogspot.mydailyjava.bytebuddy.instrumentation.method.MethodDescription;
import org.objectweb.asm.MethodVisitor;

import java.util.Arrays;

/**
 * An appender that generates the byte code for a given method. This is done by writing the byte code instructions to
 * the given ASM {@link org.objectweb.asm.MethodVisitor}.
 * <p/>
 * The {@code ByteCodeAppender} is not allowed to write
 * annotations to the method or call the {@link org.objectweb.asm.MethodVisitor#visitCode()},
 * {@link org.objectweb.asm.MethodVisitor#visitMaxs(int, int)} or {@link org.objectweb.asm.MethodVisitor#visitEnd()}
 * methods which is both done by the entity delegating the call to the {@code ByteCodeAppender}. This is done in order
 * to allow for the concatenation of several byte code appenders and therefore a more modular description of method
 * implementations.
 */
public interface ByteCodeAppender {

    /**
     * An immutable description of both the operand stack size and the size of the local variable array that is
     * required to run the code generated by this {@code ByteCodeAppender}.
     */
    static class Size {

        private final int operandStackSize;
        private final int localVariableSize;

        /**
         * @param operandStackSize  The operand stack size that is required for running given byte code.
         * @param localVariableSize The local variable array size that is required for running given byte code.
         */
        public Size(int operandStackSize, int localVariableSize) {
            this.operandStackSize = operandStackSize;
            this.localVariableSize = localVariableSize;
        }

        /**
         * Returns the required operand stack size.
         *
         * @return The required operand stack size.
         */
        public int getOperandStackSize() {
            return operandStackSize;
        }

        /**
         * Returns the required size of the local variable array.
         *
         * @return The required size of the local variable array.
         */
        public int getLocalVariableSize() {
            return localVariableSize;
        }

        /**
         * Merges two sizes in order to describe the size that is required by both size descriptions.
         *
         * @param other The other size description.
         * @return A size description incorporating both size requirements.
         */
        public Size merge(Size other) {
            return new Size(Math.max(operandStackSize, other.operandStackSize), Math.max(localVariableSize, other.localVariableSize));
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && localVariableSize == ((Size) other).localVariableSize
                    && operandStackSize == ((Size) other).operandStackSize;
        }

        @Override
        public int hashCode() {
            return 31 * operandStackSize + localVariableSize;
        }

        @Override
        public String toString() {
            return "ByteCodeAppender.Size{operandStackSize=" + operandStackSize + ", localVariableSize=" + localVariableSize + '}';
        }
    }

    /**
     * A compound appender that combines a given number of other byte code appenders.
     */
    static class Compound implements ByteCodeAppender {

        private final ByteCodeAppender[] byteCodeAppender;

        /**
         * Creates a new compound byte code appender.
         *
         * @param byteCodeAppender The byte code appenders to combine in their order.
         */
        public Compound(ByteCodeAppender... byteCodeAppender) {
            this.byteCodeAppender = byteCodeAppender;
        }

        @Override
        public boolean appendsCode() {
            for (ByteCodeAppender byteCodeAppender : this.byteCodeAppender) {
                if (byteCodeAppender.appendsCode()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Size apply(MethodVisitor methodVisitor, Instrumentation.Context instrumentationContext, MethodDescription instrumentedMethod) {
            Size size = new Size(0, instrumentedMethod.getStackSize());
            for (ByteCodeAppender byteCodeAppender : this.byteCodeAppender) {
                size = size.merge(byteCodeAppender.apply(methodVisitor, instrumentationContext, instrumentedMethod));
            }
            return size;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && Arrays.equals(byteCodeAppender, ((Compound) other).byteCodeAppender);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(byteCodeAppender);
        }

        @Override
        public String toString() {
            return "ByteCodeAppender.Compound{" + Arrays.toString(byteCodeAppender) + '}';
        }
    }

    /**
     * Determines if this byte code appender offers an (possibly empty) implementation of a method.
     *
     * @return {@code true} if this byte code appender requires this method to be implemented or {@code false} if this
     * appender describes an abstract method.
     */
    boolean appendsCode();

    /**
     * Applies this byte code appender to a type creation process.
     *
     * @param methodVisitor          The method visitor to which the byte code appender writes its code to.
     * @param instrumentationContext The instrumentation context of the current type creation process.
     * @param instrumentedMethod     The method that is the target of the instrumentation.
     * @return The required size for the applied byte code to run.
     */
    Size apply(MethodVisitor methodVisitor, Instrumentation.Context instrumentationContext, MethodDescription instrumentedMethod);
}
