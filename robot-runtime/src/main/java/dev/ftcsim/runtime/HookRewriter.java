package dev.ftcsim.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Redirects the JDK calls listed in {@link RewriteRules} to the simulator's hooks, both for direct calls and
 * for method references such as {@code System::nanoTime}, and tells the hooks when the thread acquires or
 * releases a monitor, through {@code synchronized} blocks and methods alike, so a host can keep a thread from
 * giving up its turn while it holds one. Each replacement leaves the operand stack exactly as the original code
 * did, so frames and stack limits stay valid and nothing is recomputed (recomputing frames would require loading
 * classes in the middle of loading a class). A redirected constructor is the one case that is not a call for a
 * call: see {@code redirectConstructor}.
 */
public final class HookRewriter implements BytecodeTransformer {
    private final RewriteRules rules;

    public HookRewriter() {
        this(RewriteRules.standard());
    }

    HookRewriter(RewriteRules rules) {
        this.rules = rules;
    }

    @Override
    public byte[] transform(String internalName, byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(0);
        Rewriter rewriter = new Rewriter(writer);
        reader.accept(rewriter, 0);
        return rewriter.changed ? writer.toByteArray() : classBytes;
    }

    private final class Rewriter extends ClassVisitor {
        boolean changed;
        private String className;

        Rewriter(ClassVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (next == null) {
                return null;
            }
            MethodVisitor visitor = new CallSites(next);
            if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                visitor = new SynchronizedMethod(visitor, access, descriptor);
            }
            var entryHook = rules.entryHook(className, name, descriptor);
            if (entryHook.isPresent()) {
                visitor = new EntryHooked(visitor, entryHook.get().hookName());
            }
            return visitor;
        }

        private void callHook(MethodVisitor visitor, String hookName) {
            changed = true;
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, RewriteRules.HOOKS, hookName, "()V", false);
        }

        /** Runs a hook before the first instruction of a method. */
        private final class EntryHooked extends MethodVisitor {
            private final String hookName;

            EntryHooked(MethodVisitor next, String hookName) {
                super(Opcodes.ASM9, next);
                this.hookName = hookName;
            }

            @Override
            public void visitCode() {
                super.visitCode();
                callHook(mv, hookName);
            }
        }

        /**
         * A {@code synchronized} method holds its monitor from the first instruction to its last, however it
         * ends, so the hooks are called at the start, before every return, and from a handler that covers the
         * whole body for the exceptional exit. The handler is registered last so every handler the method already
         * has still sees an exception first.
         */
        private final class SynchronizedMethod extends MethodVisitor {
            private final Label start = new Label();
            private final Label end = new Label();
            private final Label handler = new Label();
            private final Object[] locals;

            SynchronizedMethod(MethodVisitor next, int access, String descriptor) {
                super(Opcodes.ASM9, next);
                List<Object> frameLocals = new ArrayList<>();
                if ((access & Opcodes.ACC_STATIC) == 0) {
                    frameLocals.add(className);
                }
                for (Type argument : Type.getArgumentTypes(descriptor)) {
                    frameLocals.add(frameType(argument));
                }
                locals = frameLocals.toArray();
            }

            private Object frameType(Type type) {
                return switch (type.getSort()) {
                    case Type.LONG -> Opcodes.LONG;
                    case Type.FLOAT -> Opcodes.FLOAT;
                    case Type.DOUBLE -> Opcodes.DOUBLE;
                    case Type.OBJECT, Type.ARRAY -> type.getInternalName();
                    default -> Opcodes.INTEGER;
                };
            }

            @Override
            public void visitCode() {
                super.visitCode();
                callHook(mv, "lockAcquired");
                super.visitLabel(start);
            }

            @Override
            public void visitInsn(int opcode) {
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                    callHook(mv, "lockReleased");
                }
                super.visitInsn(opcode);
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {
                super.visitLabel(end);
                super.visitLabel(handler);
                super.visitFrame(Opcodes.F_FULL, locals.length, locals, 1, new Object[] {"java/lang/Throwable"});
                callHook(mv, "lockReleased");
                super.visitInsn(Opcodes.ATHROW);
                super.visitTryCatchBlock(start, end, handler, null);
                super.visitMaxs(Math.max(maxStack, 1), maxLocals);
            }
        }

        private final class CallSites extends MethodVisitor {
            /** Labels already placed: a jump to one of them goes backwards, which is how a loop repeats. */
            private final Set<Label> placed = new HashSet<>();
            /** Objects of redirected types that have been allocated but not yet constructed, innermost first. */
            private final Deque<String> unconstructed = new ArrayDeque<>();
            private boolean constructorRedirected;
            private boolean seededRedirected;

            CallSites(MethodVisitor next) {
                super(Opcodes.ASM9, next);
            }

            @Override
            public void visitLabel(Label label) {
                placed.add(label);
                super.visitLabel(label);
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                if (placed.contains(label)) {
                    callHook(mv, "backEdge");
                }
                super.visitJumpInsn(opcode, label);
            }

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                if (jumpsBack(dflt, labels)) {
                    callHook(mv, "backEdge");
                }
                super.visitTableSwitchInsn(min, max, dflt, labels);
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                if (jumpsBack(dflt, labels)) {
                    callHook(mv, "backEdge");
                }
                super.visitLookupSwitchInsn(dflt, keys, labels);
            }

            private boolean jumpsBack(Label dflt, Label[] labels) {
                return placed.contains(dflt) || Arrays.stream(labels).anyMatch(placed::contains);
            }

            @Override
            public void visitInsn(int opcode) {
                super.visitInsn(opcode);
                if (opcode == Opcodes.MONITORENTER) {
                    callHook(mv, "lockAcquired");
                } else if (opcode == Opcodes.MONITOREXIT) {
                    callHook(mv, "lockReleased");
                }
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                if (opcode == Opcodes.NEW && rules.constructs(type)) {
                    unconstructed.push(type);
                }
                super.visitTypeInsn(opcode, type);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                if (opcode == Opcodes.INVOKESPECIAL && RewriteRule.CONSTRUCTOR.equals(name)) {
                    // Either completes the innermost allocation, or is a super(...) or this(...) call, which stays.
                    if (owner.equals(unconstructed.peek())) {
                        unconstructed.pop();
                        var factory = rules.find(owner, name, descriptor, true);
                        if (factory.isPresent()) {
                            redirectConstructor(factory.get());
                            return;
                        }
                    }
                    var seeded = rules.seeded(owner, descriptor);
                    if (seeded.isPresent()) {
                        redirectSeeded(seeded.get());
                        return;
                    }
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    return;
                }
                var rule = rules.find(owner, name, descriptor, opcode != Opcodes.INVOKESTATIC);
                if (rule.isPresent() && redirectable(opcode, rule.get())) {
                    redirectCall(rule.get());
                } else {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }
            }

            /**
             * A call to the superclass's method from an override has to stay: its replacement would call the
             * method again through the receiver and arrive back in the override. Methods that no class can override
             * have no such problem.
             */
            private boolean redirectable(int opcode, RewriteRule rule) {
                return opcode != Opcodes.INVOKESPECIAL || RewriteRule.ANY_OWNER.equals(rule.owner());
            }

            private void redirectCall(RewriteRule rule) {
                changed = true;
                super.visitMethodInsn(Opcodes.INVOKESTATIC, rule.hookOwner(), rule.hookName(), rule.hookDescriptor(), false);
            }

            /**
             * {@code new T(arguments)} compiles to {@code NEW T, DUP, arguments, INVOKESPECIAL T.<init>}, leaving the new
             * object on the stack. The factory takes the same arguments and returns the object, which leaves the
             * allocated but never constructed object and its copy beneath it, and those are swapped out and popped. The
             * verifier accepts an abandoned uninitialised object, so every frame the compiler wrote stays correct.
             */
            private void redirectConstructor(RewriteRule factory) {
                changed = true;
                constructorRedirected = true;
                super.visitMethodInsn(Opcodes.INVOKESTATIC, factory.hookOwner(), factory.hookName(), factory.hookDescriptor(), false);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitInsn(Opcodes.POP);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitInsn(Opcodes.POP);
            }

            /**
             * A constructor that takes one argument more than the program gave it, which the hook supplies as the last one:
             * {@code super()} of a subclass of {@code Random} becomes {@code super(seed)}. The long is pushed above the
             * arguments, and the constructor of the longer descriptor takes them all.
             */
            private void redirectSeeded(RewriteRules.SeededConstructor seeded) {
                changed = true;
                seededRedirected = true;
                super.visitMethodInsn(Opcodes.INVOKESTATIC, seeded.hookOwner(), seeded.hookName(), "()J", false);
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, seeded.owner(), RewriteRule.CONSTRUCTOR, seeded.seededDescriptor(), false);
            }

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {
                if (!unconstructed.isEmpty()) {
                    throw new IllegalStateException("a " + unconstructed.peek() + " is allocated but not constructed in the same method");
                }
                // The factory's result sits above the two abandoned references for an instant, and a seed takes two slots.
                super.visitMaxs(maxStack + (seededRedirected ? 2 : constructorRedirected ? 1 : 0), maxLocals);
            }

            @Override
            public void visitInvokeDynamicInsn(
                    String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                Object[] mapped = bootstrapMethodArguments.clone();
                for (int i = 0; i < mapped.length; i++) {
                    mapped[i] = redirect(mapped[i]);
                }
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, mapped);
            }

            @Override
            public void visitLdcInsn(Object value) {
                super.visitLdcInsn(redirect(value));
            }

            private Object redirect(Object constant) {
                if (constant instanceof Handle handle) {
                    var rule = rules.find(handle.getOwner(), handle.getName(), handle.getDesc(), handle.getTag() != Opcodes.H_INVOKESTATIC);
                    boolean superCall = handle.getTag() == Opcodes.H_INVOKESPECIAL;
                    if (rule.isPresent() && (!superCall || RewriteRule.ANY_OWNER.equals(rule.get().owner()))) {
                        changed = true;
                        return new Handle(
                                Opcodes.H_INVOKESTATIC, rule.get().hookOwner(), rule.get().hookName(), rule.get().hookDescriptor(), false);
                    }
                }
                return constant;
            }
        }
    }
}
