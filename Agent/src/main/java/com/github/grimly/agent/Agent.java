package com.github.grimly.agent;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.ListIterator;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 *
 * @author Grimly
 */
public class Agent {

    public static void premain(String arg, Instrumentation inst) {
        inst.addTransformer((loader, className, classBeingRedefined, protectionDomain, classfileBuffer) -> {
            if ("com/github/grimly/application/Source".equals(className)) {
                try {
                    System.out.println("Transforming the source.");
                    byte[] bytes = transform(classfileBuffer);
                    System.out.println("Done.");
                    return bytes;
                } catch (Throwable e) {
                    e.printStackTrace(System.err);
                }
            }
            return null;
        });
    }

    public static byte[] transform(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        classNode.methods.stream()
                .forEach(Agent::installListeners);
        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    public static void installListeners(MethodNode method) {
        String methodId = String.format("%s%s", method.name, method.desc);

        installEnterListener(method, methodId);

        installEndListener(method, methodId);
    }

    public static void installEnterListener(MethodNode method, String methodId) {
        InsnList callEnter = new InsnList();
        Type[] argumentTypes = Type.getArgumentTypes(method.desc);
        callEnter.add(new LdcInsnNode(methodId));
        if (isStatic(method) || isConstructor(method)) {
            callEnter.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            callEnter.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        callEnter.add(stackIntInsnNode(argumentTypes.length));
        callEnter.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < argumentTypes.length; i++) {
            Type argumentType = argumentTypes[i];
            callEnter.add(new InsnNode(Opcodes.DUP));
            callEnter.add(stackIntInsnNode(i));
            callEnter.add(new VarInsnNode(argumentType.getOpcode(Opcodes.ILOAD), isStatic(method) ? i : i+1 ));
            callEnter.add(castToObject(argumentType));
            callEnter.add(new InsnNode(Opcodes.AASTORE));
        }
        callEnter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/github/grimly/agent/Agent", "enter", "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Void;", true));
        callEnter.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(callEnter);

        method.maxStack = Math.max(method.maxStack, 6);
    }

    public static void installEndListener(MethodNode method, String methodId) {
        Type returnType = Type.getReturnType(method.desc);
        int adaptedOpCode = returnType.getOpcode(Opcodes.IRETURN);
        Set<AbstractInsnNode> returnNodes = new HashSet<>();
        for (ListIterator<AbstractInsnNode> iterator = method.instructions.iterator(); iterator.hasNext();) {
            AbstractInsnNode instruction = iterator.next();
            if (instruction.getOpcode() == adaptedOpCode) {
                returnNodes.add(instruction);
            }
        }
        returnNodes.stream().forEach(returnNode -> installEndListener(method, methodId, returnNode));
        method.instructions.iterator().forEachRemaining(instruction -> {
            if (instruction.getOpcode() == adaptedOpCode) {
            }
        });

        method.maxStack = Math.max(method.maxStack, 5);
    }

    public static void installEndListener(MethodNode method, String methodId, AbstractInsnNode returnNode) {
        InsnList callEnd = new InsnList();
        Type returnType = Type.getReturnType(method.desc);

        if (Type.VOID_TYPE.equals(returnType)) {
            callEnd.add(new LdcInsnNode(methodId));
            if (isStatic(method) || isConstructor(method)) {
                callEnd.add(new InsnNode(Opcodes.ACONST_NULL));
            } else {
                callEnd.add(new VarInsnNode(Opcodes.ALOAD, 0));
            }
            callEnd.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/github/grimly/agent/Agent", "endVoid", "(Ljava/lang/String;Ljava/lang/Object;)V", false));
        } else {
            callEnd.add(castToObject(returnType));
            callEnd.add(new LdcInsnNode(methodId));
            callEnd.add(new VarInsnNode(Opcodes.ALOAD, 0));
            callEnd.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/github/grimly/agent/Agent", "end", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false));
            callEnd.add(checkcastWithType(returnType));
            callEnd.add(castFromObject(returnType));
        }
        method.instructions.insertBefore(returnNode, callEnd);
    }

    public static boolean isStatic(MethodNode method) {
        return (method.access & Opcodes.ACC_STATIC) != 0;
    }

    public static boolean isConstructor(MethodNode method) {
        return "<init>".equals(method.name);
    }

    public static AbstractInsnNode stackIntInsnNode(int n) {
        switch (n) {
            case 0:
                return new InsnNode(Opcodes.ICONST_0);
            case 1:
                return new InsnNode(Opcodes.ICONST_1);
            case 2:
                return new InsnNode(Opcodes.ICONST_2);
            case 3:
                return new InsnNode(Opcodes.ICONST_3);
            case 4:
                return new InsnNode(Opcodes.ICONST_4);
            case 5:
                return new InsnNode(Opcodes.ICONST_5);
            default:
                return new LdcInsnNode(n);
        }
    }

    /**
     * Returns a set of instructions to cast the head of the stack into its object form (boxing)
     * @param type The stack head type
     * @return The set of instructions
     */
    public static InsnList castToObject(Type type) {
        InsnList insns = new InsnList();
        switch (type.getSort()) {
            case Type.BOOLEAN:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
                break;
            case Type.BYTE:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
                break;
            case Type.SHORT:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
                break;
            case Type.CHAR:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
                break;
            case Type.INT:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
                break;
            case Type.LONG:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
                break;
            case Type.FLOAT:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(J)Ljava/lang/Float;", false));
                break;
            case Type.DOUBLE:
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(B)Ljava/lang/Double;", false));
                break;
        }
        return insns;
    }

    public static InsnList castFromObject(Type type) {
        InsnList insns = new InsnList();
        switch (type.getSort()) {
            case Type.BOOLEAN:
                insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                break;
            case Type.BYTE:
                insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Byte", "byteValue", "()B", false));
                break;
            case Type.SHORT:
                insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Short", "shortValue", "()S", false));
                break;
            case Type.CHAR:
                insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Character", "charValue", "()C", false));
                break;
            case Type.INT:
                insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Integer", "inFalue", "()I", false));
                break;
            case Type.LONG:
                insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Long", "longValue", "()J", false));
                break;
            case Type.FLOAT:
                insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Float", "floatValue", "()F", false));
                break;
            case Type.DOUBLE:
                insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Double", "doubleValue", "()D", false));
                break;
        }
        return insns;
    }

    public static InsnList checkcastWithType(Type type) {
        InsnList insns = new InsnList();
        switch (type.getSort()) {
            case Type.BOOLEAN:
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                break;
            case Type.BYTE:
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                break;
            case Type.SHORT:
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                break;
            case Type.CHAR:
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                break;
            case Type.INT:
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                break;
            case Type.LONG:
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                break;
            case Type.FLOAT:
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                break;
            case Type.DOUBLE:
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                break;
            default:
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getDescriptor()));
                break;
        }
        return insns;
    }

    public static Void enter(String name, Object subject, Object... args) {
        System.out.printf("Did enter %s on %s with args %s\n", name, subject, Arrays.toString(args));
        return null;
    }

    public static <Result> Result end(Result result, String name, Object subject) {
        System.out.println("Did end " + name + " with result " + result);
        if (String.class.equals(result.getClass())) {
            return (Result) ("Intercepted => " + result);
        }
        return result;
    }

    public static void endVoid(String name, Object subject) {
        System.out.println("Did end " + name + " with void");
    }
}
